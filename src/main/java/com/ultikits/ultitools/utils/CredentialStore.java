package com.ultikits.ultitools.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.exceptions.DataAccessException;

/**
 * <b>The single owner of {@code data.json}.</b> Any other class that opens a reader or writer on
 * that file is a defect (D-12) -- route the operation through {@link #read()}, {@link #write(Map)}
 * or {@link #update(UnaryOperator)} instead.
 * <p>
 * Before this class existed, {@code data.json} had two independent writers guarded by two
 * different monitors ({@code CloudAuthManager}'s class monitor and {@code CommonUtils}'s class
 * monitor), and both truncated the file and wrote in place with no temporary file and no atomic
 * rename. That shape allows a torn write (a crash mid-write leaves a partially-written file), a
 * lost update (two read-modify-write sequences interleaving and one silently discarding the
 * other's change), and a torn file being swallowed as "no saved token" by a broad catch. This
 * class dissolves all three by construction: one lock guards every read, write and update; every
 * write goes to a same-directory temporary file and is applied by
 * {@link Files#move(Path, Path, java.nio.file.CopyOption...)} with {@link StandardCopyOption#ATOMIC_MOVE}
 * so a crash mid-write leaves either the previous complete file or the new complete file, never a
 * truncated one; and {@link #read()} reports a file that exists but does not parse as an outcome
 * structurally distinct from an absent file, so a caller cannot conflate the two.
 * <p>
 * <b>An existing file that cannot be read as a credential document is preserved (#573).</b> A file
 * that is empty, whitespace-only, the JSON literal {@code null}, or not valid JSON is a parse
 * failure -- the store itself never writes any of those (an empty store is written as {@code {}}).
 * Following the maintainer's rule for an unreadable configuration file (#470: treated like an
 * unparseable one and never overwritten), such a file is never replaced by {@link #update}, so no
 * new server UUID is generated over it and no cloud token is written into it; the same holds for an
 * unreadable pre-6.3.0 {@code data.json} while no current file exists. The condition is logged once,
 * at {@link Level#SEVERE}, naming the file and what to check. Only a file that does not exist at all
 * starts a fresh identity.
 * <p>
 * <b>Durability (#573).</b> The temporary file is forced to disk before the atomic move, and the
 * parent directory is forced after it where the platform allows (a directory cannot be opened as a
 * channel on Windows, so that step is best-effort), so a power loss cannot leave the credential path
 * naming a file whose bytes never reached the disk. Writes are rare -- login, token refresh, the
 * first server UUID -- so the cost of the two flushes is negligible.
 * <p>
 * Both the current (new) and the pre-6.3.0 (old) target paths require a live
 * {@link UltiTools#getInstance()} to resolve in production -- the new one climbs two directories
 * above {@link UltiTools#getDataFolder()} to find the server root, the old one reads that data
 * folder directly. Neither falls back to a bare {@code System.getProperty("user.dir")} guess:
 * see {@link #resolveTargetPath()} for why a silent CWD fallback is exactly the kind of stray
 * credential file CLAUDE.md gotcha 17 warns about. Tests override both locations independently
 * via {@link #setTargetPathForTesting(Path)} and {@link #setOldLocationForTesting(Path)} so
 * neither requires a running Bukkit server.
 * <p>
 * <b>Location history (plan 08-15, D-15 pulled into 6.3.0):</b> before 6.3.0 this file lived at
 * {@code plugins/UltiTools/data.json} -- inside the Phase 6 D-15 default editable roots
 * ({@code plugins}, {@code logs}), reachable by the remote file surface if an operator widened
 * them. As of 6.3.0 it lives at {@code <server root>/.ultikits/credentials.json}, outside every
 * default editable root, with {@link #migrate()} moving an existing install's file across on
 * first use and {@code FileOperationManager}'s filename-based deny-list entry kept as defence in
 * depth (the checkpoint's {@code outside-roots-keep-filename} answer).
 *
 * @since 6.3.0
 */
public final class CredentialStore {

    /**
     * The pre-6.3.0 location's file name, inside the plugin data folder
     * ({@code plugins/UltiTools/data.json}). {@link #migrate()} is the only code that still reads
     * from this name; every other operation targets {@link #CREDENTIAL_FILE_NAME}.
     */
    private static final String OLD_FILE_NAME = "data.json";

