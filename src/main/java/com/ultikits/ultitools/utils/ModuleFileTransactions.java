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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * JAR will load. {@code /upm update} downloads the new JAR into the transactions folder,
 * {@code <server root>/.ultikits/upm-transactions} -- never into the modules folder -- and writes a
 * record. At the next start, before the module
 * class loader is built, {@link #applyBeforeLoad()} moves the old JAR aside (it is kept) and puts
 * the new one in place. After the modules have loaded, {@link #observeAfterLoad(List, Function)}
 * looks at what actually loaded: the update is committed only when the module is loaded from the
 * new JAR at the new version, and otherwise rolled back -- the old JAR restored, the new one
 * removed. Staging never touches the modules folder; the only change while the server runs is a
 * rollback right after loading, which removes the JAR of a module that did not load and puts the
 * old JAR back for the next start.
 *
 * <p><b>States.</b> {@code PENDING} (staged) -&gt; {@code APPLIED} (swapped at a start) -&gt;
 * {@code COMMITTING} or {@code ROLLING_BACK} (decided) -&gt; record deleted. {@code FAILED} (the
 * swap could not be made; nothing in the modules folder changed) is kept until the next
 * {@code /upm update} of that module reports it. An {@code APPLIED} record found at a start means
 * the previous start ended before the decision; nothing was observed, so it is rolled back before
 * the modules load.
 *
 * <p><b>Crash safety.</b> Every state change is written to the record atomically before the step
 * that depends on it, and each step decides from the record plus which files exist -- the new JAR
 * is recognised by its SHA-256, recorded when it was staged -- so running a step again after a
 * crash completes it rather than repeating it, and never deletes a file that is not the staged
 * JAR. Both versions are kept until the decision.
 *
 * <p><b>Deferred removal.</b> An uninstall whose JAR cannot be deleted while the server runs --
 * Windows holds every module JAR open through the shared module class loader -- records the file
 * ({@link #recordDeferredRemoval(String, List)}), and the next start deletes it before any module
 * loads, if it is still that file (#518).
 *
 * <p><b>Where the records live.</b> Under the server root, beside the credential store
 * ({@link #transactionsFolder(File)}), not under {@code plugins/}: the panel's file interface can
 * write under {@code plugins/} when its file writing is enabled, and a forged removal record there
 * would delete a module JAR at the next start (maintainer decision, 2026-09-30). The price is that
 * the start-up swap is an atomic rename between the modules folder and this folder: when
 * {@code plugins/} is on another file system the rename is refused, the update is not applied, and
 * one SEVERE line names both folders. There is deliberately no copy fallback -- a copy is not
 * atomic, and the crash-window guarantees below rely on every move being a rename.
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

    /** The server-root folder the credential store also lives in ({@code CredentialStore}). */
    private static final String ULTIKITS_FOLDER_NAME = ".ultikits";

    /** The {@link #ULTIKITS_FOLDER_NAME} child that holds transaction records and staged JARs. */
    private static final String TRANSACTIONS_FOLDER_NAME = "upm-transactions";

    private static final String RECORD_SUFFIX = ".json";
    private static final String TEMPORARY_PREFIX = "record-";
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final String STAGED_FOLDER = "staged";
    private static final String BACKUP_FOLDER = "backup";

    /** The "actual hash" a report gives for a staged JAR that is not there. */
    static final String MISSING = "<missing>";

    private static final Logger LOGGER = Logger.getLogger(ModuleFileTransactions.class.getName());

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Serialises every change to the transaction folder made while the server runs, so two
     * {@code /upm} commands running on async threads cannot both stage the same module.
     */
    private static final Object LOCK = new Object();

    /** The modules (by identify-string) whose update is being downloaded right now; guarded by {@link #LOCK}. */
    private static final java.util.Set<String> STAGING = new java.util.HashSet<>();

    private final File modulesFolder;
    private final File transactionsFolder;
    private final FileOps ops;
    private final CrashPoints crashPoints;
    private final List<Report> reports = new ArrayList<>();
    private final List<Record> appliedThisStart = new ArrayList<>();
    /** The failure of a {@code FAILED} record {@link #checkExisting} just discarded; guarded by {@link #LOCK}. */
    private String lastDiscardedFailure;
    /** The modules-folder file names a recorded deletion still has to remove after this start's removals. */
    private final java.util.Set<String> pendingRemovals = new java.util.HashSet<>();

    /** Which start-up step a rollback is finished from; it decides what the log line says. */
    private enum RollbackPhase {
        /** After the modules loaded and the module was not among them; the old version loads next start. */
        AFTER_LOAD,
        /** Before loading, for an update the previous start applied but never decided. */
        UNCONFIRMED,
        /** Before loading, finishing a rollback an earlier start decided but could not complete. */
        RESUMED
    }

    /**
     * The transactions of the framework whose data folder is given.
     *
     * @param dataFolder the framework's data folder
     */
    public ModuleFileTransactions(File dataFolder) {
        this(modulesFolder(dataFolder), transactionsFolder(dataFolder), FileOps.DEFAULT, CrashPoints.NONE);
    }

    /**
     * The seam tests use to inject a failing file operation or a crash at a named point.
     *
     * @param modulesFolder      the folder modules load from
     * @param transactionsFolder the folder records and staged JARs live in
     * @param ops                how files are moved and deleted
     * @param crashPoints        called at every named point of a transaction
     */
    ModuleFileTransactions(File modulesFolder, File transactionsFolder, FileOps ops, CrashPoints crashPoints) {
        this.modulesFolder = modulesFolder;
        this.transactionsFolder = transactionsFolder;
        this.ops = ops;
        this.crashPoints = crashPoints;
    }

    /**
     * The folder modules are loaded from: the framework's data folder plus {@code plugins}.
     *
     * <p>The one place this is computed (#517). The module class loader's URLs, the start-up scan,
     * install, update and uninstall all come from here; the transactions folder comes from the same
     * data folder ({@link #transactionsFolder(File)}). The data folder
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
     * The folder transaction records and staged JARs live in:
     * {@code <server root>/.ultikits/upm-transactions}, beside the credential store. It is outside
     * {@code plugins/}, so the module loader never reads anything in it and the panel's file
     * interface, whose default editable roots do not include it, cannot forge a record.
     *
     * @param dataFolder the framework's data folder
     * @return the transactions folder
     */
    public static File transactionsFolder(File dataFolder) {
        return new File(new File(serverRoot(dataFolder), ULTIKITS_FOLDER_NAME), TRANSACTIONS_FOLDER_NAME);
    }

    /**
     * The server root, found the way {@code CredentialStore} finds it: the data folder's
     * grandparent ({@code <server root>/plugins/UltiTools}). Resolved from the absolute data folder,
     * so a relative one gives the same root; never from the JVM's working directory.
     *
     * @param dataFolder the framework's data folder
     * @return the server root
     * @throws IllegalStateException when the data folder has no grandparent
     */
    private static File serverRoot(File dataFolder) {
        File root = dataFolder.getAbsoluteFile().getParentFile().getParentFile();
        if (root == null) {
            throw new IllegalStateException("the data folder " + dataFolder.getAbsolutePath()
                    + " is not inside <server root>/plugins/");
        }
        return root;
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

    // ------------------------------------------------------------------------------------------
    // While the server runs
    // ------------------------------------------------------------------------------------------

    /**
     * Stages an update of a loaded module: downloads its latest version into the transaction
     * folder and records it, leaving the modules folder untouched. It takes effect at the next
     * start. One update per module waits at a time; a failed apply recorded by an earlier start is
     * reported through {@link StageResult#getPreviousFailure()} and cleared before staging again.
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
        String key = normalize(identifyString);
        if (key == null) {
            return StageResult.failed(null, Keys.REASON_NO_IDENTIFY_STRING);
        }
        Record record = new Record();
        record.type = Record.UPDATE;
        record.key = key;
        File work = workFolder(record);
        Reservation reservation;
        // Decisions under the lock; the network outside it. An uninstall on the main thread waits
        // only for these checks and the record write, never for a download (gate-1 review).
        synchronized (LOCK) {
            reservation = reserve(record, work, identifyString, loaded, codeSource);
            if (reservation.refusal != null) {
                return reservation.refusal;
            }
            STAGING.add(key);
        }
        record.oldSha256 = reservation.oldSha256;
        try {
            return download(record, work, identifyString, reservation.module, reservation.oldJar, catalogue,
                    downloader, reservation.previousFailure);
        } finally {
            synchronized (LOCK) {
                STAGING.remove(key);
            }
        }
    }

    /**
     * The checks staging makes before it downloads, under {@link #LOCK}: no download of this
     * module running, no update already staged, no kept old JAR left without its record.
     */
    private Reservation reserve(Record record, File work, String identifyString, List<UltiToolsPlugin> loaded,
                                Function<UltiToolsPlugin, File> codeSource) {
        if (STAGING.contains(record.key)) {
            return Reservation.refused(StageResult.of(StageResult.Outcome.BUSY, null, null, null, null));
        }
        StageResult refusal = checkExisting(record);
        String previousFailure = refusal == null ? lastDiscardedFailure : refusal.getPreviousFailure();
        lastDiscardedFailure = null;
        if (refusal != null) {
            return Reservation.refused(refusal);
        }
        File backups = new File(work, BACKUP_FOLDER);
        if (mayHoldFiles(backups)) {
            // A kept old JAR without its record: never adopted as this transaction's backup and
            // never deleted by staging. The start-up log already reports it.
            return Reservation.refused(StageResult.failed(previousFailure, Keys.REASON_LEFTOVER_BACKUP,
                    backups.getAbsolutePath()));
        }
        return locate(record.key, identifyString, loaded, codeSource, previousFailure);
    }

    /** The loaded module to update and the JAR in the modules folder it was loaded from. */
    private Reservation locate(String key, String identifyString, List<UltiToolsPlugin> loaded,
                               Function<UltiToolsPlugin, File> codeSource, String previousFailure) {
        UltiToolsPlugin module = null;
        for (UltiToolsPlugin plugin : loaded) {
            if (key.equals(normalize(plugin.getIdentifyString()))) {
                module = plugin;
                break;
            }
        }
        if (module == null) {
            return Reservation.refused(StageResult.failed(previousFailure, Keys.REASON_NOT_LOADED, identifyString));
        }
        File oldJar = codeSource.apply(module);
        if (oldJar == null || !isDirectChild(modulesFolder, oldJar) || !isJarName(oldJar.getName())) {
            return Reservation.refused(StageResult.failed(previousFailure, Keys.REASON_NOT_IN_MODULES_FOLDER,
                    oldJar == null ? "?" : oldJar.getAbsolutePath(), modulesFolder.getAbsolutePath()));
        }
        String sharedWith = sharedWith(module, oldJar, loaded, codeSource);
        if (sharedWith != null) {
            return Reservation.refused(StageResult.failed(previousFailure, Keys.REASON_SHARED_JAR,
                    oldJar.getAbsolutePath(), sharedWith));
        }
        String oldSha256 = sha256Of(oldJar);
        if (oldSha256 == null) {
            return Reservation.refused(StageResult.failed(previousFailure, Keys.REASON_OLD_JAR_UNREADABLE,
                    oldJar.getAbsolutePath()));
        }
        return new Reservation(module, oldJar, oldSha256, previousFailure, null);
    }

    /**
     * The other loaded modules that came from the same JAR, or {@code null} when there are none.
     * Two module classes can be packaged in one archive; replacing it would replace them too, so
     * such an update is refused, as the uninstall refuses to delete a JAR another module uses.
     *
     * <p>Only the selected instance itself is exempt (reference identity). Every other instance is
     * compared by where it was loaded from, whatever its identify-string: that string is read from
     * the archive's {@code plugin.yml}, so all module classes of one JAR report the same one (round-8
     * review).
     */
    private static String sharedWith(UltiToolsPlugin module, File oldJar, List<UltiToolsPlugin> loaded,
                                     Function<UltiToolsPlugin, File> codeSource) {
        String jarPath = canonicalPath(oldJar);
        List<String> others = new ArrayList<>();
        for (UltiToolsPlugin plugin : loaded) {
            if (plugin == module) {
                continue;
            }
            File source = codeSource.apply(plugin);
            if (source != null && jarPath.equals(canonicalPath(source))) {
                others.add(plugin.getPluginName());
            }
        }
        return others.isEmpty() ? null : String.join(", ", others);
    }

    /** What {@link #reserve} decided: the module and JAR to stage an update for, or the refusal. */
    private static final class Reservation {
        private final UltiToolsPlugin module;
        private final File oldJar;
        private final String oldSha256;
        private final String previousFailure;
        private final StageResult refusal;

        Reservation(UltiToolsPlugin module, File oldJar, String oldSha256, String previousFailure,
                    StageResult refusal) {
            this.module = module;
            this.oldJar = oldJar;
            this.oldSha256 = oldSha256;
            this.previousFailure = previousFailure;
            this.refusal = refusal;
        }

        static Reservation refused(StageResult refusal) {
            return new Reservation(null, null, null, null, refusal);
        }
    }

    /**
     * The part of staging that touches the network and the staged file, run outside the lock; only
     * the record write at its end takes the lock again.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.ExcessiveParameterList"})
    private StageResult download(Record record, File work, String identifyString, UltiToolsPlugin module,
                                 File oldJar, Catalogue catalogue, Downloader downloader, String previousFailure) {
        String latest;
        String link;
        try {
            latest = catalogue.latestVersion(identifyString);
            link = latest == null ? null : catalogue.downloadLink(identifyString, latest);
        } catch (RuntimeException e) {
            return StageResult.failed(previousFailure, Keys.REASON_DOWNLOAD_FAILED, describe(e));
        }
        String targetName = PluginInstallUtils.installedJarName(identifyString, latest);
        if (link == null || targetName == null) {
            return StageResult.failed(previousFailure, Keys.REASON_NO_DOWNLOAD, identifyString);
        }
        File stagedFolder = new File(work, STAGED_FOLDER);
        String downloadFailure = fetch(downloader, link, targetName, work, stagedFolder);
        if (downloadFailure != null) {
            return StageResult.failed(previousFailure, Keys.REASON_DOWNLOAD_FAILED, downloadFailure);
        }
        StageResult refusal = verifyStaged(record, work, new File(stagedFolder, targetName), latest, previousFailure);
        if (refusal != null) {
            return refusal;
        }

        record.state = Record.PENDING;
        record.moduleName = module.getPluginName();
        record.oldName = oldJar.getName();
        record.oldVersion = module.getVersion();
        record.stagedName = targetName;
        record.targetName = targetName;
        return writeStaged(record, work, previousFailure);
    }

    /**
     * Checks the downloaded JAR -- the size/entry guard, its identity, and that it declares the
     * version the catalogue offered -- and on success records its version and SHA-256.
     *
     * @return the refusal, with the staging discarded, or {@code null} when the JAR is accepted
     */
    private static StageResult verifyStaged(Record record, File work, File staged, String latest,
                                            String previousFailure) {
        String targetName = staged.getName();
        if (!staged.isFile() || !SecurityPolicy.isValidModuleJar(staged)) {
            discardStaging(work);
            return StageResult.failed(previousFailure, Keys.REASON_INVALID_JAR, targetName);
        }
        String stagedVersion = declaredVersion(staged, record.key);
        String stagedHash = sha256Of(staged);
        if (stagedVersion == null || stagedHash == null) {
            discardStaging(work);
            return StageResult.failed(previousFailure, Keys.REASON_WRONG_IDENTITY, targetName, record.key);
        }
        StageResult wrongVersion = refuseOtherVersion(work, targetName, stagedVersion, latest, previousFailure);
        if (wrongVersion != null) {
            return wrongVersion;
        }
        record.newVersion = stagedVersion;
        record.stagedSha256 = stagedHash;
        return null;
    }

    /**
     * A download must be the version the catalogue offered: a stale or misconfigured endpoint can
     * serve a valid JAR of the same module declaring another version, which would otherwise be
     * recorded -- and committed -- under the offered version's file name (round-6 review).
     *
     * @return the refusal, with the staging discarded, or {@code null} when the versions agree
     */
    private static StageResult refuseOtherVersion(File work, String targetName, String stagedVersion, String latest,
                                                  String previousFailure) {
        if (stagedVersion.trim().equals(latest.trim())) {
            return null;
        }
        discardStaging(work);
        return StageResult.failed(previousFailure, Keys.REASON_WRONG_VERSION, targetName, stagedVersion.trim(),
                latest.trim());
    }

    /** Writes the {@code PENDING} record of a staged JAR, under the lock; the staging is discarded when it cannot be. */
    private StageResult writeStaged(Record record, File work, String previousFailure) {
        synchronized (LOCK) {
            try {
                writeRecord(record);
            } catch (IOException e) {
                discardStaging(work);
                return StageResult.failed(previousFailure, Keys.REASON_RECORD_FAILED, describe(e));
            }
        }
        crashPoints.reached(CrashPoints.AFTER_RECORD_WRITTEN);
        return StageResult.of(StageResult.Outcome.STAGED, record.moduleName, record.oldVersion,
                record.newVersion, previousFailure);
    }

    /**
     * Downloads the new JAR into a fresh staged folder.
     *
     * @return {@code null} when it was downloaded, otherwise the failure, described; the staged
     *         folder is then discarded
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // a download failure of any kind is reported
    private static String fetch(Downloader downloader, String link, String targetName, File work,
                                File stagedFolder) {
        discardStaging(work);
        try {
            Files.createDirectories(stagedFolder.toPath());
            downloader.download(link, targetName, stagedFolder);
            return null;
        } catch (IOException | RuntimeException e) {
            discardStaging(work);
            return describe(e);
        }
    }

    /**
     * What an existing record of this module says about staging now, under the lock: {@code null}
     * to go ahead (after discarding a {@code FAILED} record, whose failure is left in
     * {@link #lastDiscardedFailure}), or the refusal to return.
     */
    private StageResult checkExisting(Record lookup) {
        File existingFile = recordFileOf(lookup);
        if (!exists(existingFile)) {
            return null;
        }
        Record existing;
        try {
            existing = readRecord(existingFile);
        } catch (IOException | JsonParseException e) {
            return StageResult.failed(null, Keys.REASON_RECORD_UNREADABLE, existingFile.getAbsolutePath(), describe(e));
        }
        // The same validation as start-up: a record start-up would refuse is never reported as a
        // staged update (round-7 review).
        String problem = existing == null ? "empty record" : problemWith(existing, existingFile);
        if (problem != null) {
            return StageResult.failed(null, Keys.REASON_RECORD_UNREADABLE, existingFile.getAbsolutePath(), problem);
        }
        if (Record.PENDING.equals(existing.state)) {
            return StageResult.of(StageResult.Outcome.ALREADY_STAGED, existing.moduleName, existing.oldVersion,
                    existing.newVersion, null);
        }
        if (!Record.FAILED.equals(existing.state)) {
            return StageResult.of(StageResult.Outcome.BUSY, existing.moduleName, existing.oldVersion,
                    existing.newVersion, null);
        }
        String restoreError = discardFailed(existing);
        if (restoreError != null) {
            return StageResult.failed(existing.failure, Keys.REASON_PREVIOUS_UNRESTORED, restoreError);
        }
        lastDiscardedFailure = existing.failure;
        return null;
    }

    /**
     * {@link #cancelStagedUpdates(String, java.util.Collection)} by the runtime name alone.
     *
     * @param moduleName the module's runtime name
     * @return the versions whose updates were cancelled
     */
    public List<String> cancelStagedUpdates(String moduleName) {
        return cancelStagedUpdates(moduleName, Collections.<String>emptyList());
    }

    /**
     * Cancels every update transaction of a module being uninstalled: its record and its working
     * folder -- the staged JAR and any kept old JAR -- are deleted, so nothing brings the module
     * back at the next start. The modules folder is not touched; the uninstall owns that.
     *
     * <p>A transaction belongs to the module when it was staged under that runtime name, or when
     * the JAR it would replace is one the uninstall removed or recorded for removal -- the second
     * test is what matches an uninstall that named the module some other way. Whatever this
     * misses, the next start still abandons an update whose old JAR is gone or pending removal.
     *
     * @param moduleName       the module's runtime name, as the uninstall was given it
     * @param removedJarNames  the file names of the JARs the uninstall deleted or recorded
     * @return the versions whose updates were cancelled
     */
    public List<String> cancelStagedUpdates(String moduleName, java.util.Collection<String> removedJarNames) {
        synchronized (LOCK) {
            List<String> cancelled = new ArrayList<>();
            List<File> recordFiles;
            try {
                recordFiles = recordFiles();
            } catch (IOException e) {
                // The next start cannot list the folder either, so it applies nothing and reports it.
                LOGGER.log(Level.WARNING, "No staged update of " + moduleName + " could be cancelled", e);
                return cancelled;
            }
            for (File recordFile : recordFiles) {
                Record record;
                try {
                    record = readRecord(recordFile);
                } catch (IOException | JsonParseException e) {
                    LOGGER.log(Level.FINE, "Could not read " + recordFile, e);
                    continue;
                }
                if (record == null || !Record.UPDATE.equals(record.type)
                        || !recordFile.getName().equals(recordFileOf(record).getName())) {
                    continue;
                }
                boolean byName = moduleName != null && moduleName.equals(record.moduleName);
                boolean byJar = removedJarNames != null && record.oldName != null
                        && removedJarNames.contains(record.oldName);
                if (!byName && !byJar) {
                    continue;
                }
                if (deleteTree(workFolder(record)) && deleteQuietly(recordFile)) {
                    cancelled.add(record.newVersion);
                }
            }
            return cancelled;
        }
    }

    /**
     * Records module JARs an uninstall could not delete now, so the next start deletes them before
     * any module loads (#518). On Windows the shared module class loader holds every module JAR
     * open for the life of the server, so this is the only point at which such a JAR can go.
     *
     * <p>Each file is recorded with its size, modification time and SHA-256; the next start deletes
     * it only if it is still that file, so an install under the same name since -- or a copy put
     * back -- is never deleted. An install through {@code /upm} also forgets the record
     * ({@link #forgetDeferredRemoval(String)}).
     *
     * @param moduleName the module being uninstalled, as the operator named it
     * @param files      the JARs still on disk, each directly in the modules folder
     * @throws IOException when the record cannot be written, or a file is not in the modules folder
     *                     or cannot be read
     */
    public void recordDeferredRemoval(String moduleName, List<File> files) throws IOException {
        synchronized (LOCK) {
            Record record = new Record();
            record.type = Record.REMOVE;
            record.key = moduleName;
            File recordFile = recordFileOf(record);
            if (exists(recordFile)) {
                try {
                    Record existing = readRecord(recordFile);
                    if (existing != null && existing.removals != null) {
                        record.removals.addAll(existing.removals);
                    }
                } catch (IOException unreadable) {
                    // An unreadable earlier record of this module cannot be merged; the new one replaces it.
                    LOGGER.log(Level.WARNING, "Replacing an unreadable update record while recording a deletion: "
                            + recordFile.getAbsolutePath(), unreadable);
                }
            }
            for (File file : files) {
                if (!isDirectChild(modulesFolder, file)) {
                    throw new IOException(file.getAbsolutePath() + " is not in the modules folder "
                            + modulesFolder.getAbsolutePath());
                }
                String hash = sha256Of(file);
                if (hash == null) {
                    throw new IOException(file.getAbsolutePath() + " could not be read");
                }
                Removal removal = new Removal();
                removal.name = file.getName();
                removal.size = file.length();
                removal.lastModified = file.lastModified();
                removal.sha256 = hash;
                record.removals.removeIf(r -> removal.name.equals(r.name));
                record.removals.add(removal);
            }
            record.state = Record.PENDING;
            record.moduleName = moduleName;
            writeRecord(record);
        }
    }

    /**
     * Forgets a recorded deletion of a file name an install is writing again, so the next start does
     * not delete the new install -- even when it is byte-for-byte the JAR that was recorded.
     *
     * @param fileName the file name the install wrote into the modules folder
     * @throws IOException when a record cannot be read or rewritten
     */
    public void forgetDeferredRemoval(String fileName) throws IOException {
        synchronized (LOCK) {
            for (File recordFile : recordFiles()) {
                Record record;
                try {
                    record = readRecord(recordFile);
                } catch (IOException unreadable) {
                    // One malformed record never stops the others being cleared (round-5 review).
                    LOGGER.log(Level.WARNING, "Skipped an unreadable update record while clearing a recorded deletion: "
                            + recordFile.getAbsolutePath(), unreadable);
                    continue;
                }
                if (record == null || !Record.REMOVE.equals(record.type) || record.removals == null
                        || !recordFile.getName().equals(recordFileOf(record).getName())
                        || !record.removals.removeIf(removal -> fileName.equals(removal.name))) {
                    continue;
                }
                if (record.removals.isEmpty()) {
                    Files.deleteIfExists(recordFile.toPath());
                } else {
                    writeRecord(record);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Start-up -- before the module class loader is built, and after the modules load
    // ------------------------------------------------------------------------------------------

    /**
     * Carries out every recorded transaction that has to happen before the module class loader is
     * built. Called once per start, before any module JAR is opened. Never throws for a problem
     * with one transaction; every problem becomes a report line and the next record is processed.
     */
    public void applyBeforeLoad() {
        List<String> ids = new ArrayList<>();
        List<Record> removals = new ArrayList<>();
        List<Record> updates = new ArrayList<>();
        List<File> recordFiles;
        try {
            // Listed first, and guarded: every other listing of this folder runs only once this one
            // has succeeded, so a folder that cannot be listed is reported here and never ends the start.
            recordFiles = recordFiles();
        } catch (IOException e) {
            // Nothing is carried out and nothing is deleted: the records stay for a start that can read them.
            report(Level.SEVERE, Keys.RECORDS_UNLISTABLE, transactionsFolder.getAbsolutePath(), describe(e));
            return;
        }
        deleteTemporaryRecords();
        for (File recordFile : recordFiles) {
            Record record = readRecordOrReport(recordFile);
            if (record == null) {
                ids.add(stripSuffix(recordFile.getName()));
                continue;
            }
            ids.add(idOf(record));
            (Record.REMOVE.equals(record.type) ? removals : updates).add(record);
        }
        // Deletions an uninstall recorded run first, by rule and not by how record names sort: an
        // update whose old JAR an uninstall removed, or still has to remove, must see that before
        // it moves anything.
        pendingRemovals.clear();
        for (Record record : removals) {
            applyGuarded(record);
        }
        for (Record record : updates) {
            applyGuarded(record);
        }
        sweepOrphanWorkFolders(ids);
    }

    /**
     * Carries out one record. The transaction is the boundary: an unchecked exception from any file
     * operation in it -- a {@link SecurityException}, or anything else unexpected -- fails that
     * transaction and never the start (round-4 review; orchestrator decision 2026-09-30).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // the boundary of one transaction, by design
    private void applyGuarded(Record record) {
        String entryState = record.state;
        try {
            applyRecord(record);
        } catch (RecordRefused refused) {
            report(Level.WARNING, Keys.RECORD_REFUSED, recordFileOf(record).getAbsolutePath(), refused.getMessage());
        } catch (RuntimeException unexpected) {
            failUnexpectedly(record, entryState, unexpected);
        }
    }

    /**
     * An unchecked exception ended one transaction. An update being applied goes through the same
     * failure path as every other apply failure ({@link #failApply}): whatever step already
     * happened is undone, the record is {@code FAILED}, and one SEVERE line names the transaction
     * and the exception.
     *
     * <p>A record already past its apply ({@code APPLIED}, {@code COMMITTING}, {@code ROLLING_BACK}),
     * a {@code FAILED} one, or a removal is not re-marked: its state already says what is left,
     * every step is idempotent, and the next start finishes it, as after a crash at that point.
     * Marking a decided update {@code FAILED} instead would put the old JAR back beside a committed
     * new one. It is reported with one SEVERE line.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // the failure handling itself must not end the start
    private void failUnexpectedly(Record record, String entryState, RuntimeException error) {
        appliedThisStart.remove(record);
        if (Record.UPDATE.equals(record.type) && Record.PENDING.equals(entryState)) {
            try {
                failApply(record, recordFileOf(record), error);
                return;
            } catch (RecordRefused | RuntimeException again) {
                error.addSuppressed(again);
            }
        }
        report(Level.SEVERE, Keys.TRANSACTION_ERROR, recordFileOf(record).getAbsolutePath(), describe(error));
    }

    private void applyRecord(Record record) throws RecordRefused {
        if (Record.REMOVE.equals(record.type)) {
            applyRemoval(record);
            return;
        }
        if (!Record.UPDATE.equals(record.type)) {
            throw new RecordRefused("unknown record type " + record.type);
        }
        refuseLinkedFolders(record);
        switch (record.state) {
            case Record.PENDING:
                applyUpdate(record);
                break;
            case Record.APPLIED:
                // The previous start swapped the files and ended before it observed anything.
                // Nothing was observed, so nothing may be committed: restore before loading.
                record.state = Record.ROLLING_BACK;
                persist(record);
                finishRollback(record, RollbackPhase.UNCONFIRMED);
                break;
            case Record.COMMITTING:
                finishCommit(record);
                break;
            case Record.ROLLING_BACK:
                finishRollback(record, RollbackPhase.RESUMED);
                break;
            case Record.FAILED:
                restoreAfterFailure(record);
                break;
            default:
                throw new RecordRefused("unknown state " + record.state);
        }
    }

    /**
     * Decides every update applied at this start from what loaded: committed when the module is
     * loaded from the new JAR at the new version, rolled back otherwise.
     *
     * @param loaded     the modules that loaded at this start; empty when loading failed
     * @param codeSource which JAR a loaded module came from
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // one transaction never ends the start
    public void observeAfterLoad(List<UltiToolsPlugin> loaded, Function<UltiToolsPlugin, File> codeSource) {
        List<Record> decided = new ArrayList<>(appliedThisStart);
        appliedThisStart.clear();
        for (Record record : decided) {
            try {
                File target = confined(modulesFolder, record.targetName);
                if (isLoadedFrom(record, target, loaded, codeSource)) {
                    record.state = Record.COMMITTING;
                    if (!persist(record)) {
                        // The kept old JAR may be deleted only once the decision is on disk. The
                        // record still says APPLIED, so the next start restores the old version as
                        // unconfirmed -- deleting the old JAR now would leave it nothing to restore.
                        continue;
                    }
                    crashPoints.reached(CrashPoints.AFTER_DECISION_RECORDED);
                    finishCommit(record);
                } else {
                    record.state = Record.ROLLING_BACK;
                    persist(record);
                    crashPoints.reached(CrashPoints.AFTER_DECISION_RECORDED);
                    finishRollback(record, RollbackPhase.AFTER_LOAD);
                }
            } catch (RecordRefused refused) {
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFileOf(record).getAbsolutePath(),
                        refused.getMessage());
            } catch (RuntimeException unexpected) {
                // One transaction's problem never ends the start, and never hides a load failure.
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFileOf(record).getAbsolutePath(),
                        describe(unexpected));
            }
        }
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

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // a refusal of any kind fails the apply, never the start
    private void applyUpdate(Record record) throws RecordRefused {
        File work = workFolder(record);
        File old = confined(modulesFolder, record.oldName);
        File target = confined(modulesFolder, record.targetName);
        File backup = confined(new File(work, BACKUP_FOLDER), record.oldName);
        File staged = confined(new File(work, STAGED_FOLDER), record.stagedName);

        if (!present(staged)) {
            resumeWithoutStagedJar(record, target, staged);
            return;
        }
        if (stoppedBeforeMoving(record, work, old, target, backup, staged)) {
            return;
        }
        if (!present(backup)) {
            try {
                Files.createDirectories(backup.getParentFile().toPath());
                ops.move(old.toPath(), backup.toPath());
            } catch (IOException | RuntimeException e) {
                failApply(record, old, e);
                return;
            }
            crashPoints.reached(CrashPoints.AFTER_OLD_MOVED);
        }
        try {
            ops.move(staged.toPath(), target.toPath());
        } catch (IOException | RuntimeException e) {
            failApply(record, target, e);
            return;
        }
        crashPoints.reached(CrashPoints.AFTER_NEW_MOVED);
        markApplied(record);
    }

    /**
     * The checks an apply makes before it moves anything; each that fails ends the transaction.
     *
     * @return whether the transaction was ended (failed or abandoned)
     */
    private boolean stoppedBeforeMoving(Record record, File work, File old, File target, File backup, File staged)
            throws RecordRefused {
        // The staged JAR must still be the file that was downloaded, checked before anything in the
        // modules folder is touched -- on a first attempt and on one resumed after the old JAR moved.
        String actual = sha256Of(staged);
        if (!record.stagedSha256.equals(actual)) {
            failStagedIdentity(record, staged, actual == null ? MISSING : actual);
            return true;
        }
        if (abandonedBeforeApply(record, work, old, backup)) {
            return true;
        }
        boolean oldStillAtTarget = record.targetName.equals(record.oldName) && !present(backup);
        if (present(target) && !oldStillAtTarget) {
            failApply(record, target, new FileAlreadyExistsException(target.getAbsolutePath(), null,
                    "a file already has the new JAR's name; it is never replaced"));
            return true;
        }
        return false;
    }

    /**
     * The staged JAR is not in its folder: either the new JAR was moved in and the start ended
     * before {@code APPLIED} was recorded, or the staged JAR is gone. Its content decides which --
     * never its name.
     */
    private void resumeWithoutStagedJar(Record record, File target, File staged) throws RecordRefused {
        if (isStagedJar(target, record)) {
            markApplied(record);
        } else {
            failStagedIdentity(record, staged, MISSING);
        }
    }

    /**
     * The staged JAR is not the file recorded at staging (changed, or gone): the apply fails through
     * {@link #failApply}, so a kept old JAR goes back and the record is {@code FAILED}; the line
     * names the staged path and both hashes. Nothing is downloaded again.
     */
    private void failStagedIdentity(Record record, File staged, String actual) throws RecordRefused {
        String detail = "the staged JAR is not the one recorded at staging: expected SHA-256 "
                + record.stagedSha256 + ", actual " + actual;
        Throwable error = MISSING.equals(actual)
                ? new NoSuchFileException(staged.getAbsolutePath(), null, detail)
                : new IOException(detail);
        failApply(record, staged, error, new Report(Level.SEVERE, Keys.STAGED_JAR_MISMATCH, record.moduleName,
                staged.getAbsolutePath(), record.stagedSha256, actual));
    }

    /**
     * Abandons the transaction, before anything is moved, when the old JAR is no longer the one
     * staged against. Only while the old JAR is still in the modules folder: once it is kept aside,
     * the kept copy is the one that was checked.
     *
     * <ul>
     *   <li>The module's JAR left the modules folder after the update was staged -- an uninstall,
     *       whatever name it was given, or a hand removal -- or an uninstall recorded it for a
     *       deletion that has not happened yet. Installing the update now would bring back a module
     *       the operator removed.</li>
     *   <li>A different file now has the old JAR's name -- an install of the current version, a
     *       hand-made hotfix. Moving it aside would make the commit delete it; its content is
     *       compared with the SHA-256 recorded at staging, so the same bytes put back still count.</li>
     * </ul>
     *
     * @return whether the transaction was abandoned
     */
    private boolean abandonedBeforeApply(Record record, File work, File old, File backup) {
        if (exists(backup)) {
            return false;
        }
        String key;
        if (!exists(old) || pendingRemovals.contains(record.oldName)) {
            key = Keys.UPDATE_ABANDONED;
        } else if (!record.oldSha256.equals(sha256Of(old))) {
            key = Keys.UPDATE_ABANDONED_REPLACED;
        } else {
            return false;
        }
        deleteTree(work);
        deleteQuietly(recordFileOf(record));
        report(Level.WARNING, key, record.moduleName, record.newVersion, old.getAbsolutePath());
        return true;
    }

    /** An existence check of the apply or a rollback, through {@link FileOps} so a test can make it fail. */
    private boolean present(File file) {
        return ops.exists(file.toPath());
    }

    private void markApplied(Record record) {
        record.state = Record.APPLIED;
        persist(record);
        crashPoints.reached(CrashPoints.AFTER_APPLIED_RECORDED);
        appliedThisStart.add(record);
    }

    /**
     * The apply could not be made. The old JAR goes back if it was moved, the record is kept as
     * {@code FAILED} for the next {@code /upm update} to report, and one SEVERE line names the file
     * and the error.
     */
    private void failApply(Record record, File file, Throwable error) throws RecordRefused {
        failApply(record, file, error, null);
    }

    /**
     * {@link #failApply(Record, File, Throwable)} with the line to log, when the old JAR is back in
     * place, given by the caller instead of the generic one.
     *
     * @param specific the line to log when nothing is left out of place, or {@code null} for the generic one
     */
    private void failApply(Record record, File file, Throwable error, Report specific) throws RecordRefused {
        String restoreError = returnStagedJar(record);
        if (restoreError == null) {
            restoreError = restoreBackup(record);
        }
        record.state = Record.FAILED;
        if (restoreError == null && specific != null) {
            record.failure = file.getAbsolutePath() + ": " + describe(error);
            persist(record);
            reports.add(specific);
            return;
        }
        if (restoreError == null && isAcrossFileSystems(error)) {
            // plugins/ is on another file system than the records folder: the swap is a rename, and
            // a rename cannot cross file systems. Nothing moved; there is no copy fallback.
            record.failure = "the modules folder " + modulesFolder.getAbsolutePath() + " and the update folder "
                    + transactionsFolder.getAbsolutePath() + " are on different file systems: " + describe(error);
            persist(record);
            report(Level.SEVERE, Keys.APPLY_CROSS_FILE_SYSTEM, record.moduleName, record.oldVersion,
                    record.newVersion, modulesFolder.getAbsolutePath(), transactionsFolder.getAbsolutePath(),
                    record.oldVersion);
            return;
        }
        record.failure = file.getAbsolutePath() + ": " + describe(error)
                + (restoreError == null ? "" : "; moving the previous JAR back also failed: " + restoreError);
        persist(record);
        if (restoreError == null) {
            report(Level.SEVERE, Keys.APPLY_FAILED, record.moduleName, record.oldVersion, record.newVersion,
                    file.getAbsolutePath(), describe(error), record.oldVersion);
        } else {
            report(Level.SEVERE, Keys.APPLY_FAILED_UNRESTORED, record.moduleName, record.oldVersion,
                    record.newVersion, file.getAbsolutePath(), describe(error), restoreError,
                    backupOf(record).getAbsolutePath(), confined(modulesFolder, record.oldName).getAbsolutePath());
        }
    }

    /**
     * Moves the new JAR back to the staged folder when it was already moved into the modules
     * folder -- recognised by its recorded hash, so nothing else is ever moved. Part of undoing a
     * failed apply; the old JAR goes back after it.
     *
     * @return {@code null} when the new JAR is not in the modules folder (or is back), otherwise what failed
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private String returnStagedJar(Record record) throws RecordRefused {
        File target = confined(modulesFolder, record.targetName);
        File staged = confined(new File(workFolder(record), STAGED_FOLDER), record.stagedName);
        if (exists(staged) || !isStagedJar(target, record)) {
            return null;
        }
        try {
            Files.createDirectories(staged.getParentFile().toPath());
            ops.move(target.toPath(), staged.toPath());
            return null;
        } catch (IOException | RuntimeException e) {
            return target.getAbsolutePath() + " could not be moved back: " + describe(e);
        }
    }

    /** Whether a move failed because its two ends are on different file systems. */
    private static boolean isAcrossFileSystems(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AtomicMoveNotSupportedException) {
                return true;
            }
        }
        return false;
    }

    /** A failed apply found at a later start: make sure the old JAR is back, and say so if it cannot be. */
    private void restoreAfterFailure(Record record) throws RecordRefused {
        String restoreError = restoreBackup(record);
        if (restoreError != null) {
            report(Level.SEVERE, Keys.RESTORE_FAILED, record.moduleName, backupOf(record).getAbsolutePath(),
                    confined(modulesFolder, record.oldName).getAbsolutePath(), restoreError);
        }
    }

    /**
     * Moves the kept old JAR back into the modules folder when it is not there.
     *
     * @return {@code null} when the old JAR is in place (or there was none), otherwise what failed
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private String restoreBackup(Record record) throws RecordRefused {
        File backup = backupOf(record);
        File old = confined(modulesFolder, record.oldName);
        if (!exists(backup)) {
            return null;
        }
        if (exists(old)) {
            return old.getAbsolutePath() + " is occupied";
        }
        try {
            ops.move(backup.toPath(), old.toPath());
            return null;
        } catch (IOException | RuntimeException e) {
            return describe(e);
        }
    }

    /**
     * Discards a {@code FAILED} record before staging again: the old JAR goes back if it is still
     * aside, then the working folder and the record are deleted.
     *
     * @return {@code null} on success, otherwise why the old JAR could not be put back
     */
    private String discardFailed(Record record) {
        try {
            String restoreError = restoreBackup(record);
            if (restoreError != null) {
                return restoreError;
            }
        } catch (RecordRefused refused) {
            return refused.getMessage();
        }
        deleteTree(workFolder(record));
        deleteQuietly(recordFileOf(record));
        return null;
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

    private void finishCommit(Record record) {
        if (!deleteTree(workFolder(record)) || !deleteQuietly(recordFileOf(record))) {
            // Kept as COMMITTING: the next start finishes it. The module is updated either way.
            report(Level.WARNING, Keys.CLEANUP_DEFERRED, record.moduleName, workFolder(record).getAbsolutePath());
            return;
        }
        report(Level.INFO, Keys.COMMITTED, record.moduleName, record.oldVersion, record.newVersion);
    }

    /**
     * Removes the new JAR -- recognised by content, so nothing else is ever deleted -- and puts the
     * old JAR back. A step that fails leaves the record at {@code ROLLING_BACK} for the next start,
     * which finishes it before the modules load.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void finishRollback(Record record, RollbackPhase phase) throws RecordRefused {
        File target = confined(modulesFolder, record.targetName);
        File old = confined(modulesFolder, record.oldName);
        File backup = backupOf(record);
        if (!present(backup)) {
            // Nothing is kept to put back. Either an earlier attempt already put it back (then the
            // new JAR is gone too, and only the record is left to clean up), or the kept JAR went
            // missing -- and removing the new JAR then would leave no version at all, so it stays.
            if (isStagedJar(target, record)) {
                report(Level.WARNING, Keys.ROLLBACK_NO_BACKUP, record.moduleName, backup.getAbsolutePath(),
                        target.getAbsolutePath());
            }
            deleteTree(workFolder(record));
            deleteQuietly(recordFileOf(record));
            return;
        }
        try {
            if (isStagedJar(target, record)) {
                ops.delete(target.toPath());
            }
            if (exists(old)) {
                throw new FileAlreadyExistsException(old.getAbsolutePath(), null,
                        "the previous JAR's name is taken, so it cannot be put back");
            }
            ops.move(backup.toPath(), old.toPath());
        } catch (IOException | RuntimeException e) {
            report(Level.SEVERE, phase == RollbackPhase.AFTER_LOAD ? Keys.ROLLBACK_DEFERRED
                    : Keys.ROLLBACK_FAILED_BEFORE_LOAD, record.moduleName, record.newVersion, record.oldVersion,
                    describe(e));
            return;
        }
        deleteTree(workFolder(record));
        deleteQuietly(recordFileOf(record));
        if (phase == RollbackPhase.AFTER_LOAD) {
            report(Level.WARNING, Keys.ROLLED_BACK, record.moduleName, record.newVersion, record.oldVersion,
                    record.oldVersion);
        } else if (phase == RollbackPhase.UNCONFIRMED) {
            report(Level.WARNING, Keys.UNCONFIRMED_ROLLED_BACK, record.moduleName, record.newVersion,
                    record.oldVersion, record.oldVersion);
        } else {
            report(Level.INFO, Keys.ROLLBACK_FINISHED, record.moduleName, record.oldVersion, record.oldVersion);
        }
    }

    /**
     * Deletes the JARs an uninstall recorded, before any module loads: each only if it is still
     * the file that was recorded. One that still cannot be deleted stays recorded for the next
     * start and is reported; one replaced since is left alone and reported.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void applyRemoval(Record record) throws RecordRefused {
        List<Removal> recorded = record.removals == null ? Collections.<Removal>emptyList() : record.removals;
        List<File> targets = new ArrayList<>();
        for (Removal removal : recorded) {
            // Every name is checked before any file is touched, so a record with one bad name
            // deletes nothing at all.
            targets.add(confined(modulesFolder, removal.name));
        }
        List<Removal> remaining = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (int i = 0; i < recorded.size(); i++) {
            Removal removal = recorded.get(i);
            File file = targets.get(i);
            if (!exists(file)) {
                continue;
            }
            if (!isStillRecorded(removal, file)) {
                report(Level.WARNING, Keys.REMOVAL_SKIPPED, file.getAbsolutePath());
                continue;
            }
            try {
                ops.delete(file.toPath());
                deleted.add(file.getAbsolutePath());
            } catch (IOException | RuntimeException e) {
                report(Level.SEVERE, Keys.REMOVAL_FAILED, file.getAbsolutePath(), describe(e));
                remaining.add(removal);
                pendingRemovals.add(removal.name);
            }
        }
        if (!deleted.isEmpty()) {
            report(Level.INFO, Keys.REMOVED, String.join(", ", deleted));
        }
        if (remaining.isEmpty()) {
            deleteQuietly(recordFileOf(record));
        } else {
            record.removals = remaining;
            persist(record);
        }
    }

    /**
     * Whether a file is still the one an uninstall recorded: same size, same modification time,
     * same content. A copy put back after the uninstall has a new timestamp even when its bytes are
     * the same.
     */
    private static boolean isStillRecorded(Removal removal, File file) {
        return removal.sha256 != null && file.length() == removal.size
                && file.lastModified() == removal.lastModified && removal.sha256.equals(sha256Of(file));
    }

    /** Whether {@code file} is the JAR this transaction staged, by content. */
    private static boolean isStagedJar(File file, Record record) {
        if (record.stagedSha256 == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return record.stagedSha256.equals(sha256Of(file));
    }

    /**
     * The working folder and its two subfolders are this class's own; one that is a symbolic link
     * would take every name confined to it somewhere else, so the record is refused. (The
     * transactions folder itself, and the modules folder, may be links an operator set up.)
     */
    private void refuseLinkedFolders(Record record) throws RecordRefused {
        File work = workFolder(record);
        for (File folder : new File[]{work, new File(work, BACKUP_FOLDER), new File(work, STAGED_FOLDER)}) {
            if (Files.isSymbolicLink(folder.toPath())) {
                throw new RecordRefused(folder.getAbsolutePath() + " is a symbolic link");
            }
        }
    }

    private File backupOf(Record record) throws RecordRefused {
        // The folder is this transaction's working folder plus a constant; the record's name goes
        // through confined(), which refuses anything but a plain JAR name directly inside it.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        return confined(new File(workFolder(record), BACKUP_FOLDER), record.oldName);
    }

    // ------------------------------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------------------------------

    /**
     * The record files, sorted by name.
     *
     * @return the records; empty when the transactions folder does not exist
     * @throws IOException when the folder exists -- or cannot be shown not to -- but cannot be
     *                     listed; a failed listing is never read as "no records"
     */
    private List<File> recordFiles() throws IOException {
        File[] files;
        try {
            files = transactionsFolder.listFiles((dir, name) -> name.endsWith(RECORD_SUFFIX));
            if (files == null && Files.notExists(transactionsFolder.toPath())) {
                return Collections.emptyList();
            }
        } catch (SecurityException e) {
            throw new IOException(transactionsFolder.getAbsolutePath() + " cannot be listed: " + describe(e), e);
        }
        if (files == null) {
            throw new IOException(transactionsFolder.getAbsolutePath() + " cannot be listed");
        }
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
        return sorted;
    }

    private Record readRecordOrReport(File recordFile) {
        try {
            Record record = readRecord(recordFile);
            String problem = record == null ? "unrecognised record" : problemWith(record, recordFile);
            if (problem != null) {
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFile.getAbsolutePath(), problem);
                return null;
            }
            return record;
        } catch (IOException | JsonParseException e) {
            report(Level.WARNING, Keys.RECORD_UNREADABLE, recordFile.getAbsolutePath(), describe(e));
            return null;
        }
    }

    /**
     * Why a record read from a file cannot be carried out, or {@code null} when it can: the one
     * validation start-up and staging share.
     */
    private String problemWith(Record record, File recordFile) {
        if (record.type == null || record.key == null || record.state == null
                || !recordFile.getName().equals(recordFileOf(record).getName())) {
            return "unrecognised record";
        }
        String missing = missingField(record);
        return missing == null ? null : "missing field " + missing;
    }

    /** The first field a record of its type needs and lacks, or {@code null} when it is complete. */
    private static String missingField(Record record) {
        if (Record.REMOVE.equals(record.type)) {
            if (record.removals == null || record.removals.isEmpty()) {
                return "removals";
            }
            for (Removal removal : record.removals) {
                if (removal == null || removal.name == null || removal.sha256 == null) {
                    return "removals[].name/sha256";
                }
            }
            return null;
        }
        String[][] fields = {
            {"moduleName", record.moduleName}, {"oldName", record.oldName}, {"oldVersion", record.oldVersion},
            {"oldSha256", record.oldSha256}, {"stagedName", record.stagedName}, {"targetName", record.targetName},
            {"newVersion", record.newVersion}, {"stagedSha256", record.stagedSha256}};
        for (String[] field : fields) {
            if (field[1] == null) {
                return field[0];
            }
        }
        return null;
    }

    /**
     * Reads one record.
     *
     * @return the record, or {@code null} for an empty file
     * @throws IOException when the file cannot be read, or cannot be parsed as a record -- a parse
     *                     failure (Gson's unchecked {@link JsonParseException}, or any other unchecked
     *                     exception while parsing) becomes an {@code IOException} naming the file, so
     *                     every reader handles a malformed record through its checked path
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // any parse failure means "this record cannot be read"
    private static Record readRecord(File recordFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(recordFile.toPath(), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, Record.class);
        } catch (RuntimeException malformed) {
            throw new IOException(recordFile.getAbsolutePath() + " is not a readable record: " + describe(malformed),
                    malformed);
        }
    }

    /**
     * Writes a record atomically: a temporary file in the same folder, then an atomic move over the
     * record, so a crash leaves either the previous record or the new one, never a torn file.
     */
    private void writeRecord(Record record) throws IOException {
        Files.createDirectories(transactionsFolder.toPath());
        Path temporary = Files.createTempFile(transactionsFolder.toPath(), TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
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

    /**
     * {@link #writeRecord(Record)} at start-up, where a failure is a report line, not an abort.
     *
     * @return whether the record is on disk
     */
    private boolean persist(Record record) {
        try {
            writeRecord(record);
            return true;
        } catch (IOException e) {
            report(Level.SEVERE, Keys.RECORD_WRITE_FAILED, recordFileOf(record).getAbsolutePath(), describe(e));
            return false;
        }
    }

    /** A crash while writing a record leaves a temporary file; it is never a record. */
    private void deleteTemporaryRecords() {
        File[] temporaries;
        try {
            temporaries = transactionsFolder.listFiles((dir, name) -> name.startsWith(TEMPORARY_PREFIX)
                    && name.endsWith(TEMPORARY_SUFFIX));
        } catch (SecurityException e) {
            // Clean-up only: a leftover temporary file is never read as a record.
            LOGGER.log(Level.FINE, "Could not list " + transactionsFolder, e);
            return;
        }
        if (temporaries != null) {
            for (File temporary : temporaries) {
                deleteQuietly(temporary);
            }
        }
    }

    /**
     * A working folder no record refers to was left by a crash during staging, before its record
     * was written: it can hold only a partial download, which is deleted. One that holds a kept old
     * JAR is never deleted without its record; it is reported instead.
     */
    private void sweepOrphanWorkFolders(List<String> ids) {
        File[] folders;
        try {
            folders = transactionsFolder.listFiles(File::isDirectory);
        } catch (SecurityException e) {
            // Clean-up only: an orphan folder is left for a later start, never deleted unread.
            LOGGER.log(Level.FINE, "Could not list " + transactionsFolder, e);
            return;
        }
        if (folders == null) {
            return;
        }
        for (File folder : folders) {
            if (ids.contains(folder.getName())) {
                continue;
            }
            File backups = new File(folder, BACKUP_FOLDER);
            if (mayHoldFiles(backups)) {
                String[] kept = namesIn(backups);
                report(Level.WARNING, Keys.ORPHAN_BACKUP, backups.getAbsolutePath(),
                        kept == null ? "(the folder cannot be listed)" : String.join(", ", kept));
            } else {
                deleteTree(folder);
            }
        }
    }

    private File recordFileOf(Record record) {
        // idOf() is a lower-cased type plus 16 hex digits of a SHA-256: no separator or ".." can
        // reach the name, whatever the record contains.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        return new File(transactionsFolder, idOf(record) + RECORD_SUFFIX);
    }

    private File workFolder(Record record) {
        // Same name as recordFileOf() without the suffix: a hash, never text from the record.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        return new File(transactionsFolder, idOf(record));
    }

    /** The name a record's files take: its type and a hash of its key, never text from the record. */
    private static String idOf(Record record) {
        return record.type.toLowerCase(Locale.ROOT) + "-"
                + sha256(record.type + ":" + record.key).substring(0, 16);
    }

    private static String stripSuffix(String name) {
        return name.endsWith(RECORD_SUFFIX) ? name.substring(0, name.length() - RECORD_SUFFIX.length()) : name;
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
        if (!isJarName(name)) {
            throw new RecordRefused("not a JAR file name: " + name);
        }
        // This is the confinement itself: the name was refused above unless it is a plain JAR file
        // name, and the canonical check below refuses one that resolves anywhere but directly in
        // the folder (a link included).
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        File file = new File(folder, name);
        if (!isDirectChild(folder, file)) {
            throw new RecordRefused(file.getAbsolutePath() + " resolves outside " + folder.getAbsolutePath());
        }
        return file;
    }

    /**
     * Whether a folder holds anything, or might: {@code true} when it lists an entry, and also
     * when it exists (or cannot be shown not to) but cannot be listed -- a failed listing is never
     * read as an empty folder.
     */
    private static boolean mayHoldFiles(File folder) {
        String[] names = namesIn(folder);
        if (names != null) {
            return names.length > 0;
        }
        try {
            return !Files.notExists(folder.toPath());
        } catch (SecurityException e) {
            return true;
        }
    }

    /** The names in a folder, or {@code null} when it does not exist or cannot be listed. */
    private static String[] namesIn(File folder) {
        try {
            return folder.list();
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Whether a file name is one the module loader would load: it ends in {@code .jar}. */
    private static boolean isJarName(String name) {
        return name != null && name.endsWith(".jar");
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

    private static boolean deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not delete " + file, e);
            return false;
        }
    }

    /**
     * Deletes what staging put in a working folder, and the folder itself only if nothing else is
     * in it -- a kept old JAR is never deleted by staging.
     */
    private static void discardStaging(File work) {
        deleteTree(new File(work, STAGED_FOLDER));
        // Deleted only when shown empty: a folder that cannot be listed may hold a kept old JAR.
        if (!mayHoldFiles(work)) {
            deleteQuietly(work);
        }
    }

    /**
     * Deletes a folder this class derived itself (never one named by a record), bottom-up.
     *
     * @return whether it is gone
     */
    private static boolean deleteTree(File folder) {
        if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            Collections.reverse(paths);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | SecurityException | java.io.UncheckedIOException e) {
            LOGGER.log(Level.FINE, "Could not delete " + folder, e);
            return false;
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

    /** The identify-string normalisation every install, update and lookup shares (one implementation). */
    private static String normalize(String identifyString) {
        return PluginInstallUtils.normalizeIdentifyString(identifyString);
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : error.getClass().getSimpleName() + ": " + message;
    }

    private static String sha256(String text) {
        return ResourceHashSidecar.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A file's SHA-256 through the framework's one hashing helper, or {@code null} when it cannot be read. */
    static String sha256Of(File file) {
        try {
            return ResourceHashSidecar.sha256(file);
        } catch (java.io.UncheckedIOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not read " + file, e);
            return null;
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

    /** How files are moved and deleted; a test substitutes a failing one. */
    interface FileOps {
        /**
         * An atomic rename that never replaces an existing file, and a plain delete. A rename
         * across file systems throws {@link AtomicMoveNotSupportedException}, which the apply
         * reports as such; it is never replaced by a copy.
         */
        FileOps DEFAULT = new FileOps() {
            @Override
            public void move(Path from, Path to) throws IOException {
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(to.toString());
                }
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void delete(Path path) throws IOException {
                Files.deleteIfExists(path);
            }
        };

        void move(Path from, Path to) throws IOException;

        void delete(Path path) throws IOException;

        /**
         * Whether a file exists, links not followed -- the existence checks of an apply.
         *
         * @param path the file
         * @return whether it exists
         */
        default boolean exists(Path path) {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }
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

    /** A record whose names would leave their folder, or that this version does not understand. */
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
        static final String REMOVE = "REMOVE";
        static final String PENDING = "PENDING";
        static final String APPLIED = "APPLIED";
        static final String COMMITTING = "COMMITTING";
        static final String ROLLING_BACK = "ROLLING_BACK";
        static final String FAILED = "FAILED";

        String type;
        String key;
        String state;
        String moduleName;
        String oldName;
        String oldVersion;
        /** The old JAR's content when the update was staged; the apply moves only that file aside. */
        String oldSha256;
        String stagedName;
        String targetName;
        String newVersion;
        String stagedSha256;
        String failure;
        List<Removal> removals = new ArrayList<>();
    }

    /** One file a {@code REMOVE} record deletes at the next start, and what it was when recorded. */
    @SuppressWarnings("PMD.DataClass")
    static final class Removal {
        String name;
        long size;
        long lastModified;
        String sha256;
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
            /** An update of this module is already staged; nothing changed. */
            ALREADY_STAGED,
            /** An earlier update of this module is still being finished at the next start; nothing changed. */
            BUSY,
            /** Nothing was staged; the reason says why. */
            FAILED
        }

        private final Outcome outcome;
        private final String moduleName;
        private final String oldVersion;
        private final String newVersion;
        private final String previousFailure;
        private final String reasonKey;
        private final Object[] reasonArgs;

        @SuppressWarnings("PMD.ExcessiveParameterList")
        private StageResult(Outcome outcome, String moduleName, String oldVersion, String newVersion,
                            String previousFailure, String reasonKey, Object[] reasonArgs) {
            this.outcome = outcome;
            this.moduleName = moduleName;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
            this.previousFailure = previousFailure;
            this.reasonKey = reasonKey;
            this.reasonArgs = reasonArgs.clone();
        }

        static StageResult of(Outcome outcome, String moduleName, String oldVersion, String newVersion,
                              String previousFailure) {
            return new StageResult(outcome, moduleName, oldVersion, newVersion, previousFailure, null,
                    new Object[0]);
        }

        static StageResult failed(String previousFailure, String reasonKey, Object... reasonArgs) {
            return new StageResult(Outcome.FAILED, null, null, null, previousFailure, reasonKey, reasonArgs);
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

        /**
         * An earlier start's failed apply of this module -- file and error -- which the operator is
         * told about before anything else, or {@code null}.
         *
         * @return the failure, or {@code null}
         */
        public String getPreviousFailure() {
            return previousFailure;
        }

        /** @return the catalogue key of the reason nothing was staged, or {@code null} */
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
        public static final String ROLLED_BACK = "模块 %s 更新到 %s 后没有加载，已恢复为 %s；%s 将在下次启动时加载。";
        public static final String ROLLBACK_NO_BACKUP = "模块 %s 保留的旧版本 JAR（%s）不见了，无法回滚；%s 保持不动。";
        public static final String UNCONFIRMED_ROLLED_BACK =
                "上次启动在确认模块 %s 的 %s 版本是否加载之前就结束了；已恢复为 %s，本次启动加载 %s。";
        public static final String ROLLBACK_DEFERRED =
                "模块 %s 更新到 %s 后没有加载；恢复 %s 未能在本次完成（%s），将在下次启动、加载模块之前完成。";
        public static final String ROLLBACK_FAILED_BEFORE_LOAD = "模块 %s 从 %s 恢复为 %s 未能完成（%s）；下次启动会再试。";
        public static final String ROLLBACK_FINISHED = "模块 %s 的回滚已完成：已恢复为 %s，本次启动加载 %s。";
        public static final String APPLY_FAILED =
                "模块 %s 的更新（%s → %s）未能应用：%s：%s。模块目录未改变，本次启动加载 %s；下次执行 /upm update 时会再次报告。";
        public static final String APPLY_CROSS_FILE_SYSTEM =
                "模块 %s 的更新（%s → %s）未能应用：模块目录 %s 与更新目录 %s 不在同一个文件系统上，无法原子移动。模块目录未改变，本次启动加载 %s；请把两个目录放到同一个文件系统上，再执行 /upm update。";
        public static final String STAGED_JAR_MISMATCH =
                "模块 %s 暂存的 JAR %s 已不是暂存时下载的文件（预期 SHA-256 %s，实际 %s），更新未应用；模块目录未改变，下次执行 /upm update 时会再次报告。";
        public static final String APPLY_FAILED_UNRESTORED =
                "模块 %s 的更新（%s → %s）未能应用：%s：%s；把旧版本移回也失败了（%s）。旧版本 JAR 在 %s，请停止服务器后手动移回 %s。";
        public static final String RESTORE_FAILED = "模块 %s 的旧版本 JAR 仍在 %s，未能移回 %s（%s）；请停止服务器后手动移回。";
        public static final String RECORDS_UNLISTABLE =
                "更新目录 %s 无法列出（%s）；本次启动没有执行任何暂存的更新或卸载时记录的删除，它们保持原样，下次启动会再试。";
        public static final String TRANSACTION_ERROR =
                "更新记录 %s 处理时出错（%s）；记录保持原样，下次启动会从它记录的步骤继续。";
        public static final String CLEANUP_DEFERRED = "模块 %s 的更新已确认，但更新目录 %s 未能清理，下次启动会再清理。";
        public static final String ORPHAN_BACKUP = "更新目录 %s 中有不属于任何更新记录的旧版本 JAR，已保留：%s";
        public static final String UPDATE_ABANDONED = "模块 %s 暂存的 %s 版本更新已放弃：它的 JAR %s 在暂存之后已不在模块目录中（例如已卸载）。";
        public static final String UPDATE_ABANDONED_REPLACED =
                "模块 %s 暂存的 %s 版本更新已放弃：它的 JAR %s 在暂存之后已被替换（例如重新安装或手动替换），替换后的文件保持不动。";
        public static final String REMOVED = "已删除卸载时未能删除的模块 JAR：%s。";
        public static final String REMOVAL_FAILED = "卸载时记录的模块 JAR %s 仍无法删除（%s）；本次启动会再次加载它，下次启动会再试。";
        public static final String REMOVAL_SKIPPED = "卸载时记录的模块 JAR %s 自卸载后已被替换，未删除。";
        public static final String RECORD_REFUSED = "更新记录 %s 已拒绝执行：%s";
        public static final String RECORD_UNREADABLE = "更新记录 %s 无法读取，已跳过：%s";
        public static final String RECORD_WRITE_FAILED = "更新记录 %s 无法写入：%s";
        public static final String REASON_NO_IDENTIFY_STRING = "该模块没有 identify-string";
        public static final String REASON_NOT_LOADED = "没有已加载的模块声明 identify-string %s";
        public static final String REASON_NOT_IN_MODULES_FOLDER = "模块的 JAR %s 不在模块目录 %s 中";
        public static final String REASON_SHARED_JAR = "它的 JAR %s 同时也是已加载模块 %s 的 JAR，更新会把该模块一起替换";
        public static final String REASON_OLD_JAR_UNREADABLE = "模块当前的 JAR %s 无法读取";
        public static final String REASON_NO_DOWNLOAD = "云端没有 %s 的可下载版本";
        public static final String REASON_DOWNLOAD_FAILED = "下载失败：%s";
        public static final String REASON_INVALID_JAR = "下载的文件 %s 不是有效的模块 JAR";
        public static final String REASON_WRONG_IDENTITY = "下载的 %s 没有声明 identify-string %s 和版本号";
        public static final String REASON_WRONG_VERSION = "下载的 %s 声明的版本是 %s，不是云端给出的最新版本 %s";
        public static final String REASON_RECORD_FAILED = "更新记录无法写入：%s";
        public static final String REASON_RECORD_UNREADABLE = "该模块已有的更新记录 %s 无法读取：%s";
        public static final String REASON_LEFTOVER_BACKUP = "%s 中有上一次更新留下、没有记录的旧版本 JAR；请先检查并移走它";
        public static final String REASON_PREVIOUS_UNRESTORED = "上一次更新留下的旧版本 JAR 无法移回模块目录：%s";

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
