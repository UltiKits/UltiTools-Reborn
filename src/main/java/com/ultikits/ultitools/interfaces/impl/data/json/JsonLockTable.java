package com.ultikits.ultitools.interfaces.impl.data.json;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;

/**
 * The table write locks of the JSON backend: one per {@link JsonStore}, shared by every operator
 * the store hands out (an operator constructed directly keeps its own). A table is one
 * {@link SimpleJsonDataOperator}, so one entity type of one plugin.
 * <p>
 * <b>Ownership.</b> A lock is owned by a thread, with a hold count. A thread that already owns a
 * table takes it again at once (the count goes up), so a {@code REQUIRES_NEW} or
 * {@code NOT_SUPPORTED} body re-enters the locks of the transaction it suspended instead of
 * waiting for itself. Each caller releases exactly the holds it took; the table is free when the
 * count is back at zero.
 * <p>
 * <b>Waiting.</b> A request for a table another thread owns registers the caller as waiting for
 * that table and waits until the table is free or the caller's deadline has passed; past the
 * deadline it fails with a {@link DataAccessException} naming the lock-wait timeout. The caller
 * chooses the deadline: the default bound (5 seconds, question 7 option A of the Phase 20.4
 * decisions) from the time of the request, or the transaction's own timeout deadline if that comes
 * sooner.
 * <p>
 * <b>Deadlocks.</b> Before it registers, a requester follows the wait-for chain from the table's
 * owner (the owner, the table that owner waits for, that table's owner, and so on). A chain that
 * leads back to the requester means its wait would close a cycle: the request fails at once with a
 * {@link DataAccessException} naming the deadlock, without registering. Its transaction is marked
 * rollback-only ({@link JsonTransaction}) and releases its locks when it ends, so the other side
 * can go on. No global lock order is needed, which matters because module code touches tables in
 * any order.
 * <p>
 * <b>One monitor.</b> Every owner, hold count and waiter registration is guarded by one lock and
 * changed only while holding it, and every wait is on that lock's one condition. The cycle check
 * and the registration are one critical section, so of two requests that close a cycle at the same
 * moment the second always sees the first one's registration and fails, while the first waits and
 * then acquires.
 * <p>
 * <b>Lock order.</b> A table lock is always requested before, never while holding, either side of
 * the identity's visibility lock; the visibility write side is always the last lock a thread takes.
 * The two mechanisms therefore cannot wait for each other in a cycle.
 *
 * @since 6.3.0
 */
final class JsonLockTable {

    /** The default upper bound of one lock wait, in milliseconds (question 7, option A). */
    static final long DEFAULT_LOCK_WAIT_MILLIS = 5000L;

    /** Fired under the monitor right after a waiter has registered, before it starts waiting. */
    static final String STEP_WAITER_REGISTERED = "lock.waiterRegistered";

    private static final JsonTransaction.StepHook NO_STEP_HOOK = step -> { };

    /**
     * Test seam: called with {@link #STEP_WAITER_REGISTERED}. A no-op in production; a test
     * installs a callback that only counts down a latch (it must never block, since it runs
     * holding the monitor) and resets it afterwards.
     */
    private static volatile JsonTransaction.StepHook stepHook = NO_STEP_HOOK;

    private final ReentrantLock monitor = new ReentrantLock();

    /** Signalled whenever a table becomes free. */
    private final Condition released = monitor.newCondition();

    /** The owner and hold count of every table currently held. Guarded by {@link #monitor}. */
    private final Map<SimpleJsonDataOperator<?>, Hold> holds = new HashMap<>();

    /** The table each waiting thread is waiting for. Guarded by {@link #monitor}. */
    private final Map<Thread, SimpleJsonDataOperator<?>> waitsFor = new HashMap<>();

    private volatile long defaultWaitNanos = TimeUnit.MILLISECONDS.toNanos(DEFAULT_LOCK_WAIT_MILLIS);

