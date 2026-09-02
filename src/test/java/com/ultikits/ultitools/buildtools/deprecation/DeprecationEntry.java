package com.ultikits.ultitools.buildtools.deprecation;

/**
 * One row of the cumulative deprecation ledger (D-07): a single deprecated, announced-for-removal,
 * or already-removed public/protected member of {@code com.ultikits.ultitools}.
 *
 * <p>Immutable, Java-8-target POJO (a {@code record} is not available at this bytecode level) with
 * a builder. {@link #key} is the single identifier space D-22 defines — the same string form
 * serves as the registry primary key, the japicmp {@code <exclude>} entry, and D-01's join key
 * between the registry and the japicmp report.
 */
public final class DeprecationEntry {

    /** What kind of member {@link #key} identifies. */
    public enum Kind {
        CLASS, METHOD, FIELD, CONSTRUCTOR
    }

    /** Where this entry sits in D-07's cumulative lifecycle. */
    public enum Status {
        DEPRECATED, ANNOUNCED, REMOVED
    }

    private final RegistryKey key;
    private final Kind kind;
    private final String since;
    private final boolean forRemoval;
    private final String removeIn;
    private final String replacement;
    private final Status status;
    private final String removedIn;

    private DeprecationEntry(Builder builder) {
        this.key = builder.key;
        this.kind = builder.kind;
        this.since = builder.since;
        this.forRemoval = builder.forRemoval;
        this.removeIn = builder.removeIn;
        this.replacement = builder.replacement;
        this.status = builder.status;
        this.removedIn = builder.removedIn;
    }

    public RegistryKey getKey() {
        return key;
    }

    public Kind getKind() {
        return kind;
    }

    public String getSince() {
        return since;
    }

    public boolean isForRemoval() {
        return forRemoval;
    }

    public String getRemoveIn() {
        return removeIn;
    }

    public String getReplacement() {
        return replacement;
    }

    public Status getStatus() {
        return status;
    }

    public String getRemovedIn() {
        return removedIn;
    }

    /** Returns a copy of this entry with {@link #status} set to {@code REMOVED} and {@link #removedIn} set. */
    public DeprecationEntry withRemoved(String removedInVersion) {
        return builder()
                .key(key)
                .kind(kind)
                .since(since)
                .forRemoval(forRemoval)
                .removeIn(removeIn)
                .replacement(replacement)
                .status(Status.REMOVED)
                .removedIn(removedInVersion)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeprecationEntry)) {
            return false;
        }
        DeprecationEntry that = (DeprecationEntry) o;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "DeprecationEntry{" + key + ", status=" + status + '}';
    }

    /** Builder for {@link DeprecationEntry}. */
    public static final class Builder {
        private RegistryKey key;
        private Kind kind;
        private String since;
        private boolean forRemoval;
        private String removeIn;
        private String replacement;
        private Status status = Status.DEPRECATED;
        private String removedIn;

        private Builder() {
        }

        public Builder key(RegistryKey key) {
            this.key = key;
            return this;
        }

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder since(String since) {
            this.since = since;
            return this;
        }

        public Builder forRemoval(boolean forRemoval) {
            this.forRemoval = forRemoval;
            return this;
        }

        public Builder removeIn(String removeIn) {
            this.removeIn = removeIn;
            return this;
        }

        public Builder replacement(String replacement) {
            this.replacement = replacement;
            return this;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder removedIn(String removedIn) {
            this.removedIn = removedIn;
            return this;
        }

        public DeprecationEntry build() {
            if (key == null) {
                throw new IllegalStateException("key is required");
            }
            if (kind == null) {
                throw new IllegalStateException("kind is required");
            }
            return new DeprecationEntry(this);
        }
    }
}
