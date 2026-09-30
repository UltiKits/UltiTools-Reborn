package com.ultikits.ultitools.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * The files of the module subsystem and the transactions that change them: which folder modules
 * load from, and how {@code /upm update} replaces a module's JAR (#505, #513).
 *
 * <p><b>Observation, not prediction.</b> An update never decides before a restart whether the new
 * JAR will load. {@code /upm update} downloads the new JAR into a transaction folder beside the
 * modules folder -- never into it -- and writes a record. At the next start, before the module
 * class loader is built, {@link #applyBeforeLoad()} moves the old JAR aside (it is kept) and puts
 * the new one in place. After the modules have loaded, {@link #observeAfterLoad(List, Function)}
 * looks at what actually loaded: the update is committed only when the module is loaded from the
 * new JAR at the new version. No file in the live modules folder changes while the server runs.
 *
 * <p><b>Crash safety.</b> Every state change is written to the record atomically before the step
 * that depends on it, and each step decides from the record plus which files exist, so running a
 * step again after a crash completes it rather than repeating it.
 *
 * <p><b>Confinement.</b> A record names files only by their file name. Each name is resolved
 * against the one folder it belongs to and canonicalised; a name that leaves that folder -- a
 * separator, {@code ..}, or a link pointing elsewhere -- makes the whole record refused with a
 * warning, so a crafted record cannot move or delete an arbitrary file. The transaction's own
 * working folder is derived from a hash of the record's type and key, never read from the record.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ModuleFileTransactions {

    /** The data-folder child modules are loaded from. */
    private static final String MODULES_FOLDER_NAME = "plugins";

    /** The data-folder child that holds transaction records and staged JARs. */
    private static final String TRANSACTIONS_FOLDER_NAME = "upm-transactions";

    private static final String RECORD_SUFFIX = ".json";
    private static final String STAGED_FOLDER = "staged";
    private static final String BACKUP_FOLDER = "backup";

    private static final Logger LOGGER = Logger.getLogger(ModuleFileTransactions.class.getName());

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Serialises every change to the transaction folder made while the server runs, so two
     * {@code /upm} commands running on async threads cannot both stage the same module.
     */
    private static final Object LOCK = new Object();

    private final File modulesFolder;
    private final File transactionsFolder;
    private final FileMover mover;
    private final CrashPoints crashPoints;
    private final List<Report> reports = new ArrayList<>();
    private final List<Record> appliedThisStart = new ArrayList<>();

    /**
     * The transactions of the framework whose data folder is given.
     *
     * @param dataFolder the framework's data folder
     */
    public ModuleFileTransactions(File dataFolder) {
        this(modulesFolder(dataFolder), transactionsFolder(dataFolder), FileMover.ATOMIC, CrashPoints.NONE);
    }

    /**
     * The seam tests use to inject a failing move or a crash at a named point.
     *
     * @param modulesFolder      the folder modules load from
     * @param transactionsFolder the folder records and staged JARs live in
     * @param mover              how a file is moved
     * @param crashPoints        called at every named point of a transaction
     */
    ModuleFileTransactions(File modulesFolder, File transactionsFolder, FileMover mover, CrashPoints crashPoints) {
        this.modulesFolder = modulesFolder;
        this.transactionsFolder = transactionsFolder;
        this.mover = mover;
        this.crashPoints = crashPoints;
    }

    /**
     * The folder modules are loaded from: the framework's data folder plus {@code plugins}.
     *
     * <p>The one place this is computed (#517). The module class loader's URLs, the start-up scan,
     * install, update, uninstall and the transaction folder all come from here. The data folder
     * follows Bukkit's own plugin-directory option, so this is right wherever the server keeps its
     * plugins -- unlike the JVM's working directory, which a launcher may set anywhere.
     *
     * @param dataFolder the framework's data folder
     * @return the modules folder
     */
    public static File modulesFolder(File dataFolder) {
        return new File(dataFolder, MODULES_FOLDER_NAME);
    }

    /**
     * The folder transaction records and staged JARs live in: a sibling of the modules folder, so
     * the module loader never reads anything in it.
     *
     * @param dataFolder the framework's data folder
     * @return the transactions folder
     */
    public static File transactionsFolder(File dataFolder) {
        return new File(modulesFolder(dataFolder).getParentFile(), TRANSACTIONS_FOLDER_NAME);
    }

    // ------------------------------------------------------------------------------------------
    // Staging -- while the server runs
    // ------------------------------------------------------------------------------------------

    /**
     * Stages an update of a loaded module: downloads its latest version into the transaction
     * folder and records it, leaving the modules folder untouched. It takes effect at the next
     * start.
     *
     * @param identifyString the module's identify-string
     * @param loaded         the loaded modules
     * @param codeSource     which JAR a loaded module came from
     * @param catalogue      where the latest version and its download link come from
     * @param downloader     how the new JAR is downloaded
     * @return what happened
     */
    public StageResult stageUpdate(String identifyString, List<UltiToolsPlugin> loaded,
                                   Function<UltiToolsPlugin, File> codeSource, Catalogue catalogue,
                                   Downloader downloader) {
        synchronized (LOCK) {
            String key = normalize(identifyString);
            if (key == null) {
                return StageResult.failed(Keys.REASON_NO_IDENTIFY_STRING);
            }
            UltiToolsPlugin module = null;
            for (UltiToolsPlugin plugin : loaded) {
                if (key.equals(normalize(plugin.getIdentifyString()))) {
                    module = plugin;
                    break;
                }
            }
            if (module == null) {
                return StageResult.failed(Keys.REASON_NOT_LOADED, identifyString);
            }
            File oldJar = codeSource.apply(module);
            if (oldJar == null || !isDirectChild(modulesFolder, oldJar)) {
                return StageResult.failed(Keys.REASON_NOT_IN_MODULES_FOLDER,
                        oldJar == null ? "?" : oldJar.getAbsolutePath(), modulesFolder.getAbsolutePath());
            }
            String latest = catalogue.latestVersion(identifyString);
            String link = latest == null ? null : catalogue.downloadLink(identifyString, latest);
            String targetName = PluginInstallUtils.installedJarName(identifyString, latest);
            if (link == null || targetName == null) {
                return StageResult.failed(Keys.REASON_NO_DOWNLOAD, identifyString);
            }

            Record record = new Record();
            record.type = Record.UPDATE;
            record.key = key;
            File work = workFolder(record);
            File stagedFolder = new File(work, STAGED_FOLDER);
            try {
                Files.createDirectories(stagedFolder.toPath());
                downloader.download(link, targetName, stagedFolder);
            } catch (IOException | RuntimeException e) {
                deleteTree(work);
                return StageResult.failed(Keys.REASON_DOWNLOAD_FAILED, describe(e));
            }
            File staged = new File(stagedFolder, targetName);
            if (!staged.isFile() || !SecurityPolicy.isValidModuleJar(staged)) {
                deleteTree(work);
                return StageResult.failed(Keys.REASON_INVALID_JAR, targetName);
            }
            String stagedVersion = declaredVersion(staged, key);
            if (stagedVersion == null) {
                deleteTree(work);
                return StageResult.failed(Keys.REASON_WRONG_IDENTITY, targetName, key);
            }

            record.state = Record.PENDING;
            record.moduleName = module.getPluginName();
            record.oldName = oldJar.getName();
            record.oldVersion = module.getVersion();
            record.stagedName = targetName;
            record.targetName = targetName;
            record.newVersion = stagedVersion;
            try {
                writeRecord(record);
            } catch (IOException e) {
                deleteTree(work);
                return StageResult.failed(Keys.REASON_RECORD_FAILED, describe(e));
            }
            crashPoints.reached(CrashPoints.AFTER_RECORD_WRITTEN);
            return StageResult.staged(record.moduleName, record.oldVersion, record.newVersion);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Start-up -- before the module class loader is built, and after the modules load
    // ------------------------------------------------------------------------------------------

    /**
     * Carries out every recorded transaction that has to happen before the module class loader is
     * built: puts staged updates in place. Called once per start, before any module JAR is opened.
     * Never throws; every problem becomes a report line.
     */
    public void applyBeforeLoad() {
        for (File recordFile : recordFiles()) {
            Record record = readRecordOrReport(recordFile);
            if (record == null) {
                continue;
            }
            try {
                if (Record.UPDATE.equals(record.type) && Record.PENDING.equals(record.state)) {
                    applyUpdate(record);
                }
            } catch (RecordRefused refused) {
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFile.getName(), refused.getMessage());
            }
        }
    }

    /**
     * Decides every update applied at this start from what loaded: committed when the module is
     * loaded from the new JAR at the new version.
     *
     * @param loaded     the modules that loaded at this start
     * @param codeSource which JAR a loaded module came from
     */
    public void observeAfterLoad(List<UltiToolsPlugin> loaded, Function<UltiToolsPlugin, File> codeSource) {
        for (Record record : new ArrayList<>(appliedThisStart)) {
            appliedThisStart.remove(record);
            try {
                File target = confined(modulesFolder, record.targetName);
                if (isLoadedFrom(record, target, loaded, codeSource)) {
                    commit(record);
                }
            } catch (RecordRefused refused) {
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFileOf(record).getName(), refused.getMessage());
            }
        }
    }

    /**
     * Which file a loaded module was loaded from -- the question the uninstall asks, asked the same
     * way here.
     *
     * @param module a loaded module
     * @return its code source, or {@code null} when it cannot be determined
     */
    public static File codeSourceOf(UltiToolsPlugin module) {
        return PluginInstallUtils.DEFAULT_MODULE_CODE_SOURCE.apply(module);
    }

    /**
     * Writes the lines this start collected, translated.
     *
     * @param logger where to write them
     * @param i18n   the translation to apply to each line's key
     */
    public void flushReports(Logger logger, Function<String, String> i18n) {
        for (Report report : reports) {
            logger.log(report.getLevel(), String.format(i18n.apply(report.getKey()), report.getArgs()));
        }
        reports.clear();
    }

    /**
     * The lines collected and not yet written.
     *
     * @return the lines, in order
     */
    List<Report> pendingReports() {
        return Collections.unmodifiableList(new ArrayList<>(reports));
    }

    private void applyUpdate(Record record) throws RecordRefused {
        File work = workFolder(record);
        File old = confined(modulesFolder, record.oldName);
        File target = confined(modulesFolder, record.targetName);
        File backup = confined(new File(work, BACKUP_FOLDER), record.oldName);
        File staged = confined(new File(work, STAGED_FOLDER), record.stagedName);

        if (exists(staged)) {
            if (!exists(backup) && exists(old)) {
                try {
                    Files.createDirectories(backup.getParentFile().toPath());
                    mover.move(old.toPath(), backup.toPath());
                } catch (IOException | RuntimeException e) {
                    fail(record, old, e);
                    return;
                }
                crashPoints.reached(CrashPoints.AFTER_OLD_MOVED);
            }
            try {
                mover.move(staged.toPath(), target.toPath());
            } catch (IOException | RuntimeException e) {
                fail(record, staged, e);
                return;
            }
            crashPoints.reached(CrashPoints.AFTER_NEW_MOVED);
        }
        record.hadOld = exists(backup);
        record.state = Record.APPLIED;
        persist(record);
        crashPoints.reached(CrashPoints.AFTER_APPLIED_RECORDED);
        appliedThisStart.add(record);
    }

    private void fail(Record record, File file, Throwable error) {
        record.state = Record.FAILED;
        record.failure = file.getAbsolutePath() + ": " + describe(error);
        persist(record);
        report(Level.SEVERE, Keys.APPLY_FAILED, record.moduleName, record.oldVersion, record.newVersion,
                file.getAbsolutePath(), describe(error), record.oldVersion);
    }

    private boolean isLoadedFrom(Record record, File target, List<UltiToolsPlugin> loaded,
                                 Function<UltiToolsPlugin, File> codeSource) {
        String targetPath = canonicalPath(target);
        for (UltiToolsPlugin plugin : loaded) {
            if (!record.key.equals(normalize(plugin.getIdentifyString()))) {
                continue;
            }
            File source = codeSource.apply(plugin);
            if (source != null && targetPath.equals(canonicalPath(source))
                    && record.newVersion.equals(plugin.getVersion())) {
                return true;
            }
        }
        return false;
    }

    private void commit(Record record) {
        record.state = Record.COMMITTING;
        persist(record);
        crashPoints.reached(CrashPoints.AFTER_DECISION_RECORDED);
        deleteTree(workFolder(record));
        deleteQuietly(recordFileOf(record));
        report(Level.INFO, Keys.COMMITTED, record.moduleName, record.oldVersion, record.newVersion);
    }

    // ------------------------------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------------------------------

    private List<File> recordFiles() {
        File[] files = transactionsFolder.listFiles((dir, name) -> name.endsWith(RECORD_SUFFIX));
        if (files == null) {
            return Collections.emptyList();
        }
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
        return sorted;
    }

    private Record readRecordOrReport(File recordFile) {
        try {
            Record record = readRecord(recordFile);
            if (record == null || record.type == null || record.key == null || record.state == null
                    || !recordFile.getName().equals(recordFileOf(record).getName())) {
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFile.getName(), "unrecognised record");
                return null;
            }
            return record;
        } catch (IOException | JsonParseException e) {
            report(Level.WARNING, Keys.RECORD_UNREADABLE, recordFile.getAbsolutePath(), describe(e));
            return null;
        }
    }

    private static Record readRecord(File recordFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(recordFile.toPath(), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, Record.class);
        }
    }

    /**
     * Writes a record atomically: a temporary file in the same folder, then an atomic move over the
     * record, so a crash leaves either the previous record or the new one, never a torn file.
     */
    private void writeRecord(Record record) throws IOException {
        Files.createDirectories(transactionsFolder.toPath());
        Path temporary = Files.createTempFile(transactionsFolder.toPath(), "record-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(record, writer);
            }
            Files.move(temporary, recordFileOf(record).toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** {@link #writeRecord(Record)} at start-up, where a failure is a report line, not an abort. */
    private void persist(Record record) {
        try {
            writeRecord(record);
        } catch (IOException e) {
            report(Level.SEVERE, Keys.RECORD_WRITE_FAILED, recordFileOf(record).getAbsolutePath(), describe(e));
        }
    }

    private File recordFileOf(Record record) {
        return new File(transactionsFolder, idOf(record) + RECORD_SUFFIX);
    }

    private File workFolder(Record record) {
        return new File(transactionsFolder, idOf(record));
    }

    /** The name a record's files take: its type and a hash of its key, never text from the record. */
    private static String idOf(Record record) {
        return record.type.toLowerCase(Locale.ROOT) + "-" + sha256(record.type + ":" + record.key).substring(0, 16);
    }

    // ------------------------------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves a file name a record carries against the one folder it belongs to, refusing any
     * name that would leave it.
     *
     * @param folder the folder the name belongs to
     * @param name   the name from the record
     * @return the file
     * @throws RecordRefused when the name is not a plain name inside {@code folder}
     */
    static File confined(File folder, String name) throws RecordRefused {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new RecordRefused("not a plain file name: " + name);
        }
        File file = new File(folder, name);
        if (!isDirectChild(folder, file)) {
            throw new RecordRefused(file.getAbsolutePath() + " resolves outside " + folder.getAbsolutePath());
        }
        return file;
    }

    /** Whether {@code file}, canonicalised, sits directly in {@code folder}, canonicalised. */
    private static boolean isDirectChild(File folder, File file) {
        try {
            File parent = file.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(folder.getCanonicalFile());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException | SecurityException e) {
            return file.getAbsolutePath();
        }
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not delete " + file, e);
        }
    }

    /** Deletes a folder this class derived itself (never one named by a record), bottom-up. */
    private static void deleteTree(File folder) {
        if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            Collections.reverse(paths);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not delete " + folder, e);
        }
    }

    /**
     * The version a staged JAR's {@code plugin.yml} declares, when it declares the expected
     * identify-string and a version -- identity and version only, read the same way
     * {@code UltiToolsPlugin} reads them, and nothing about whether its classes would load.
     */
    private static String declaredVersion(File jar, String key) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar)) {
            java.util.jar.JarEntry entry = jarFile.getJarEntry("plugin.yml");
            if (entry == null) {
                return null;
            }
            try (InputStream in = jarFile.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                YamlConfiguration yml = new YamlConfiguration();
                yml.load(reader);
                String version = yml.getString("version");
                if (!key.equals(normalize(yml.getString("identify-string")))
                        || version == null || version.trim().isEmpty()) {
                    return null;
                }
                return version;
            }
        } catch (IOException | InvalidConfigurationException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not read plugin.yml of " + jar, e);
            return null;
        }
    }

    private static String normalize(String identifyString) {
        if (identifyString == null) {
            return null;
        }
        String normalized = identifyString.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : error.getClass().getSimpleName() + ": " + message;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private void report(Level level, String key, Object... args) {
        reports.add(new Report(level, key, args));
    }

    // ------------------------------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------------------------------

    /** Where the latest version of a module, and its download, come from. */
    public interface Catalogue {
        /**
         * The latest version of a module.
         *
         * @param identifyString the module's identify-string
         * @return the version, or {@code null} when there is none
         */
        String latestVersion(String identifyString);

        /**
         * The download link of one version of a module.
         *
         * @param identifyString the module's identify-string
         * @param version        the version
         * @return the link, or {@code null} when there is none
         */
        String downloadLink(String identifyString, String version);
    }

    /** How a JAR is downloaded. */
    @FunctionalInterface
    public interface Downloader {
        /**
         * Downloads a file.
         *
         * @param link     the link
         * @param fileName the file name to write
         * @param folder   the folder to write it into
         * @throws IOException when the download fails
         */
        void download(String link, String fileName, File folder) throws IOException;
    }

    /** How a file is moved; a test substitutes a failing one. */
    @FunctionalInterface
    interface FileMover {
        /** An atomic rename, never replacing an existing file. */
        FileMover ATOMIC = (from, to) -> {
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(to.toString());
            }
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("the modules folder and the transactions folder must be on one file system: "
                        + e.getMessage(), e);
            }
        };

        void move(Path from, Path to) throws IOException;
    }

    /** Named points of a transaction; a test crashes at one to reproduce that window. */
    @FunctionalInterface
    interface CrashPoints {
        CrashPoints NONE = point -> { };
        String AFTER_RECORD_WRITTEN = "after-record-written";
        String AFTER_OLD_MOVED = "after-old-moved";
        String AFTER_NEW_MOVED = "after-new-moved";
        String AFTER_APPLIED_RECORDED = "after-applied-recorded";
        String AFTER_DECISION_RECORDED = "after-decision-recorded";

        void reached(String point);
    }

    /** A record whose names would leave their folder. */
    static final class RecordRefused extends Exception {
        private static final long serialVersionUID = 1L;

        RecordRefused(String message) {
            super(message);
        }
    }

    /** The persisted record of one transaction. Field names are the on-disk format. */
    @SuppressWarnings("PMD.DataClass")
    static final class Record {
        static final String UPDATE = "UPDATE";
        static final String PENDING = "PENDING";
        static final String APPLIED = "APPLIED";
        static final String COMMITTING = "COMMITTING";
        static final String FAILED = "FAILED";

        String type;
        String key;
        String state;
        String moduleName;
        String oldName;
        String oldVersion;
        String stagedName;
        String targetName;
        String newVersion;
        boolean hadOld;
        String failure;
    }

    /** One line for the start-up log, with its catalogue key and arguments. */
    public static final class Report {
        private final Level level;
        private final String key;
        private final Object[] args;

        Report(Level level, String key, Object... args) {
            this.level = level;
            this.key = key;
            this.args = args.clone();
        }

        /** @return the log level */
        public Level getLevel() {
            return level;
        }

        /** @return the catalogue key */
        public String getKey() {
            return key;
        }

        /** @return the format arguments */
        public Object[] getArgs() {
            return args.clone();
        }
    }

    /** What {@link #stageUpdate} did. */
    public static final class StageResult {
        /** The outcome. */
        public enum Outcome {
            /** Downloaded and recorded; takes effect at the next start. */
            STAGED,
            /** Nothing was staged; the reason says why. */
            FAILED
        }

        private final Outcome outcome;
        private final String moduleName;
        private final String oldVersion;
        private final String newVersion;
        private final String reasonKey;
        private final Object[] reasonArgs;

        @SuppressWarnings("PMD.ExcessiveParameterList")
        private StageResult(Outcome outcome, String moduleName, String oldVersion, String newVersion,
                            String reasonKey, Object[] reasonArgs) {
            this.outcome = outcome;
            this.moduleName = moduleName;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
            this.reasonKey = reasonKey;
            this.reasonArgs = reasonArgs.clone();
        }

        static StageResult staged(String moduleName, String oldVersion, String newVersion) {
            return new StageResult(Outcome.STAGED, moduleName, oldVersion, newVersion, null, new Object[0]);
        }

        static StageResult failed(String reasonKey, Object... reasonArgs) {
            return new StageResult(Outcome.FAILED, null, null, null, reasonKey, reasonArgs);
        }

        /** @return the outcome */
        public Outcome getOutcome() {
            return outcome;
        }

        /** @return the module's runtime name, when known */
        public String getModuleName() {
            return moduleName;
        }

        /** @return the version that is loaded now, when known */
        public String getOldVersion() {
            return oldVersion;
        }

        /** @return the staged version, when known */
        public String getNewVersion() {
            return newVersion;
        }

        /** @return the catalogue key of the reason, or {@code null} */
        public String getReasonKey() {
            return reasonKey;
        }

        /** @return the reason's format arguments */
        public Object[] getReasonArgs() {
            return reasonArgs.clone();
        }
    }

    /**
     * The catalogue keys of every line this class produces. Each has an entry in {@code en.json}
     * and {@code zh.json}; {@code ModuleFileCatalogueTest} holds that.
     */
    public static final class Keys {
        public static final String COMMITTED = "模块 %s 已从 %s 更新到 %s：本次启动确认新版本已加载，旧版本 JAR 已删除。";
        public static final String APPLY_FAILED =
                "模块 %s 的更新（%s → %s）未能应用：%s：%s。模块目录未改变，本次启动加载 %s；下次执行 /upm update 时会再次报告。";
        public static final String RECORD_REFUSED = "更新记录 %s 已拒绝执行：%s";
        public static final String RECORD_UNREADABLE = "更新记录 %s 无法读取，已跳过：%s";
        public static final String RECORD_WRITE_FAILED = "更新记录 %s 无法写入：%s";
        public static final String REASON_NO_IDENTIFY_STRING = "该模块没有 identify-string";
        public static final String REASON_NOT_LOADED = "没有已加载的模块声明 identify-string %s";
        public static final String REASON_NOT_IN_MODULES_FOLDER = "模块的 JAR %s 不在模块目录 %s 中";
        public static final String REASON_NO_DOWNLOAD = "云端没有 %s 的可下载版本";
        public static final String REASON_DOWNLOAD_FAILED = "下载失败：%s";
        public static final String REASON_INVALID_JAR = "下载的文件 %s 不是有效的模块 JAR";
        public static final String REASON_WRONG_IDENTITY = "下载的 %s 没有声明 identify-string %s 和版本号";
        public static final String REASON_RECORD_FAILED = "更新记录无法写入：%s";

        private Keys() {
        }

        /**
         * Every key, for the catalogue test.
         *
         * @return the keys
         */
        static List<String> all() {
            List<String> keys = new ArrayList<>();
            for (java.lang.reflect.Field field : Keys.class.getFields()) {
                try {
                    keys.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            return keys;
        }
    }
}