    /**
     * The directory {@link #CREDENTIAL_FILE_NAME} lives in, resolved as a sibling of {@code plugins/}
     * under the server root -- outside every Phase 6 D-15 default editable root.
     */
    private static final String NEW_DIR_NAME = ".ultikits";

    /**
     * The current ({@code >=} 6.3.0) credential file's bare name. Public so
     * {@code FileOperationManager}'s filename-based deny-list can reference it instead of
     * duplicating the literal -- a future rename then cannot leave that guard pointing at an
     * abandoned name (plan 08-15 Task 2's key link).
     */
    public static final String CREDENTIAL_FILE_NAME = "credentials.json";

    private static final String TEMP_FILE_NAME = CREDENTIAL_FILE_NAME + ".tmp";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    /**
     * Overrides the production target (new-location) path for a test. {@code null} once cleared,
     * restoring the default production resolution. Package-private -- production code must never
     * call this.
     */
    private static volatile Path targetPathOverride;

    /**
     * Overrides {@link #migrate()}'s old-location lookup for a test, independently of
     * {@link #targetPathOverride}. {@code null} once cleared, restoring the default production
     * resolution (the live plugin's data folder). Package-private -- production code must never
     * call this.
     */
    private static volatile Path oldLocationOverride;

    /**
     * Test-only fault injection: when {@code true}, {@link #writeLocked(Map)} throws instead of
     * writing, so a migration write-failure path can be exercised deterministically without
     * relying on filesystem permission tricks (which behave differently when tests run as root in
     * a container).
     */
    private static volatile boolean simulateWriteFailureForTesting;

    /**
     * The SEVERE message last logged for an unreadable store, so one condition is reported once
     * rather than on every read; cleared by the next read that finds a readable or absent store.
     * Guarded by {@link #LOCK}.
     */
    private static String reportedUnreadable;

    /**
     * The WARNING last logged for an unreadable pre-6.3.0 file left beside a valid current file.
     * Guarded by {@link #LOCK}.
     */
    private static String reportedLeftover;

    private CredentialStore() {
    }

    /**
     * Points this store at a fixed path instead of resolving it from {@link UltiTools#getInstance()}.
     * Test-only: lets a test exercise this class against a {@code @TempDir} without a running
     * Bukkit server. Call {@link #clearTargetPathForTesting()} in an {@code @AfterEach} to restore
     * production resolution.
     *
     * @param path the path a test wants {@code data.json} operations to target
     */
    static void setTargetPathForTesting(Path path) {
        targetPathOverride = path;
    }

    /**
     * Restores production target-path resolution after a test that called
     * {@link #setTargetPathForTesting(Path)}.
     */
    static void clearTargetPathForTesting() {
        targetPathOverride = null;
    }

    /**
     * Points {@link #migrate()}'s old-location lookup at a fixed path instead of resolving it
     * from {@link UltiTools#getInstance()}'s data folder. Test-only, independent of
     * {@link #setTargetPathForTesting(Path)} -- a migration test needs to control the old and new
     * locations separately. Call {@link #clearOldLocationForTesting()} in an {@code @AfterEach}.
     *
     * @param path the path a test wants the pre-6.3.0 {@code data.json} to be looked up at
     */
    static void setOldLocationForTesting(Path path) {
        oldLocationOverride = path;
    }

    /**
     * Restores production old-location resolution after a test that called
     * {@link #setOldLocationForTesting(Path)}.
     */
    static void clearOldLocationForTesting() {
        oldLocationOverride = null;
    }

    /**
     * Test-only: makes the next (and every subsequent, until cleared) {@link #writeLocked(Map)}
     * fail with a {@link DataAccessException} before touching the filesystem, so a migration test
     * can exercise the "new file write fails" path deterministically. Call with {@code false} (or
     * rely on {@code @AfterEach}) to restore normal writes.
     *
     * @param simulate {@code true} to make writes fail, {@code false} to restore normal writes
     */
    static void setSimulateWriteFailureForTesting(boolean simulate) {
        simulateWriteFailureForTesting = simulate;
    }

