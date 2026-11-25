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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import static com.ultikits.ultitools.text.Arg.string;
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
import com.ultikits.ultitools.manager.ModuleApiLevels;

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
 * loads, if it is still that file (#518). Only a file confirmed to be the recorded one is deleted; one
 * confirmed to be a different file is left alone and its entry dropped; one whose content cannot be
 * read at that start stays recorded, unchanged, for the next start to try again -- a failure to read
 * the file's content or metadata never cancels an uninstall the operator asked for. Size and
 * modification-time checks use checked NIO reads: an I/O failure is unknown identity, not a different
 * file. Recording also refuses an unreadable size or modification time rather than recording zero.
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

    /**
     * The modules (by identify-string) whose update is being downloaded right now, each with the
     * module's name and old JAR file name an uninstall matches on; guarded by {@link #LOCK}.
     */
    private static final java.util.Map<DownloadKey, Record> STAGING = new java.util.HashMap<>();
    private record DownloadKey(File folder, String identifyString) { }

    /**
     * The downloads in {@link #STAGING} an uninstall cancelled while they ran: their record is never
     * written (round-9 review); guarded by {@link #LOCK}.
     */
    private static final java.util.Set<DownloadKey> CANCELLED = new java.util.HashSet<>();

    private final File modulesFolder;
    private final File transactionsFolder;
    private final FileOps ops;
    private final CrashPoints crashPoints;
    private static final AtomicLong GENERATION = new AtomicLong();
    private final List<Report> reports = new ArrayList<>();
    private final List<StartOutcome> startOutcomes = new ArrayList<>();

    /** A decision observed at this start; kept in memory until shutdown. */
    @ApiStatus.Internal
    public record StartOutcome(PendingChanges.Kind kind, String module, String fromVersion,
                               String toVersion, Result result) {
        /** The result of trying to apply a change. */
        public enum Result { APPLIED, ROLLED_BACK, NOT_APPLIED, NEEDS_OPERATOR }
    }

    /** @return the start-up decisions, as an immutable snapshot */
    public List<StartOutcome> startOutcomes() {
        synchronized (LOCK) {
            return List.copyOf(startOutcomes);
        }
    }

    /** @return the number of additions and replacements made during this process */
    public long pendingGeneration() {
        return GENERATION.get();
    }

    private void outcome(Record record, StartOutcome.Result result) {
        // Recovery uses oldVersion; fromVersion is optional for records written by older versions.
        synchronized (LOCK) {
            startOutcomes.add(new StartOutcome(kindOf(record), displayName(record),
                    record.fromVersion == null ? record.oldVersion : record.fromVersion, record.newVersion, result));
        }
    }
    private final List<Record> appliedThisStart = new ArrayList<>();
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
        this(modulesFolder(dataFolder), transactionsFolder(dataFolder), FileOps.DEFAULT, CrashPoints.fromSystemProperty());
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
     * The module JARs of a modules folder, in file-name order: the entries whose names end in
     * {@code .jar}, the test the module loader applies. The start-up scan and the module class
     * loader both read this one listing (#476).
     *
     * <p>Sorted because {@code File#listFiles} promises no order (on ext4 it is hash order), and the
     * order decides which of two JARs carrying the same class supplies it and which copy of a
     * duplicated module is read first. Sorting by name, compared as plain strings, gives the same
     * answer for the same folder on every file system and in every locale.
     *
     * @param modulesFolder the modules folder
     * @return the JARs sorted by file name, or {@code null} when the folder cannot be listed
     */
    public static File[] moduleJars(File modulesFolder) {
        return sortedByName(modulesFolder.listFiles(file -> file.getName().endsWith(".jar")));
    }

    /**
     * {@code files} sorted by file name, compared as plain strings (#476).
     *
     * @param files a folder listing, or {@code null}
     * @return the same array, sorted, or {@code null}
     */
    public static File[] sortedByName(File[] files) {
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
        }
        return files;
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
        return stageUpdate(identifyString, loaded, codeSource, catalogue, downloader, module -> null);
    }

    /**
     * Stages an update of a loaded module, as {@link #stageUpdate(String, List, Function, Catalogue,
     * Downloader)} does, with the module's main class as the module loader recorded it.
     *
     * <p>Once the new JAR's file name is known and before anything is downloaded, the update is
     * refused when another copy of the module would load instead of it at the next start: a JAR in
     * the modules folder whose {@code plugin.yml} declares the same {@code main:} and whose file name
     * sorts before the new JAR's (maintainer follow-up 19). All module JARs share one class loader
     * whose class path is in file-name order (#476), so such a copy supplies the module's classes
     * and the observation would roll the update back at every attempt. A copy that sorts after the
     * new JAR does not stop it; the start-up duplicate warning names that copy. A copy added after
     * staging is caught by the observation.
     *
     * @param identifyString the module's identify-string
     * @param loaded         the loaded modules
     * @param codeSource     which JAR a loaded module came from
     * @param catalogue      where the latest version and its download link come from
     * @param downloader     how the new JAR is downloaded
     * @param mainClassOf    the main class the module loader recorded for a loaded module, or
     *                       {@code null} when it has none; the old JAR's own {@code plugin.yml}
     *                       {@code main:} is used then
     * @return what happened
     */
    public StageResult stageUpdate(String identifyString, List<UltiToolsPlugin> loaded,
                                   Function<UltiToolsPlugin, File> codeSource, Catalogue catalogue,
                                   Downloader downloader, Function<UltiToolsPlugin, String> mainClassOf) {
        return stageUpdate(identifyString, () -> loaded, codeSource, catalogue, downloader, mainClassOf);
    }

    /**
     * Stages an update, as {@link #stageUpdate(String, List, Function, Catalogue, Downloader, Function)}
     * does, reading the loaded modules only under the lock that an uninstall's cancellation also takes
     * (Codex round 16, #561). A list taken before the lock could still name a module an uninstall
     * removed in between, and that uninstall would have found neither a running download nor a record
     * to cancel. Read under the lock, either the module is already gone from the list (refused as not
     * loaded) or the download is marked running before the cancellation can look (and is cancelled).
     *
     * @param identifyString the module's identify-string
     * @param loaded         reads the modules loaded now; called once, under the lock
     * @param codeSource     which JAR a loaded module came from
     * @param catalogue      where the latest version and its download link come from
     * @param downloader     how the new JAR is downloaded
     * @param mainClassOf    the main class the module loader recorded for a loaded module, or
     *                       {@code null}
     * @return what happened
     */
    public StageResult stageUpdate(String identifyString, java.util.function.Supplier<List<UltiToolsPlugin>> loaded,
                                   Function<UltiToolsPlugin, File> codeSource, Catalogue catalogue,
                                   Downloader downloader, Function<UltiToolsPlugin, String> mainClassOf) {
        return stageChange(identifyString, null, null, null, loaded, codeSource, catalogue, downloader, mainClassOf);
    }

    /**
     * Stages a change to a specified version, or the latest compatible version when unspecified.
     * A non-null install name identifies an explicit install request, even when it becomes an update.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    StageResult stageChange(String identifyString, String requestedVersion, String installName,
                            PendingChanges.Requester requester, java.util.function.Supplier<List<UltiToolsPlugin>> loaded,
                            Function<UltiToolsPlugin, File> codeSource, Catalogue catalogue, Downloader downloader,
                            Function<UltiToolsPlugin, String> mainClassOf) {
        String key = normalize(identifyString);
        if (key == null) {
            return StageResult.failed(null, UpmKeys.REASON_NO_IDENTIFY_STRING);
        }
        boolean explicitInstall = installName != null;
        var runningKey = new DownloadKey(transactionsFolder, key);
        Record record = new Record();
        record.key = key;
        record.requestedBy = requester;
        record.requestedAt = System.currentTimeMillis();
        record.newVersion = requestedVersion;
        Record previous;
        String previousFailure = null;
        Reservation reservation = null;
        synchronized (LOCK) {
            try {
                List<UltiToolsPlugin> modules = loaded.get();
                UltiToolsPlugin module = modules.stream().filter(it -> key.equals(normalize(it.getIdentifyString())))
                        .findFirst().orElse(null);
                record.type = module == null ? Record.INSTALL : Record.UPDATE;
                record.moduleName = module == null ? (explicitInstall ? installName : identifyString) : module.getPluginName();
                record.oldVersion = module == null ? null : module.getVersion();
                record.fromVersion = record.oldVersion;
                File oldJar = module == null ? null : codeSource.apply(module);
                record.oldName = oldJar == null ? null : oldJar.getName();
                RemovedModule identity = identityOf(record);
                if (activeDownload(identity, true) != null) {
                    return StageResult.of(StageResult.Outcome.BUSY, displayName(record), null, null, null);
                }
                previous = findStored(identity, record).map(Stored::record).orElse(null);
                if (previous != null) {
                    if (Record.NEEDS_OPERATOR.equals(previous.state)) {
                        return StageResult.failed(null, UpmKeys.REASON_NEEDS_OPERATOR, previous.failure,
                                recordFileOf(previous).getAbsolutePath()).withModule(displayName(previous));
                    }
                    if (Record.FAILED.equals(previous.state)) {
                        previousFailure = previous.failure;
                        String error = discardFailed(previous);
                        if (error != null) {
                            return cleanupRefusal(previous, error);
                        }
                        previous = null;
                    } else if (!Record.PENDING.equals(previous.state)) {
                        return resultFor(StageResult.Outcome.UNFINISHED, previous, null, null);
                    } else if (!explicitInstall && Record.REMOVE.equals(previous.type)) {
                        return resultFor(StageResult.Outcome.PENDING_UNINSTALL, previous, null, null);
                    }
                }
                if (module == null && !explicitInstall && (previous == null || !Record.INSTALL.equals(previous.type))) {
                    return StageResult.failed(previousFailure, UpmKeys.REASON_NOT_LOADED, identifyString);
                }
                if (previous != null && module == null) {
                    record.moduleName = previous.moduleName;
                }
                if (requestedVersion != null) {
                    StageResult noDownload = decideVersion(record, previous, previousFailure, explicitInstall);
                    if (noDownload != null) {
                        return noDownload;
                    }
                }
                File work = workFolder(record);
                if (mayHoldFiles(new File(work, BACKUP_FOLDER))) {
                    return StageResult.failed(previousFailure, UpmKeys.REASON_LEFTOVER_BACKUP,
                            new File(work, BACKUP_FOLDER).getAbsolutePath());
                }
                if (module != null) {
                    reservation = locate(key, identifyString, modules, codeSource, previousFailure);
                    if (reservation.refusal != null) {
                        return reservation.refusal;
                    }
                    record.oldSha256 = reservation.oldSha256;
                }
                STAGING.put(runningKey, record);
            } catch (SecurityException denied) {
                return StageResult.failed(previousFailure, UpmKeys.REASON_ACCESS_DENIED, describe(denied));
            } catch (IOException failure) {
                return StageResult.failed(previousFailure, UpmKeys.REASON_RECORD_UNREADABLE,
                        recordFileOf(record).getAbsolutePath(), text(failure.getMessage()));
            }
        }
        File incoming = null;
        try {
            String version = requestedVersion == null ? catalogue.latestVersion(identifyString) : requestedVersion;
            String target = PluginInstallUtils.installedJarName(identifyString, version);
            synchronized (LOCK) {
                if (CANCELLED.contains(runningKey)) {
                    return cancelled(previousFailure);
                }
                record.newVersion = version;
                record.targetName = target;
                StageResult noDownload = decideVersion(record, previous, previousFailure, explicitInstall);
                if (noDownload != null) {
                    return noDownload;
                }
            }
            String url = target == null ? null : catalogue.downloadLink(identifyString, version);
            if (url == null) {
                return StageResult.failed(previousFailure, UpmKeys.REASON_NO_DOWNLOAD, identifyString);
            }
            if (reservation != null) {
                StageResult refusal = refuseBeforeDownload(identifyString, url, target, reservation, mainClassOf);
                if (refusal != null) {
                    return refusal;
                }
            }
            // Keep the previous journal and staged bytes intact until the replacement is durable.
            Files.createDirectories(transactionsFolder.toPath());
            incoming = Files.createTempDirectory(transactionsFolder.toPath(), "incoming-").toFile();
            File stagedFolder = new File(incoming, STAGED_FOLDER);
            String error = fetch(downloader, url, target, incoming, stagedFolder);
            if (error != null) {
                return StageResult.failed(previousFailure, UpmKeys.REASON_DOWNLOAD_FAILED, error);
            }
            File staged = new File(stagedFolder, target);
            if (reservation != null || !explicitInstall) {
                StageResult refusal = verifyStaged(record, incoming, staged, version, previousFailure);
                if (refusal != null) {
                    return refusal;
                }
            } else {
                record.stagedSha256 = readSha256(staged);
            }
            synchronized (LOCK) {
                if (CANCELLED.contains(runningKey)) {
                    return cancelled(previousFailure);
                }
                // The lock and the download reservation prevent other writers from replacing this slot.
                // If a caller revoked its predecessor by id while downloading, cancel rather than revive it.
                if (previous != null && !exists(recordFileOf(previous))) {
                    return StageResult.of(StageResult.Outcome.BUSY, displayName(record), null, null, previousFailure);
                }
                if (reservation == null) {
                    String name = PluginYmlReader.readFromJarFile(staged).getName();
                    if (name != null && !name.isBlank()) {
                        record.moduleName = name;
                    }
                    Record match = findStored(identityOf(record), record).map(Stored::record).orElse(null);
                    if (match != null && (previous == null || !idOf(match).equals(idOf(previous)))) {
                        return StageResult.of(StageResult.Outcome.BUSY, displayName(record), null, null, previousFailure);
                    }
                }
                record.state = Record.PENDING;
                publishDownload(record, previous, staged);
                // Show the durable entry, not the download reservation, as soon as it is published.
                STAGING.remove(runningKey);
            }
            crashPoints.reached(CrashPoints.AFTER_RECORD_WRITTEN);
            StageResult.Outcome queued = reservation == null
                    ? previous == null ? StageResult.Outcome.QUEUED_INSTALL : StageResult.Outcome.REPLACED_INSTALL
                    : previous == null ? StageResult.Outcome.STAGED : Record.REMOVE.equals(previous.type)
                    ? StageResult.Outcome.CANCELLED_UNINSTALL : StageResult.Outcome.REPLACED_UPDATE;
            return resultFor(queued, record, previous, previousFailure);
        } catch (NoCompatibleVersionException e) {
            return StageResult.failed(previousFailure, UpmKeys.REASON_NO_COMPATIBLE_UPDATE, identifyString,
                    e.getFrameworkLevel(), ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL, e.getFrameworkLevel());
        } catch (SecurityException denied) {
            return StageResult.failed(previousFailure, UpmKeys.REASON_ACCESS_DENIED, describe(denied));
        } catch (IOException failure) {
            return StageResult.failed(previousFailure, UpmKeys.REASON_RECORD_FAILED, describe(failure));
        } finally {
            if (incoming != null) {
                discardStaging(incoming);
            }
            synchronized (LOCK) {
                STAGING.remove(runningKey, record);
                // Only this reservation can be cancelled while it is still registered.
                if (!STAGING.containsKey(runningKey)) {
                    CANCELLED.remove(runningKey);
                }
            }
        }
    }

    private static StageResult cancelled(String previousFailure) {
        return StageResult.failed(previousFailure, UpmKeys.REASON_CANCELLED_WHILE_DOWNLOADING);
    }

    private StageResult decideVersion(Record record, Record previous, String previousFailure, boolean explicitInstall) {
        if (record.newVersion == null) {
            return null;
        }
        if (explicitInstall && record.newVersion.equals(record.oldVersion)) {
            if (previous == null) {
                return resultFor(StageResult.Outcome.ALREADY_CURRENT, record, null, previousFailure);
            }
            String error = cancelPending(previous);
            if (error != null) {
                return cleanupRefusal(previous, error);
            }
            return resultFor(Record.REMOVE.equals(previous.type) ? StageResult.Outcome.CANCELLED_UNINSTALL_SAME
                    : StageResult.Outcome.CANCELLED_UPDATE, record, previous, previousFailure);
        }
        if (previous != null && record.newVersion.equals(previous.newVersion)) {
            return resultFor(StageResult.Outcome.ALREADY_STAGED, previous, null, previousFailure);
        }
        return null;
    }

    private static StageResult resultFor(StageResult.Outcome outcome, Record record, Record previous, String failure) {
        return StageResult.of(outcome, displayName(record), record.oldVersion, record.newVersion, failure)
                .withPreviousVersion(previous == null ? null : previous.newVersion);
    }

    private StageResult cleanupRefusal(Record previous, String error) {
        return Record.NEEDS_OPERATOR.equals(previous.state)
                ? StageResult.failed(null, UpmKeys.REASON_NEEDS_OPERATOR, previous.failure,
                        recordFileOf(previous).getAbsolutePath()).withModule(displayName(previous))
                : StageResult.failed(previous.failure, UpmKeys.REASON_PREVIOUS_UNRESTORED, error);
    }

    private void publishDownload(Record record, Record previous, File source) throws IOException {
        checkReplacement(previous);
        File destination;
        try {
            refuseLinkedFolders(record);
            record.stagedName = record.targetName;
            destination = stagedOf(record);
            if (exists(destination)) {
                // Even when two version strings sanitise to the same basename, never overwrite the
                // previous record's bytes before replacing the journal that references them.
                record.stagedName = "download-" + java.util.UUID.randomUUID() + ".jar";
                destination = stagedOf(record);
            }
        } catch (RecordRefused refusal) {
            throw new IOException(refusal.getMessage(), refusal);
        }
        Files.createDirectories(destination.getParentFile().toPath());
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        try {
            publish(record, previous);
        } catch (IOException failure) {
            deleteQuietly(destination);
            if (previous == null || !idOf(previous).equals(idOf(record))) {
                deleteTree(workFolder(record));
            }
            throw failure;
        }
        if (previous != null && idOf(previous).equals(idOf(record))) {
            try {
                deleteQuietly(stagedOf(previous));
            } catch (RecordRefused refusal) {
                LOGGER.log(Level.WARNING, "Could not clean the earlier staged JAR", refusal);
            }
        }
    }

    private void checkReplacement(Record previous) throws IOException {
        if (previous == null) {
            return;
        }
        purgeSuperseded(previous);
        try {
            checkPendingFiles(previous);
        } catch (RecordRefused refusal) {
            throw new IOException(refusal.getMessage(), refusal);
        }
    }

    private void publish(Record record, Record previous) throws IOException {
        if (previous != null && !idOf(record).equals(idOf(previous))) {
            record.supersedes = idOf(previous);
        }
        writeRecord(record);
        GENERATION.incrementAndGet();
        if (record.supersedes != null) {
            try {
                purgeSuperseded(record);
            } catch (IOException cleanup) {
                // The new journal is authoritative even if cleanup could not finish; startup retries.
                LOGGER.log(Level.WARNING, "An obsolete transaction could not be cleaned up", cleanup);
            }
        }
    }

    private void purgeSuperseded(Record record) throws IOException {
        String previousId = record.supersedes;
        if (previousId == null) {
            return;
        }
        if (!previousId.matches("(?:install|update|remove)-[0-9a-f]{16}") || previousId.equals(idOf(record))) {
            throw new IOException("unrecognised predecessor of " + idOf(record));
        }
        // The validated id is exactly a journal basename, never an arbitrary path.
        File retiredFile = new File(transactionsFolder, previousId + RECORD_SUFFIX);
        Files.deleteIfExists(retiredFile.toPath());
        deleteTree(new File(transactionsFolder, previousId));
        record.supersedes = null;
        try {
            writeRecord(record);
        } catch (IOException failure) {
            record.supersedes = previousId;
            throw failure;
        }
    }

    private static java.util.Set<String> supersededIds(List<Record> records) {
        var ids = new java.util.HashSet<String>();
        for (Record record : records) {
            if (record.supersedes != null) {
                ids.add(record.supersedes);
            }
        }
        return ids;
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
            return Reservation.refused(StageResult.failed(previousFailure, UpmKeys.REASON_NOT_LOADED, identifyString));
        }
        File oldJar = codeSource.apply(module);
        if (oldJar == null || !isDirectChild(modulesFolder, oldJar) || !isJarName(oldJar.getName())) {
            return Reservation.refused(StageResult.failed(previousFailure, UpmKeys.REASON_NOT_IN_MODULES_FOLDER,
                    oldJar == null ? "?" : oldJar.getAbsolutePath(), modulesFolder.getAbsolutePath()));
        }
        StageResult sharedOrAmbiguous = refuseSharedOrAmbiguous(module, oldJar, key, identifyString, loaded, codeSource,
                previousFailure);
        if (sharedOrAmbiguous != null) {
            return Reservation.refused(sharedOrAmbiguous);
        }
        String oldSha256 = sha256Of(oldJar);
        if (oldSha256 == null) {
            return Reservation.refused(StageResult.failed(previousFailure, UpmKeys.REASON_OLD_JAR_UNREADABLE,
                    oldJar.getAbsolutePath()));
        }
        return new Reservation(module, oldJar, oldSha256, previousFailure, null);
    }

    /**
     * The refusal when the JAR to replace is not the selected module's alone, or {@code null}: another
     * loaded module came from the same JAR, or another loaded module in another JAR declares the same
     * identify-string. {@code /upm update} names a module only by its identify-string, so in the second
     * case which module was meant cannot be told apart, and updating the first match could replace --
     * and commit -- the other module's JAR while reporting the requested one staged (Codex, #561).
     * The same-JAR check runs first, so the second only ever sees modules from other JARs.
     */
    private static StageResult refuseSharedOrAmbiguous(UltiToolsPlugin module, File oldJar, String key,
                                                       String identifyString, List<UltiToolsPlugin> loaded,
                                                       Function<UltiToolsPlugin, File> codeSource,
                                                       String previousFailure) {
        String sharedWith = sharedWith(module, oldJar, loaded, codeSource);
        if (sharedWith != null) {
            return StageResult.failed(previousFailure, UpmKeys.REASON_SHARED_JAR, oldJar.getAbsolutePath(), sharedWith);
        }
        List<String> declarers = new ArrayList<>();
        for (UltiToolsPlugin plugin : loaded) {
            if (key.equals(normalize(plugin.getIdentifyString()))) {
                declarers.add(plugin.getPluginName());
            }
        }
        return declarers.size() > 1 ? StageResult.failed(previousFailure, UpmKeys.REASON_AMBIGUOUS_IDENTIFY_STRING,
                identifyString, String.join(", ", declarers)) : null;
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

    /** The loaded module and its JAR, or the reason it cannot be staged. */
    private record Reservation(UltiToolsPlugin module, File oldJar, String oldSha256, String previousFailure,
                               StageResult refusal) {
        static Reservation refused(StageResult result) {
            return new Reservation(null, null, null, null, result);
        }
    }

    /**
     * The refusals decided once the new JAR's file name is known, before anything is downloaded or
     * written: no download offered, or another copy of the module that would load instead of the new
     * JAR at the next start (maintainer follow-up 19).
     *
     * @return the refusal, or {@code null} to download
     */
    private StageResult refuseBeforeDownload(String identifyString, String link, String targetName,
                                             Reservation reservation, Function<UltiToolsPlugin, String> mainClassOf) {
        if (link == null || targetName == null) {
            return StageResult.failed(reservation.previousFailure, UpmKeys.REASON_NO_DOWNLOAD, identifyString);
        }
        List<String> copies = copiesLoadingFirst(reservation.oldJar, targetName, mainClassOf(reservation, mainClassOf));
        return copies.isEmpty() ? null : StageResult.failed(reservation.previousFailure,
                UpmKeys.REASON_OTHER_COPY_LOADS_FIRST, targetName, String.join(", ", copies));
    }

    /**
     * The module's main class: as the module loader recorded it, else as the old JAR's own
     * {@code plugin.yml} declares it -- the same entry the loader reads. {@code null} when neither
     * says.
     */
    private static String mainClassOf(Reservation reservation, Function<UltiToolsPlugin, String> recorded) {
        String mainClass = recorded == null ? null : recorded.apply(reservation.module);
        return mainClass != null ? mainClass : PluginYmlReader.readFromJarFile(reservation.oldJar).getMain();
    }

    /**
     * The other JARs in the modules folder that would supply {@code mainClass} ahead of a new JAR
     * named {@code targetName}: those the start-up would put on the module class path, whose
     * {@code plugin.yml} declares it now and whose file name sorts before {@code targetName} in the
     * order the module loader reads the folder (#476; {@link #loadsAhead}). The
     * old JAR is not one: the update moves it away. Judged by what each file declares now, not by
     * the start-up index alone, which goes stale when a copy is removed or replaced. Reads only
     * {@code plugin.yml}.
     *
     * @return their file names in file-name order; empty when there is none, when the main class is
     *         unknown, or when the folder cannot be listed (the observation still backs the update)
     */
    private List<String> copiesLoadingFirst(File oldJar, String targetName, String mainClass) {
        List<String> copies = new ArrayList<>();
        File[] jars = mainClass == null ? null : moduleJars(modulesFolder);
        if (jars == null) {
            return copies;
        }
        String oldPath = canonicalPath(oldJar);
        for (File jar : jars) {
            if (jar.getName().compareTo(targetName) >= 0) {
                break;
            }
            if (loadsAhead(jar, oldPath, mainClass)) {
                copies.add(jar.getName());
            }
        }
        return copies;
    }

    /**
     * Whether {@code jar}, sorting before the new JAR, would supply {@code mainClass} at the next start:
     * it is not the old JAR, it passes the check the start-up applies before putting a JAR on the
     * module class path ({@link SecurityPolicy#isValidModuleJar}, as {@code UltiTools#collectModuleJarUrls}
     * and {@code PluginManager#loadPluginMainClass} do; a JAR failing it is never loaded -- Codex round
     * 17), its API declaration meets the shared minimum, and its {@code plugin.yml} declares the class.
     */
    private static boolean loadsAhead(File jar, String oldPath, String mainClass) {
        if (oldPath.equals(canonicalPath(jar)) || !SecurityPolicy.isValidModuleJar(jar)) {
            return false;
        }
        PluginYmlReader.PluginYmlInfo metadata = PluginYmlReader.readFromJarFile(jar);
        return mainClass.equals(metadata.getMain())
                && ModuleApiLevels.isLoadable(ModuleApiLevels.parse(metadata.getApiVersion()));
    }

    /**
     * Checks the downloaded JAR -- the size/entry guard, its identity, that it declares the version
     * the catalogue offered, and that its {@code api-version} is one this framework installs
     * ({@link ModuleApiLevels}; plan 18-40) -- and on success records its version and SHA-256.
     *
     * @return the refusal, with the staging discarded, or {@code null} when the JAR is accepted
     */
    private static StageResult verifyStaged(Record record, File work, File staged, String latest,
                                            String previousFailure) {
        String targetName = staged.getName();
        if (!staged.isFile() || !SecurityPolicy.isValidModuleJar(staged)) {
            discardStaging(work);
            return StageResult.failed(previousFailure, UpmKeys.REASON_INVALID_JAR, targetName);
        }
        String stagedVersion;
        String stagedHash;
        try {
            stagedVersion = declaredVersion(staged, record.key);
            stagedHash = readSha256(staged);
        } catch (IOException readError) {
            discardStaging(work);
            return StageResult.failed(previousFailure, UpmKeys.REASON_STAGED_UNREADABLE, targetName, describe(readError));
        }
        if (stagedVersion == null) {
            discardStaging(work);
            return StageResult.failed(previousFailure, UpmKeys.REASON_WRONG_IDENTITY, targetName, record.key);
        }
        StageResult wrongVersion = refuseOtherVersion(work, targetName, stagedVersion, latest, previousFailure);
        if (wrongVersion != null) {
            return wrongVersion;
        }
        StageResult wrongLevel = refuseOtherApiLevel(work, staged, previousFailure);
        if (wrongLevel != null) {
            return wrongLevel;
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
        return StageResult.failed(previousFailure, UpmKeys.REASON_WRONG_VERSION, targetName, stagedVersion.trim(),
                latest.trim());
    }

    /**
     * A download must declare an {@code api-version} this framework installs: from
     * {@link ModuleApiLevels#MIN_INSTALLABLE_API_LEVEL} to its own level. A catalogue that does not
     * filter by level, or a version recorded wrongly, can otherwise stage a module that cannot load
     * here -- one built before 6.3.0 overrides the now-final {@code unregisterSelf()} -- and every
     * start would roll it back (plan 18-40).
     *
     * @return the refusal, with the staging discarded, or {@code null} when the level is accepted
     */
    private static StageResult refuseOtherApiLevel(File work, File staged, String previousFailure) {
        int level = ModuleApiLevels.frameworkLevel();
        int floor = ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL;
        String declared = PluginYmlReader.readFromJarFile(staged).getApiVersion();
        if (declared == null || declared.trim().isEmpty()) {
            discardStaging(work);
            return StageResult.failed(previousFailure, UpmKeys.REASON_NO_API_LEVEL, staged.getName(), level, floor, level);
        }
        Integer moduleLevel = ModuleApiLevels.parse(declared);
        if (moduleLevel != null && ModuleApiLevels.isInstallable(moduleLevel, floor, level)) {
            return null;
        }
        discardStaging(work);
        return StageResult.failed(previousFailure, UpmKeys.REASON_INCOMPATIBLE_API_LEVEL, staged.getName(), declared.trim(),
                level, floor, level);
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

    private DownloadKey activeDownload(RemovedModule identity, boolean includeCancelled) {
        for (var download : STAGING.entrySet()) {
            DownloadKey slot = download.getKey();
            if (transactionsFolder.equals(slot.folder) && (includeCancelled || !CANCELLED.contains(slot))
                    && matches(identity, download.getValue())) {
                return slot;
            }
        }
        return null;
    }

    private record Stored(Record record, String revision) { }

    private List<Stored> liveRecords(Record direct) throws IOException {
        var records = new ArrayList<Stored>();
        for (File file : recordFiles()) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                Record record = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Record.class);
                String problem = record == null ? "empty record" : problemWith(record, file);
                if (problem != null) {
                    throw new IOException(problem);
                }
                records.add(new Stored(record, sha256(bytes).substring(0, 16)));
            } catch (IOException | JsonParseException invalid) {
                if (direct != null && file.equals(recordFileOf(direct))) {
                    throw new IOException(invalid.getMessage(), invalid);
                }
                LOGGER.log(Level.FINE, "Could not list " + file, invalid);
            }
        }
        var superseded = supersededIds(records.stream().map(Stored::record).toList());
        return records.stream().filter(stored -> !superseded.contains(idOf(stored.record))).toList();
    }

    private java.util.Optional<Stored> findStored(RemovedModule identity, Record direct) throws IOException {
        return liveRecords(direct).stream().filter(stored -> matches(identity, stored.record)).findFirst();
    }

    private static RemovedModule identityOf(Record record) {
        var files = new ArrayList<>(record.removals.stream().map(removal -> removal.name).toList());
        files.add(record.oldName);
        files.add(record.targetName);
        return new RemovedModule(Collections.singletonList(record.key), Collections.singletonList(record.moduleName), files);
    }

    private static RemovedModule identityOf(String name) {
        return new RemovedModule(List.of(name), List.of(name), List.of(name));
    }

    private static boolean matches(RemovedModule identity, Record record) {
        return identity.matches(record.key, record.moduleName, record.oldName)
                || identity.matches(null, null, record.targetName)
                || record.removals.stream().anyMatch(removal -> identity.matches(null, null, removal.name));
    }

    private static String displayName(Record record) {
        return record.moduleName == null || record.moduleName.isBlank() ? record.key : record.moduleName;
    }

    private static PendingChanges.Kind kindOf(Record record) {
        return switch (record.type) {
            case Record.INSTALL -> PendingChanges.Kind.INSTALL;
            case Record.UPDATE -> PendingChanges.Kind.UPDATE;
            default -> PendingChanges.Kind.UNINSTALL;
        };
    }

    private static PendingChanges.State stateOf(Record record) {
        return switch (record.state) {
            case Record.PENDING -> PendingChanges.State.PENDING;
            case Record.FAILED -> PendingChanges.State.FAILED;
            case Record.NEEDS_OPERATOR -> PendingChanges.State.NEEDS_OPERATOR;
            default -> PendingChanges.State.IN_PROGRESS;
        };
    }

    private PendingChanges.PendingChange pendingView(Record record, String revision, boolean downloading) {
        PendingChanges.Kind kind = kindOf(record);
        List<String> files = kind == PendingChanges.Kind.UNINSTALL
                ? record.removals.stream().map(removal -> removal.name).toList()
                : record.targetName == null ? List.of() : List.of(record.targetName);
        String module = displayName(record);
        return new PendingChanges.PendingChange(idOf(record), revision, kind, module, record.fromVersion,
                record.newVersion, files, record.requestedAt, record.requestedBy,
                downloading ? PendingChanges.State.DOWNLOADING : stateOf(record), record.failure,
                downloading ? null : recordFileOf(record), kind == PendingChanges.Kind.UNINSTALL
                ? removalContent(record) : ModuleContent.NONE);
    }

    PendingChanges.Snapshot pendingSnapshot() {
        synchronized (LOCK) {
            var entries = new ArrayList<PendingChanges.PendingChange>();
            try {
                for (Stored stored : liveRecords(null)) {
                    if (activeDownload(identityOf(stored.record), false) == null) {
                        entries.add(pendingView(stored.record, stored.revision, false));
                    }
                }
            } catch (IOException | SecurityException unreadable) {
                LOGGER.log(Level.WARNING, "Could not list module transactions", unreadable);
            }
            for (var download : STAGING.entrySet()) {
                if (transactionsFolder.equals(download.getKey().folder) && !CANCELLED.contains(download.getKey())) {
                    entries.add(pendingView(download.getValue(), null, true));
                }
            }
            entries.sort(Comparator.comparing(PendingChanges.PendingChange::requestedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(PendingChanges.PendingChange::id));
            int size = entries.size();
            return new PendingChanges.Snapshot(entries.subList(0, Math.min(size, PendingChanges.MAX_ITEMS)),
                    Math.max(0, size - PendingChanges.MAX_ITEMS), pendingGeneration());
        }
    }

    /**
     * Finds the catalogue identity of a queued change without consulting update scan results.
     *
     * @param name a module name, catalogue identifier or JAR name
     * @return the queued catalogue identifier, or null if the change cannot be identified
     */
    public String pendingIdentifyString(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        synchronized (LOCK) {
            try {
                RemovedModule identity = identityOf(name);
                DownloadKey download = activeDownload(identity, false);
                if (download != null) {
                    return download.identifyString;
                }
                return findStored(identity, null).map(stored -> normalize(stored.record.key)).orElse(null);
            } catch (IOException | SecurityException unreadable) {
                LOGGER.log(Level.WARNING, "Could not read module transactions", unreadable);
                return null;
            }
        }
    }

    PendingChanges.PendingChange findPending(String name) {
        synchronized (LOCK) {
            try {
                var identity = identityOf(name);
                DownloadKey download = activeDownload(identity, false);
                if (download != null) {
                    return pendingView(STAGING.get(download), null, true);
                }
                return findStored(identity, null).map(stored -> pendingView(stored.record, stored.revision, false))
                        .orElse(null);
            } catch (IOException | SecurityException failure) {
                LOGGER.log(Level.WARNING, "Could not read module transactions", failure);
                return null;
            }
        }
    }

    PendingChanges.RevokeResult revokePending(String id, String expectedRevision, boolean checkRevision) {
        synchronized (LOCK) {
            try {
                for (var download : STAGING.entrySet()) {
                    if (transactionsFolder.equals(download.getKey().folder) && !CANCELLED.contains(download.getKey())
                            && idOf(download.getValue()).equals(id)) {
                        if (checkRevision && expectedRevision != null) {
                            return PendingChanges.RevokeResult.changed();
                        }
                        CANCELLED.add(download.getKey());
                        return PendingChanges.RevokeResult.revoked(pendingView(download.getValue(), null, true));
                    }
                }
                for (Stored stored : liveRecords(null)) {
                    if (idOf(stored.record).equals(id)) {
                        if (activeDownload(identityOf(stored.record), false) != null
                                || checkRevision && !stored.revision.equals(expectedRevision)) {
                            return PendingChanges.RevokeResult.changed();
                        }
                        var before = pendingView(stored.record, stored.revision, false);
                        if (!before.revocable()) {
                            return PendingChanges.RevokeResult.notRevocable(before);
                        }
                        String error = Record.FAILED.equals(stored.record.state)
                                ? discardFailed(stored.record) : cancelPending(stored.record);
                        return error == null ? PendingChanges.RevokeResult.revoked(before)
                                : PendingChanges.RevokeResult.failed(error);
                    }
                }
                return PendingChanges.RevokeResult.notFound();
            } catch (IOException | SecurityException failure) {
                return PendingChanges.RevokeResult.failed(describe(failure));
            }
        }
    }

    PendingChanges.RevokeResult revokeByModule(String name) {
        synchronized (LOCK) {
            var current = findPending(name);
            return current == null ? PendingChanges.RevokeResult.notFound() : revokePending(current.id(), null, false);
        }
    }

    static <T> T underLock(java.util.function.Supplier<T> work) {
        synchronized (LOCK) {
            return work.get();
        }
    }

    PluginInstallUtils.PendingResult queueRemoval(String name, String key, String version, RemovedModule identity,
                                                  List<File> files, List<String> undetermined, CommandSender sender,
                                                  boolean running) {
        var result = PluginInstallUtils.PendingResult.builder();
        try {
            Record record = new Record();
            record.type = Record.REMOVE;
            record.removalIdentifyString = normalize(key);
            record.key = record.removalIdentifyString == null ? name : record.removalIdentifyString;
            record.moduleName = name;
            record.fromVersion = version;
            Record previous = findStored(identity, record).map(Stored::record).orElse(null);
            return queueRemovalOf(record, previous, identity, files, undetermined, sender, running, result);
        } catch (IOException | SecurityException failure) {
            return result.add(UpmKeys.UNINSTALL_NOT_RECORDED, string("module", name),
                    string("error", describe(failure))).build();
        }
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    private PluginInstallUtils.PendingResult queueRemovalOf(Record record, Record previous, RemovedModule identity,
                                                            List<File> files, List<String> undetermined, CommandSender sender,
                                                            boolean running, PluginInstallUtils.PendingResult.Builder result)
            throws IOException {
        Arg module = string("module", displayName(record));
        if (previous != null) {
            switch (previous.state) {
                case Record.NEEDS_OPERATOR:
                    return result.add(UpmKeys.UNINSTALL_REFUSED_HELD_FOR_OPERATOR, module,
                            string("file", recordFileOf(previous).getAbsolutePath())).build();
                case Record.FAILED:
                    result.add(UpmKeys.PENDING_PREVIOUS_FAILED, module, string("failure", text(previous.failure)));
                    String error = discardFailed(previous);
                    if (error != null) {
                        throw new IOException(error);
                    }
                    previous = null;
                    break;
                case Record.PENDING:
                    if (Record.INSTALL.equals(previous.type) && !running) {
                        String cancelError = cancelPending(previous);
                        if (cancelError != null) {
                            throw new IOException(cancelError);
                        }
                        cancelDownload(identity);
                        return result.add(UpmKeys.UNINSTALL_CANCELLED_INSTALL, string("module", displayName(previous)),
                                string("version", previous.newVersion)).build();
                    }
                    if (Record.REMOVE.equals(previous.type)) {
                        return result.add(UpmKeys.UNINSTALL_ALREADY_QUEUED, module)
                                .warning(displayName(previous), removalContent(previous)).build();
                    }
                    break;
                default:
                    return result.add(UpmKeys.PENDING_UNFINISHED, module).build();
            }
        }
        if (files.isEmpty()) {
            DownloadKey download = activeDownload(identity, false);
            if (!running && download != null && Record.INSTALL.equals(STAGING.get(download).type)) {
                CANCELLED.add(download);
                return result.add(UpmKeys.REVOKE_DONE_DOWNLOADING, module).build();
            }
            if (running) {
                result.add(UpmKeys.UNINSTALL_NO_JAR, module, string("folder", modulesFolder.getAbsolutePath()));
            } else {
                result.add(UpmKeys.UNINSTALL_NOT_FOUND);
            }
            return result.undetermined(undetermined).build();
        }
        for (File file : files) {
            record.removals.add(captureRemoval(file));
        }
        record.state = Record.PENDING;
        record.requestedAt = System.currentTimeMillis();
        record.requestedBy = PendingChanges.Requester.from(sender);
        checkReplacement(previous);
        publish(record, previous);
        cancelDownload(identity);
        result.queued().add(UpmKeys.UNINSTALL_QUEUED, module,
                string("files", String.join(", ", record.removals.stream().map(r -> r.name).toList())));
        if (previous != null && Record.UPDATE.equals(previous.type)) {
            result.add(UpmKeys.UNINSTALL_ALSO_CANCELLED_UPDATE, module, string("version", previous.newVersion));
        }
        return result.warning(displayName(record), removalContent(record)).undetermined(undetermined).build();
    }

    private static ModuleContent removalContent(Record record) {
        return ModuleContentLedgers.contentOf(record.removalIdentifyString, displayName(record));
    }

    private void cancelDownload(RemovedModule identity) {
        DownloadKey download = activeDownload(identity, false);
        if (download != null) {
            CANCELLED.add(download);
        }
    }

    private Removal captureRemoval(File file) throws IOException {
        if (!isDirectChild(modulesFolder, file) || !isJarName(file.getName())) {
            throw new IOException(file.getAbsolutePath() + " is not a JAR in " + modulesFolder.getAbsolutePath());
        }
        Removal removal = new Removal();
        removal.name = file.getName();
        removal.size = Files.size(file.toPath());
        removal.lastModified = Files.getLastModifiedTime(file.toPath()).toMillis();
        removal.sha256 = readSha256(file);
        return removal;
    }

    private void checkPendingFiles(Record record) throws RecordRefused {
        refuseLinkedFolders(record);
        if (Record.INSTALL.equals(record.type)) {
            requireRecorded(record, may(stagedOf(record), record.stagedSha256));
        } else if (Record.UPDATE.equals(record.type)) {
            requireRecorded(record, may(backupOf(record)), may(stagedOf(record), record.stagedSha256));
        }
    }

    private String cancelPending(Record record) {
        try {
            checkPendingFiles(record);
        } catch (RecordRefused refusal) {
            return refusal.getMessage();
        }
        return removePendingRecord(record);
    }

    private String removePendingRecord(Record record) {
        try {
            purgeSuperseded(record);
            // Unlink the authoritative journal first. A scratch directory left after that cannot apply.
            Files.delete(recordFileOf(record).toPath());
        } catch (IOException | SecurityException failure) {
            return describe(failure);
        }
        deleteTree(workFolder(record));
        return null;
    }

    static String text(String value) {
        return value == null ? "?" : value;
    }

    /**
     * {@link #cancelStagedUpdates(RemovedModule)} for a module known by one runtime name only.
     *
     * @param moduleName the module's runtime name
     * @return the versions whose updates were cancelled
     */
    List<String> cancelStagedUpdates(String moduleName) {
        return cancelStagedUpdates(new RemovedModule(Collections.<String>emptyList(),
                Collections.singletonList(moduleName), Collections.<String>emptyList()));
    }

    /**
     * Cancels every update transaction of a module being uninstalled: its record and its working
     * folder -- the staged JAR and any kept old JAR -- are deleted, so nothing brings the module
     * back at the next start, and a download of its update still running is discarded. The modules
     * folder is not touched; the uninstall owns that.
     *
     * <p>A transaction belongs to the module when its identify-string, the runtime name it was staged
     * under, or the JAR it would replace is one the uninstall resolved (see {@link RemovedModule}). The
     * uninstall resolves those before it touches the modules folder, so the match holds whatever
     * happened to the files afterwards -- deleted, deferred, not deletable, or a folder that could not
     * be listed (round-10 review).
     *
     * @param removed the uninstalled module, as the uninstall resolved it
     * @return the versions whose updates were cancelled
     */
    public List<String> cancelStagedUpdates(RemovedModule removed) {
        synchronized (LOCK) {
            cancelDownload(removed);
            List<String> cancelled = new ArrayList<>();
            List<File> recordFiles;
            try {
                recordFiles = recordFiles();
            } catch (IOException e) {
                // The next start cannot list the folder either, so it applies nothing and reports it.
                LOGGER.log(Level.WARNING, "No staged update of " + removed + " could be cancelled", e);
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
                if (!removed.matches(record.key, record.moduleName, record.oldName)) {
                    continue;
                }
                if (cancelOne(record, recordFile)) {
                    cancelled.add(record.newVersion);
                }
            }
            return cancelled;
        }
    }

    /**
     * Cancels one staged update: its working folder and record are deleted -- unless the record is
     * held for the operator, or its folder holds a file the transaction cannot identify, which is then
     * held for the operator rather than deleted (foreign-file rule). Called under {@link #LOCK}.
     *
     * @return whether it was cancelled
     */
    private boolean cancelOne(Record record, File recordFile) {
        if (Record.NEEDS_OPERATOR.equals(record.state)) {
            return false;
        }
        try {
            requireRecorded(record, may(backupOf(record), record.oldSha256), may(stagedOf(record), record.stagedSha256));
        } catch (RecordRefused refused) {
            LOGGER.log(Level.SEVERE, "The staged update of module " + record.moduleName + " was not cancelled: "
                    + refused.getMessage() + "; its record " + recordFile.getAbsolutePath() + " is kept");
            return false;
        }
        return deleteTree(workFolder(record)) && deleteQuietly(recordFile);
    }

    /**
     * The records of this module's updates held for the operator, which {@code /upm uninstall} refuses
     * to go past (foreign-file rule): the module is matched as a cancellation matches it.
     *
     * @param removed the module an uninstall would remove
     * @return the record files, empty when there is none (or the folder cannot be listed)
     */
    public List<String> heldForOperator(RemovedModule removed) {
        List<String> held = new ArrayList<>();
        synchronized (LOCK) {
            List<File> recordFiles;
            try {
                recordFiles = recordFiles();
            } catch (IOException e) {
                return held;
            }
            for (File recordFile : recordFiles) {
                Record record = updateRecordIn(recordFile);
                if (record != null && Record.NEEDS_OPERATOR.equals(record.state)
                        && removed.matches(record.key, record.moduleName, record.oldName)) {
                    held.add(recordFile.getAbsolutePath());
                }
            }
        }
        return held;
    }

    /** The update record in {@code recordFile}, or {@code null} when it cannot be read or is not one. */
    private Record updateRecordIn(File recordFile) {
        try {
            Record record = readRecord(recordFile);
            return record != null && Record.UPDATE.equals(record.type)
                    && recordFile.getName().equals(recordFileOf(record).getName()) ? record : null;
        } catch (IOException e) {
            return null;
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
        deniedAsIo(transactionsFolder, () -> recordDeferredRemovalChecked(moduleName, files));
    }

    /** {@link #recordDeferredRemoval(String, List)}, before a denied file access is made a checked failure. */
    private void recordDeferredRemovalChecked(String moduleName, List<File> files) throws IOException {
        synchronized (LOCK) {
            Record record = new Record();
            record.type = Record.REMOVE;
            record.key = moduleName;
            File recordFile = recordFileOf(record);
            if (exists(recordFile)) {
                record.removals.addAll(mergeableRemovals(recordFile));
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
                removal.size = Files.size(file.toPath());
                removal.lastModified = Files.getLastModifiedTime(file.toPath()).toMillis();
                removal.sha256 = hash;
                record.removals.removeIf(r -> removal.name.equals(r.name));
                record.removals.add(removal);
            }
            record.state = Record.PENDING;
            record.moduleName = moduleName;
            writeRecord(record);
            GENERATION.incrementAndGet();
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
        deniedAsIo(transactionsFolder, () -> forgetDeferredRemovalChecked(fileName));
    }

    /** {@link #forgetDeferredRemoval(String)}, before a denied file access is made a checked failure. */
    private void forgetDeferredRemovalChecked(String fileName) throws IOException {
        synchronized (LOCK) {
            for (File recordFile : recordFiles()) {
                Record record = removalRecordIn(recordFile);
                if (record == null || !record.removals.removeIf(removal -> fileName.equals(removal.name))) {
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

    /**
     * The entries of an earlier removal record of the same module, to merge into a new one; none when
     * it cannot be read or fails the validation start-up applies ({@link #problemWith}: for example a
     * null entry), and it is then replaced, with a warning naming it (Codex round 18, #561).
     */
    private List<Removal> mergeableRemovals(File recordFile) {
        Record existing;
        try {
            existing = readRecord(recordFile);
        } catch (IOException unreadable) {
            // An unreadable earlier record of this module cannot be merged; the new one replaces it.
            LOGGER.log(Level.WARNING, "Replacing an unreadable update record while recording a deletion: "
                    + recordFile.getAbsolutePath(), unreadable);
            return Collections.emptyList();
        }
        String problem = existing == null ? null : problemWith(existing, recordFile);
        if (problem != null) {
            LOGGER.log(Level.WARNING, "Replacing an update record that cannot be carried out (" + problem
                    + ") while recording a deletion: " + recordFile.getAbsolutePath());
            return Collections.emptyList();
        }
        return existing == null || existing.removals == null ? Collections.<Removal>emptyList() : existing.removals;
    }

    /**
     * The removal record in {@code recordFile}, or {@code null} when it holds none: a record of another
     * type, or one that cannot be read or fails the validation start-up applies ({@link #problemWith}:
     * for example a null entry). A record that cannot be used is warned about, naming it, and never
     * stops the others being cleared (round-5 review; Codex round 18, #561).
     */
    private Record removalRecordIn(File recordFile) {
        Record record;
        try {
            record = readRecord(recordFile);
        } catch (IOException unreadable) {
            LOGGER.log(Level.WARNING, "Skipped an unreadable update record while clearing a recorded deletion: "
                    + recordFile.getAbsolutePath(), unreadable);
            return null;
        }
        String problem = record == null ? "empty record" : problemWith(record, recordFile);
        if (problem != null) {
            LOGGER.log(Level.WARNING, "Skipped an update record that cannot be carried out (" + problem
                    + ") while clearing a recorded deletion: " + recordFile.getAbsolutePath());
            return null;
        }
        return Record.REMOVE.equals(record.type) ? record : null;
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
        var candidates = new ArrayList<>(removals);
        candidates.addAll(updates);
        var superseded = supersededIds(candidates);
        for (Record record : removals) {
            if (!superseded.contains(idOf(record))) {
                applyGuarded(record);
            }
        }
        for (Record record : updates) {
            if (!superseded.contains(idOf(record))) {
                applyGuarded(record);
            }
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
            purgeSuperseded(record);
            applyRecord(record);
        } catch (NeedsOperator held) {
            // Already recorded and reported by requireRecorded.
            appliedThisStart.remove(record);
        } catch (IOException | RecordRefused refused) {
            outcome(record, StartOutcome.Result.NOT_APPLIED);
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
            } catch (NeedsOperator held) {
                return;
            } catch (RecordRefused | RuntimeException again) {
                error.addSuppressed(again);
            }
        }
        outcome(record, StartOutcome.Result.NOT_APPLIED);
        report(Level.SEVERE, Keys.TRANSACTION_ERROR, recordFileOf(record).getAbsolutePath(), describe(error));
    }

    private void applyRecord(Record record) throws RecordRefused {
        if (Record.NEEDS_OPERATOR.equals(record.state)) {
            outcome(record, StartOutcome.Result.NEEDS_OPERATOR);
            report(Level.SEVERE, Keys.NEEDS_OPERATOR, displayName(record), record.failure,
                    recordFileOf(record).getAbsolutePath());
            return;
        }
        if (Record.REMOVE.equals(record.type)) {
            applyRemoval(record);
            return;
        }
        if (!Record.UPDATE.equals(record.type) && !Record.INSTALL.equals(record.type)) {
            throw new RecordRefused("unknown record type " + record.type);
        }
        refuseLinkedFolders(record);
        switch (record.state) {
            case Record.PENDING:
                if (Record.INSTALL.equals(record.type)) {
                    applyInstall(record);
                } else {
                    applyUpdate(record);
                }
                break;
            case Record.APPLIED:
                // The previous start swapped the files and ended before it observed anything.
                // Nothing was observed, so nothing may be committed: restore before loading.
                record.state = Record.ROLLING_BACK;
                if (persist(record) || Record.UPDATE.equals(record.type)) {
                    finishRollback(record, RollbackPhase.UNCONFIRMED);
                } else {
                    outcome(record, StartOutcome.Result.NOT_APPLIED);
                }
                break;
            case Record.COMMITTING:
                finishCommit(record);
                break;
            case Record.ROLLING_BACK:
                finishRollback(record, RollbackPhase.RESUMED);
                break;
            case Record.FAILED:
                restoreAfterFailure(record);
                outcome(record, StartOutcome.Result.NOT_APPLIED);
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
                        outcome(record, StartOutcome.Result.NOT_APPLIED);
                        continue;
                    }
                    crashPoints.reached(CrashPoints.AFTER_DECISION_RECORDED);
                    finishCommit(record);
                } else {
                    record.state = Record.ROLLING_BACK;
                    if (persist(record) || Record.UPDATE.equals(record.type)) {
                        crashPoints.reached(CrashPoints.AFTER_DECISION_RECORDED);
                        finishRollback(record, RollbackPhase.AFTER_LOAD);
                    } else {
                        outcome(record, StartOutcome.Result.NOT_APPLIED);
                    }
                }
            } catch (NeedsOperator held) {
                // Already recorded and reported by requireRecorded.
                continue;
            } catch (RecordRefused refused) {
                outcome(record, StartOutcome.Result.NOT_APPLIED);
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFileOf(record).getAbsolutePath(),
                        refused.getMessage());
            } catch (RuntimeException unexpected) {
                // One transaction's problem never ends the start, and never hides a load failure.
                outcome(record, StartOutcome.Result.NOT_APPLIED);
                report(Level.WARNING, Keys.RECORD_REFUSED, recordFileOf(record).getAbsolutePath(),
                        describe(unexpected));
            }
        }
    }

    /**
     * Writes the lines this start collected, translated.
     *
     * @param logger where to write them
     * @param i18n   legacy translation lookup
     * @param messages the text engine, or null if it could not be initialized
     */
    public void flushReports(Logger logger, Function<String, String> i18n, Messages messages) {
        for (Report report : reports) {
            String line;
            if (report.messageKey == null) {
                line = String.format(i18n.apply(report.getKey()), report.getArgs());
            } else if (messages != null) {
                line = PlainTextComponentSerializer.plainText().serialize(
                        messages.render(report.messageKey, Bukkit.getConsoleSender(), report.messageArgs));
            } else {
                line = report.messageKey.translationKey() + " " + Arrays.stream(report.messageArgs)
                        .map(arg -> switch (arg) {
                            case Arg.Unparsed value -> value.value();
                            case Arg.Numeric number -> number.value().toString();
                            case Arg.Moment moment -> moment.value().toString();
                            case Arg.Localized text -> text.value().toString();
                        }).collect(java.util.stream.Collectors.joining(", "));
            }
            logger.log(report.getLevel(), line);
        }
        reports.clear();
    }

    private void report(Level level, MessageKey key, Arg... args) {
        reports.add(new Report(level, key, args));
    }

    /**
     * The lines collected and not yet written.
     *
     * @return the lines, in order
     */
    List<Report> pendingReports() {
        return Collections.unmodifiableList(new ArrayList<>(reports));
    }

    private void applyInstall(Record record) throws RecordRefused {
        File staged = stagedOf(record);
        File target = confined(modulesFolder, record.targetName);
        if (present(staged) && present(target)) {
            record.state = Record.FAILED;
            record.failure = "a file already exists: " + target.getAbsolutePath();
            persist(record);
            outcome(record, StartOutcome.Result.NOT_APPLIED);
            reportInstall(Level.SEVERE, UpmKeys.START_INSTALL_TARGET_EXISTS, record,
                    string("file", target.getName()), string("folder", modulesFolder.getAbsolutePath()));
            return;
        }
        try {
            if (present(staged) && isStagedJar(staged, record)) {
                Files.createDirectories(modulesFolder.toPath());
                ops.move(staged.toPath(), target.toPath());
                crashPoints.reached(CrashPoints.AFTER_INSTALL_MOVED);
            } else if (present(staged) || !isStagedJar(target, record)) {
                throw new IOException("the checked staged JAR is missing or its SHA-256 has changed");
            }
        } catch (IOException | SecurityException failure) {
            failInstall(record, failure);
            return;
        }
        record.state = Record.APPLIED;
        if (persist(record)) {
            crashPoints.reached(CrashPoints.AFTER_INSTALL_APPLIED_RECORDED);
        }
        // Even if persisting failed, the file is present. Decide from the actual load at this start.
        appliedThisStart.add(record);
    }

    private void failInstall(Record record, Throwable failure) {
        record.state = Record.FAILED;
        record.failure = describe(failure);
        persist(record);
        outcome(record, StartOutcome.Result.NOT_APPLIED);
        if (isAcrossFileSystems(failure)) {
            reportInstall(Level.SEVERE, UpmKeys.START_INSTALL_CROSS_FILE_SYSTEM, record,
                    string("folder", modulesFolder.getAbsolutePath()), string("work", transactionsFolder.getAbsolutePath()));
        } else {
            reportInstall(Level.SEVERE, UpmKeys.START_INSTALL_FAILED, record, string("error", record.failure));
        }
    }

    private void reportInstall(Level level, MessageKey key, Record record, Arg... details) {
        Arg[] args = Arrays.copyOf(details, details.length + 2);
        args[details.length] = string("module", displayName(record));
        args[details.length + 1] = string("version", record.newVersion);
        report(level, key, args);
    }

    private void finishInstallRollback(Record record, RollbackPhase phase) throws RecordRefused {
        File target = confined(modulesFolder, record.targetName);
        requireRecorded(record, may(target, record.stagedSha256), may(stagedOf(record), record.stagedSha256));
        String error = returnStagedJar(record);
        if (error == null && (!deleteTree(workFolder(record)) || !deleteQuietly(recordFileOf(record)))) {
            error = "could not clean the transaction folder or record";
        }
        if (error != null) {
            outcome(record, StartOutcome.Result.NOT_APPLIED);
            reportInstall(Level.SEVERE, UpmKeys.START_INSTALL_ROLLBACK_DEFERRED, record, string("error", error));
            return;
        }
        outcome(record, StartOutcome.Result.ROLLED_BACK);
        reportInstall(Level.WARNING, phase == RollbackPhase.UNCONFIRMED ? UpmKeys.START_INSTALL_UNCONFIRMED
                : UpmKeys.START_INSTALL_ROLLED_BACK, record);
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
        // The swap moves the old JAR aside and the staged JAR in: each file it touches must be the
        // recorded one, and the places it moves them to must be free (foreign-file rule).
        requireRecorded(record, may(old, record.oldSha256), may(backup, record.oldSha256),
                must(staged, record.stagedSha256), may(target));
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
        // A different file under the old JAR's name never reaches this point: the swap's guard holds
        // the transaction for the operator instead (foreign-file rule).
        if (exists(old) && !pendingRemovals.contains(record.oldName)) {
            return false;
        }
        deleteTree(work);
        deleteQuietly(recordFileOf(record));
        outcome(record, StartOutcome.Result.NOT_APPLIED);
        report(Level.WARNING, Keys.UPDATE_ABANDONED, record.moduleName, record.newVersion, old.getAbsolutePath());
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
        String restoreError = undoApply(record);
        record.state = Record.FAILED;
        outcome(record, StartOutcome.Result.NOT_APPLIED);
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
     * Undoes whatever part of an apply happened: the new JAR goes back to the staged folder if it is
     * in the modules folder ({@link #returnStagedJar}, by its recorded hash), then the kept old JAR goes
     * back ({@link #restoreBackup}) -- in that order, and the second only once the first is done, so
     * the modules folder never holds both. The one undo of a failed apply: when it fails
     * ({@link #failApply}), at every later start while the record is {@code FAILED}
     * ({@link #restoreAfterFailure}), and when {@code /upm update} discards the record
     * ({@link #discardFailed}). Restoring only the old JAR at those later points put it back beside a
     * new JAR whose return had failed (Codex round 14, #561).
     *
     * @return {@code null} when nothing of the apply is left in the modules folder and the old JAR is in
     *         place (or there was none), otherwise what failed
     */
    private String undoApply(Record record) throws RecordRefused {
        if (Record.REMOVE.equals(record.type)) {
            return null;
        }
        if (Record.INSTALL.equals(record.type)) {
            // A failed install may have been refused because its target was already occupied.
            // In that case the staged copy still proves nothing was ever moved into modules/.
            requireRecorded(record, may(stagedOf(record), record.stagedSha256));
            return returnStagedJar(record);
        }
        File target = confined(modulesFolder, record.targetName);
        File old = confined(modulesFolder, record.oldName);
        requireRecorded(record, may(target, record.stagedSha256), may(stagedOf(record), record.stagedSha256),
                may(backupOf(record), record.oldSha256), may(old, record.oldSha256));
        String error = returnStagedJar(record);
        return error != null ? error : restoreBackup(record);
    }

    /** A file a destructive step touches, and the recorded hashes it may hold; none means it must be absent. */
    private static final class Expected {
        private final File file;
        private final java.util.Set<String> hashes = new java.util.LinkedHashSet<>();
        private boolean mustExist;

        Expected(File file, boolean mustExist, String... hashes) {
            this.file = file;
            this.mustExist = mustExist;
            Collections.addAll(this.hashes, hashes);
        }
    }

    /** {@code file} may be absent, or hold one of {@code hashes}; with none given it must be absent. */
    private static Expected may(File file, String... hashes) {
        return new Expected(file, false, hashes);
    }

    /** {@code file} must be there and hold {@code hash}. */
    private static Expected must(File file, String hash) {
        return new Expected(file, true, hash);
    }

    /**
     * The foreign-file rule, at every destructive step: the transaction only ever moves, replaces or
     * deletes a file whose SHA-256 matches the record -- the staged new JAR or the kept old JAR -- and
     * only into a place that is free. Each file the step touches, source and target, is checked here
     * before the step does anything. When one is not what the record says -- another actor replaced
     * the new JAR, dropped a file where the old one is kept, and so on -- nothing is done to any file:
     * the record is kept as {@code NEEDS_OPERATOR}, which later starts do not act on and which
     * {@code /upm update} and {@code /upm uninstall} of the module refuse to go past, and one SEVERE
     * line names each unexpected file with its expected and actual hash and says how to resolve it
     * (Codex round 19, #561; the maintainer's precedent for ambiguous identity: write nothing, warn).
     * Two expectations for the same path (the old and new JAR sharing a name) are merged.
     *
     * @throws NeedsOperator when a file is not the recorded one; the record is already held
     */
    private void requireRecorded(Record record, Expected... expected) throws NeedsOperator {
        java.util.Map<String, Expected> byPath = new java.util.LinkedHashMap<>();
        for (Expected each : expected) {
            Expected merged = byPath.computeIfAbsent(canonicalPath(each.file), path -> new Expected(each.file, false));
            merged.hashes.addAll(each.hashes);
            merged.mustExist |= each.mustExist;
        }
        List<String> unexpected = new ArrayList<>();
        for (Expected each : byPath.values()) {
            String problem = unexpected(each);
            if (problem != null) {
                unexpected.add(problem);
            }
        }
        if (!unexpected.isEmpty()) {
            holdForOperator(record, String.join("; ", unexpected));
        }
    }

    /** What is wrong with one expected file, or {@code null} when it is as recorded. */
    private static String unexpected(Expected expected) {
        String actual = exists(expected.file) ? sha256Of(expected.file) : MISSING;
        boolean recorded = MISSING.equals(actual) ? !expected.mustExist : expected.hashes.contains(actual);
        if (recorded) {
            return null;
        }
        String wanted = expected.hashes.isEmpty() ? "no file" : "SHA-256 " + String.join(" or ", expected.hashes);
        String found = MISSING.equals(actual) ? "no file" : actual == null ? "an unreadable file" : "SHA-256 " + actual;
        return expected.file.getAbsolutePath() + " (expected " + wanted + ", found " + found + ")";
    }

    /** Keeps the record as {@code NEEDS_OPERATOR}, says so once, and ends the step. */
    private void holdForOperator(Record record, String detail) throws NeedsOperator {
        record.state = Record.NEEDS_OPERATOR;
        record.failure = detail;
        persist(record);
        outcome(record, StartOutcome.Result.NEEDS_OPERATOR);
        report(Level.SEVERE, Keys.NEEDS_OPERATOR, record.moduleName, detail, recordFileOf(record).getAbsolutePath());
        throw new NeedsOperator(detail);
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
        // The folder is this transaction's working folder plus a constant; the record's name goes
        // through confined(), which refuses anything but a plain JAR name directly inside it.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
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

    /**
     * A failed apply found at a later start: make sure it is undone -- the new JAR out of the modules
     * folder and the old JAR back ({@link #undoApply}) -- and say so if it cannot be.
     */
    private void restoreAfterFailure(Record record) throws RecordRefused {
        String restoreError = undoApply(record);
        if (restoreError != null && Record.INSTALL.equals(record.type)) {
            reportInstall(Level.SEVERE, UpmKeys.START_INSTALL_ROLLBACK_DEFERRED, record, string("error", restoreError));
        } else if (restoreError != null) {
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
     * Discards a {@code FAILED} record before staging again: the apply is undone ({@link #undoApply}:
     * the new JAR out of the modules folder, the old JAR back if it is still aside), then the working
     * folder and the record are deleted.
     *
     * @return {@code null} on success, otherwise why the old JAR could not be put back
     */
    private String discardFailed(Record record) {
        try {
            String restoreError = undoApply(record);
            if (restoreError != null) {
                return restoreError;
            }
        } catch (RecordRefused refused) {
            return refused.getMessage();
        }
        return removePendingRecord(record);
    }

    private boolean isLoadedFrom(Record record, File target, List<UltiToolsPlugin> loaded,
                                 Function<UltiToolsPlugin, File> codeSource) {
        String targetPath = canonicalPath(target);
        for (UltiToolsPlugin plugin : loaded) {
            if (!Record.INSTALL.equals(record.type) && !record.key.equals(normalize(plugin.getIdentifyString()))) {
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

    private void finishCommit(Record record) throws RecordRefused {
        if (Record.INSTALL.equals(record.type)) {
            requireRecorded(record, may(stagedOf(record), record.stagedSha256),
                    must(confined(modulesFolder, record.targetName), record.stagedSha256));
            outcome(record, StartOutcome.Result.APPLIED);
            if (!deleteTree(workFolder(record)) || !deleteQuietly(recordFileOf(record))) {
                reportInstall(Level.WARNING, UpmKeys.START_INSTALL_CLEANUP_DEFERRED, record,
                        string("folder", workFolder(record).getAbsolutePath()));
            } else {
                reportInstall(Level.INFO, UpmKeys.START_INSTALL_COMMITTED, record);
            }
            return;
        }
        // The commit deletes the kept old JAR: it must be the recorded one, and the new JAR it commits
        // to must still be the staged one at the new JAR's name (Codex round 19, foreign-file rule).
        requireRecorded(record, may(backupOf(record), record.oldSha256), may(stagedOf(record), record.stagedSha256),
                must(confined(modulesFolder, record.targetName), record.stagedSha256));
        outcome(record, StartOutcome.Result.APPLIED);
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
        if (Record.INSTALL.equals(record.type)) {
            finishInstallRollback(record, phase);
            return;
        }
        File target = confined(modulesFolder, record.targetName);
        File old = confined(modulesFolder, record.oldName);
        File backup = backupOf(record);
        // The rollback deletes the new JAR and puts the kept old JAR back: nothing is removed or put
        // back beside a file it cannot identify (Codex round 19, foreign-file rule).
        requireRecorded(record, may(target, record.stagedSha256), may(backup, record.oldSha256),
                may(old, record.oldSha256), may(stagedOf(record), record.stagedSha256));
        if (!present(backup)) {
            // Nothing is kept to put back. Either an earlier attempt already put it back (then the
            // new JAR is gone too, and only the record is left to clean up), or the kept JAR went
            // missing -- and removing the new JAR then would leave no version at all, so it stays.
            boolean noBackup = isStagedJar(target, record);
            outcome(record, noBackup ? StartOutcome.Result.NOT_APPLIED : StartOutcome.Result.ROLLED_BACK);
            if (noBackup) {
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
            outcome(record, StartOutcome.Result.NOT_APPLIED);
            report(Level.SEVERE, phase == RollbackPhase.AFTER_LOAD ? Keys.ROLLBACK_DEFERRED
                    : Keys.ROLLBACK_FAILED_BEFORE_LOAD, record.moduleName, record.newVersion, record.oldVersion,
                    describe(e));
            return;
        }
        deleteTree(workFolder(record));
        deleteQuietly(recordFileOf(record));
        outcome(record, StartOutcome.Result.ROLLED_BACK);
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
     * Deletes the JARs an uninstall recorded, before any module loads: each only if it is confirmed
     * to still be the file that was recorded. One that still cannot be deleted stays recorded for the
     * next start and is reported; one confirmed to be a different file -- replaced since -- is left
     * alone, reported, and its entry dropped. One whose content or metadata cannot be read to confirm
     * its identity is not deleted either, and keeps its entry exactly as recorded, reported SEVERE
     * with the read error:
     * whether it is still the recorded file is unknown, and dropping the entry would let a module the
     * operator uninstalled load at every later start.
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
        boolean skipped = false;
        for (int i = 0; i < recorded.size(); i++) {
            Removal removal = recorded.get(i);
            File file = targets.get(i);
            if (!exists(file)) {
                continue;
            }
            RecordedIdentity identity = recordedIdentity(removal, file);
            if (identity.readError != null) {
                report(Level.SEVERE, UpmKeys.START_REMOVAL_UNREADABLE, string("module", displayName(record)),
                        string("file", file.getAbsolutePath()), string("error", describe(identity.readError)));
                remaining.add(removal);
                pendingRemovals.add(removal.name);
                continue;
            }
            if (!identity.same) {
                skipped = true;
                report(Level.WARNING, UpmKeys.START_REMOVAL_SKIPPED, string("module", displayName(record)),
                        string("file", file.getAbsolutePath()));
                continue;
            }
            try {
                ops.delete(file.toPath());
                deleted.add(file.getName());
            } catch (IOException | RuntimeException e) {
                report(Level.SEVERE, UpmKeys.START_REMOVAL_FAILED, string("module", displayName(record)),
                        string("file", file.getAbsolutePath()), string("error", describe(e)));
                remaining.add(removal);
                pendingRemovals.add(removal.name);
            }
        }
        if (!deleted.isEmpty()) {
            report(Level.INFO, UpmKeys.START_REMOVAL_REMOVED, string("module", displayName(record)),
                    string("files", String.join(", ", deleted)));
        }
        outcome(record, remaining.isEmpty() && !skipped ? StartOutcome.Result.APPLIED : StartOutcome.Result.NOT_APPLIED);
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
     * the same. Three outcomes, not two: a file whose content or metadata cannot be read is neither
     * confirmed the same nor confirmed different.
     */
    private static RecordedIdentity recordedIdentity(Removal removal, File file) {
        if (removal.sha256 == null) {
            return RecordedIdentity.DIFFERENT;
        }
        try {
            if (Files.size(file.toPath()) != removal.size
                    || Files.getLastModifiedTime(file.toPath()).toMillis() != removal.lastModified) {
                return RecordedIdentity.DIFFERENT;
            }
            return removal.sha256.equals(readSha256(file)) ? RecordedIdentity.SAME : RecordedIdentity.DIFFERENT;
        } catch (IOException e) {
            return new RecordedIdentity(false, e);
        } catch (SecurityException e) {
            return new RecordedIdentity(false, new IOException(file.getAbsolutePath() + ": access denied", e));
        }
    }

    /**
     * What {@link #recordedIdentity} found: the same file, a different file, or -- with the read
     * error -- a file whose content could not be read to tell.
     */
    private static final class RecordedIdentity {
        static final RecordedIdentity SAME = new RecordedIdentity(true, null);
        static final RecordedIdentity DIFFERENT = new RecordedIdentity(false, null);

        final boolean same;
        /** Why the content or metadata could not be read; {@code null} when the reads succeeded. */
        final IOException readError;

        RecordedIdentity(boolean same, IOException readError) {
            this.same = same;
            this.readError = readError;
        }
    }

    /**
     * A file's SHA-256, failing with the reason it cannot be read. {@link #sha256Of(File)} returns
     * {@code null} instead, which its other callers treat as a refusal or a held state; a recorded
     * removal needs the reason, and must not read "cannot be read" as "a different file".
     */
    private static String readSha256(File file) throws IOException {
        try {
            return ResourceHashSidecar.sha256(file);
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } catch (SecurityException e) {
            throw new IOException(file.getAbsolutePath() + ": access denied: " + describe(e), e);
        }
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

    private File stagedOf(Record record) throws RecordRefused {
        // The folder is this transaction's working folder plus a constant; the record's name goes
        // through confined(), which refuses anything but a plain JAR name directly inside it.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        return confined(new File(workFolder(record), STAGED_FOLDER), record.stagedName);
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
        if (!(Record.INSTALL.equals(record.type) || Record.UPDATE.equals(record.type) || Record.REMOVE.equals(record.type))
                || record.key == null || record.state == null
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
        if (Record.UPDATE.equals(record.type) && (record.oldName == null || record.oldVersion == null
                || record.oldSha256 == null)) {
            return record.oldName == null ? "oldName" : record.oldVersion == null ? "oldVersion" : "oldSha256";
        }
        String[][] fields = {
            {"moduleName", record.moduleName}, {"stagedName", record.stagedName}, {"targetName", record.targetName},
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
        File recordFile = recordFileOf(record);
        // A denied file access (SecurityException) is an IOException here, so every caller's
        // checked path -- a failure result, discarding the staging, a report line -- runs (Codex, #561).
        deniedAsIo(recordFile, () -> {
            Files.createDirectories(transactionsFolder.toPath());
            Path temporary = Files.createTempFile(transactionsFolder.toPath(), TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
            try {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    GSON.toJson(record, writer);
                }
                crashPoints.reached(CrashPoints.BEFORE_RECORD_MOVED);
                Files.move(temporary, recordFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                // Gone once moved; a leftover is never a record and the next start sweeps it.
                deleteQuietly(temporary.toFile());
            }
        });
    }

    /** File work that may fail with an {@link IOException}. */
    @FunctionalInterface
    private interface FileWork {
        void run() throws IOException;
    }

    /**
     * Runs {@code work}, turning a denied file access -- a {@link SecurityException} from any of its
     * {@code Files} calls -- into an {@link IOException} naming {@code file}, so a caller that handles
     * the checked failure handles this one too.
     */
    private static void deniedAsIo(File file, FileWork work) throws IOException {
        try {
            work.run();
        } catch (SecurityException denied) {
            throw new IOException(file.getAbsolutePath() + ": access denied: " + describe(denied), denied);
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
        // The existence check is inside the guard too: a denied access here is "not deleted", never an
        // exception, so discarding a staging or cancelling an update cannot throw (Codex, #561).
        try {
            if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            walkAndDelete(folder);
            return true;
        } catch (IOException | SecurityException | java.io.UncheckedIOException e) {
            LOGGER.log(Level.FINE, "Could not delete " + folder, e);
            return false;
        }
    }

    /** Deletes {@code folder} and everything under it, deepest first. */
    private static void walkAndDelete(File folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            Collections.reverse(paths);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * The version a staged JAR's {@code plugin.yml} declares, when it declares the expected
     * identify-string and a version -- identity and version only, read the same way
     * {@code UltiToolsPlugin} reads them, and nothing about whether its classes would load.
     */
    private static String declaredVersion(File jar, String key) throws IOException {
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
        } catch (InvalidConfigurationException e) {
            LOGGER.log(Level.FINE, "Could not parse plugin.yml of " + jar, e);
            return null;
        } catch (SecurityException e) {
            throw new IOException(jar.getName() + ": plugin.yml access denied", e);
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

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
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
         * @throws NoCompatibleVersionException when the catalogue has versions of the module but
         *         none this framework installs
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

    /**
     * Thrown by {@link Catalogue#latestVersion} when the catalogue answers that versions of the
     * module exist but none is compatible with this framework's API level (HTTP 409 from the
     * level-filtered {@code latest}; plan 18-40), so staging can say so instead of "no download".
     */
    public static final class NoCompatibleVersionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int frameworkLevel;

        /**
         * @param frameworkLevel the API level the request carried
         */
        public NoCompatibleVersionException(int frameworkLevel) {
            super("the catalogue has no version compatible with UltiTools API level " + frameworkLevel);
            this.frameworkLevel = frameworkLevel;
        }

        /** @return the API level the request carried */
        public int getFrameworkLevel() {
            return frameworkLevel;
        }
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
        String BEFORE_RECORD_MOVED = "before-record-moved";
        String AFTER_OLD_MOVED = "after-old-moved";
        String AFTER_NEW_MOVED = "after-new-moved";
        String AFTER_APPLIED_RECORDED = "after-applied-recorded";
        String AFTER_DECISION_RECORDED = "after-decision-recorded";
        String AFTER_INSTALL_MOVED = "after-install-moved";
        String AFTER_INSTALL_APPLIED_RECORDED = "after-install-applied-recorded";

        static CrashPoints fromSystemProperty() {
            String armed = System.getProperty("ultitools.upm.testCrashAt");
            if (armed == null) {
                return NONE;
            }
            return switch (armed) {
                case BEFORE_RECORD_MOVED, AFTER_RECORD_WRITTEN, AFTER_OLD_MOVED, AFTER_NEW_MOVED,
                        AFTER_APPLIED_RECORDED, AFTER_DECISION_RECORDED, AFTER_INSTALL_MOVED,
                        AFTER_INSTALL_APPLIED_RECORDED -> point -> {
                    if (armed.equals(point)) {
                        LOGGER.severe("Test crash point reached: " + point);
                        Runtime.getRuntime().halt(86);
                    }
                };
                default -> {
                    LOGGER.warning("Ignoring unknown ultitools.upm.testCrashAt test crash point.");
                    yield NONE;
                }
            };
        }

        void reached(String point);
    }

    /** A record whose names would leave their folder, or that this version does not understand. */
    static class RecordRefused extends Exception {
        private static final long serialVersionUID = 1L;

        RecordRefused(String message) {
            super(message);
        }
    }

    /** A step found a file the transaction cannot identify; the record is held for the operator. */
    static final class NeedsOperator extends RecordRefused {
        private static final long serialVersionUID = 1L;

        NeedsOperator(String message) {
            super(message);
        }
    }

    /** The persisted record of one transaction. Field names are the on-disk format. */
    @SuppressWarnings("PMD.DataClass")
    static final class Record {
        static final String INSTALL = "INSTALL";
        static final String UPDATE = "UPDATE";
        static final String REMOVE = "REMOVE";
        static final String PENDING = "PENDING";
        static final String APPLIED = "APPLIED";
        static final String COMMITTING = "COMMITTING";
        static final String ROLLING_BACK = "ROLLING_BACK";
        static final String FAILED = "FAILED";
        /** Held for the operator: a file the transaction cannot identify; later starts do nothing to it. */
        static final String NEEDS_OPERATOR = "NEEDS_OPERATOR";

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
        Long requestedAt;
        PendingChanges.Requester requestedBy;
        String fromVersion;
        // Unlike key, this is never a fallback module name.
        String removalIdentifyString;
        // A replacement of another kind can be durable before its predecessor is unlinked.
        String supersedes;
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

    /**
     * A module an uninstall removed, in every form a transaction can name it by: the
     * identify-strings and runtime names of the instances it unloaded (plus the argument it was
     * given), and the file names of its JARs -- the ones those instances were loaded from, the ones
     * the start-up scan recorded as declaring their main class, and the ones it deleted or recorded
     * for deletion. A cancellation matches on all of them, so it follows the identity the uninstall
     * resolved rather than the name the operator typed (round-10 review).
     */
    public static final class RemovedModule {
        private final java.util.Set<String> identifyKeys = new java.util.HashSet<>();
        private final java.util.Set<String> moduleNames = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> jarNames = new java.util.HashSet<>();

        /**
         * The uninstalled module.
         *
         * @param identifyStrings its identify-strings; a {@code null} or blank one is skipped
         * @param moduleNames     the names it was loaded under or named by
         * @param jarNames        the file names of its JARs in the modules folder
         */
        public RemovedModule(java.util.Collection<String> identifyStrings, java.util.Collection<String> moduleNames,
                             java.util.Collection<String> jarNames) {
            for (String identifyString : identifyStrings) {
                String key = normalize(identifyString);
                if (key != null) {
                    identifyKeys.add(key);
                }
            }
            addNonNull(this.moduleNames, moduleNames);
            addNonNull(this.jarNames, jarNames);
        }

        private static void addNonNull(java.util.Set<String> into, java.util.Collection<String> values) {
            for (String value : values) {
                if (value != null) {
                    into.add(value);
                }
            }
        }

        /**
         * Whether a transaction that names a module this way belongs to this one.
         *
         * @param key        the transaction's normalised identify-string
         * @param moduleName the runtime name it was staged under
         * @param oldJarName the file name of the JAR it replaces
         * @return {@code true} when any of them is this module's
         */
        boolean matches(String key, String moduleName, String oldJarName) {
            return key != null && identifyKeys.contains(key)
                    || moduleName != null && moduleNames.contains(moduleName)
                    || oldJarName != null && jarNames.contains(oldJarName);
        }

        @Override
        public String toString() {
            return String.join("/", moduleNames);
        }
    }

    /** One line for the start-up log, with its catalogue key and arguments. */
    public static final class Report {
        private final Level level;
        private final String key;
        private final Object[] args;
        private final MessageKey messageKey;
        private final Arg[] messageArgs;

        Report(Level level, MessageKey key, Arg... args) {
            this.level = level;
            this.key = key.translationKey();
            this.args = args.clone();
            this.messageKey = key;
            this.messageArgs = args.clone();
        }

        Report(Level level, String key, Object... args) {
            this.level = level;
            this.key = key;
            this.args = args.clone();
            this.messageKey = null;
            this.messageArgs = null;
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

    private static Arg[] namedArgs(MessageKey key, Object[] values) {
        String names = switch (key.path()) {
            case "upm.reason.no_identify_string" -> "";
            case "upm.reason.not_loaded" -> "module";
            case "upm.reason.not_in_modules_folder" -> "file folder";
            case "upm.reason.shared_jar" -> "file modules";
            case "upm.reason.old_jar_unreadable" -> "file";
            case "upm.reason.ambiguous_identify_string" -> "module modules";
            case "upm.reason.no_download" -> "module";
            case "upm.reason.incompatible_api_level" -> "file declared level floor max";
            case "upm.reason.no_api_level" -> "file level floor max";
            case "upm.reason.no_compatible_update" -> "module level floor max";
            case "upm.reason.other_copy_loads_first" -> "file files";
            case "upm.reason.download_failed" -> "error";
            case "upm.reason.invalid_jar" -> "file";
            case "upm.reason.wrong_identity" -> "file module";
            case "upm.reason.staged_unreadable" -> "file error";
            case "upm.reason.wrong_version" -> "file declared version";
            case "upm.reason.cancelled_while_downloading" -> "";
            case "upm.reason.needs_operator" -> "failure file";
            case "upm.reason.record_failed" -> "error";
            case "upm.reason.access_denied" -> "error";
            case "upm.reason.record_unreadable" -> "file error";
            case "upm.reason.leftover_backup" -> "folder";
            case "upm.reason.previous_unrestored" -> "error";
            default -> throw new IllegalArgumentException("Not an update reason: " + key);
        };
        String[] tags = names.isEmpty() ? new String[0] : names.split(" ");
        if (tags.length != values.length) {
            throw new IllegalArgumentException("Wrong argument count for " + key);
        }
        Arg[] result = new Arg[tags.length];
        for (int i = 0; i < tags.length; i++) {
            result[i] = values[i] instanceof Number number ? Arg.number(tags[i], number)
                    : string(tags[i], values[i] == null ? "?" : values[i].toString());
        }
        return result;
    }

    /** What {@link #stageUpdate} did. */
    public static final class StageResult {
        /** The outcome. */
        public enum Outcome {
            /** Downloaded and recorded; takes effect at the next start. */
            STAGED,
            QUEUED_INSTALL, REPLACED_INSTALL, REPLACED_UPDATE, CANCELLED_UPDATE,
            CANCELLED_UNINSTALL, CANCELLED_UNINSTALL_SAME, ALREADY_CURRENT, PENDING_UNINSTALL, UNFINISHED,
            /** An update of this module is already staged; nothing changed. */
            ALREADY_STAGED,
            /** An earlier update of this module is still being finished at the next start; nothing changed. */
            BUSY,
            /** Nothing was staged; the reason says why. */
            FAILED
        }

        private final Outcome outcome;
        private String moduleName;
        private final String oldVersion;
        private final String newVersion;
        private final String previousFailure;
        private final MessageKey reasonKey;
        private final Arg[] reasonArgs;
        private String previousVersion;

        @SuppressWarnings("PMD.ExcessiveParameterList")
        private StageResult(Outcome outcome, String moduleName, String oldVersion, String newVersion,
                            String previousFailure, MessageKey reasonKey, Arg[] reasonArgs) {
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
                    new Arg[0]);
        }

        static StageResult failed(String previousFailure, MessageKey reasonKey, Object... values) {
            return new StageResult(Outcome.FAILED, null, null, null, previousFailure, reasonKey,
                    namedArgs(reasonKey, values));
        }

        StageResult withModule(String module) {
            moduleName = module;
            return this;
        }

        StageResult withPreviousVersion(String version) {
            previousVersion = version;
            return this;
        }

        /** @return the earlier pending version when an operation replaced or cancelled it */
        public String getPreviousVersion() {
            return previousVersion;
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
        public MessageKey getReasonKey() {
            return reasonKey;
        }

        /** @return the reason's format arguments */
        public Arg[] getReasonArgs() {
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
        public static final String NEEDS_OPERATOR =
                "模块 %s 的更新已暂停，等待处理：%s。在这些文件与更新记录一致之前，更新不会移动或删除任何文件，/upm update 和 /upm uninstall 也会拒绝该模块。请检查这些文件（需要的 JAR 可从更新目录中取回），然后删除更新记录 %s 及其同名文件夹，以放弃这次更新。";
        public static final String RECORD_REFUSED = "更新记录 %s 已拒绝执行：%s";
        public static final String RECORD_UNREADABLE = "更新记录 %s 无法读取，已跳过：%s";
        public static final String RECORD_WRITE_FAILED = "更新记录 %s 无法写入：%s";

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