    /** Test seam: sets the default upper bound of one lock wait. */
    void setDefaultWaitMillis(long millis) {
        defaultWaitNanos = TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /** The deadline of a wait that starts now and is bounded only by the default. */
    long defaultDeadlineNanos() {
        return System.nanoTime() + defaultWaitNanos;
    }

    /**
     * Takes {@code table} for the calling thread: at once if it is free or the caller already owns
     * it, otherwise after waiting until it is free.
     *
     * @param table         the table to lock
     * @param deadlineNanos the {@link System#nanoTime()} value past which the wait fails
     * @throws DataAccessException if waiting would close a wait-for cycle (deadlock), if the
     *                             deadline passes first (lock-wait timeout), or if the thread is
     *                             interrupted while waiting
     */
    void acquire(SimpleJsonDataOperator<?> table, long deadlineNanos) {
        Thread caller = Thread.currentThread();
        long start = System.nanoTime();
        monitor.lock();
        try {
            if (tryTake(table, caller)) {
                return;
            }
            failIfWaitingClosesACycle(table, caller);
            waitsFor.put(caller, table);
            try {
                stepHook.onStep(STEP_WAITER_REGISTERED);
                while (!tryTake(table, caller)) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0L) {
                        throw lockWaitTimeout(table, start);
                    }
                    released.awaitNanos(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                        "Interrupted while waiting for the write lock of JSON table '" + table.tableName() + "'.", e);
            } finally {
                waitsFor.remove(caller);
            }
        } finally {
            monitor.unlock();
        }
    }

    /**
     * Gives back one hold the calling thread took on {@code table}; the table becomes free, and
     * every waiter is woken, when the thread's last hold is given back.
     *
     * @throws IllegalStateException if the calling thread does not own {@code table}
     */
    void release(SimpleJsonDataOperator<?> table) {
        monitor.lock();
        try {
            Hold hold = holds.get(table);
            if (hold == null || hold.owner != Thread.currentThread()) {
                throw new IllegalStateException("The write lock of JSON table '" + table.tableName()
                        + "' is not held by thread '" + Thread.currentThread().getName() + "'.");
            }
            hold.count--;
            if (hold.count == 0) {
                holds.remove(table);
                released.signalAll();
            }
        } finally {
            monitor.unlock();
        }
    }

    static void installStepHook(JsonTransaction.StepHook hook) {
        stepHook = hook == null ? NO_STEP_HOOK : hook;
    }

    static void resetStepHook() {
        stepHook = NO_STEP_HOOK;
    }

    /** Takes {@code table} if it is free or owned by {@code caller}. Called holding the monitor. */
    private boolean tryTake(SimpleJsonDataOperator<?> table, Thread caller) {
        Hold hold = holds.get(table);
        if (hold == null) {
            holds.put(table, new Hold(caller));
            return true;
        }
        if (hold.owner == caller) {
            hold.count++;
            return true;
        }
        return false;
    }

    /**
     * Follows the wait-for chain from {@code table}'s owner; reaching {@code caller} means its wait
     * would close a cycle. Called holding the monitor, right before {@code caller} registers.
     */
    private void failIfWaitingClosesACycle(SimpleJsonDataOperator<?> table, Thread caller) {
        Set<Thread> visited = new HashSet<>();
        SimpleJsonDataOperator<?> next = table;
        while (next != null) {
            Hold hold = holds.get(next);
            if (hold == null || !visited.add(hold.owner)) {
                return;
            }
            if (hold.owner == caller) {
                throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                        "JSON write failed with a deadlock: waiting for table '" + table.tableName()
                                + "', locked by thread '" + holds.get(table).owner.getName()
                                + "', would close a cycle of transactions waiting for each other. This request"
                                + " fails at once; roll its transaction back and retry it.");
            }
            next = waitsFor.get(hold.owner);
        }
    }

    /** Called holding the monitor. */
    private DataAccessException lockWaitTimeout(SimpleJsonDataOperator<?> table, long start) {
        Hold hold = holds.get(table);
        String owner = hold == null ? "another transaction" : "thread '" + hold.owner.getName() + "'";
        return new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                "JSON write failed with a lock-wait timeout after "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        + " ms: table '" + table.tableName() + "' is locked by " + owner
                        + " until its transaction commits or rolls back. The write was not applied.");
    }

    /** The owning thread of one table and how many holds it has on it. */
    private static final class Hold {
        final Thread owner;
        int count = 1;

        Hold(Thread owner) {
            this.owner = owner;
        }
    }
}