    /**
     * Resolves the current (&gt;= 6.3.0) target path. Deliberately requires a live
     * {@link UltiTools#getInstance()} in production -- exactly as the pre-6.3.0 resolution
     * against {@link UltiTools#getDataFolder()} always did -- rather than falling back to a bare
     * {@code System.getProperty("user.dir")}. A silent CWD fallback would let any test that
     * reaches {@link #read()}/{@link #write(Map)}/{@link #update(UnaryOperator)} without mocking
     * {@link UltiTools} write a real {@code .ultikits/credentials.json} at whatever the test
     * runner's working directory happens to be -- for {@code mvn test} run from the repository
     * root, the repository root itself (CLAUDE.md gotcha 17; measured happening during this
     * plan's own execution before this guard was added). Failing loudly here is the same shape as
     * the {@link NullPointerException} the pre-6.3.0 code would have thrown in that situation, not
     * a new restriction.
     *
     * @throws DataAccessException if no test override is set and no {@link UltiTools} instance is
     *                              up to resolve the server root from
     */
    private static Path resolveTargetPath() {
        Path override = targetPathOverride;
        if (override != null) {
            return override.toAbsolutePath();
        }
        UltiTools instance = UltiTools.getInstance();
        if (instance == null) {
            throw new DataAccessException(
                    "CredentialStore cannot resolve its target path: no live UltiTools instance and "
                            + "no test override (setTargetPathForTesting) is set. Refusing to fall back "
                            + "to a bare working-directory guess, which would risk writing a real "
                            + "credential file wherever the current process happens to be running.");
        }
        File serverRoot = instance.getDataFolder().getParentFile().getParentFile();
        return new File(new File(serverRoot, NEW_DIR_NAME), CREDENTIAL_FILE_NAME).toPath().toAbsolutePath();
    }

    /**
     * Resolves the pre-6.3.0 location {@link #migrate()} looks for, or {@code null} if it cannot
     * be resolved yet -- {@link UltiTools#getInstance()} is not up (a test with no override, or a
     * caller running before the plugin has enabled). A {@code null} result is not an error: it
     * means "nothing to migrate can be determined right now", and {@link #migrateLocked()}
     * treats it exactly like an absent file.
     *
     * @return the old {@code data.json} location, or {@code null} if it cannot be resolved
     */
    private static Path resolveOldLocationPath() {
        Path override = oldLocationOverride;
        if (override != null) {
            return override.toAbsolutePath();
        }
        UltiTools instance = UltiTools.getInstance();
        if (instance == null) {
            return null;
        }
        return new File(instance.getDataFolder(), OLD_FILE_NAME).toPath().toAbsolutePath();
    }

    /**
     * Reads the credential document under the single store lock. Runs {@link #migrate()} first
     * (a cheap no-op once the old file is gone), so a caller can never observe the old location's
     * content through this method once a migration was possible.
     *
     * @return a {@link ReadResult} distinguishing an absent file, a successfully parsed file, and a
     *         file that exists but failed to parse
     */
    public static ReadResult read() {
        migrate();
        synchronized (LOCK) {
            return readCurrentLocked();
        }
    }

    /**
     * Replaces the entire content of {@code data.json} with {@code data}, atomically.
     * <p>
     * Writes go to a fixed, same-directory temporary file and are applied with
     * {@link Files#move(Path, Path, java.nio.file.CopyOption...)} using
     * {@link StandardCopyOption#ATOMIC_MOVE} and {@link StandardCopyOption#REPLACE_EXISTING}.
     * {@code ATOMIC_MOVE} is only guaranteed on the same filesystem, which is why the temporary
     * file is always created in the target's own parent directory rather than under the JVM's
     * temp directory. If the filesystem cannot honour an atomic move, this method fails loudly
     * ({@link DataAccessException} wrapping {@link AtomicMoveNotSupportedException}) rather than
     * silently falling back to a non-atomic copy.
     *
     * @param data the complete document to persist; replaces any existing content
     */
    public static void write(Map<String, Object> data) {
        Objects.requireNonNull(data, "data");
        migrate();
        synchronized (LOCK) {
            writeLocked(data);
        }
    }

