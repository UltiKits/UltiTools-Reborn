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
 * new JAR at the new version, and otherwise rolled back -- the old JAR restored, the new one
 * removed. No file in the live modules folder changes while the server runs.
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
    private static final String TEMPORARY_PREFIX = "record-";
    private static final String TEMPORARY_SUFFIX = ".tmp";
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
    private final FileOps ops;
    private final CrashPoints crashPoints;
    private final List<Report> reports = new ArrayList<>();
    private final List<Record> appliedThisStart = new ArrayList<>();

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
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // a catalogue or download failure of any kind is reported
    public StageResult stageUpdate(String identifyString, List<UltiToolsPlugin> loaded,
                                   Function<UltiToolsPlugin, File> codeSource, Catalogue catalogue,
                                   Downloader downloader) {
        synchronized (LOCK) {
            String key = normalize(identifyString);
            if (key == null) {
                return StageResult.failed(null, Keys.REASON_NO_IDENTIFY_STRING);
            }
            Record lookup = new Record();
            lookup.type = Record.UPDATE;
            lookup.key = key;
            File existingFile = recordFileOf(lookup);
            String previousFailure = null;
            if (exists(existingFile)) {
                Record existing;
                try {
                    existing = readRecord(existingFile);
                } catch (IOException | JsonParseException e) {
                    return StageResult.failed(null, Keys.REASON_RECORD_UNREADABLE,
                            existingFile.getAbsolutePath(), describe(e));
                }
                if (existing == null || existing.state == null) {
                    return StageResult.failed(null, Keys.REASON_RECORD_UNREADABLE,
                            existingFile.getAbsolutePath(), "empty record");
                }
                if (Record.PENDING.equals(existing.state)) {
                    return StageResult.of(StageResult.Outcome.ALREADY_STAGED, existing.moduleName,
                            existing.oldVersion, existing.newVersion, null);
                }
                if (!Record.FAILED.equals(existing.state)) {
                    return StageResult.of(StageResult.Outcome.BUSY, existing.moduleName, existing.oldVersion,
                            existing.newVersion, null);
                }
                previousFailure = existing.failure;
                String restoreError = discardFailed(existing);
                if (restoreError != null) {
                    return StageResult.failed(previousFailure, Keys.REASON_PREVIOUS_UNRESTORED, restoreError);
                }
            }

            UltiToolsPlugin module = null;
            for (UltiToolsPlugin plugin : loaded) {
                if (key.equals(normalize(plugin.getIdentifyString()))) {
                    module = plugin;
                    break;
                }
            }
            if (module == null) {
                return StageResult.failed(previousFailure, Keys.REASON_NOT_LOADED, identifyString);
            }
            File oldJar = codeSource.apply(module);
            if (oldJar == null || !isDirectChild(modulesFolder, oldJar)) {
                return StageResult.failed(previousFailure, Keys.REASON_NOT_IN_MODULES_FOLDER,
                        oldJar == null ? "?" : oldJar.getAbsolutePath(), modulesFolder.getAbsolutePath());
            }
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

            Record record = new Record();
            record.type = Record.UPDATE;
            record.key = key;
            File work = workFolder(record);
            File stagedFolder = new File(work, STAGED_FOLDER);
            discardStaging(work);
            try {
                Files.createDirectories(stagedFolder.toPath());
                downloader.download(link, targetName, stagedFolder);
            } catch (IOException | RuntimeException e) {
                discardStaging(work);
                return StageResult.failed(previousFailure, Keys.REASON_DOWNLOAD_FAILED, describe(e));
            }
            File staged = new File(stagedFolder, targetName);
            if (!staged.isFile() || !SecurityPolicy.isValidModuleJar(staged)) {
                discardStaging(work);
                return StageResult.failed(previousFailure, Keys.REASON_INVALID_JAR, targetName);
            }
            String stagedVersion = declaredVersion(staged, key);
            String stagedHash = sha256Of(staged);
            if (stagedVersion == null || stagedHash == null) {
                discardStaging(work);
                return StageResult.failed(previousFailure, Keys.REASON_WRONG_IDENTITY, targetName, key);
            }

            record.state = Record.PENDING;
            record.moduleName = module.getPluginName();
            record.oldName = oldJar.getName();
            record.oldVersion = module.getVersion();
            record.stagedName = targetName;
            record.targetName = targetName;
            record.newVersion = stagedVersion;
            record.stagedSha256 = stagedHash;
            try {
                writeRecord(record);
            } catch (IOException e) {
                discardStaging(work);
                return StageResult.failed(previousFailure, Keys.REASON_RECORD_FAILED, describe(e));
            }
            crashPoints.reached(CrashPoints.AFTER_RECORD_WRITTEN);
            return StageResult.of(StageResult.Outcome.STAGED, record.moduleName, record.oldVersion,
                    record.newVersion, previousFailure);
        }
    }

    /**
     * Cancels every update transaction of a module being uninstalled: its record and its working
     * folder -- the staged JAR and any kept old JAR -- are deleted, so nothing brings the module
     * back at the next start. The modules folder is not touched; the uninstall owns that.
     *
     * @param moduleName the module's runtime name
     * @return the versions whose updates were cancelled
     */
    public List<String> cancelStagedUpdates(String moduleName) {
        synchronized (LOCK) {
            List<String> cancelled = new ArrayList<>();
            for (File recordFile : recordFiles()) {
                Record record;
                try {
                    record = readRecord(recordFile);
                } catch (IOException | JsonParseException e) {
                    LOGGER.log(Level.FINE, "Could not read " + recordFile, e);
                    continue;
                }
                if (record == null || !Record.UPDATE.equals(record.type) || moduleName == null
                        || !moduleName.equals(record.moduleName)
                        || !recordFile.getName().equals(recordFileOf(record).getName())) {
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
     * <p>Each file is recorded with its size and SHA-256; the next start deletes it only if it is
     * still that file, so a newer install under the same name is never deleted.
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
                Record existing = readRecord(recordFile);
                if (existing != null && existing.removals != null) {
                    record.removals.addAll(existing.removals);
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
                removal.sha256 = hash;
                record.removals.removeIf(r -> removal.name.equals(r.name));
                record.removals.add(removal);
            }
            record.state = Record.PENDING;
            record.moduleName = moduleName;
            writeRecord(record);
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
        deleteTemporaryRecords();
        List<String> ids = new ArrayList<>();
        for (File recordFile : recordFiles()) {
            Record record = readRecordOrReport(recordFile);
            if (record == null) {
                ids.add(stripSuffix(recordFile.getName()));
                continue;
            }
            ids.add(idOf(record));
            try {
                applyRecord(record);
            } catch (RecordRefused refused) {
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFile.getAbsolutePath(), refused.getMessage());
            }
        }
        sweepOrphanWorkFolders(ids);
    }

    private void applyRecord(Record record) throws RecordRefused {
        if (Record.REMOVE.equals(record.type)) {
            applyRemoval(record);
            return;
        }
        if (!Record.UPDATE.equals(record.type)) {
            throw new RecordRefused("unknown record type " + record.type);
        }
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
    public void observeAfterLoad(List<UltiToolsPlugin> loaded, Function<UltiToolsPlugin, File> codeSource) {
        List<Record> decided = new ArrayList<>(appliedThisStart);
        appliedThisStart.clear();
        for (Record record : decided) {
            try {
                File target = confined(modulesFolder, record.targetName);
                if (isLoadedFrom(record, target, loaded, codeSource)) {
                    record.state = Record.COMMITTING;
                    persist(record);
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

        if (!exists(staged)) {
            // Either the new JAR was moved in and the start ended before APPLIED was recorded, or
            // the staged JAR is gone. Its content decides which -- never its name.
            if (isStagedJar(target, record)) {
                markApplied(record, backup);
            } else {
                failApply(record, staged, new NoSuchFileException(staged.getAbsolutePath(), null,
                        "the staged JAR is missing"));
            }
            return;
        }
        boolean oldStillAtTarget = record.targetName.equals(record.oldName) && !exists(backup);
        if (exists(target) && !oldStillAtTarget) {
            failApply(record, target, new FileAlreadyExistsException(target.getAbsolutePath(), null,
                    "a file already has the new JAR's name; it is never replaced"));
            return;
        }
        if (!exists(backup) && exists(old)) {
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
        markApplied(record, backup);
    }

    private void markApplied(Record record, File backup) {
        record.hadOld = exists(backup);
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
        String restoreError = restoreBackup(record);
        record.state = Record.FAILED;
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
        try {
            if (isStagedJar(target, record)) {
                ops.delete(target.toPath());
            }
            if (exists(backup)) {
                if (exists(old)) {
                    throw new FileAlreadyExistsException(old.getAbsolutePath(), null,
                            "the previous JAR's name is taken, so it cannot be put back");
                }
                ops.move(backup.toPath(), old.toPath());
            }
        } catch (IOException | RuntimeException e) {
            report(Level.SEVERE, phase == RollbackPhase.AFTER_LOAD ? Keys.ROLLBACK_DEFERRED
                    : Keys.ROLLBACK_FAILED_BEFORE_LOAD, record.moduleName, record.newVersion, record.oldVersion,
                    describe(e));
            return;
        }
        deleteTree(workFolder(record));
        deleteQuietly(recordFileOf(record));
        if (!record.hadOld) {
            report(Level.WARNING, Keys.ROLLED_BACK_NO_OLD, record.moduleName, record.newVersion);
        } else if (phase == RollbackPhase.AFTER_LOAD) {
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
            if (removal.sha256 == null || !removal.sha256.equals(sha256Of(file))) {
                report(Level.WARNING, Keys.REMOVAL_SKIPPED, file.getAbsolutePath());
                continue;
            }
            try {
                ops.delete(file.toPath());
                deleted.add(file.getAbsolutePath());
            } catch (IOException | RuntimeException e) {
                report(Level.SEVERE, Keys.REMOVAL_FAILED, file.getAbsolutePath(), describe(e));
                remaining.add(removal);
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

    /** Whether {@code file} is the JAR this transaction staged, by content. */
    private static boolean isStagedJar(File file, Record record) {
        if (record.stagedSha256 == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return record.stagedSha256.equals(sha256Of(file));
    }

    private File backupOf(Record record) throws RecordRefused {
        return confined(new File(workFolder(record), BACKUP_FOLDER), record.oldName);
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
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFile.getAbsolutePath(), "unrecognised record");
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

    /** {@link #writeRecord(Record)} at start-up, where a failure is a report line, not an abort. */
    private void persist(Record record) {
        try {
            writeRecord(record);
        } catch (IOException e) {
            report(Level.SEVERE, Keys.RECORD_WRITE_FAILED, recordFileOf(record).getAbsolutePath(), describe(e));
        }
    }

    /** A crash while writing a record leaves a temporary file; it is never a record. */
    private void deleteTemporaryRecords() {
        File[] temporaries = transactionsFolder.listFiles((dir, name) -> name.startsWith(TEMPORARY_PREFIX)
                && name.endsWith(TEMPORARY_SUFFIX));
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
        File[] folders = transactionsFolder.listFiles(File::isDirectory);
        if (folders == null) {
            return;
        }
        for (File folder : folders) {
            if (ids.contains(folder.getName())) {
                continue;
            }
            File backups = new File(folder, BACKUP_FOLDER);
            String[] kept = backups.list();
            if (kept != null && kept.length > 0) {
                report(Level.WARNING, Keys.ORPHAN_BACKUP, backups.getAbsolutePath(), String.join(", ", kept));
            } else {
                deleteTree(folder);
            }
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
        String[] rest = work.list();
        if (rest != null && rest.length == 0) {
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
        return hex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** A file's SHA-256, or {@code null} when it cannot be read. */
    static String sha256Of(File file) {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not read " + file, e);
            return null;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
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
        /** An atomic rename that never replaces an existing file, and a plain delete. */
        FileOps DEFAULT = new FileOps() {
            @Override
            public void move(Path from, Path to) throws IOException {
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(to.toString());
                }
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new IOException("the modules folder and the transactions folder must be on one"
                            + " file system: " + e.getMessage(), e);
                }
            }

            @Override
            public void delete(Path path) throws IOException {
                Files.deleteIfExists(path);
            }
        };

        void move(Path from, Path to) throws IOException;

        void delete(Path path) throws IOException;
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
        String stagedName;
        String targetName;
        String newVersion;
        String stagedSha256;
        boolean hadOld;
        String failure;
        List<Removal> removals = new ArrayList<>();
    }

    /** One file a {@code REMOVE} record deletes at the next start, and what it was when recorded. */
    @SuppressWarnings("PMD.DataClass")
    static final class Removal {
        String name;
        long size;
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
        public static final String ROLLED_BACK_NO_OLD = "模块 %s 的 %s 版本没有确认加载，已移除；没有可恢复的旧版本。";
        public static final String UNCONFIRMED_ROLLED_BACK =
                "上次启动在确认模块 %s 的 %s 版本是否加载之前就结束了；已恢复为 %s，本次启动加载 %s。";
        public static final String ROLLBACK_DEFERRED =
                "模块 %s 更新到 %s 后没有加载；恢复 %s 未能在本次完成（%s），将在下次启动、加载模块之前完成。";
        public static final String ROLLBACK_FAILED_BEFORE_LOAD = "模块 %s 从 %s 恢复为 %s 未能完成（%s）；下次启动会再试。";
        public static final String ROLLBACK_FINISHED = "模块 %s 的回滚已完成：已恢复为 %s，本次启动加载 %s。";
        public static final String APPLY_FAILED =
                "模块 %s 的更新（%s → %s）未能应用：%s：%s。模块目录未改变，本次启动加载 %s；下次执行 /upm update 时会再次报告。";
        public static final String APPLY_FAILED_UNRESTORED =
                "模块 %s 的更新（%s → %s）未能应用：%s：%s；把旧版本移回也失败了（%s）。旧版本 JAR 在 %s，请停止服务器后手动移回 %s。";
        public static final String RESTORE_FAILED = "模块 %s 的旧版本 JAR 仍在 %s，未能移回 %s（%s）；请停止服务器后手动移回。";
        public static final String CLEANUP_DEFERRED = "模块 %s 的更新已确认，但更新目录 %s 未能清理，下次启动会再清理。";
        public static final String ORPHAN_BACKUP = "更新目录 %s 中有不属于任何更新记录的旧版本 JAR，已保留：%s";
        public static final String REMOVED = "已删除卸载时未能删除的模块 JAR：%s。";
        public static final String REMOVAL_FAILED = "卸载时记录的模块 JAR %s 仍无法删除（%s）；本次启动会再次加载它，下次启动会再试。";
        public static final String REMOVAL_SKIPPED = "卸载时记录的模块 JAR %s 自卸载后已被替换，未删除。";
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
        public static final String REASON_RECORD_UNREADABLE = "该模块已有的更新记录 %s 无法读取：%s";
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
