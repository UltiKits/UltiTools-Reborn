package com.ultikits.ultitools.interfaces.impl.data.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.manager.JsonTransactionManager;

/**
 * One JSON transaction's staged writes: for every {@link SimpleJsonDataOperator} the transaction
 * has written, an overlay of staged puts and deletes, kept in first-touch order.
 * <p>
 * A write made inside the transaction is staged here instead of reaching the operator's
 * committed entries. The operator merges the overlay into every read the same transaction makes,
 * so the transaction sees its own writes; every other thread reads the committed entries only.
 * {@link #commit()} applies every overlay to its operator's committed entries while holding the
 * identity's visibility write lock once, from before the first operator to after the last, so
 * another thread's read sees all of a commit's changes on every table or none of them.
 * {@link #rollback()} drops the overlays; it never touches committed entries, so it cannot undo a
 * write another caller made.
 * <p>
 * Before its first write to a table the transaction takes that table's write lock
 * ({@link JsonLockTable}) and holds it until it commits (after the apply) or rolls back, so no
 * other writer -- another transaction or a plain write -- can change the table in between. It
 * records every hold it takes and releases exactly those, so a transaction that runs while another
 * one on the same thread is suspended never releases the suspended one's holds.
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

    /** Fired after the whole apply, once the visibility write lock is released, before the table locks are. */
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
     * Takes {@code operator}'s table write lock for the rest of this transaction, unless this
     * transaction already holds it. Called before a write evaluates or stages anything, and never
     * while holding either side of the visibility lock.
     *
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if the lock wait passes its
     *         bound; the caller's failure path then rolls this transaction back, which releases
     *         every lock it holds
     */
    void lockForWrite(SimpleJsonDataOperator<?> operator) {
        if (heldLocks.containsKey(operator)) {
            return;
        }
        JsonLockTable table = operator.lockTable();
        table.acquire(operator, table.defaultDeadlineNanos());
        heldLocks.put(operator, table);
    }

    /** Whether this transaction has staged any write for {@code operator}. */
    boolean hasStagedWrites(SimpleJsonDataOperator<?> operator) {
        Overlay<?> overlay = overlays.get(operator);
        return overlay != null && !overlay.isEmpty();
    }

    /**
     * Applies every overlay to its operator's committed entries, under the identity's visibility
     * write lock held once across all operators, then discards the overlays and releases the table
     * locks this transaction holds, whether or not the apply succeeded. A transaction that staged
     * nothing takes no visibility lock.
     */
    public void commit() {
        try {
            applyOverlays();
        } finally {
            overlays.clear();
            releaseLocks();
        }
    }

    /** Discards every staged write and releases this transaction's table locks. Committed entries are not touched. */
    public void rollback() {
        overlays.clear();
        releaseLocks();
    }

    private void applyOverlays() {
        if (overlays.isEmpty()) {
            return;
        }
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
        stepHook.onStep(STEP_APPLIED);
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
