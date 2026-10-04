package com.ultikits.ultitools.config.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

/**
 * What one write through {@link OperatorFileWriter} may change in a config file: the value paths it sets,
 * inserts or removes, the paths whose comment lines it rewrites, or - only when the file does not exist yet -
 * the whole file. Everything else in the file is the operator's and must come out of the write byte for byte
 * as it went in (maintainer decision 2026-10-04, "what code may write, by file type").
 * <p>
 * A path is a list of whole keys, one element per mapping level; a key containing {@code .} is one key. A
 * value path owns the whole key: its key line, its value, and - when the key is inserted or removed, or its
 * comment is owned - the comment lines directly above it. Instances are immutable.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class OwnedPaths {

    private static final OwnedPaths WHOLE_FILE = new OwnedPaths(Collections.<List<String>>emptyList(),
            Collections.<List<String>>emptyList(), true);

    private final List<List<String>> values;
    private final List<List<String>> comments;
    private final boolean wholeFile;

    private OwnedPaths(List<List<String>> values, List<List<String>> comments, boolean wholeFile) {
        this.values = values;
        this.comments = comments;
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
            return this;
        }

        /**
         * Builds the immutable description.
         *
         * @return the owned paths
         */
        public OwnedPaths build() {
            return new OwnedPaths(Collections.unmodifiableList(new ArrayList<>(values)),
                    Collections.unmodifiableList(new ArrayList<>(comments)), false);
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
