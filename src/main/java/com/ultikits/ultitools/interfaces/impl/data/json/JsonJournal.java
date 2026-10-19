package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;

/**
 * The redo journal that makes a multi-file JSON commit crash consistent (20.4-RESEARCH.md
 * section 12.7). One journal serves one identity: the directory whose sub-directories are that
 * identity's tables, each holding one {@code <key>.json} file per entity.
 * <p>
 * <b>Records.</b> A record lists entity after-images (or deletes) for any number of tables. It is
 * written to {@code <seq>.tmp} in the journal directory, forced to disk, and renamed atomically to
 * {@code <seq>.commit}; <b>the rename is the commit point</b>. A crash before it leaves only the
 * temporary record, which recovery deletes without applying; a crash after it leaves a committed
 * record, which recovery applies whole. Line 1 of a record is its JSON, line 2 the SHA-256 of
 * line 1; the file name is the zero-padded sequence number.
 * <p>
 * <b>Temporary files never enter a table directory.</b> An operator loads every file of its table
 * directory as an entity, so entity files are written in the journal's own {@code tmp/} directory,
 * forced, and moved into place with an atomic rename. Recovery empties that directory first.
 * <p>
 * <b>Recovery</b> deletes temporary records and files, applies the committed records in ascending
 * order, forces the touched table directories, then deletes the applied records in ascending
 * order, forcing the journal directory after each deletion. Every record carries full after-images
 * and every entity file is replaced atomically, so a recovery interrupted at any step and run again
 * ends at the same files. A damaged committed record stops recovery before any file changes; it is
 * never deleted, skipped or applied.
 * <p>
 * <b>Checkpoint</b> writes its own record first, the entity files only after that record is
 * committed, then deletes every record below its number, forces the journal directory, and only
 * then deletes its own record, so no interruption lets a replay bring an older value back over a
 * newer one.
 * <p>
 * <b>Why deletions are forced one step at a time.</b> Deleting in ascending order is not enough
 * on its own: a file system may make a later unlink durable before an earlier one, so after a
 * power loss an older record could survive while the record that supersedes it is gone, and replay
 * would bring the older values back. The journal therefore forces the journal directory, as a
 * barrier, before it deletes a record whose absence would expose an older one: before the
 * checkpoint's own record, which supersedes every record below it, and, in recovery, before each
 * next record, because a recovered record supersedes only the keys it carries. PostgreSQL keeps the
 * same order: it removes old WAL segments only after the checkpoint that supersedes them is
 * durable, and its {@code durable_unlink} (fd.c) fsyncs the parent directory after an unlink
 * because the unlink alone is not durable.
 * <p>
 * <b>Directory forces.</b> After a rename or a deletion the journal forces the directory that holds
 * it, so the change survives a power loss. A directory the journal creates (the journal directory,
 * a table directory, and any missing parent of either) exists on disk only once the directory
 * holding its entry is forced, so the journal remembers each one and forces its parent at the same
 * points: after a record's rename, and before a checkpoint or recovery relies on its table files.
 * One whose parent force fails stays remembered and is forced again at the next of those points.
 * The journal's own {@code tmp/} directory is not remembered: its files only ever move into a
 * table directory, and the forced table directory is what keeps them. A failed force has one of
 * three outcomes:
 * <ul>
 *   <li>a directory that cannot be opened for a force ({@link AccessDeniedException}, which
 *       Windows gives, or {@link UnsupportedOperationException}) is skipped, logged once at
 *       {@code FINE};</li>
 *   <li>any other failure is a real I/O error and is logged at {@code WARNING} naming the directory,
 *       every time. After a commit record's rename, the commit point, the commit still succeeds:
 *       the transaction survives a server crash, but may not survive a power loss or
 *       operating-system crash until the next checkpoint completes;</li>
 *   <li>everywhere else a real failure stops the operation where it happens and is thrown: a
 *       checkpoint never truncates the journal, and a recovery never deletes an applied record,
 *       behind a directory that was not forced. The next checkpoint or recovery repeats the
 *       work.</li>
 * </ul>
 * <p>
 * <b>Moves.</b> On Windows a move another program briefly holds (an antivirus scanner, an indexer,
 * a backup tool) is retried for at most 150 ms before it fails; elsewhere a move is attempted
 * once.
 * <p>
 * <b>Threads.</b> {@link #nextSequence()} may be called from any thread. Several
 * {@link #writeRecord(long, Kind, List)} calls may run at once if their sequence numbers differ:
 * each touches only its own two record file names. A checkpoint may run alongside them, because it
 * deletes only records numbered at or below its own number, which its caller has captured; the
 * caller runs one checkpoint at a time. {@link #recover()} runs alone, before the identity is
 * used.
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

    /** Version written into every record's first line. */
    static final int FORMAT_VERSION = 1;

    /** Entity-file phase of {@link #recover()}. */
    static final String PHASE_RECOVERY = "recovery";
    /** Entity-file phase of a checkpoint. */
    static final String PHASE_CHECKPOINT = "checkpoint";

    /** Record steps, prefixed with the record kind's label. */
    static final String RECORD_WRITTEN = ".recordWritten";
    static final String RECORD_FORCED = ".recordForced";
    static final String RENAMED = ".renamed";
    static final String DIRECTORY_FORCED = ".directoryForced";

    /** Entity-file steps, prefixed with the phase. */
    static final String ENTITY_TEMP_WRITTEN = ".entityTempWritten";
    static final String BETWEEN_ENTITY_FILES = ".betweenEntityFiles";
    static final String TABLE_DIRS_FORCED = ".tableDirsForced";

    static final String RECOVERY_TEMPS_DELETED = "recovery.tempsDeleted";
    static final String RECOVERY_BETWEEN_RECORDS = "recovery.betweenRecords";
    static final String RECOVERY_RECORDS_APPLIED = "recovery.recordsApplied";
    static final String RECOVERY_BETWEEN_DELETIONS = "recovery.betweenDeletions";
    static final String CHECKPOINT_BETWEEN_TRUNCATIONS = "checkpoint.betweenTruncations";

    private static final Logger LOGGER = Logger.getLogger(JsonJournal.class.getName());

    private static final Pattern RECORD_NAME = Pattern.compile("(\\d{1,19})(\\.commit|\\.tmp)");

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final StepHook NO_STEP_HOOK = step -> { };

    private static final DirectoryForcer DEFAULT_DIRECTORY_FORCER = JsonJournal::forceDirectoryChannel;

    private static final Mover DEFAULT_MOVER = Files::move;

    /**
     * Pauses between the attempts of a retried move, in milliseconds: at most 5 attempts and
     * 150 ms of waiting in all (20.4-DECISIONS.md question 10 A).
     */
    private static final long[] MOVE_RETRY_PAUSES_MILLIS = {10L, 20L, 40L, 80L};

    /** Whether a failed move is retried on this platform; decided once from {@code os.name}. */
    private static final boolean DEFAULT_MOVE_RETRY = retriesMovesOn(System.getProperty("os.name"));

    /**
     * Test seam: called with a step name at the points named above. A no-op in production; a test
     * installs a callback that pauses, fails or simulates a crash at one step and resets it
     * afterwards.
     */
    private static volatile StepHook stepHook = NO_STEP_HOOK;

    /** Test seam for the directory force; the default opens the directory and forces it. */
    private static volatile DirectoryForcer directoryForcer = DEFAULT_DIRECTORY_FORCER;

    /** Test seam for every file move the journal makes; the default is {@link Files#move}. */
    private static volatile Mover mover = DEFAULT_MOVER;

    /** Test seam: whether a failed move is retried; the default follows the operating system. */
    private static volatile boolean moveRetry = DEFAULT_MOVE_RETRY;

    private final Path identityRoot;
    private final Path directory;
    private final Path tempDirectory;

    /**
     * Directories this journal created whose entry in their parent directory is not yet known to be
     * on disk. Also the lock that makes creating a directory and remembering it one step.
     */
    private final Set<Path> unforcedNewDirectories = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean directoryCannotBeOpenedLogged = new AtomicBoolean();
    private final AtomicBoolean atomicMoveFallbackLogged = new AtomicBoolean();
    private final AtomicBoolean moveRetryLogged = new AtomicBoolean();

    /** The next sequence number to hand out; {@code 0} until seeded from the journal directory. */
    private long next;

    /**
     * @param identityRoot the directory whose sub-directories are the identity's tables
     * @param directory    the journal directory; created by the first record write
     */
    JsonJournal(Path identityRoot, Path directory) {
        this.identityRoot = Objects.requireNonNull(identityRoot, "identityRoot");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.tempDirectory = directory.resolve(TEMP_DIRECTORY_NAME);
    }

    Path identityRoot() {
        return identityRoot;
    }

    Path directory() {
        return directory;
    }

    /**
     * Hands out the next sequence number. The first call seeds the counter from the highest record
     * number in the journal directory (temporary or committed) plus one, so numbers keep increasing
     * across a reopen while records remain. Writing a record is separate (see
     * {@link #writeRecord(long, Kind, List)}).
     *
     * @return a number greater than every number handed out or on disk before
     * @throws IOException if the journal directory cannot be listed
     */
    synchronized long nextSequence() throws IOException {
        if (next == 0L) {
            long highest = 0L;
            for (RecordFile file : listRecordFiles()) {
                highest = Math.max(highest, file.sequence);
            }
            next = highest + 1L;
        }
        return next++;
    }

    /**
     * Writes and commits one record: {@code <seq>.tmp}, forced, then renamed atomically to
     * {@code <seq>.commit} (the commit point), then the journal directory forced, together with the
     * parent of every directory the journal created and has not yet forced into its parent. Creates
     * the journal directory if it is missing.
     * <p>
     * An {@link IOException} before the rename deletes the temporary record and propagates: nothing
     * was committed. Any other throwable propagates and leaves the temporary record for recovery,
     * as a crash would. A real failure of any of these directory forces after the rename is logged at
     * {@code WARNING}; for a {@link Kind#COMMIT} record this method still returns, because the
     * record is committed, and for a {@link Kind#CHECKPOINT} record the failure propagates, so the
     * checkpoint writes no entity file.
     *
     * @param sequence the record's number, from {@link #nextSequence()}
     * @param kind     what the record stands for; its label prefixes the record steps
     * @param entries  the record's changes, in the order they are to be applied
     * @throws IOException if the record could not be committed, or a checkpoint record's directory
     *                     could not be forced
     */
    void writeRecord(long sequence, Kind kind, List<Entry> entries) throws IOException {
        Objects.requireNonNull(kind, "kind");
        byte[] bytes = encode(sequence, kind, entries);
        createDirectories(directory);
        Path committed = directory.resolve(recordFileName(sequence, COMMITTED_SUFFIX));
        if (Files.exists(committed)) {
            throw new FileAlreadyExistsException(committed.toString(), null,
                    "a committed journal record with this number already exists");
        }
        Path temporary = directory.resolve(recordFileName(sequence, TEMPORARY_SUFFIX));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writeFully(channel, bytes);
                fire(kind.label + RECORD_WRITTEN);
                channel.force(true);
                fire(kind.label + RECORD_FORCED);
            }
            move(temporary, committed);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        fire(kind.label + RENAMED);
        if (kind == Kind.COMMIT) {
            String consequence = "The JSON transaction is committed and survives a server crash, but it may not"
                    + " survive a power loss or operating-system crash until the next checkpoint completes.";
            forceDirectory(directory, consequence);
            forceNewDirectoryParents(consequence);
        } else {
            String consequence = "The checkpoint stops before writing its entity files; the changed"
                    + " entries stay pending and the next flush writes them again.";
            requireDirectoryForced(directory, consequence);
            requireNewDirectoryParentsForced(consequence);
        }
        fire(kind.label + DIRECTORY_FORCED);
    }

    /**
     * Reads every committed record in ascending sequence order, verifying each.
     *
     * @return the committed records, oldest first; empty if the journal directory is missing
     * @throws DataAccessException if a record is damaged; the message names the file
     * @throws IOException         if a record cannot be read
     */
    List<Record> listCommitted() throws IOException {
        List<Record> records = new ArrayList<>();
        for (RecordFile file : listRecordFiles()) {
            if (!file.temporary) {
                records.add(decode(file));
            }
        }
        return records;
    }

    /**
     * Brings the identity's table files to the state of its committed records: empties the
     * journal's {@code tmp/} directory and deletes temporary records, verifies <b>every</b>
     * committed record, applies them in ascending order through the entity-file writer, then
     * deletes the applied records in ascending order, forcing the journal directory after each one
     * it actually deleted, so the records left on disk after any power loss are always a suffix of
     * the applied ones and replay to the same files. A missing journal directory is an empty journal; nothing is created. A real
     * failure to force a table directory stops recovery before any applied record is deleted.
     * <p>
     * A damaged committed record (question 8 option A of 20.4-DECISIONS.md) makes recovery throw
     * before any table file or record is changed. The record is never deleted, skipped or applied:
     * recovery keeps refusing until the server operator moves the file out of the journal
     * directory, and then applies the remaining records.
     *
     * @throws DataAccessException if a committed record is damaged; the message names the file
     * @throws IOException         if a file cannot be read, written, moved or deleted, or a
     *                             directory cannot be forced to disk
     */
    void recover() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        deleteTemporaries();
        fire(RECOVERY_TEMPS_DELETED);
        List<Record> records = listCommitted();
        for (int i = 0; i < records.size(); i++) {
            writeEntityFiles(PHASE_RECOVERY, records.get(i).entries());
            if (i < records.size() - 1) {
                fire(RECOVERY_BETWEEN_RECORDS);
            }
        }
        fire(RECOVERY_RECORDS_APPLIED);
        for (int i = 0; i < records.size(); i++) {
            if (Files.deleteIfExists(records.get(i).file())) {
                requireDirectoryForced(directory, "An applied journal record is deleted but the deletion may not be"
                        + " on disk; recovery stops before deleting the next one, reports a failure and runs again at"
                        + " the next request.");
            }
            if (i < records.size() - 1) {
                fire(RECOVERY_BETWEEN_DELETIONS);
            }
        }
    }

    /**
     * Writes a checkpoint, in this order: commits checkpoint record {@code sequence} (K) holding
     * every entry's after-image; only after K's rename, writes the entity files and deletes the
     * files of deleted entries, then forces the touched table directories; then deletes every
     * record, committed or temporary, numbered below K (and a temporary K, if any), in ascending
     * order, forces the journal directory, and only then deletes K and forces the journal
     * directory again. Records above K are left alone.
     * <p>
     * Why this order: replay applies records in ascending order and the last record touching a
     * key decides its value. An entity file on disk before K is committed could be overwritten by
     * an older surviving record at replay; deleting K before an older record could leave that
     * older record without K, and a file system may make K's unlink durable before an older one's
     * unless a directory force separates them. With this order, every interruption, power loss
     * included, leaves records whose replay ends with K's values once K is renamed, and the older
     * values before it.
     * <p>
     * Truncation deletes records by number without reading them. The caller guarantees that every
     * record numbered below K is already reflected in {@code entries} or in the table files.
     * <p>
     * A real failure to force a directory stops the checkpoint where it happens: after K's rename
     * no entity file is written, after the table directories no record is deleted, after the older
     * records' deletion K is kept, and after K's deletion the failure is still reported. The caller keeps its entries pending, and the next
     * checkpoint writes them again.
     *
     * @param sequence K, from {@link #nextSequence()}
     * @param entries  the captured after-images and deletes
     * @throws IOException if a record or entity file cannot be written, moved or deleted, or a
     *                     directory cannot be forced to disk
     */
    void checkpoint(long sequence, List<Entry> entries) throws IOException {
        writeRecord(sequence, Kind.CHECKPOINT, entries);
        writeEntityFiles(PHASE_CHECKPOINT, entries);
        List<RecordFile> older = new ArrayList<>();
        RecordFile own = null;
        for (RecordFile file : listRecordFiles()) {
            if (file.sequence < sequence || (file.sequence == sequence && file.temporary)) {
                older.add(file);
            } else if (file.sequence == sequence) {
                own = file;
            }
        }
        boolean deletedOlder = false;
        for (int i = 0; i < older.size(); i++) {
            deletedOlder |= Files.deleteIfExists(older.get(i).path);
            if (i < older.size() - 1) {
                fire(CHECKPOINT_BETWEEN_TRUNCATIONS);
            }
        }
        if (own != null) {
            // The barrier: K supersedes every record below it, so its deletion is issued only once
            // theirs are on disk (see the class javadoc).
            if (deletedOlder) {
                requireDirectoryForced(directory, "The checkpoint's older records are deleted but the deletion may not"
                        + " be on disk; the checkpoint keeps its own record and reports a failure, the changed entries"
                        + " stay pending and the next flush writes them again.");
            }
            if (!older.isEmpty()) {
                fire(CHECKPOINT_BETWEEN_TRUNCATIONS);
            }
            Files.deleteIfExists(own.path);
        }
        requireDirectoryForced(directory, "The checkpoint's records are deleted but the deletion may not be on disk;"
                + " the flush reports a failure, the changed entries stay pending and the next flush writes them"
                + " again.");
    }

    /**
     * Applies entries to the table files, in order: a put writes {@code <table>/<key>.json} by
     * writing and forcing a file in the journal's {@code tmp/} directory and moving it into place
     * atomically, replacing the old file; a delete removes the file if present. Then forces the
     * parent of every directory the journal created and has not yet forced into its parent (a new
     * table directory and its new parents among them), and every touched table directory; a real
     * failure to force one propagates, after the files are in place, so the caller deletes no
     * journal record. No file other than {@code <key>.json} is ever created in a table directory.
     *
     * @param phase   {@link #PHASE_RECOVERY} or {@link #PHASE_CHECKPOINT}; prefixes the steps
     * @param entries the changes to apply
     * @throws IOException if a file cannot be written, moved or deleted, or a table directory
     *                     cannot be forced to disk
     */
    void writeEntityFiles(String phase, List<Entry> entries) throws IOException {
        Set<Path> touched = new LinkedHashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Path tableDirectory = identityRoot.resolve(entry.table());
            Path target = tableDirectory.resolve(entry.key() + ".json");
            if (entry.isDelete()) {
                Files.deleteIfExists(target);
            } else {
                Path temporary = writeTemporaryEntityFile(entry.afterImage());
                try {
                    fire(phase + ENTITY_TEMP_WRITTEN);
                    createDirectories(tableDirectory);
                    moveReplacing(temporary, target);
                } catch (IOException failure) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            }
            touched.add(tableDirectory);
            if (i < entries.size() - 1) {
                fire(phase + BETWEEN_ENTITY_FILES);
            }
        }
        String consequence = PHASE_RECOVERY.equals(phase)
                ? "Recovery stops before deleting the applied journal records; this data stays closed and the next"
                        + " request runs recovery again."
                : "The checkpoint stops before truncating the journal; the changed entries stay pending and the next"
                        + " flush writes them again.";
        requireNewDirectoryParentsForced(consequence);
        for (Path tableDirectory : touched) {
            if (Files.isDirectory(tableDirectory)) {
                requireDirectoryForced(tableDirectory, consequence);
            }
        }
        fire(phase + TABLE_DIRS_FORCED);
    }

    // ===== Internals =====

    private Path writeTemporaryEntityFile(String afterImage) throws IOException {
        // Not remembered for a parent force: a temporary file only ever moves into a table
        // directory, and the forced table directory is what keeps it (see the class javadoc).
        Files.createDirectories(tempDirectory);
        Path temporary = tempDirectory.resolve("entity-" + UUID.randomUUID() + ".part");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            writeFully(channel, afterImage.getBytes(StandardCharsets.UTF_8));
            channel.force(true);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return temporary;
    }

    /** Deletes every file in the journal's {@code tmp/} directory and every temporary record. */
    private void deleteTemporaries() throws IOException {
        if (Files.isDirectory(tempDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDirectory)) {
                for (Path path : stream) {
                    if (Files.isRegularFile(path)) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        for (RecordFile file : listRecordFiles()) {
            if (file.temporary) {
                Files.deleteIfExists(file.path);
            }
        }
    }

    /**
     * The journal's record files in ascending order of number, a temporary record before a
     * committed one of the same number; empty if the journal directory is missing.
     */
    List<RecordFile> listRecordFiles() throws IOException {
        List<RecordFile> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                Matcher matcher = RECORD_NAME.matcher(path.getFileName().toString());
                if (matcher.matches() && Files.isRegularFile(path)) {
                    files.add(new RecordFile(Long.parseLong(matcher.group(1)),
                            TEMPORARY_SUFFIX.equals(matcher.group(2)), path));
                }
            }
        }
        files.sort(Comparator.comparingLong((RecordFile file) -> file.sequence)
                .thenComparing(file -> !file.temporary));
        return files;
    }

    /**
     * The file name of record {@code sequence}: the number zero-padded to 19 ASCII digits, then
     * {@code suffix}. Formatted with {@link Locale#ROOT}: under a default locale with other
     * digits (Arabic-Indic, for example) the name would not match {@code RECORD_NAME}, and
     * numbering and recovery would never see the record.
     */
    static String recordFileName(long sequence, String suffix) {
        return String.format(Locale.ROOT, "%019d", sequence) + suffix;
    }

    private static byte[] encode(long sequence, Kind kind, List<Entry> entries) {
        JsonObject record = new JsonObject();
        record.addProperty("format", FORMAT_VERSION);
        record.addProperty("sequence", sequence);
        record.addProperty("kind", kind.label);
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("table", entry.table());
            item.addProperty("key", entry.key());
            item.addProperty("op", entry.isDelete() ? "delete" : "put");
            if (!entry.isDelete()) {
                item.addProperty("afterImage", entry.afterImage());
            }
            array.add(item);
        }
        record.add("entries", array);
        byte[] line = GSON.toJson(record).getBytes(StandardCharsets.UTF_8);
        byte[] checksum = sha256Hex(line).getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(line.length + 1 + checksum.length + 1);
        buffer.put(line).put((byte) '\n').put(checksum).put((byte) '\n');
        return buffer.array();
    }

    /** Reads and verifies one committed record; any defect is reported as a damaged record. */
    private Record decode(RecordFile file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.path);
        int newline = indexOf(bytes, (byte) '\n', 0);
        if (newline < 0) {
            throw damaged(file.path, "it has no checksum line", null);
        }
        byte[] line = new byte[newline];
        System.arraycopy(bytes, 0, line, 0, newline);
        int end = bytes.length;
        if (end > newline + 1 && bytes[end - 1] == '\n') {
            end--;
        }
        String checksum = new String(bytes, newline + 1, end - newline - 1, StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(sha256Hex(line).getBytes(StandardCharsets.US_ASCII),
                checksum.getBytes(StandardCharsets.US_ASCII))) {
            throw damaged(file.path, "its checksum does not match its content", null);
        }
        try {
            JsonObject record = JsonParser.parseString(new String(line, StandardCharsets.UTF_8)).getAsJsonObject();
            if (member(record, "format").getAsInt() != FORMAT_VERSION) {
                throw damaged(file.path, "its format version is not " + FORMAT_VERSION, null);
            }
            if (member(record, "sequence").getAsLong() != file.sequence) {
                throw damaged(file.path, "its sequence number does not match its file name", null);
            }
            Kind kind = Kind.of(member(record, "kind").getAsString());
            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : member(record, "entries").getAsJsonArray()) {
                JsonObject item = element.getAsJsonObject();
                String table = member(item, "table").getAsString();
                String key = member(item, "key").getAsString();
                String op = member(item, "op").getAsString();
                if ("put".equals(op)) {
                    entries.add(Entry.put(table, key, member(item, "afterImage").getAsString()));
                } else if ("delete".equals(op)) {
                    entries.add(Entry.delete(table, key));
                } else {
                    throw damaged(file.path, "an entry has the unknown operation " + op, null);
                }
            }
            return new Record(file.sequence, kind, entries, file.path);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException unreadable) {
            throw damaged(file.path, "its content cannot be read as a journal record", unreadable);
        }
    }

    /** A required member of a record object; a missing or null member makes the record unreadable. */
    private static JsonElement member(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new IllegalStateException("missing member " + name);
        }
        return value;
    }

    private DataAccessException damaged(Path file, String reason, Throwable cause) {
        return new DataAccessException(ErrorCode.DATA_INTEGRITY_VIOLATION,
                "The JSON journal record " + file.toAbsolutePath() + " is damaged: " + reason
                        + ". No table file was changed. This plugin's JSON data stays closed until the file"
                        + " is moved out of " + directory.toAbsolutePath() + ".", cause);
    }

    private static int indexOf(byte[] bytes, byte value, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** Renames a record atomically; falls back to a plain move only if the platform refuses. */
    private void move(Path source, Path target) throws IOException {
        try {
            moveWithRetry(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            logAtomicMoveFallback(unsupported);
            moveWithRetry(source, target);
        }
    }

    /** Moves an entity file into place atomically, replacing the old one. */
    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            moveWithRetry(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            logAtomicMoveFallback(unsupported);
            moveWithRetry(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Every file move the journal makes goes through here. On Windows another program (an
     * antivirus scanner, an indexer, a backup tool) can hold a journal file open for a moment, and
     * the move then fails with an {@link AccessDeniedException} or with a plain
     * {@link FileSystemException} (the JDK's mapping of a sharing or lock violation). Such a move is
     * retried: at most 5 attempts, pausing 10, 20, 40 and 80 ms between them. Every subclass of
     * {@code FileSystemException} other than {@code AccessDeniedException} is a definite answer and
     * is not retried, and off Windows a move is attempted once, because there an open file does not
     * stop a rename and an access error is permanent. An interrupt during a pause stops the retries
     * and keeps the interrupt flag. The failure thrown in the end carries the earlier ones as
     * suppressed.
     */
    private void moveWithRetry(Path source, Path target, CopyOption... options) throws IOException {
        List<IOException> earlier = new ArrayList<>();
        for (int attempt = 0; ; attempt++) {
            try {
                mover.move(source, target, options);
                return;
            } catch (IOException failure) {
                if (!moveRetry || !isTransientMoveFailure(failure) || attempt >= MOVE_RETRY_PAUSES_MILLIS.length) {
                    throw withSuppressed(failure, earlier);
                }
                if (moveRetryLogged.compareAndSet(false, true)) {
                    LOGGER.log(Level.FINE, "Moving the JSON journal file to " + target.toAbsolutePath()
                            + " failed, probably because another program holds it; retrying."
                            + " Further retries are not logged.", failure);
                }
                earlier.add(failure);
                try {
                    TimeUnit.MILLISECONDS.sleep(MOVE_RETRY_PAUSES_MILLIS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw withSuppressed(failure, earlier);
                }
            }
        }
    }

    /** An access-denied failure, or exactly a {@link FileSystemException}: what a transient Windows lock gives. */
    private static boolean isTransientMoveFailure(IOException failure) {
        return failure instanceof AccessDeniedException || failure.getClass() == FileSystemException.class;
    }

    private static IOException withSuppressed(IOException last, List<IOException> earlier) {
        for (IOException each : earlier) {
            if (each != last) {
                last.addSuppressed(each);
            }
        }
        return last;
    }

    private void logAtomicMoveFallback(AtomicMoveNotSupportedException unsupported) {
        if (atomicMoveFallbackLogged.compareAndSet(false, true)) {
            LOGGER.log(Level.WARNING, "The file system under " + directory.toAbsolutePath()
                    + " does not support atomic moves; JSON journal files are moved without that guarantee.",
                    unsupported);
        }
    }

    /**
     * Forces a directory's entries to disk and classifies a failure (see the class javadoc). A
     * directory that cannot be opened for a force is skipped and logged once at {@code FINE}; any
     * other failure is logged at {@code WARNING}, naming the directory and stating
     * {@code consequence}, and returned.
     *
     * @return the real failure, or {@code null} if the directory was forced or cannot be opened
     */
    private IOException forceDirectory(Path target, String consequence) {
        try {
            directoryForcer.force(target);
            return null;
        } catch (AccessDeniedException | UnsupportedOperationException cannotOpen) {
            if (directoryCannotBeOpenedLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.FINE, "Could not open the directory " + target.toAbsolutePath()
                        + " to force it to disk; this platform does not support it. Further failures are not"
                        + " logged.", cannotOpen);
            }
            return null;
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "Could not force the directory " + target.toAbsolutePath() + " to disk ("
                    + failure + "). " + consequence, failure);
            return failure;
        }
    }

    /** {@link #forceDirectory(Path, String)}, throwing a real failure. */
    private void requireDirectoryForced(Path target, String consequence) throws IOException {
        IOException failure = forceDirectory(target, consequence);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Creates {@code target} and every missing parent, as {@link Files#createDirectories} does, and
     * remembers each directory it created for {@link #forceNewDirectoryParents(String)}. Creating
     * and remembering are one step under a lock, so a caller that finds {@code target} already
     * present also finds it remembered while its parent is still unforced, and forces that parent
     * before it relies on the directory.
     */
    private void createDirectories(Path target) throws IOException {
        synchronized (unforcedNewDirectories) {
            if (Files.isDirectory(target)) {
                return;
            }
            List<Path> missing = new ArrayList<>();
            for (Path each = target.toAbsolutePath(); each != null && !Files.exists(each); each = each.getParent()) {
                missing.add(each);
            }
            Files.createDirectories(target);
            unforcedNewDirectories.addAll(missing);
        }
    }

    /**
     * Forces, once each, the parent of every directory in {@link #unforcedNewDirectories} and
     * forgets the directories whose parent was forced or cannot be opened for a force. A real
     * failure is classified and logged as {@link #forceDirectory(Path, String)} does; the
     * directories under that parent stay remembered and are forced again at the next call.
     *
     * @return the first real failure, or {@code null}
     */
    private IOException forceNewDirectoryParents(String consequence) {
        if (unforcedNewDirectories.isEmpty()) {
            return null;
        }
        Map<Path, List<Path>> byParent = new LinkedHashMap<>();
        for (Path created : new ArrayList<>(unforcedNewDirectories)) {
            Path parent = created.getParent();
            if (parent == null) {
                unforcedNewDirectories.remove(created);
            } else {
                byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(created);
            }
        }
        IOException first = null;
        for (Map.Entry<Path, List<Path>> entry : byParent.entrySet()) {
            IOException failure = forceDirectory(entry.getKey(), consequence);
            if (failure == null) {
                unforcedNewDirectories.removeAll(entry.getValue());
            } else if (first == null) {
                first = failure;
            }
        }
        return first;
    }

    /** {@link #forceNewDirectoryParents(String)}, throwing the first real failure. */
    private void requireNewDirectoryParentsForced(String consequence) throws IOException {
        IOException failure = forceNewDirectoryParents(consequence);
        if (failure != null) {
            throw failure;
        }
    }

    private static void forceDirectoryChannel(Path target) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", missing);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static void fire(String step) throws IOException {
        stepHook.onStep(step);
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

    static void installMover(Mover replacement) {
        mover = replacement == null ? DEFAULT_MOVER : replacement;
    }

    static void resetMover() {
        mover = DEFAULT_MOVER;
    }

    static void installMoveRetry(boolean retry) {
        moveRetry = retry;
    }

    static void resetMoveRetry() {
        moveRetry = DEFAULT_MOVE_RETRY;
    }

    /**
     * Whether a journal move that fails is retried on the operating system named {@code osName}
     * (the {@code os.name} system property).
     *
     * @param osName the operating system's name; may be {@code null}
     * @return whether a failed move is retried there
     */
    static boolean retriesMovesOn(String osName) {
        return osName != null && osName.startsWith("Windows");
    }

    /** A test callback for the named journal steps. */
    interface StepHook {
        void onStep(String step) throws IOException;
    }

    /** Forces a directory's entries to disk; replaceable in tests. */
    interface DirectoryForcer {
        void force(Path directory) throws IOException;
    }

    /** Moves a file; replaceable in tests. */
    interface Mover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
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

        static Kind of(String label) {
            for (Kind kind : values()) {
                if (kind.label.equals(label)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown record kind " + label);
        }
    }

    /** A record file found in the journal directory. */
    static final class RecordFile {
        final long sequence;
        final boolean temporary;
        final Path path;

        RecordFile(long sequence, boolean temporary, Path path) {
            this.sequence = sequence;
            this.temporary = temporary;
            this.path = path;
        }
    }

    /**
     * One change of a record: an entity file to write with its after-image, or to delete. The
     * table name and the key are single path segments, so the entity file is always
     * {@code <identity root>/<table>/<key>.json} and nothing is written outside that directory.
     */
    static final class Entry {
        private final String table;
        private final String key;
        private final String afterImage;

        private Entry(String table, String key, String afterImage) {
            this.table = checkTable(table);
            this.key = checkKey(key);
            this.afterImage = afterImage;
        }

        /**
         * @param table      the table directory's name
         * @param key        the entity's key; a non-string key is written in its string form
         * @param afterImage the entity's JSON, written to the file as is
         */
        static Entry put(String table, Object key, String afterImage) {
            return new Entry(table, String.valueOf(Objects.requireNonNull(key, "key")),
                    Objects.requireNonNull(afterImage, "afterImage"));
        }

        static Entry delete(String table, Object key) {
            return new Entry(table, String.valueOf(Objects.requireNonNull(key, "key")), null);
        }

        private static String checkTable(String table) {
            Objects.requireNonNull(table, "table");
            if (".".equals(table) || "..".equals(table)) {
                throw new IllegalArgumentException("A JSON journal table name cannot be '" + table + "'");
            }
            return checkSegment("table name", table);
        }

        private static String checkKey(String key) {
            return checkSegment("key", key);
        }

        private static String checkSegment(String what, String value) {
            if (value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("A JSON journal " + what + " must be one non-empty path segment: '"
                        + value + "'");
            }
            return value;
        }

        String table() {
            return table;
        }

        String key() {
            return key;
        }

        /** The entity's JSON for a put; {@code null} for a delete. */
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
