package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

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
 * This class makes no Bukkit API call. Its target path is resolved through an injectable hook
 * ({@link #setTargetPathForTesting(Path)}) so tests can point it at a temporary directory without
 * a running server; production resolves the path from {@link UltiTools#getInstance()}'s data
 * folder, exactly as the writers it replaces did.
 *
 * @since 6.3.0
 */
public final class CredentialStore {

    private static final String FILE_NAME = "data.json";
    private static final String TEMP_FILE_NAME = "data.json.tmp";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    /**
     * Overrides the production target path for a test. {@code null} once cleared, restoring the
     * default production resolution. Package-private -- production code must never call this.
     */
    private static volatile Path targetPathOverride;

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

    private static Path resolveTargetPath() {
        Path override = targetPathOverride;
        if (override != null) {
            return override.toAbsolutePath();
        }
        return new File(UltiTools.getInstance().getDataFolder(), FILE_NAME).toPath().toAbsolutePath();
    }

    /**
     * Reads {@code data.json} under the single store lock.
     *
     * @return a {@link ReadResult} distinguishing an absent file, a successfully parsed file, and a
     *         file that exists but failed to parse
     */
    public static ReadResult read() {
        synchronized (LOCK) {
            return readLocked();
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
     *
     * @param mutator receives a mutable copy of the current document and returns the document to
     *                persist; a {@code null} return is treated as an empty document
     */
    public static void update(UnaryOperator<Map<String, Object>> mutator) {
        Objects.requireNonNull(mutator, "mutator");
        synchronized (LOCK) {
            ReadResult current = readLocked();
            if (current.isParseFailure()) {
                throw new DataAccessException(
                        "Refusing to update data.json: the existing file exists but failed to parse. "
                                + "Resolve or replace it with write(...) before calling update(...).");
            }
            Map<String, Object> mutableCopy = new LinkedHashMap<>(current.data());
            Map<String, Object> updated = mutator.apply(mutableCopy);
            writeLocked(updated != null ? updated : Collections.emptyMap());
        }
    }

    @SuppressWarnings("unchecked")
    private static ReadResult readLocked() {
        Path target = resolveTargetPath();
        if (!Files.exists(target)) {
            return ReadResult.absent();
        }
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            Map<String, Object> parsed;
            try {
                parsed = GSON.fromJson(reader, Map.class);
            } catch (JsonParseException e) {
                return ReadResult.parseFailure();
            }
            return ReadResult.parsed(parsed != null ? parsed : new LinkedHashMap<>());
        } catch (IOException e) {
            throw new DataAccessException("Failed to read " + target, e);
        }
    }

    private static void writeLocked(Map<String, Object> data) {
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
            try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
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

        private ReadResult(Outcome outcome, Map<String, Object> data) {
            this.outcome = outcome;
            this.data = data;
        }

        private static ReadResult absent() {
            return new ReadResult(Outcome.ABSENT, Collections.emptyMap());
        }

        private static ReadResult parsed(Map<String, Object> data) {
            return new ReadResult(Outcome.PARSED, data);
        }

        private static ReadResult parseFailure() {
            return new ReadResult(Outcome.PARSE_FAILURE, null);
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
         * @return {@code true} if {@code data.json} existed but did not parse as valid JSON -- a
         *         torn file, distinguishable from {@link #isAbsent()}
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
