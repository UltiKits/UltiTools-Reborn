package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;

/**
 * The durable state shared by every JSON table of one identity (a plugin, an external data folder,
 * or one directly constructed operator): its redo journal, the sequence numbers its records draw
 * from, the commit gate, the checkpoint mutex, the registry of its operators and the generation
 * counter behind their dirty marks (20.4-RESEARCH.md section 12.7).
 * <p>
 * <b>Commit gate.</b> A commit holds the gate's read side from allocating its record's sequence
 * number until its changes are in the operators' committed entries; a write outside a transaction
 * holds it while it applies. A checkpoint takes the write side only to capture the dirty entries and
 * allocate its own number, so every commit numbered below the checkpoint is fully applied before
 * the capture and every other commit is numbered above it.
 * <p>
 * <b>Recovery.</b> {@link #recoverOnce()} replays the identity's committed records into its table
 * files before any of its tables is read. Success is remembered; a refusal (a damaged record, an
 * unreadable file) is not, so the next request for one of the identity's operators runs recovery
 * again, for example after the server operator has moved a damaged record aside.
 * <p>
 * Internal to the JSON backend. Public only because {@code JsonTransactionManager}, in another
 * package, holds one; not part of the supported API.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class JsonIdentity {

    /** Fired after a checkpoint's capture and number allocation, with the gate released, before its record is written. */
    static final String STEP_CHECKPOINT_CAPTURED = "checkpoint.captured";

    /** Fired after a checkpoint's truncation, before its dirty marks are cleared. */
    static final String STEP_CHECKPOINT_TRUNCATED = "checkpoint.truncated";

    private static final StepHook NO_STEP_HOOK = step -> { };

    /**
     * Test seam: called with a step name at the points named above. A no-op in production; a test
     * installs a callback that pauses or fails at one step and resets it afterwards.
     */
    private static volatile StepHook stepHook = NO_STEP_HOOK;

    /** The identity's name, used only in messages. */
    private final String name;

    private final JsonJournal journal;

    /** See the class javadoc. Lock order: table locks, then this, then the visibility lock. */
    private final ReentrantReadWriteLock commitGate = new ReentrantReadWriteLock();

    /** Held by a checkpoint from its capture until its dirty marks are cleared: one checkpoint at a time. */
    private final ReentrantLock checkpointMutex = new ReentrantLock();

    /** The identity's operators, in registration order. */
    private final List<SimpleJsonDataOperator<?>> operators = new CopyOnWriteArrayList<>();

    /** Source of dirty-mark generations; a mark's generation changes whenever its key is written again. */
    private final AtomicLong generations = new AtomicLong();

    /** Whether {@link #recoverOnce()} has succeeded; guarded by {@code this}. */
    private boolean recovered;

    private JsonIdentity(String name, Path root, Path journalDirectory) {
        this.name = Objects.requireNonNull(name, "name");
        this.journal = new JsonJournal(root, journalDirectory);
    }

    /**
     * The identity of a plugin or an external data folder: its tables are the sub-directories of
     * {@code root}, and its journal is {@code <root>/.ultitools-journal}.
     *
     * @param name the identity's name, for messages
     * @param root the directory whose sub-directories are the identity's tables
     * @return the identity
     */
    static JsonIdentity atRoot(String name, Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        return new JsonIdentity(name, absolute, absolute.resolve(JsonJournal.DIRECTORY_NAME));
    }

    /**
     * The identity of one directly constructed operator: its table directory's parent is the root,
     * and its journal is {@code <table dir>.ultitools-journal}, a sibling of the table directory, so
     * the table directory never holds a journal file.
     *
     * @param tableDirectory the operator's table directory
     * @return the identity
     */
    static JsonIdentity forStandaloneTable(String tableDirectory) {
        Path table = Paths.get(tableDirectory).toAbsolutePath().normalize();
        Path parent = table.getParent() != null ? table.getParent() : table;
        return new JsonIdentity(tableDirectory, parent,
                parent.resolve(table.getFileName() + JsonJournal.DIRECTORY_NAME));
    }

    /**
     * The identity's name, for messages.
     *
     * @return the plugin name, data-folder path or table directory this identity stands for
     */
    public String name() {
        return name;
    }

    JsonJournal journal() {
        return journal;
    }

    ReentrantReadWriteLock commitGate() {
        return commitGate;
    }

    ReentrantLock checkpointMutex() {
        return checkpointMutex;
    }

    /**
     * The name a journal record gives {@code tableDirectory}: its path relative to the identity
     * root. A table directory that is not a direct child of the root gives a name the journal
     * refuses, so a record can never write outside the table's own directory.
     */
    String tableName(String tableDirectory) {
        Path table = Paths.get(tableDirectory).toAbsolutePath().normalize();
        return journal.identityRoot().relativize(table).toString();
    }

    /** Adds {@code operator} to the operators a checkpoint captures. */
    void register(SimpleJsonDataOperator<?> operator) {
        if (!operators.contains(operator)) {
            operators.add(operator);
        }
    }

    List<SimpleJsonDataOperator<?>> operators() {
        return operators;
    }

    /** A generation no dirty mark of this identity has carried before. */
    long nextGeneration() {
        return generations.incrementAndGet();
    }

    /**
     * Brings the identity's table files to the state of its committed journal records, once.
     * Called before any of the identity's tables is read. Success is remembered; a refusal is not,
     * so the next call runs recovery again.
     *
     * @throws DataAccessException naming the file if a committed record is damaged
     *                             ({@code DATA_INTEGRITY_VIOLATION}, question 8 option A), or
     *                             {@code DATA_PERSISTENCE_FAILED} if a journal or table file cannot
     *                             be read, written or deleted; either way the identity stays closed
     */
    synchronized void recoverOnce() {
        if (recovered) {
            return;
        }
        try {
            journal.recover();
        } catch (IOException e) {
            throw new DataAccessException(ErrorCode.DATA_PERSISTENCE_FAILED,
                    "Could not recover the JSON journal of " + name + " in " + journal.directory()
                            + "; this identity's JSON data stays closed until recovery succeeds.", e);
        }
        recovered = true;
    }

    static void installStepHook(StepHook hook) {
        stepHook = hook == null ? NO_STEP_HOOK : hook;
    }

    static void resetStepHook() {
        stepHook = NO_STEP_HOOK;
    }

    static void fire(String step) {
        stepHook.onStep(step);
    }

    /** A test callback for the named checkpoint steps. */
    interface StepHook {
        void onStep(String step);
    }
}