    /**
     * Reads, applies {@code mutator}, and writes back -- all under a single acquisition of the
     * store lock, so two concurrent {@code update} calls each see the other's completed result
     * rather than clobbering it.
     * <p>
     * Refuses to run if the existing file exists but fails to parse: starting {@code mutator} from
     * an empty map in that case would silently discard whatever the torn file held, which is
     * exactly the swallow-as-absent failure this class exists to prevent. Resolve the parse
     * failure (inspect the file, or overwrite it with {@link #write(Map)}) before calling this.
     * <p>
     * WR-03 (08-REVIEW.md): {@code mutator} must not return {@code null}. A caller that mutates
     * the map it was given in place and forgets the trailing {@code return}, or falls through an
     * early-return branch without a value, must not have that silently truncate the credential
     * file to {@code {}} -- this store is the sole owner of a file that holds live OAuth tokens,
     * so a contract violation here fails loudly ({@link NullPointerException}) rather than
     * discarding the caller's data. This mirrors {@link ReadResult#data()}, which likewise throws
     * rather than offering an empty-map fallback for a parse failure -- neither method in this
     * class treats "something is wrong" as "return an empty document". To clear the file
     * deliberately, call {@link #write(Map) write(Collections.emptyMap())} instead of relying on
     * a {@code null} return from {@code mutator}.
     *
     * @param mutator receives a mutable copy of the current document and returns the document to
     *                persist; must not return {@code null}
     * @throws NullPointerException if {@code mutator} returns {@code null}
     */
    public static void update(UnaryOperator<Map<String, Object>> mutator) {
        Objects.requireNonNull(mutator, "mutator");
        migrate();
        synchronized (LOCK) {
            ReadResult current = readCurrentLocked();
            if (current.isParseFailure()) {
                throw new DataAccessException("Refusing to update the credential store. "
                        + current.failureMessage());
            }
            Map<String, Object> mutableCopy = new LinkedHashMap<>(current.data());
            Map<String, Object> updated = mutator.apply(mutableCopy);
            Objects.requireNonNull(updated, "update() mutator returned null; return the map it was "
                    + "given (mutated or not) rather than null. Use write(Collections.emptyMap()) to "
                    + "clear the credential file deliberately.");
            writeLocked(updated);
        }
    }

    /**
     * One-time migration from the pre-6.3.0 location ({@code plugins/UltiTools/data.json}) to the
     * current one ({@code <server root>/.ultikits/credentials.json}). Called from {@link #read()},
     * {@link #write(Map)} and {@link #update(UnaryOperator)} -- the store's own entry points, not
     * a bootstrap step in {@code UltiTools} or any manager, so a caller can never reach the file
     * without migration having had the chance to run first. Naturally idempotent: once the old
     * file is gone, every subsequent call is a single {@link Files#exists(Path, java.nio.file.LinkOption...)}
     * check and nothing else.
     * <p>
     * The whole safety property is the order of operations, in one sentence: <b>read the old
     * file, write and verify the new one, and only then delete the old one</b> -- so a crash or a
     * write failure at any point during migration leaves the operator's credential exactly where
     * it already was, never in neither place.
     */
    static void migrate() {
        synchronized (LOCK) {
            migrateLocked();
        }
    }

    private static void migrateLocked() {
        Path oldPath = resolveOldLocationPath();
        if (oldPath == null || !Files.exists(oldPath)) {
            return;
        }

        ReadResult oldResult = readFrom(oldPath);
        if (oldResult.isParseFailure()) {
            // Never migrated, never deleted (#573). With no current file, readCurrentLocked()
            // reports the store as unreadable, so nothing can start a fresh identity over it.
            Path currentPath = resolveTargetPath();
            if (Files.exists(currentPath)) {
                String message = "The pre-6.3.0 credential file " + oldPath + " is empty or is not a "
                        + "valid JSON document; it has been left in place, and the current file "
                        + currentPath + " is used. Delete the old file once you have checked it holds "
                        + "nothing you need.";
                if (!message.equals(reportedLeftover)) {
                    reportedLeftover = message;
                    logWarning(message);
                }
            }
            return;
        }
        // isAbsent() cannot happen here: Files.exists(oldPath) was just confirmed true above, and
        // readFrom(...) only reports absence when the file does not exist.

        Path newPath = resolveTargetPath();
        if (Files.exists(newPath)) {
            logInfo("Both the pre-6.3.0 credential file (" + oldPath + ") and the current one ("
                    + newPath + ") exist; leaving the current file untouched and removing the old one.");
            deleteOldFile(oldPath);
            return;
        }

        try {
            writeLocked(oldResult.data());
        } catch (RuntimeException e) {
            logWarning("Failed to migrate the credential file from " + oldPath + " to " + newPath
                    + " -- the old file has been left in place, and the operator's credential is "
                    + "unaffected: " + e.getMessage());
            return;
        }

        ReadResult verify = readFrom(newPath);
        if (!verify.isParsed() || !verify.data().equals(oldResult.data())) {
            logWarning("Migrated the credential file to " + newPath + " but the read-back did not "
                    + "match what was written -- leaving the old file at " + oldPath + " in place "
                    + "as a precaution rather than deleting a credential that failed verification.");
            return;
        }

        deleteOldFile(oldPath);
        logInfo("Migrated the credential file from " + oldPath + " to " + newPath + ".");
    }

