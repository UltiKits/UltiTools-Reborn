package com.ultikits.ultitools.config.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

/**
 * What one write through {@link OperatorFileWriter} may change in a config file: the value paths it sets,
 * inserts or removes, the paths whose comment lines it rewrites, or - only when the file does not exist yet -
 * the whole file. Everything else in the file is the operator's and must come out of the write byte for byte
 * as it went in (maintainer decision 2026-10-04, "what code may write, by file type").
 * <p>
 * A path is a list of whole keys, one element per mapping level; a key containing {@code .} is one key. A
 * value path owns the whole key: its key line, its value, and - when the key is inserted or removed, or its
 * comment is owned - the comment lines directly above it. An owned comment is either the key's whole comment or,
 * for a framework comment ({@link Builder#frameworkComment(List, int)}), only its last lines: the run the
 * framework identified as its own, so every comment line above that run stays the operator's (#604).
 * <p>
 * <b>An inserted key under a section left with no value</b> (#620, owned-span rule revision 3, maintainer decision
 * 2026-10-06). When the operator deletes every key of a section, the section key is left on its own line with nothing
 * after the colon ({@code messages:}), which reads as no value at all. A value path inserted below such a line also owns
 * that one line: the section becomes a mapping that holds exactly the inserted keys. This cannot overwrite operator
 * content - the line carried no value, its key text, comment and line terminator are kept, and every other line of the
 * file must still come out byte for byte. {@link OperatorFileWriter} decides this from the file at write time; a section
 * written as an empty value ({@code ~}, {@code null}, {@code {}}) is the operator's value and is never owned this way.
 * Instances are immutable.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class OwnedPaths {

    private static final OwnedPaths WHOLE_FILE = new OwnedPaths(Collections.<List<String>>emptyList(),
            Collections.<List<String>>emptyList(), Collections.<List<String>, Integer>emptyMap(), true);

    private final List<List<String>> values;
    private final List<List<String>> comments;
    private final Map<List<String>, Integer> commentRuns;
    private final boolean wholeFile;

    private OwnedPaths(List<List<String>> values, List<List<String>> comments, Map<List<String>, Integer> commentRuns,
            boolean wholeFile) {
        this.values = values;
        this.comments = comments;
        this.commentRuns = commentRuns;
        this.wholeFile = wholeFile;
    }

    /**
     * The whole file, for creating a file that does not exist. {@link OperatorFileWriter} refuses this
     * ownership for a file that exists.
     *
     * @return the whole-file ownership
     */
    public static OwnedPaths wholeFile() {
        return WHOLE_FILE;
    }

    /**
     * Starts a description of owned keys.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The value paths this write sets, inserts or removes.
     *
     * @return unmodifiable paths, in declaration order
     */
    public List<List<String>> values() {
        return values;
    }

    /**
     * The paths whose comment lines this write rewrites.
     *
     * @return unmodifiable paths, in declaration order
     */
    public List<List<String>> comments() {
        return comments;
    }

    /**
     * How many of the last comment lines above the key at {@code path} this write owns in the file as read, when
     * the path was declared with {@link Builder#frameworkComment(List, int)}; {@code null} when the whole comment
     * is owned or the path's comment is not owned at all.
     *
     * @param path the key path
     * @return the owned run's length in the file, or {@code null}
     */
    public Integer commentRun(List<String> path) {
        return commentRuns.get(path);
    }

    /**
     * Whether this write owns the whole file (allowed only when the file is absent).
     *
     * @return whether the whole file is owned
     */
    public boolean isWholeFile() {
        return wholeFile;
    }

    /** Collects owned paths; each path is copied, so later changes to the caller's list do not leak in. */
    public static final class Builder {

        private final List<List<String>> values = new ArrayList<>();
        private final List<List<String>> comments = new ArrayList<>();
        private final Map<List<String>, Integer> commentRuns = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Owns the whole key at {@code path}: its value and, when it is inserted or removed, its comment.
         *
         * @param path the key path, at least one key
         * @return this builder
         */
        public Builder value(List<String> path) {
            add(values, path);
            return this;
        }

        /**
         * Owns the comment lines directly above the key at {@code path}.
         *
         * @param path the key path, at least one key
         * @return this builder
         */
        public Builder comment(List<String> path) {
            add(comments, path);
            commentRuns.remove(path);
            return this;
        }

        /**
         * Owns only the last {@code linesInFile} comment lines directly above the key at {@code path} - the run the
         * framework identified as its own comment in the file as read (0 when the key has no comment) - and the
         * lines that replace them. Every comment line above that run is not owned, so the gate refuses a write that
         * changes one of them (maintainer decision 2026-10-04, "only the framework's own comments are rewritten").
         *
         * @param path        the key path, at least one key
         * @param linesInFile the length of the owned run in the file as read, at least 0
         * @return this builder
         */
        public Builder frameworkComment(List<String> path, int linesInFile) {
            if (linesInFile < 0) {
                throw new IllegalArgumentException("An owned framework comment run cannot be negative");
            }
            add(comments, path);
            commentRuns.put(Collections.unmodifiableList(new ArrayList<>(path)), linesInFile);
            return this;
        }

        /**
         * Builds the immutable description.
         *
         * @return the owned paths
         */
        public OwnedPaths build() {
            return new OwnedPaths(Collections.unmodifiableList(new ArrayList<>(values)),
                    Collections.unmodifiableList(new ArrayList<>(comments)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(commentRuns)), false);
        }

        private static void add(List<List<String>> target, List<String> path) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("An owned config key path needs at least one key");
            }
            List<String> copy = Collections.unmodifiableList(new ArrayList<>(path));
            if (!target.contains(copy)) {
                target.add(copy);
            }
        }
    }
}
