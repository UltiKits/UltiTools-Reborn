package com.ultikits.ultitools.config.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Representer;

/**
 * The write gate for operator-editable config files: the one way the framework changes such a file
 * automatically (maintainer decisions of 2026-10-04: "operator-written configuration is never overwritten
 * automatically" and "what code may write, by file type").
 * <p>
 * <b>Why it cannot overwrite operator content.</b> Every call declares the paths it owns ({@link OwnedPaths}).
 * The gate reads the file at write time through {@link ConfigDocument#load(Path)}, applies the caller's edit
 * to that fresh document, renders the whole document, and then <em>verifies the result instead of trusting
 * the renderer</em>:
 * <ol>
 *   <li>the rendered text parses back to exactly the edited values, and outside the owned value paths the
 *       edited values equal the file's;</li>
 *   <li>owned spans are computed on both sides from SnakeYAML node marks: a value path's span runs from its
 *       key's line to the last line of its value, plus the comment lines directly above the key when the key
 *       is inserted or removed or its comment is owned; an owned comment's span is those comment lines - or,
 *       for a framework comment run ({@link OwnedPaths.Builder#frameworkComment(List, int)}), only the run's last
 *       lines, so the comment lines above the run are unowned and must come out unchanged (#604);</li>
 *   <li>the lines outside the owned spans - each compared as text including its line terminator - are the
 *       same sequence on both sides (the line diff restricted to unowned lines; stricter than a plain
 *       longest-common-subsequence diff, which could match an unowned line against an owned one);</li>
 *   <li>byte-order mark and final line break are unchanged;</li>
 *   <li>no owned span shares a line with a node of a path it does not own (a flow collection holding an
 *       owned and an unowned key on one line is refused, not partially rewritten).</li>
 * </ol>
 * A file using YAML anchors, aliases or merge keys is refused before the edit is applied: the renderer
 * cannot write one back without expanding or moving shared content. If any check fails nothing is written,
 * one WARNING names the absolute file, the keys the write would have changed (a key below a secret-shaped key
 * is redacted) and the reason - never a value - and the caller keeps its in-memory value. Layout is never
 * normalized and no operator value, key or comment is repaired to make a write pass.
 * <p>
 * When the caller passes the fingerprint of the bytes it last read and the file no longer holds them, the
 * outcome is {@link Outcome#FILE_CHANGED}. Immediately before publishing, after rendering and verifying, the
 * gate reads the target again - after the new content is staged, under the lock every load and write holds - and
 * abandons the write when the bytes moved; the residual window between that read and the atomic rename cannot be
 * closed between an editor and the JVM. Publication goes through
 * {@link AtomicConfigWriter}; a file the gate read absent is created exclusively (a hard link of the staged file,
 * or a re-check and a move that does not replace where links are refused), so a file that appeared meanwhile
 * is never replaced. Files that are unreadable or unparseable are never written.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class OperatorFileWriter {

    /** The expected fingerprint of a file that was absent when the caller read it. */
    public static final String ABSENT = "absent";

    private static final Logger LOGGER = Logger.getLogger(OperatorFileWriter.class.getName());
    private static final List<String> SECRET_WORDS = Arrays.asList("password", "secret", "token", "credential",
            "apikey", "api_key", "key", "auth", "private", "cert");
    private static final String ANCHORED = "the file uses YAML anchors, aliases or merge keys";
    private static final String COMMENT_NOT_LOCATED = "the comment of a key this write owns cannot be located in the file";
    /** Files already named in an anchored-file warning this server run (canonical paths). */
    private static final Set<Path> WARNED_ANCHORED = ConcurrentHashMap.newKeySet();

    private OperatorFileWriter() {
    }

    /** What a write did. */
    public enum Outcome {
        /** The edited file was published. */
        WRITTEN,
        /** The edit changed nothing; the file was not touched. */
        UNCHANGED,
        /** The file was not what the caller read, or it moved before publishing; nothing was written. */
        FILE_CHANGED,
        /** The write would have changed bytes it does not own, or the file cannot be written safely. */
        REFUSED
    }

    /** The outcome of one write, its one-phrase reason, and the document now on disk when there is one. */
    public static final class Result {

        private final Outcome outcome;
        private final String reason;
        private final ConfigDocument document;
        private final String fingerprint;
        private final String readFingerprint;

        private Result(Outcome outcome, String reason, ConfigDocument document) {
            this(outcome, reason, document, null, null);
        }

        private Result(Outcome outcome, String reason, ConfigDocument document, String fingerprint, String readFingerprint) {
            this.outcome = outcome;
            this.reason = reason;
            this.document = document;
            this.fingerprint = fingerprint;
            this.readFingerprint = readFingerprint;
        }

        /**
         * What happened.
         *
         * @return the outcome
         */
        public Outcome outcome() {
            return outcome;
        }

        /**
         * Why nothing was written, as a phrase without any value; empty when written or unchanged.
         *
         * @return the reason
         */
        public String reason() {
            return reason;
        }

        /**
         * The edited document, which is what the file now holds, for {@link Outcome#WRITTEN} and
         * {@link Outcome#UNCHANGED}; {@code null} otherwise.
         *
         * @return the document or {@code null}
         */
        public ConfigDocument document() {
            return document;
        }

        /**
         * The SHA-256 (lower-case hex, as {@link ConfigLoadResult#fingerprint()}) of the bytes the gate wrote, or of
         * the bytes it read when nothing needed writing; {@code null} when it did not apply the edit. A caller records
         * this, never a fresh read of the file, as the bytes it last saw, so an edit made after them stays visible.
         *
         * @return the fingerprint or {@code null}
         */
        public String fingerprint() {
            return fingerprint;
        }

        /**
         * The SHA-256 (lower-case hex, or {@link OperatorFileWriter#ABSENT}) of the bytes the gate read and verified the edit against -
         * for {@link Outcome#WRITTEN}, the bytes the file held immediately before it was replaced; {@code null} when
         * it did not apply the edit. A caller that compares this with the bytes it last bound can tell whether the
         * file it wrote still held only what the caller had read, or also an edit made since.
         *
         * @return the fingerprint of the bytes read, or {@code null}
         */
        public String readFingerprint() {
            return readFingerprint;
        }

        /**
         * Whether the file now holds the edited document.
         *
         * @return whether the outcome is written or unchanged
         */
        public boolean applied() {
            return outcome == Outcome.WRITTEN || outcome == Outcome.UNCHANGED;
        }
    }

    /**
     * Applies {@code edit} to the file as it is now and publishes the result only when every byte outside
     * {@code owned} stays as it is (see the class description for why this cannot overwrite operator content).
     *
     * @param file                the config file
     * @param owned               what this write may change
     * @param expectedFingerprint the SHA-256 (lower-case hex) of the bytes the caller last read, as
     *                            {@link ConfigLoadResult#fingerprint()} reports it, {@link #ABSENT} for a file
     *                            that was absent, or {@code null} for no expectation
     * @param edit                the change, applied to a freshly read document; it must change only owned paths
     * @return the outcome; a refusal or a changed file has already been logged once
     * @throws IOException if staging or publishing the verified text fails (the target is then as
     *                     {@link AtomicConfigWriter} leaves it)
     */
    public static Result write(Path file, OwnedPaths owned, String expectedFingerprint, Consumer<ConfigDocument> edit)
            throws IOException {
        return write(file, owned, expectedFingerprint, edit, null);
    }

    /** As {@link #write(Path, OwnedPaths, String, Consumer)}, with the publication steps as a test seam ({@code null}: real). */
    static Result write(Path file, OwnedPaths owned, String expectedFingerprint, Consumer<ConfigDocument> edit,
            AtomicConfigWriter.FileOperations files) throws IOException {
        return stage(file, owned, expectedFingerprint, edit, files).commit();
    }

    /**
     * As {@link #write(Path, OwnedPaths, String, Consumer)}, but stops once the verified content is staged (written and
     * forced to a temporary file beside the target), so several files can be prepared before any of them is replaced.
     * {@link Staged#commit()} then performs the last-moment check - the target must still hold exactly the bytes the
     * write was verified against, read again under the lock every load and write holds - and the replacement or the
     * exclusive creation; {@link Staged#discard()} releases a staged file that will not be committed. Every check of the
     * class description applies unchanged, so a staged write cannot overwrite operator content either: a file saved
     * between staging and commit is kept and the commit reports {@link Outcome#FILE_CHANGED}.
     *
     * @param file                the config file
     * @param owned               what this write may change
     * @param expectedFingerprint as for {@link #write(Path, OwnedPaths, String, Consumer)}
     * @param edit                the change, applied to a freshly read document; it must change only owned paths
     * @return the staged write; when nothing needed staging, it already holds its result ({@link Staged#isPending()} is
     *         {@code false}) - a refusal or a changed file has then already been logged once
     * @throws IOException if staging the verified text fails
     */
    public static Staged stage(Path file, OwnedPaths owned, String expectedFingerprint, Consumer<ConfigDocument> edit)
            throws IOException {
        return stage(file, owned, expectedFingerprint, edit, null);
    }

    static Staged stage(Path file, OwnedPaths owned, String expectedFingerprint, Consumer<ConfigDocument> edit,
            AtomicConfigWriter.FileOperations files) throws IOException {
        Path absolute = file.toAbsolutePath();
        Snapshot snapshot = Snapshot.read(file, owned, expectedFingerprint);
        if (snapshot.failure != null) {
            if (ANCHORED.equals(snapshot.reason)) {
                return Staged.settled(refuseAnchored(absolute, owned));
            }
            List<String> wouldChange = Collections.emptyList();
            if (snapshot.parsed && snapshot.failure == Outcome.FILE_CHANGED && !owned.isWholeFile()
                    && !usesAnchors(compose(snapshot.text))) {
                // Name only what the write would have changed in the file as it is now; nothing is written. A newer
                // file using anchors is never edited, not even in memory (review round 2 IN-R2-01).
                edit.accept(snapshot.candidate);
                Changes changes = Changes.of(snapshot.original, snapshot.candidate, owned);
                wouldChange = describe(changes.values, changes.comments);
            }
            return Staged.settled(fail(snapshot.failure, absolute, owned, wouldChange, snapshot.reason));
        }
        ConfigDocument candidate = snapshot.candidate;
        edit.accept(candidate);
        Changes changes = Changes.of(snapshot.original, candidate, owned);
        List<String> keys = describe(changes.values, changes.comments);
        if (!owned.isWholeFile() && changes.isEmpty()) {
            return Staged.settled(PlainData.plainEquals(snapshot.original.toPlain(), candidate.toPlain())
                    ? new Result(Outcome.UNCHANGED, "", candidate, snapshot.fingerprint, snapshot.fingerprint)
                    : refuse(absolute, owned, keys, "the write would change keys it does not own"));
        }
        String rendered = candidate.render();
        if (rendered.equals(snapshot.text)) {
            return Staged.settled(new Result(Outcome.UNCHANGED, "", candidate, snapshot.fingerprint, snapshot.fingerprint));
        }
        String failure = owned.isWholeFile()
                ? verifyValues(snapshot.original, candidate, rendered, Collections.<List<String>>emptyList(), true)
                : verify(snapshot.text, rendered, snapshot.original, candidate, changes, owned);
        if (failure != null) {
            return Staged.settled(refuse(absolute, owned, keys, failure));
        }
        if (ABSENT.equals(snapshot.fingerprint)) {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
        AtomicConfigWriter.StagedWrite temporary = files == null ? AtomicConfigWriter.stage(file, rendered)
                : AtomicConfigWriter.stage(file, rendered, files);
        return new Staged(file, absolute, owned, keys, rendered, candidate, snapshot.fingerprint,
                ABSENT.equals(snapshot.fingerprint) ? null : snapshot.text, temporary);
    }

    /**
     * A verified write whose content is staged and not yet published - or, when nothing needed staging, the write's
     * settled result. Publishing re-reads the target under the lock every load and write holds, immediately before the
     * replacement, and replaces it only while it still holds exactly the bytes the write was verified against (an edit
     * saved while the content was being staged or while other files were being prepared is seen and kept: review round
     * 1 IN-01, plan 17-65); a file the gate read absent is created exclusively, never replacing one that appeared.
     */
    public static final class Staged {

        private final Result settled;
        private final Path file;
        private final Path absolute;
        private final OwnedPaths owned;
        private final List<String> keys;
        private final String rendered;
        private final ConfigDocument candidate;
        private final String read;
        private final String readText;
        private final AtomicConfigWriter.StagedWrite temporary;
        private boolean finished;

        private Staged(Path file, Path absolute, OwnedPaths owned, List<String> keys, String rendered,
                ConfigDocument candidate, String read, String readText, AtomicConfigWriter.StagedWrite temporary) {
            this.settled = null;
            this.file = file;
            this.absolute = absolute;
            this.owned = owned;
            this.keys = keys;
            this.rendered = rendered;
            this.candidate = candidate;
            this.read = read;
            this.readText = readText;
            this.temporary = temporary;
        }

        private Staged(Result settled) {
            this.settled = settled;
            this.file = null;
            this.absolute = null;
            this.owned = null;
            this.keys = null;
            this.rendered = null;
            this.candidate = null;
            this.read = null;
            this.readText = null;
            this.temporary = null;
            this.finished = true;
        }

        static Staged settled(Result result) {
            return new Staged(result);
        }

        /**
         * Whether verified content is staged and waits for {@link #commit()}.
         *
         * @return {@code false} when the write was settled without staging (refused, file changed, or unchanged)
         */
        public boolean isPending() {
            return settled == null;
        }

        /**
         * The text the staged content was verified against - exactly what the file held when the gate read it - so a
         * caller undoing a published write can put back precisely those bytes.
         *
         * @return the text read, or {@code null} when the file was absent or nothing is staged
         */
        public String readText() {
            return readText;
        }

        /**
         * The settled result of a write that needed no staging, or {@code null} while content is staged.
         *
         * @return the result or {@code null}
         */
        public Result settledResult() {
            return settled;
        }

        /**
         * Publishes the staged content when the target still holds exactly the bytes the write was verified against
         * (or, for a file the gate read absent, while no file exists there); otherwise the staged file is released and
         * the result is {@link Outcome#FILE_CHANGED}, logged once. A write that needed no staging returns its settled
         * result. A staged write is committed at most once.
         *
         * @return the outcome: {@link Outcome#WRITTEN} when published
         * @throws IOException if the replacement or the creation fails (the target is then as
         *                     {@link AtomicConfigWriter} leaves it)
         */
        public Result commit() throws IOException {
            if (settled != null) {
                return settled;
            }
            if (finished) {
                throw new IllegalStateException("A staged config write is committed or discarded only once");
            }
            finished = true;
            boolean creating = ABSENT.equals(read);
            boolean published = false;
            synchronized (AtomicConfigWriter.WRITE_LOAD_LOCK) {
                try {
                    byte[] now = readOrNull(file);
                    if (creating && now == null) {
                        temporary.commitAsNewFile();
                        published = true;
                    } else if (!creating && now != null && ConfigDocument.sha256(now).equals(read)) {
                        temporary.commit();
                        published = true;
                    }
                } catch (FileAlreadyExistsException appeared) {
                    // A file appeared at the target after the re-read: it is left as it is, and this is reported below.
                    LOGGER.fine("Config creation lost the race to an existing file at " + absolute);
                } finally {
                    if (!published) {
                        temporary.discard();
                    }
                }
            }
            if (published) {
                return written(rendered, candidate, read);
            }
            return changed(absolute, owned, keys, creating ? "a file appeared while the new content was being prepared"
                    : "the file changed while the new content was being prepared");
        }

        /**
         * Releases staged content that will not be committed; nothing on disk other than the staged temporary file is
         * touched. Does nothing after {@link #commit()} or for a settled write.
         *
         * @return whether no staged temporary file is left behind
         */
        public boolean discard() {
            if (finished) {
                return true;
            }
            finished = true;
            return temporary.discard();
        }
    }

    private static Result written(String rendered, ConfigDocument candidate, String read) {
        return new Result(Outcome.WRITTEN, "", candidate, ConfigDocument.sha256(rendered.getBytes(StandardCharsets.UTF_8)), read);
    }

    private static Result fail(Outcome outcome, Path absolute, OwnedPaths owned, List<String> keys, String reason) {
        return outcome == Outcome.FILE_CHANGED ? changed(absolute, owned, keys, reason) : refuse(absolute, owned, keys, reason);
    }

    /** An anchored file is refused on every write but named once per file per server run. */
    private static Result refuseAnchored(Path absolute, OwnedPaths owned) {
        Path canonical = absolute.normalize();
        try {
            canonical = absolute.toRealPath();
        } catch (IOException | SecurityException e) {
            LOGGER.fine("Using the normalized path of " + absolute + " for its anchored-file warning");
        }
        if (WARNED_ANCHORED.add(canonical)) {
            warn(absolute, owned, Collections.<String>emptyList(), ANCHORED
                    + " (named once per server run; the declared defaults are used for keys the file lacks)");
        }
        return new Result(Outcome.REFUSED, ANCHORED, null);
    }

    /** Forgets which anchored files were named, for tests that start a fresh "server run". */
    static void resetAnchorWarnings() {
        WARNED_ANCHORED.clear();
    }

    private static Result refuse(Path absolute, OwnedPaths owned, List<String> keys, String reason) {
        warn(absolute, owned, keys, reason);
        return new Result(Outcome.REFUSED, reason, null);
    }

    private static Result changed(Path absolute, OwnedPaths owned, List<String> keys, String reason) {
        warn(absolute, owned, keys, reason);
        return new Result(Outcome.FILE_CHANGED, reason, null);
    }

    private static void warn(Path absolute, OwnedPaths owned, List<String> keys, String reason) {
        List<String> named = keys;
        if (named.isEmpty()) {
            named = owned.isWholeFile() ? Collections.singletonList("the whole file")
                    : describe(owned.values(), owned.comments());
        }
        // Values are deliberately omitted: any key may hold a credential.
        LOGGER.warning("Configuration file " + absolute + " was not written: " + reason
                + ". Keys this write would have changed: " + String.join(", ", named)
                + ". The values in memory are used; the file is unchanged.");
    }

    /** The file as the gate read it at write time, or why the write cannot start. */
    private static final class Snapshot {

        private static final String UNREADABLE = "the file cannot be read or parsed";

        private String text = "";
        private String fingerprint = ABSENT;
        private ConfigDocument original = ConfigDocument.empty();
        private ConfigDocument candidate = ConfigDocument.empty();
        private boolean absent = true;
        /** Whether the file was read and parsed (original and candidate are the file's, not empty placeholders). */
        private boolean parsed;
        private Outcome failure;
        private String reason;

        static Snapshot read(Path file, OwnedPaths owned, String expectedFingerprint) {
            Snapshot snapshot = new Snapshot();
            ConfigLoadResult loaded = ConfigDocument.load(file);
            if (loaded.state() == ConfigLoadResult.State.LOADED) {
                snapshot.readLoaded(file, loaded);
            } else if (loaded.state() != ConfigLoadResult.State.ABSENT) {
                snapshot.fail(Outcome.REFUSED, UNREADABLE);
            }
            if (snapshot.failure == null) {
                snapshot.checkPreconditions(owned, expectedFingerprint);
            }
            return snapshot;
        }

        /** Reads the bytes the load parsed; a second read that differs means the file moved in between. */
        private void readLoaded(Path file, ConfigLoadResult loaded) {
            absent = false;
            byte[] bytes;
            try {
                bytes = readOrNull(file);
            } catch (IOException e) {
                fail(Outcome.REFUSED, UNREADABLE);
                return;
            }
            fingerprint = bytes == null ? ABSENT : ConfigDocument.sha256(bytes);
            if (!fingerprint.equals(loaded.fingerprint())) {
                fail(Outcome.FILE_CHANGED, "the file changed while it was being read");
                return;
            }
            text = decode(bytes);
            candidate = text == null ? null : parseOrNull(text);
            if (candidate == null) {
                fail(Outcome.REFUSED, UNREADABLE);
                return;
            }
            original = loaded.document();
            parsed = true;
        }

        private void checkPreconditions(OwnedPaths owned, String expectedFingerprint) {
            if (expectedFingerprint != null && !expectedFingerprint.equals(fingerprint)) {
                fail(Outcome.FILE_CHANGED, "the file changed after it was read");
            } else if (owned.isWholeFile() && !absent) {
                fail(Outcome.REFUSED, "the whole file may be written only when it does not exist");
            } else if (!absent && usesAnchors(compose(text))) {
                fail(Outcome.REFUSED, ANCHORED);
            }
        }

        private void fail(Outcome outcome, String why) {
            failure = outcome;
            reason = why;
        }
    }

    /** The owned paths the edit actually changed: values (inserted, set or removed) and comments. */
    private static final class Changes {

        private final List<List<String>> values = new ArrayList<>();
        private final List<List<String>> comments = new ArrayList<>();

        static Changes of(ConfigDocument original, ConfigDocument candidate, OwnedPaths owned) {
            Changes changes = new Changes();
            for (List<String> path : owned.values()) {
                if (original.contains(path) != candidate.contains(path)
                        || !PlainData.plainEquals(original.get(path), candidate.get(path))) {
                    changes.values.add(path);
                }
            }
            for (List<String> path : owned.comments()) {
                if (candidate.contains(path) && !original.blockComment(path).equals(candidate.blockComment(path))) {
                    changes.comments.add(path);
                }
            }
            return changes;
        }

        boolean isEmpty() {
            return values.isEmpty() && comments.isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------- checks

    private static String verify(String text, String rendered, ConfigDocument original, ConfigDocument candidate,
            Changes changes, OwnedPaths owned) {
        String failure = verifyValues(original, candidate, rendered, changes.values, false);
        if (failure != null) {
            return failure;
        }
        Node before = compose(text);
        Node after = compose(rendered);
        if (before == null || after == null) {
            return "the rendered text would not parse back";
        }
        if (hasByteOrderMark(text) != hasByteOrderMark(rendered) || endsWithLineBreak(text) != endsWithLineBreak(rendered)) {
            return "the file's layout would change (byte-order mark or final line break)";
        }
        Side left = new Side(original, before, lines(text));
        Side right = new Side(candidate, after, lines(rendered));
        failure = markSpans(left, right, changes, owned);
        return failure != null ? failure : compareUnowned(left, right);
    }

    /** Checks 2 and 5: marks every owned span on both sides, refusing a span that shares a line with an unowned key. */
    private static String markSpans(Side left, Side right, Changes changes, OwnedPaths owned) {
        for (List<String> path : changes.values) {
            boolean present = left.document.contains(path);
            boolean stays = right.document.contains(path);
            int[] comment = present && stays && changes.comments.contains(path)
                    ? ownedCommentLines(owned, left, right, path) : new int[] {0, 0};
            if (comment == null) {
                return COMMENT_NOT_LOCATED;
            }
            String failure = null;
            if (present) {
                failure = left.markValue(path, stays ? comment[0] : commentLineCount(left.document, path));
            }
            if (failure == null && stays) {
                // An inserted key may have needed new parent mappings; the outermost new one is the span.
                List<String> span = present ? path : topmostAbsentPrefix(left.document, path);
                failure = right.markValue(span, present ? comment[1] : commentLineCount(right.document, span));
            }
            if (failure != null) {
                return failure;
            }
        }
        for (List<String> path : changes.comments) {
            if (!changes.values.contains(path)) {
                int[] comment = ownedCommentLines(owned, left, right, path);
                String failure = comment == null ? COMMENT_NOT_LOCATED : left.markComment(path, comment[0]);
                if (failure == null) {
                    failure = right.markComment(path, comment[1]);
                }
                if (failure != null) {
                    return failure;
                }
            }
        }
        return null;
    }

    /**
     * The owned comment lines directly above the key at {@code path}, before and after the edit: the whole comment,
     * or - for a framework comment run - the run's length in the file as read and the length of what replaced it.
     * The comment lines above a run are the same count on both sides and unowned, so check 3 requires them unchanged.
     *
     * @return {@code {before, after}}, or {@code null} when the run does not fit the comment on either side
     */
    private static int[] ownedCommentLines(OwnedPaths owned, Side left, Side right, List<String> path) {
        int before = commentLineCount(left.document, path);
        int after = commentLineCount(right.document, path);
        Integer run = owned.commentRun(path);
        if (run == null) {
            return new int[] {before, after};
        }
        int kept = before - run;
        return kept < 0 || after < kept ? null : new int[] {run, after - kept};
    }

    /** Check 3: the unowned lines of both sides are the same sequence, each line with its terminator. */
    private static String compareUnowned(Side left, Side right) {
        List<Integer> beforeKept = unowned(left.owned);
        List<Integer> afterKept = unowned(right.owned);
        for (int i = 0; i < Math.max(beforeKept.size(), afterKept.size()); i++) {
            if (i >= beforeKept.size() || i >= afterKept.size()
                    || !sameLine(left.lines, beforeKept.get(i), left.owned, right.lines, afterKept.get(i), right.owned)) {
                int line = i < beforeKept.size() ? beforeKept.get(i) : left.lines.size() - 1;
                return "the file's layout outside the keys this write owns would change (line " + (line + 1) + ")";
            }
        }
        return null;
    }

    /** One side of the comparison: its document, node tree, lines, nodes by line and owned-line marks. */
    private static final class Side {

        private final ConfigDocument document;
        private final Node tree;
        private final List<String> lines;
        private final boolean[] owned;
        private final Map<Integer, List<List<Object>>> nodes;

        Side(ConfigDocument document, Node tree, List<String> lines) {
            this.document = document;
            this.tree = tree;
            this.lines = lines;
            this.owned = new boolean[lines.size()];
            this.nodes = nodesByLine(tree, lines);
        }

        /**
         * Marks the span of the key at {@code path} - key line to the last line of its value, plus the
         * {@code commentLines} owned comment lines directly above it - and returns a reason when a line of that
         * span also holds a node of a path the write does not own, or the comment is not where it belongs.
         */
        String markValue(List<String> path, int commentLines) {
            NodeTuple tuple = find(tree, path);
            if (tuple == null) {
                return "a key this write owns cannot be located in the file";
            }
            int keyLine = tuple.getKeyNode().getStartMark().getLine();
            int last = Math.max(lastLine(tuple.getKeyNode(), lines), lastLine(tuple.getValueNode(), lines));
            int first = keyLine - commentLines;
            if (!commentOrBlank(lines, first, keyLine)) {
                return COMMENT_NOT_LOCATED;
            }
            for (int line = first; line <= last; line++) {
                List<List<Object>> here = nodes.get(line);
                for (List<Object> other : here == null ? Collections.<List<Object>>emptyList() : here) {
                    if (!startsWith(other, path)) {
                        return "a key this write owns shares line " + (line + 1) + " with a key it does not own";
                    }
                }
            }
            mark(first, last + 1);
            return null;
        }

        /** Marks the {@code commentLines} owned comment lines directly above the key at {@code path}. */
        String markComment(List<String> path, int commentLines) {
            NodeTuple tuple = find(tree, path);
            if (tuple == null) {
                return "a key this write owns cannot be located in the file";
            }
            int keyLine = tuple.getKeyNode().getStartMark().getLine();
            int first = keyLine - commentLines;
            if (!commentOrBlank(lines, first, keyLine)) {
                return COMMENT_NOT_LOCATED;
            }
            mark(first, keyLine);
            return null;
        }

        private void mark(int from, int to) {
            for (int line = Math.max(0, from); line < Math.min(to, owned.length); line++) {
                owned[line] = true;
            }
        }
    }

    /**
     * Whether an unowned line is unchanged: equal text including its terminator. One separator is not
     * operator content: the last line of a file without a final line break gains a line break when owned
     * lines are inserted after it (and loses it when the owned lines after it are removed); the line's own
     * text stays byte for byte, and check 4 keeps the file's final line break as it was.
     */
    private static boolean sameLine(List<String> before, int b, boolean[] beforeOwned, List<String> after, int a,
            boolean[] afterOwned) {
        String left = before.get(b);
        String right = after.get(a);
        if (left.equals(right)) {
            return true;
        }
        boolean appended = b == before.size() - 1 && !endsWithLineBreak(left) && a + 1 < after.size()
                && afterOwned[a + 1] && right.startsWith(left) && isLineBreak(right.substring(left.length()));
        boolean truncated = a == after.size() - 1 && !endsWithLineBreak(right) && b + 1 < before.size()
                && beforeOwned[b + 1] && left.startsWith(right) && isLineBreak(left.substring(right.length()));
        return appended || truncated;
    }

    private static boolean isLineBreak(String text) {
        return "\n".equals(text) || "\r\n".equals(text) || "\r".equals(text);
    }

    /** Check 1: the rendered text holds exactly the edited values, and the edit stayed inside its paths. */
    private static String verifyValues(ConfigDocument original, ConfigDocument candidate, String rendered,
            List<List<String>> changedValues, boolean wholeFile) {
        ConfigDocument reparsed = parseOrNull(rendered);
        if (reparsed == null) {
            return "the rendered text would not parse back";
        }
        if (!PlainData.plainEquals(reparsed.toPlain(), candidate.toPlain())) {
            return "the rendered text would not hold the intended values";
        }
        if (wholeFile) {
            return null;
        }
        Map<String, Object> before = original.toPlain();
        Map<String, Object> after = candidate.toPlain();
        for (List<String> path : changedValues) {
            strip(before, after, path);
        }
        return PlainData.plainEquals(before, after) ? null : "the write would change keys it does not own";
    }

    private static void strip(Map<String, Object> before, Map<String, Object> after, List<String> path) {
        remove(before, path);
        remove(after, path);
        // A mapping the edit created only to hold the owned key is part of that key.
        for (int k = path.size() - 1; k >= 1; k--) {
            List<String> prefix = path.subList(0, k);
            pruneCreatedMapping(before, after, prefix);
            pruneCreatedMapping(after, before, prefix);
        }
    }

    private static void pruneCreatedMapping(Map<String, Object> tree, Map<String, Object> other, List<String> prefix) {
        Object value = lookup(tree, prefix);
        if (value instanceof Map && ((Map<?, ?>) value).isEmpty() && lookup(other, prefix) == null
                && !containsPath(other, prefix)) {
            remove(tree, prefix);
        }
    }

    private static Object lookup(Map<String, Object> tree, List<String> path) {
        Object current = tree;
        for (String key : path) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return current;
    }

    private static boolean containsPath(Map<String, Object> tree, List<String> path) {
        Object current = tree;
        for (String key : path) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(key)) {
                return false;
            }
            current = ((Map<?, ?>) current).get(key);
        }
        return true;
    }

    private static void remove(Map<String, Object> tree, List<String> path) {
        Object parent = lookup(tree, path.subList(0, path.size() - 1));
        if (parent instanceof Map) {
            ((Map<?, ?>) parent).remove(path.get(path.size() - 1));
        }
    }

    private static List<String> topmostAbsentPrefix(ConfigDocument original, List<String> path) {
        for (int k = 1; k <= path.size(); k++) {
            if (!original.contains(path.subList(0, k))) {
                return path.subList(0, k);
            }
        }
        return path;
    }

    /**
     * How many lines directly above the key at {@code path} are its own comment in {@code document}: the
     * comment lines {@link ConfigDocument#blockComment(List)} reports, without the blank lines above them
     * (those are kept by every comment write). The file header and a deeper-indented trailing comment of the
     * previous section are not part of a key's comment, so they are never owned through it.
     */
    private static int commentLineCount(ConfigDocument document, List<String> path) {
        List<String> comment = document.blockComment(path);
        int leadingBlank = 0;
        while (leadingBlank < comment.size() && comment.get(leadingBlank) == null) {
            leadingBlank++;
        }
        return comment.size() - leadingBlank;
    }

    /** Whether every line in {@code [from, to)} is a comment line or blank; {@code false} when out of range. */
    private static boolean commentOrBlank(List<String> lines, int from, int to) {
        if (from < 0 || to > lines.size()) {
            return false;
        }
        for (int i = from; i < to; i++) {
            String text = lines.get(i).trim();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1).trim();
            }
            if (!text.isEmpty() && text.charAt(0) != '#') {
                return false;
            }
        }
        return true;
    }

    /**
     * The last line of {@code node} in {@code lines}. A block scalar ({@code |}, {@code >}) ends at its last content
     * line: SnakeYAML's end mark lies after the line breaks it consumed, which include the blank or whitespace-only
     * lines that follow a clipped or stripped value; those lines are the operator's separators, not the value, and are
     * never owned. With keep chomping ({@code |+}, {@code >+}) the blank lines the value keeps stay part of it (owned-span
     * rule revision 1, review round 1 IN-02).
     */
    private static int lastLine(Node node, List<String> lines) {
        if (node instanceof ScalarNode || isFlow(node)) {
            int start = node.getStartMark().getLine();
            int line = node.getEndMark().getLine();
            line = node.getEndMark().getColumn() == 0 && line > start ? line - 1 : line;
            return node instanceof ScalarNode ? withoutSeparatorLines((ScalarNode) node, start, line, lines) : line;
        }
        int last = node.getStartMark().getLine();
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                last = Math.max(last, Math.max(lastLine(tuple.getKeyNode(), lines), lastLine(tuple.getValueNode(), lines)));
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode) node).getValue()) {
                last = Math.max(last, lastLine(element, lines));
            }
        }
        return last;
    }

    private static int withoutSeparatorLines(ScalarNode scalar, int start, int end, List<String> lines) {
        if (scalar.getScalarStyle() != DumperOptions.ScalarStyle.LITERAL
                && scalar.getScalarStyle() != DumperOptions.ScalarStyle.FOLDED) {
            return end;
        }
        // The value keeps one trailing line break per kept blank line, plus the content's own (keep chomping).
        String value = scalar.getValue();
        int kept = 0;
        for (int i = value.length() - 1; i >= 0 && value.charAt(i) == '\n'; i--) {
            kept++;
        }
        int keptBlankLines = Math.max(0, kept - 1);
        int last = Math.min(end, lines.size() - 1);
        int blank = 0;
        while (last - blank > start && lines.get(last - blank).trim().isEmpty()) {
            blank++;
        }
        return last - Math.max(0, blank - keptBlankLines);
    }

    private static boolean isFlow(Node node) {
        return node instanceof MappingNode && ((MappingNode) node).getFlowStyle() == DumperOptions.FlowStyle.FLOW
                || node instanceof SequenceNode && ((SequenceNode) node).getFlowStyle() == DumperOptions.FlowStyle.FLOW;
    }

    /** The tuple of the key at {@code path} (the last duplicate wins, as on read), or {@code null}. */
    private static NodeTuple find(Node tree, List<String> path) {
        Node current = tree;
        NodeTuple found = null;
        for (String key : path) {
            if (!(current instanceof MappingNode)) {
                return null;
            }
            found = null;
            for (NodeTuple tuple : ((MappingNode) current).getValue()) {
                if (!Tag.MERGE.equals(tuple.getKeyNode().getTag()) && key.equals(identity(tuple.getKeyNode()))) {
                    found = tuple;
                }
            }
            if (found == null) {
                return null;
            }
            current = found.getValueNode();
        }
        return found;
    }

    /** For every line, the paths of the nodes on it: keys, scalars and flow collections (a list index is an Integer). */
    private static Map<Integer, List<List<Object>>> nodesByLine(Node tree, List<String> lines) {
        Map<Integer, List<List<Object>>> result = new LinkedHashMap<>();
        collect(tree, new ArrayList<Object>(), result, lines);
        return result;
    }

    private static void collect(Node node, List<Object> path, Map<Integer, List<List<Object>>> result, List<String> lines) {
        if (node instanceof ScalarNode || isFlow(node)) {
            record(node, path, result, lines);
        }
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                List<Object> child = new ArrayList<>(path);
                child.add(identity(tuple.getKeyNode()));
                record(tuple.getKeyNode(), child, result, lines);
                collect(tuple.getValueNode(), child, result, lines);
            }
        } else if (node instanceof SequenceNode) {
            List<Node> elements = ((SequenceNode) node).getValue();
            for (int i = 0; i < elements.size(); i++) {
                List<Object> child = new ArrayList<>(path);
                child.add(i);
                collect(elements.get(i), child, result, lines);
            }
        }
    }

    private static void record(Node node, List<Object> path, Map<Integer, List<List<Object>>> result, List<String> lines) {
        int last = lastLine(node, lines);
        for (int line = node.getStartMark().getLine(); line <= last; line++) {
            List<List<Object>> here = result.get(line);
            if (here == null) {
                here = new ArrayList<>();
                result.put(line, here);
            }
            here.add(path);
        }
    }

    private static boolean startsWith(List<Object> path, List<String> prefix) {
        if (path.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(path.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> unowned(boolean[] owned) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < owned.length; i++) {
            if (!owned[i]) {
                result.add(i);
            }
        }
        return result;
    }

    /** Anchors, aliases (a node reached twice) and merge keys. */
    private static boolean usesAnchors(Node tree) {
        return usesAnchors(tree, Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
    }

    private static boolean usesAnchors(Node node, Set<Node> seen) {
        if (!seen.add(node) || node.getAnchor() != null) {
            return true;
        }
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                if (Tag.MERGE.equals(tuple.getKeyNode().getTag()) || usesAnchors(tuple.getKeyNode(), seen)
                        || usesAnchors(tuple.getValueNode(), seen)) {
                    return true;
                }
            }
        } else if (node instanceof SequenceNode) {
            for (Node element : ((SequenceNode) node).getValue()) {
                if (usesAnchors(element, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    // --------------------------------------------------------------------------------------------- helpers

    /**
     * Splits {@code text} after every line break SnakeYAML counts ({@code \r\n}, {@code \r}, {@code \n},
     * U+0085, U+2028, U+2029), keeping the break with its line, so a node mark's line indexes this list.
     */
    static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                i++;
            }
            if (c == '\r' || c == '\n' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    private static boolean hasByteOrderMark(String text) {
        return text.startsWith("\uFEFF");
    }

    private static boolean endsWithLineBreak(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char c = text.charAt(text.length() - 1);
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private static Node compose(String text) {
        try (Reader reader = new UnicodeReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))) {
            DumperOptions options = new DumperOptions();
            return new Yaml(new ConfigDocument.NodeConstructor(), new Representer(options), options,
                    ConfigDocument.loaderOptions()).compose(reader);
        } catch (YAMLException | IOException e) {
            return null;
        }
    }

    private static String identity(Node key) {
        try {
            return String.valueOf(new ConfigDocument.NodeConstructor().construct(key));
        } catch (YAMLException e) {
            return "\u0000unconstructable";
        }
    }

    private static ConfigDocument parseOrNull(String text) {
        try {
            return ConfigDocument.parse(text);
        } catch (ConfigParseException | RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private static byte[] readOrNull(Path file) throws IOException {
        try {
            return Files.readAllBytes(AtomicConfigWriter.resolve(file));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Key names for a warning: a key below a secret-shaped key is redacted; comment paths are marked. */
    private static List<String> describe(List<List<String>> values, List<List<String>> comments) {
        List<String> result = new ArrayList<>();
        for (List<String> path : values) {
            result.add(redacted(path));
        }
        for (List<String> path : comments) {
            if (!values.contains(path)) {
                result.add(redacted(path) + " (comment)");
            }
        }
        return result;
    }

    private static String redacted(List<String> path) {
        StringBuilder text = new StringBuilder();
        boolean secret = false;
        for (String key : path) {
            if (text.length() > 0) {
                text.append('.');
            }
            text.append(secret ? "<redacted>" : key);
            String lower = key.toLowerCase(Locale.ROOT);
            for (String word : SECRET_WORDS) {
                secret |= lower.contains(word);
            }
        }
        return text.toString();
    }
}
