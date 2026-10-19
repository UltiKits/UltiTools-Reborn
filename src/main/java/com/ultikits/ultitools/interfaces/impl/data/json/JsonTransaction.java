package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.manager.JsonTransactionManager;

/**
 * One JSON transaction's staged writes: for every {@link SimpleJsonDataOperator} the transaction
 * has written, an overlay of staged puts and deletes, kept in first-touch order.
 * <p>
 * A write made inside the transaction is staged here instead of reaching the operator's
 * committed entries. The operator merges the overlay into every read the same transaction makes,
 * so the transaction sees its own writes; every other thread reads the committed entries only.
 * {@link #commit()} first makes the transaction durable: it writes every staged change to one
 * record of the identity's journal ({@link JsonJournal}), forced to disk and renamed into place;
 * <b>the rename is the commit point</b>. It then applies every overlay to its operator's committed
 * entries while holding the identity's visibility write lock once, from before the first operator
 * to after the last, so another thread's read sees all of a commit's changes on every table or none
 * of them, and marks every changed key dirty for the next checkpoint. The identity's commit gate is
 * held, read side, from the record's sequence number to the end of the apply. A transaction that
 * staged nothing writes no record, forces nothing and takes no gate.
 * {@link #rollback()} drops the overlays; it never touches committed entries, so it cannot undo a
 * write another caller made.
 * <p>
 * Before its first write to a table the transaction takes that table's write lock
 * ({@link JsonLockTable}) and holds it until it commits (after the apply) or rolls back, so no
 * other writer -- another transaction or a plain write -- can change the table in between. It
 * records every hold it takes and releases exactly those, so a transaction that runs while another
 * one on the same thread is suspended never releases the suspended one's holds.
 * <p>
 * Settings, applied by {@link JsonTransactionManager} right after the transaction begins:
 * <ul>
 *   <li><b>Lock at first read</b> ({@code REPEATABLE_READ}, {@code SERIALIZABLE}): the first read of
 *       a table also takes its write lock, so the table stays still for the rest of the transaction
 *       and a read-modify-write cannot lose an update. The lock is requested before the read takes
 *       the visibility read side, never while holding it.</li>
 *   <li><b>Read-only</b>: every write fails with a {@link DataAccessException} before it locks or
 *       stages anything.</li>
 *   <li><b>Deadline</b> ({@code @Transactional(timeout)}): every operation checks it first and fails
 *       once it has passed, and it bounds every lock wait (the sooner of it and the 5-second default
 *       bound).</li>
 * </ul>
 * <p>
 * <b>A failed lock wait marks the transaction rollback-only</b> (question 7 A of the Phase 20.4
 * decisions). A write, or a {@code REPEATABLE_READ}/{@code SERIALIZABLE} read, whose table lock
 * cannot be obtained -- the wait reached its bound or the transaction's deadline, it would close a
 * cycle, or the thread was interrupted -- marks this transaction rollback-only before the
 * {@link DataAccessException} reaches the caller. Catching the exception does not save the
 * transaction: its outermost commit discards every staged write and throws
 * {@code UnexpectedRollbackException}. The mark is the manager's own rollback-only flag, the one a
 * failed nested scope sets. The transaction keeps the table locks it already holds until it ends,
 * like any other open transaction; the failed request itself holds nothing.
 * <p>
 * Staged values are detached copies: no object the caller holds is reachable from an overlay.
 * An instance is confined to the thread whose {@link JsonTransactionManager} context holds it
 * (suspended contexts included), so it needs no synchronisation of its own.
 * <p>
 * Internal to the JSON backend. It lives in the operator's package so it can reach the
 * operator's package-private apply method; {@link JsonTransactionManager} creates and drives it.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class JsonTransaction {

    /** Fired inside the visibility write lock, after one operator's overlay is applied and before the next. */
    static final String STEP_APPLY_BETWEEN_OPERATORS = "commit.applyBetweenOperators";

    /** Fired after the whole apply, once the visibility write lock is released, before the commit gate and the table locks are. */
    static final String STEP_APPLIED = "commit.applied";

    private static final StepHook NO_STEP_HOOK = step -> { };

    /**
     * Test seam: called with a step name at the points named above. A no-op in production; a test
     * installs a callback that pauses or fails at one step and resets it afterwards.
     */
    private static volatile StepHook stepHook = NO_STEP_HOOK;

    private final JsonTransactionManager manager;

    /** One overlay per operator this transaction has written, in first-touch order. */
    private final Map<SimpleJsonDataOperator<?>, Overlay<?>> overlays = new LinkedHashMap<>();

    /** The table locks this transaction holds, in the order it took them, with the lock table of each. */
    private final Map<SimpleJsonDataOperator<?>, JsonLockTable> heldLocks = new LinkedHashMap<>();

    /** Whether a first read of a table also takes its lock ({@code REPEATABLE_READ}, {@code SERIALIZABLE}). */
    private boolean lockAtFirstRead;

    private boolean readOnly;

    /** The {@link System#nanoTime()} value at which the transaction's timeout passes, or {@code null}. */
    private Long deadlineNanos;

    /**
     * Created by {@link JsonTransactionManager#begin()} for a new outermost transaction.
     *
     * @param manager the manager whose context holds this transaction and whose visibility lock
     *                the commit publishes under
     */
    public JsonTransaction(JsonTransactionManager manager) {
        this.manager = manager;
    }

    /**
     * The overlay this transaction keeps for {@code operator}, or {@code null} if the transaction
     * has not written it.
     */
    @SuppressWarnings("unchecked")
    <T extends BaseDataEntity<String>> Overlay<T> overlay(SimpleJsonDataOperator<T> operator) {
        return (Overlay<T>) overlays.get(operator);
    }

    /** The overlay for {@code operator}, created (in first-touch order) if this is its first write. */
    @SuppressWarnings("unchecked")
    <T extends BaseDataEntity<String>> Overlay<T> overlayForWrite(SimpleJsonDataOperator<T> operator) {
        return (Overlay<T>) overlays.computeIfAbsent(operator, key -> new Overlay<T>());
    }

    /**
     * Sets whether a table's first read also takes its write lock. Called by
     * {@link JsonTransactionManager#setIsolationLevel(int)}; not part of the supported API.
     *
     * @param lockAtFirstRead {@code true} for {@code REPEATABLE_READ} and {@code SERIALIZABLE}
     */
    public void setLockAtFirstRead(boolean lockAtFirstRead) {
        this.lockAtFirstRead = lockAtFirstRead;
    }

    /**
     * Sets whether writes are rejected. Called by {@link JsonTransactionManager#setReadOnly(boolean)};
     * not part of the supported API.
     *
     * @param readOnly {@code true} to reject every write of this transaction
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * Sets the deadline. Called by {@link JsonTransactionManager#setTimeout(int)}; not part of the
     * supported API.
     *
     * @param deadlineNanos a {@link System#nanoTime()} value, or {@code null} for none
     */
    public void setDeadlineNanos(Long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * The deadline, as a {@link System#nanoTime()} value, or {@code null} if none is set. Not part
     * of the supported API.
     *
     * @return the deadline or {@code null}
     */
    public Long getDeadlineNanos() {
        return deadlineNanos;
    }

    /**
     * Called before a read of {@code operator} takes the visibility read side: checks the deadline
     * and, at {@code REPEATABLE_READ} or {@code SERIALIZABLE}, takes the table's write lock.
     *
     * @throws DataAccessException if the deadline has passed, or the lock wait fails; a failed lock
     *         wait first marks this transaction rollback-only
     */
    void beforeRead(SimpleJsonDataOperator<?> operator) {
        checkDeadline(operator);
        if (lockAtFirstRead) {
            lock(operator);
        }
    }

    /**
     * Called before a write evaluates or stages anything, never while holding either side of the
     * visibility lock: rejects it in a read-only transaction, checks the deadline, and takes
     * {@code operator}'s table write lock for the rest of this transaction unless it already holds
     * it.
     *
     * @throws DataAccessException if the transaction is read-only, the deadline has passed, or the
     *         lock wait fails (deadlock or lock-wait timeout); a failed lock wait first marks this
     *         transaction rollback-only, so catching the exception does not save it, and its locks
     *         are released when it ends
     */
    void beforeWrite(SimpleJsonDataOperator<?> operator) {
        if (readOnly) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                    "Write to JSON table '" + operator.tableName() + "' rejected: the transaction is read-only.");
        }
        checkDeadline(operator);
        lock(operator);
    }

    private void checkDeadline(SimpleJsonDataOperator<?> operator) {
        if (deadlineNanos != null && System.nanoTime() - deadlineNanos >= 0L) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                    "JSON transaction timeout: the transaction's deadline passed before this operation on table '"
                            + operator.tableName() + "' started.");
        }
    }

    /**
     * Takes {@code operator}'s table lock unless this transaction holds it, waiting at most until
     * the sooner bound. A wait that fails marks this transaction rollback-only before the
     * exception propagates.
     */
    private void lock(SimpleJsonDataOperator<?> operator) {
        if (heldLocks.containsKey(operator)) {
            return;
        }
        JsonLockTable table = operator.lockTable();
        long deadline = table.defaultDeadlineNanos();
        if (deadlineNanos != null && deadlineNanos - deadline < 0L) {
            deadline = deadlineNanos;
        }
        try {
            table.acquire(operator, deadline);
        } catch (DataAccessException failedWait) {
            markRollbackOnly();
            throw failedWait;
        }
        heldLocks.put(operator, table);
    }

    /**
     * Marks this transaction rollback-only through the manager's own mechanism, the way
     * {@code TransactionInterceptor} records a failed participant: the failed request joins the
     * transaction as one more scope and rolls that scope back, and a rollback at a nested depth
     * sets the manager's rollback-only flag (D-08) without ending the transaction or releasing its
     * locks. Called only from {@link #beforeRead} and {@link #beforeWrite}, which an operator calls
     * on its manager's current transaction, so the manager's active context is this transaction's.
     */
    private void markRollbackOnly() {
        manager.begin();
        manager.rollback();
    }

    /** Whether this transaction has staged any write for {@code operator}. */
    boolean hasStagedWrites(SimpleJsonDataOperator<?> operator) {
        Overlay<?> overlay = overlays.get(operator);
        return overlay != null && !overlay.isEmpty();
    }

    /**
     * Commits: with the identity's commit gate held (read side), allocates a sequence number,
     * writes the journal record of every staged change, forced and renamed into place (the commit
     * point), then applies every overlay to its operator's committed entries under the visibility
     * write lock held once across all operators and marks each changed key dirty. Releases the
     * gate, then the table locks. The overlays are discarded and the table locks released whether
     * or not the commit succeeded. A transaction that staged nothing writes no record and takes
     * neither the gate nor the visibility lock.
     *
     * @throws DataAccessException ({@code TRANSACTION_FAILED}) if the journal record could not be
     *         written; nothing was committed, and the transaction is rolled back
     */
    public void commit() {
        try {
            if (!hasAnyStagedWrite()) {
                return;
            }
            JsonIdentity identity = manager.journalIdentity();
            List<JsonJournal.Entry> record = buildRecord();
            ReentrantReadWriteLock.ReadLock gate = identity.commitGate().readLock();
            gate.lock();
            try {
                writeRecord(identity, record);
                applyOverlays();
                stepHook.onStep(STEP_APPLIED);
            } finally {
                gate.unlock();
            }
        } finally {
            overlays.clear();
            releaseLocks();
        }
    }

    private boolean hasAnyStagedWrite() {
        for (Overlay<?> overlay : overlays.values()) {
            if (!overlay.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The journal entries of every staged write, operators in first-touch order and each
     * operator's writes in staging order.
     *
     * @throws DataAccessException ({@code TRANSACTION_FAILED}) if a staged key cannot name an
     *         entity file (it is not one path segment); nothing was committed
     */
    private List<JsonJournal.Entry> buildRecord() {
        List<JsonJournal.Entry> record = new ArrayList<>();
        for (Map.Entry<SimpleJsonDataOperator<?>, Overlay<?>> entry : overlays.entrySet()) {
            try {
                addEntries(entry.getKey(), entry.getValue(), record);
            } catch (IllegalArgumentException invalid) {
                throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                        "JSON transaction not committed: a write to table '" + entry.getKey().tableName()
                                + "' cannot be stored as a file (" + invalid.getMessage() + ").", invalid);
            }
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    private static <T extends BaseDataEntity<String>> void addEntries(SimpleJsonDataOperator<?> operator,
            Overlay<?> overlay, List<JsonJournal.Entry> record) {
        ((SimpleJsonDataOperator<T>) operator).journalEntries((Overlay<T>) overlay, record);
    }

    /** Allocates the record's number and writes the record; its rename is the commit point. */
    private static void writeRecord(JsonIdentity identity, List<JsonJournal.Entry> record) {
        JsonJournal journal = identity.journal();
        try {
            journal.writeRecord(journal.nextSequence(), JsonJournal.Kind.COMMIT, record);
        } catch (IOException e) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                    "JSON transaction of " + identity.name() + " not committed: its journal record could not be"
                            + " written to " + journal.directory() + ". Nothing was changed.", e);
        }
    }

    /** Discards every staged write and releases this transaction's table locks. Committed entries are not touched. */
    public void rollback() {
        overlays.clear();
        releaseLocks();
    }

    private void applyOverlays() {
        manager.publishUnderVisibilityLock(() -> {
            Iterator<Map.Entry<SimpleJsonDataOperator<?>, Overlay<?>>> iterator = overlays.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<SimpleJsonDataOperator<?>, Overlay<?>> entry = iterator.next();
                apply(entry.getKey(), entry.getValue());
                if (iterator.hasNext()) {
                    stepHook.onStep(STEP_APPLY_BETWEEN_OPERATORS);
                }
            }
            return null;
        });
    }

    /**
     * Releases every table lock this transaction took, most recent first. A release that fails
     * does not stop the others; the first failure is rethrown afterwards with the rest suppressed.
     */
    private void releaseLocks() {
        if (heldLocks.isEmpty()) {
            return;
        }
        List<Map.Entry<SimpleJsonDataOperator<?>, JsonLockTable>> held = new ArrayList<>(heldLocks.entrySet());
        heldLocks.clear();
        Collections.reverse(held);
        RuntimeException failure = null;
        for (Map.Entry<SimpleJsonDataOperator<?>, JsonLockTable> each : held) {
            try {
                each.getValue().release(each.getKey());
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends BaseDataEntity<String>> void apply(SimpleJsonDataOperator<?> operator, Overlay<?> overlay) {
        ((SimpleJsonDataOperator<T>) operator).applyStaged((Overlay<T>) overlay);
    }

    static void installStepHook(StepHook hook) {
        stepHook = hook == null ? NO_STEP_HOOK : hook;
    }

    static void resetStepHook() {
        stepHook = NO_STEP_HOOK;
    }

    /** A test callback for the named steps of a commit. */
    interface StepHook {
        void onStep(String step);
    }

    /**
     * One operator's staged writes, keyed by the key the operator's committed entries use. A
     * {@code null} value marks a staged delete.
     *
     * @param <T> the operator's entity type
     */
    static final class Overlay<T> {
        private final Map<Object, Staged<T>> entries = new LinkedHashMap<>();

        boolean isEmpty() {
            return entries.isEmpty();
        }

        boolean contains(Object key) {
            return entries.containsKey(key);
        }

        /** The staged write for {@code key}, or {@code null} if nothing is staged for it. */
        Staged<T> get(Object key) {
            return entries.get(key);
        }

        void put(Object key, T detachedValue) {
            entries.put(key, new Staged<>(detachedValue));
        }

        void delete(Object key) {
            entries.put(key, new Staged<>(null));
        }

        /** Visits every staged write in staging order; the value is {@code null} for a delete. */
        void forEach(BiConsumer<Object, T> visitor) {
            for (Map.Entry<Object, Staged<T>> entry : entries.entrySet()) {
                visitor.accept(entry.getKey(), entry.getValue().value);
            }
        }
    }

    /**
     * One staged write: a detached value to put, or {@code null} for a delete.
     *
     * @param <T> the operator's entity type
     */
    static final class Staged<T> {
        final T value;

        Staged(T value) {
            this.value = value;
        }
    }
}