    private static void deleteOldFile(Path oldPath) {
        try {
            Files.delete(oldPath);
        } catch (IOException e) {
            logWarning("Migrated the credential file to the new location, but failed to delete the "
                    + "old file at " + oldPath + " -- it is safe to delete by hand: " + e.getMessage());
        }
    }

    /**
     * Best-effort, non-throwing logging at {@link Level#INFO} -- mirrors {@link #logWarning}, see
     * that method for why a missing {@link UltiTools} instance must never surface as an exception
     * here.
     */
    private static void logInfo(String message) {
        log(Level.INFO, message);
    }

    /**
     * Best-effort, non-throwing logging. Migration outcomes are operator-visible on purpose (an
     * operator whose credential just moved location needs to know it happened), but a logging
     * failure -- or {@link UltiTools#getInstance()} being {@code null}, as in a plain unit test --
     * must never turn into an exception that could mask the real outcome being logged.
     */
    private static void logWarning(String message) {
        log(Level.WARNING, message);
    }

    private static void log(Level level, String message) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance != null) {
                instance.getLogger().log(level, message);
            }
        } catch (Exception ignored) {
            // Best-effort logging only -- never let a diagnostic log line mask a migration outcome.
        }
    }

    /**
     * Reads the current file under {@link #LOCK}. When it does not exist but an unreadable pre-6.3.0
     * file does, the store is reported as unreadable rather than empty, so no caller starts a fresh
     * identity over a credential that merely failed to migrate (#573). Logs an unreadable store once
     * per episode.
     */
    private static ReadResult readCurrentLocked() {
        Path target = resolveTargetPath();
        ReadResult result = readFrom(target);
        if (result.isAbsent()) {
            Path oldPath = resolveOldLocationPath();
            if (oldPath != null && Files.exists(oldPath) && readFrom(oldPath).isParseFailure()) {
                result = ReadResult.parseFailure(describeUnreadable(oldPath, target));
            }
        }
        if (result.isParseFailure()) {
            String message = result.failureMessage();
            if (!message.equals(reportedUnreadable)) {
                reportedUnreadable = message;
                log(Level.SEVERE, message);
            }
        } else {
            reportedUnreadable = null;
        }
        return result;
    }

    /**
     * The operator-facing description of an unreadable credential file: which file, that it was
     * left untouched, and what to check.
     *
     * @param unreadable     the file that exists but cannot be read as a credential document
     * @param missingCurrent the current file's path when {@code unreadable} is the pre-6.3.0 file
     *                       and no current file exists yet, otherwise {@code null}
     */
    private static String describeUnreadable(Path unreadable, Path missingCurrent) {
        String subject = missingCurrent == null
                ? "The credential file " + unreadable
                : "The pre-6.3.0 credential file " + unreadable + " (not yet migrated to " + missingCurrent + ")";
        return subject + " exists but is empty or is not a valid JSON document. It has been left "
                + "untouched: no new server UUID is generated and no UltiCloud token is written until "
                + "it is fixed. Check the file: restore it from a backup, or, to start over with a new "
                + "server identity (and log in to UltiCloud again), move it out of the way.";
    }

    @SuppressWarnings("unchecked")
    private static ReadResult readFrom(Path target) {
        if (!Files.exists(target)) {
            return ReadResult.absent();
        }
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            Map<String, Object> parsed;
            try {
                parsed = GSON.fromJson(reader, Map.class);
            } catch (JsonParseException e) {
                return ReadResult.parseFailure(describeUnreadable(target, null));
            }
            // Gson returns null for an empty, whitespace-only or literal-null document. The store
            // never writes one (an empty store is written as {}), so it is a torn file, not an empty
            // credential document (#573).
            if (parsed == null) {
                return ReadResult.parseFailure(describeUnreadable(target, null));
            }
            return ReadResult.parsed(parsed);
        } catch (IOException e) {
            throw new DataAccessException("Failed to read " + target, e);
        }
    }

    private static void writeLocked(Map<String, Object> data) {
        if (simulateWriteFailureForTesting) {
            throw new DataAccessException("Simulated write failure (test only)");
        }
        Path target = resolveTargetPath();
        Path parent = target.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new DataAccessException("Failed to create parent directory " + parent, e);
        }

        Path tempFile = (parent != null ? parent : target).resolve(TEMP_FILE_NAME);
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 Writer writer = new BufferedWriter(new OutputStreamWriter(
                         Channels.newOutputStream(channel), StandardCharsets.UTF_8.newEncoder()))) {
                GSON.toJson(data, writer);
                writer.flush();
                // The bytes must reach the disk before the rename can, or a power loss could leave
                // the credential path naming an empty file (#573).
                channel.force(true);
            }
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
                forceDirectory(parent);
            } catch (AtomicMoveNotSupportedException e) {
                throw new DataAccessException(
                        "Atomic replace of " + target + " is not supported on this filesystem "
                                + "(temp file " + tempFile + "); refusing to silently fall back to a "
                                + "non-atomic copy", e);
            }
        } catch (IOException e) {
            throw new DataAccessException("Failed to write " + target, e);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup only; the write already failed and has been reported above.
                }
            }
        }
    }

    /**
     * Forces a directory's entries to disk after the rename, so the rename itself survives a power
     * loss. Best-effort: Windows cannot open a directory as a channel, and a failure here cannot undo
     * a rename that already happened, so it is logged at {@link Level#FINE} and not reported as a
     * failed write.
     */
    private static void forceDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            log(Level.FINE, "Could not force the credential directory " + directory + " to disk: " + e);
        }
    }

    /**
     * The outcome of a {@link #read()} call. Deliberately has no {@code isEmpty()}-style method
     * that a caller could use to conflate {@link #isAbsent()} with {@link #isParseFailure()} --
     * the two are structurally distinct outcomes and must be checked separately.
     */
    public static final class ReadResult {

        private enum Outcome {
            ABSENT, PARSED, PARSE_FAILURE
        }

        private final Outcome outcome;
        private final Map<String, Object> data;
        private final String failureMessage;

        private ReadResult(Outcome outcome, Map<String, Object> data, String failureMessage) {
            this.outcome = outcome;
            this.data = data;
            this.failureMessage = failureMessage;
        }

        private static ReadResult absent() {
            return new ReadResult(Outcome.ABSENT, Collections.emptyMap(), null);
        }

        private static ReadResult parsed(Map<String, Object> data) {
            return new ReadResult(Outcome.PARSED, data, null);
        }

        private static ReadResult parseFailure(String failureMessage) {
            return new ReadResult(Outcome.PARSE_FAILURE, null, failureMessage);
        }

        /**
         * The operator-facing description of a parse failure -- which file, that it was left
         * untouched, and what to check -- for a caller that turns it into its own failure.
         *
         * @return the description, or {@code null} unless {@link #isParseFailure()}
         */
        String failureMessage() {
            return failureMessage;
        }

        /**
         * @return {@code true} if {@code data.json} did not exist at read time
         */
        public boolean isAbsent() {
            return outcome == Outcome.ABSENT;
        }

        /**
         * @return {@code true} if {@code data.json} existed and parsed successfully
         */
        public boolean isParsed() {
            return outcome == Outcome.PARSED;
        }

        /**
         * @return {@code true} if the credential file existed but is empty, whitespace-only, the
         *         JSON literal {@code null} or not valid JSON -- a torn file, distinguishable from
         *         {@link #isAbsent()} (#573) -- or if only an unreadable pre-6.3.0 file exists
         */
        public boolean isParseFailure() {
            return outcome == Outcome.PARSE_FAILURE;
        }

        /**
         * The parsed content. An absent file yields an empty map, by definition.
         *
         * @return the parsed document
         * @throws IllegalStateException if this result {@link #isParseFailure()} -- there is no
         *                                empty-map fallback for a torn file; the caller must check
         *                                the outcome first
         */
        public Map<String, Object> data() {
            if (outcome == Outcome.PARSE_FAILURE) {
                throw new IllegalStateException(
                        "data.json exists but failed to parse -- check isParseFailure()/isAbsent() "
                                + "before calling data()");
            }
            return data;
        }
    }
}
