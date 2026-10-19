package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The redo journal that makes a multi-file JSON commit crash consistent (skeleton).
 *
 * @since 6.3.0
 */
final class JsonJournal {

    /** Name of an identity's journal directory. */
    static final String DIRECTORY_NAME = ".ultitools-journal";

    /** Name of the directory, inside the journal directory, that holds temporary entity files. */
    static final String TEMP_DIRECTORY_NAME = "tmp";

    static final String COMMITTED_SUFFIX = ".commit";
    static final String TEMPORARY_SUFFIX = ".tmp";

    static final String PHASE_RECOVERY = "recovery";
    static final String PHASE_CHECKPOINT = "checkpoint";

    static final String RECORD_WRITTEN = ".recordWritten";
    static final String RECORD_FORCED = ".recordForced";
    static final String RENAMED = ".renamed";
    static final String DIRECTORY_FORCED = ".directoryForced";
    static final String ENTITY_TEMP_WRITTEN = ".entityTempWritten";
    static final String BETWEEN_ENTITY_FILES = ".betweenEntityFiles";
    static final String TABLE_DIRS_FORCED = ".tableDirsForced";

    static final String RECOVERY_TEMPS_DELETED = "recovery.tempsDeleted";
    static final String RECOVERY_BETWEEN_RECORDS = "recovery.betweenRecords";
    static final String RECOVERY_RECORDS_APPLIED = "recovery.recordsApplied";
    static final String RECOVERY_BETWEEN_DELETIONS = "recovery.betweenDeletions";
    static final String CHECKPOINT_BETWEEN_TRUNCATIONS = "checkpoint.betweenTruncations";

    private static final StepHook NO_STEP_HOOK = step -> { };
    private static final DirectoryForcer DEFAULT_DIRECTORY_FORCER = directory -> { };

    private static volatile StepHook stepHook = NO_STEP_HOOK;
    private static volatile DirectoryForcer directoryForcer = DEFAULT_DIRECTORY_FORCER;

    private final Path identityRoot;
    private final Path directory;

    JsonJournal(Path identityRoot, Path directory) {
        this.identityRoot = Objects.requireNonNull(identityRoot, "identityRoot");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    Path identityRoot() {
        return identityRoot;
    }

    Path directory() {
        return directory;
    }

    long nextSequence() throws IOException {
        return 0L;
    }

    void writeRecord(long sequence, Kind kind, List<Entry> entries) throws IOException {
        // skeleton
    }

    List<Record> listCommitted() throws IOException {
        return Collections.emptyList();
    }

    void recover() throws IOException {
        // skeleton
    }

    void writeEntityFiles(String phase, List<Entry> entries) throws IOException {
        // skeleton
    }

    static void installStepHook(StepHook hook) {
        stepHook = hook == null ? NO_STEP_HOOK : hook;
    }

    static void resetStepHook() {
        stepHook = NO_STEP_HOOK;
    }

    static void installDirectoryForcer(DirectoryForcer forcer) {
        directoryForcer = forcer == null ? DEFAULT_DIRECTORY_FORCER : forcer;
    }

    static void resetDirectoryForcer() {
        directoryForcer = DEFAULT_DIRECTORY_FORCER;
    }

    /** A test callback for the named journal steps. */
    interface StepHook {
        void onStep(String step) throws IOException;
    }

    /** Forces a directory's entries to disk; replaceable in tests. */
    interface DirectoryForcer {
        void force(Path directory) throws IOException;
    }

    /** What a record stands for. */
    enum Kind {
        COMMIT("commit"),
        CHECKPOINT("checkpoint");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** One change of a record: an entity file to write with its after-image, or to delete. */
    static final class Entry {
        private final String table;
        private final String key;
        private final String afterImage;

        private Entry(String table, String key, String afterImage) {
            this.table = table;
            this.key = key;
            this.afterImage = afterImage;
        }

        static Entry put(String table, Object key, String afterImage) {
            return new Entry(table, String.valueOf(key), Objects.requireNonNull(afterImage, "afterImage"));
        }

        static Entry delete(String table, Object key) {
            return new Entry(table, String.valueOf(key), null);
        }

        String table() {
            return table;
        }

        String key() {
            return key;
        }

        String afterImage() {
            return afterImage;
        }

        boolean isDelete() {
            return afterImage == null;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry that = (Entry) other;
            return table.equals(that.table) && key.equals(that.key) && Objects.equals(afterImage, that.afterImage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(table, key, afterImage);
        }

        @Override
        public String toString() {
            return (isDelete() ? "delete " : "put ") + table + "/" + key;
        }
    }

    /** A committed record read back from the journal directory. */
    static final class Record {
        private final long sequence;
        private final Kind kind;
        private final List<Entry> entries;
        private final Path file;

        Record(long sequence, Kind kind, List<Entry> entries, Path file) {
            this.sequence = sequence;
            this.kind = kind;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.file = file;
        }

        long sequence() {
            return sequence;
        }

        Kind kind() {
            return kind;
        }

        List<Entry> entries() {
            return entries;
        }

        Path file() {
            return file;
        }
    }
}
