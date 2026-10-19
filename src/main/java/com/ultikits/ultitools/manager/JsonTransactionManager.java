package com.ultikits.ultitools.manager;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonIdentity;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonTransaction;

/**
 * The {@link TransactionManager} for the JSON backend, giving {@code @Transactional} and
 * {@code DataOperator#transaction(...)} on {@code datasource.type: json} the same propagation
 * behaviour {@link DataSourceTransactionManager} gives the two JDBC backends.
 * <p>
 * Scoped to one requesting identity (a plugin name on the internal path, a canonical data-folder
 * path on the external one) and shared by every {@code SimpleJsonDataOperator} that
 * {@code JsonStore} hands out for that identity. {@link #begin()} opens (or, by depth, joins) a
 * transaction on the current thread; the context it binds holds one {@link JsonTransaction}.
 * <ul>
 *   <li><b>Staging.</b> A write an operator receives inside the transaction is staged in that
 *       {@link JsonTransaction}, per operator, instead of reaching the operator's committed
 *       entries. Reads made inside the transaction see its own staged writes; other threads see
 *       committed data only.</li>
 *   <li><b>Commit, durable before it returns.</b> {@link #commit()} at depth 1 first writes one
 *       record of the identity's journal holding every staged change of every table, forces it to
 *       disk and renames it into place; the rename is the commit point. Only then does it apply the
 *       changes, holding this manager's visibility write lock once across all of the identity's
 *       operators, so another thread's read sees all of a commit's changes, on every table, or
 *       none of them. Once {@code commit()} has returned, the transaction survives a crash. One
 *       exception: when forcing the journal directory to disk after the record's rename fails with
 *       a real I/O error, {@code commit()} still returns, because the transaction is committed and
 *       visible, and a {@code WARNING} is logged; the transaction then survives a server crash, but
 *       survives a power loss or operating-system crash only once the next checkpoint has
 *       completed. A transaction that changed nothing writes no record and costs no disk access. A
 *       record that cannot be written fails the commit with a {@code DataAccessException}
 *       ({@code TRANSACTION_FAILED}) and rolls the transaction back. On Windows, a journal file
 *       move that another program (an antivirus scanner, an indexer, a backup tool) briefly holds
 *       is retried for at most 150 ms before the commit fails.</li>
 *   <li><b>Rollback.</b> {@link #rollback()} at depth 1 discards the staged writes and touches
 *       nothing that was committed, by this transaction's own inner {@code REQUIRES_NEW} scope or
 *       by any other caller. At depth greater than 1 it marks the transaction rollback-only
 *       instead, mirroring {@link DataSourceTransactionManager}'s D-08 semantics, and the outer
 *       {@link #commit()} then discards the staged writes and throws
 *       {@link UnexpectedRollbackException}.</li>
 *   <li><b>Table write locks.</b> A transaction's first write to a table (one operator) takes that
 *       table's write lock, and the transaction holds it until its depth-1 {@link #commit()}
 *       (after the apply) or {@link #rollback()}; a write outside a transaction holds it for the
 *       call. Another writer of the table waits for it, at most 5 seconds (less if its own
 *       transaction's timeout ends sooner), then fails with a {@code DataAccessException}; a wait
 *       that would close a cycle of transactions waiting for each other fails at once. Dirty
 *       writes and blind overwrites therefore cannot interleave with an open transaction. At the
 *       default isolation reads take no table lock, so a read-modify-write can still lose an
 *       update: compute a write from a read with {@code updateIf}, or use {@code REPEATABLE_READ}
 *       or {@code SERIALIZABLE}, which lock a table at its first read.</li>
 *   <li><b>Settings.</b> {@link #setIsolationLevel(int)}: {@code REPEATABLE_READ} and
 *       {@code SERIALIZABLE} also lock a table at its first read; {@code DEFAULT},
 *       {@code READ_UNCOMMITTED} and {@code READ_COMMITTED} lock at the first write (read
 *       uncommitted is served at read committed). {@link #setReadOnly(boolean)} rejects every write.
 *       {@link #setTimeout(int)} sets a deadline every operation and lock wait observes.</li>
 *   <li><b>Suspension.</b> {@link #suspend()} detaches the context, staged writes and table locks
 *       included, so a {@code REQUIRES_NEW} or {@code NOT_SUPPORTED} body runs independently of it;
 *       {@link #resume(Object)} restores it. Locks belong to the thread, so a body that writes a
 *       table its suspended caller holds re-enters that lock instead of waiting for itself.</li>
 *   <li><b>Recovery at start-up.</b> Before any table of the identity is read, its committed
 *       journal records are replayed into the table files, so a transaction whose commit returned
 *       is present after a crash and one interrupted before its commit point is absent, on every
 *       table. A damaged committed record keeps the identity's data closed, with an error naming
 *       the file, until the server operator moves the file aside.</li>
 *   <li><b>Writes outside a transaction</b> are not journaled; they reach disk at the next flush,
 *       which writes every changed entry of the identity through one checkpoint record, so a
 *       crash during a flush never leaves a half-written table.</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.3.0
 */
public class JsonTransactionManager implements TransactionManager {

    private static final Logger LOGGER = Logger.getLogger(JsonTransactionManager.class.getName());

    /**
     * The requesting identity this manager governs -- a plugin name or a canonical data-folder
     * path, matching whatever {@code JsonStore} used to key the operators it hands out for this
     * manager. Used only for diagnostic log/exception messages; never compared against anything.
     */
    private final String identity;

    /**
     * The identity's durable state: its journal, commit gate and operators. A commit writes its
     * journal record through it before the commit returns.
     */
    private final JsonIdentity journalIdentity;

    /**
     * Instance-scoped {@link ThreadLocal}, matching the FOUND-04 reasoning already documented on
     * {@link DataSourceTransactionManager#contextHolder}: one manager per identity, built once by
     * {@code JsonStore}, so two identities can never cross transaction state.
     */
    private final ThreadLocal<Context> contextHolder = new ThreadLocal<>();

    /**
     * ThreadLocal holding the stack of contexts {@link #suspend()} has detached and not yet
     * {@link #resume(Object)}d, most-recently-suspended on top -- the JSON analogue of {@code
     * DataSourceTransactionManager}'s field of the same name (02-11, D-09).
     * <p>
     * Deliberately a sibling of {@link #contextHolder} rather than folding the active context
     * into the same structure: {@link #getTransactionDepth()}, {@link #hasActiveTransaction()},
     * {@link #commit()} and {@link #rollback()} all read the single active context and stay
     * completely unchanged by this field's existence. Only {@link #suspend()}/
     * {@link #resume(Object)} ever touch it. Never left holding an empty {@link Deque} -- the
     * last pop calls {@code ThreadLocal.remove()} so a Bukkit worker thread returned to the pool
     * does not carry a stale frame.
     */
    private final ThreadLocal<Deque<Context>> suspendedStack = new ThreadLocal<>();

    /**
     * The identity's visibility lock: one per manager, so one per identity. A read of committed
     * state holds the read side while it collects references; a commit holds the write side once
     * while it applies every operator's staged writes, and a write outside a transaction holds it
     * while it applies its change. The write side covers in-memory map updates only.
     */
    private final ReentrantReadWriteLock visibility = new ReentrantReadWriteLock();

    /**
     * Constructed by {@code JsonStore} (a different package, hence {@code public}) when it first
     * resolves a manager for a given identity, and by a {@code SimpleJsonDataOperator} that was
     * constructed directly, for its own standalone identity.
     *
     * @param journalIdentity the identity this manager governs; its name is used in messages
     */
    public JsonTransactionManager(JsonIdentity journalIdentity) {
        this.journalIdentity = Objects.requireNonNull(journalIdentity, "journalIdentity");
        this.identity = journalIdentity.name();
    }

    /**
     * The identity whose journal, commit gate and operators this manager's transactions use.
     * Public because the JSON operators live in another package; not part of the supported API.
     *
     * @return this manager's identity
     */
    @ApiStatus.Internal
    public JsonIdentity journalIdentity() {
        return journalIdentity;
    }

    @Override
    public void begin() {
        Context ctx = contextHolder.get();
        if (ctx != null) {
            ctx.depth++;
            LOGGER.fine("Nested JSON transaction started for " + identity + ", depth: " + ctx.depth);
            return;
        }
        ctx = new Context(new JsonTransaction(this));
        ctx.depth = 1;
        contextHolder.set(ctx);
        LOGGER.fine("JSON transaction started for " + identity);
    }

    /**
     * {@inheritDoc}
     * <p>
     * At depth 1, writes the transaction's journal record and forces it to disk (the commit point),
     * then applies every operator's staged writes under the visibility write lock, held once across
     * all operators, so other threads see the whole commit at one instant, then releases the
     * transaction's table locks. When this returns the transaction is durable. The context is
     * removed from this thread, and the locks released, whether or not the commit succeeds; a
     * record that cannot be written throws {@code DataAccessException} ({@code TRANSACTION_FAILED})
     * and nothing is committed.
     */
    @Override
    public void commit() {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            LOGGER.warning("Commit called but no active JSON transaction for " + identity);
            return;
        }

        if (ctx.depth > 1) {
            ctx.depth--;
            LOGGER.fine("Nested JSON transaction committed for " + identity + ", depth: " + ctx.depth);
            return;
        }

        // D-08: an inner rollback() at a depth this commit() has now unwound to may have marked
        // the context rollback-only instead of tearing it down. At depth 1 that marker must be
        // honoured with a real rollback, not silently overridden by a commit the caller never
        // asked to happen after all -- exactly the same reasoning as
        // DataSourceTransactionManager#commit().
        if (ctx.rollbackOnly) {
            try {
                ctx.transaction.rollback();
                LOGGER.fine("JSON transaction rolled back for " + identity
                        + " (was marked rollback-only by a nested scope)");
            } finally {
                contextHolder.remove();
            }
            throw UnexpectedRollbackException.markedBy("a nested transaction scope in " + identity);
        }

        try {
            ctx.transaction.commit();
            LOGGER.fine("JSON transaction committed for " + identity);
        } finally {
            contextHolder.remove();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * At depth 1, discards the staged writes and releases the transaction's table locks; committed
     * data is never touched. At a greater depth, marks the transaction rollback-only (D-08).
     */
    @Override
    public void rollback() {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            LOGGER.warning("Rollback called but no active JSON transaction for " + identity);
            return;
        }

        // D-08: an inner rollback() must not tear down the whole context -- that would silently
        // discard whatever the outer scope(s) still have to do, and the outer commit() would then
        // proceed as if nothing had gone wrong. Mark rollback-only and decrement instead; only the
        // depth-1 rollback below actually discards anything.
        if (ctx.depth > 1) {
            ctx.rollbackOnly = true;
            ctx.depth--;
            LOGGER.fine("Nested JSON transaction marked rollback-only for " + identity + ", depth: " + ctx.depth);
            return;
        }

        try {
            ctx.transaction.rollback();
            LOGGER.fine("JSON transaction rolled back for " + identity);
        } finally {
            contextHolder.remove();
        }
    }

    @Override
    public boolean hasActiveTransaction() {
        Context ctx = contextHolder.get();
        return ctx != null && ctx.depth > 0;
    }

    @Override
    public int getTransactionDepth() {
        Context ctx = contextHolder.get();
        return ctx != null ? ctx.depth : 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Detaches the current {@code Context}, its staged writes included, from this thread and
     * pushes it onto {@code suspendedStack}, leaving {@link #hasActiveTransaction()} {@code
     * false}. A {@link #begin()} that runs while the original stays suspended opens a fully
     * independent transaction with its own staged writes; its commit applies only those. The
     * suspended one is not touched until {@link #resume(Object)} restores it.
     */
    @Override
    public Object suspend() {
        Context current = contextHolder.get();
        if (current == null) {
            return null;
        }
        Deque<Context> stack = suspendedStack.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            suspendedStack.set(stack);
        }
        stack.push(current);
        contextHolder.remove();
        LOGGER.fine("JSON transaction suspended for " + identity + ", depth: " + current.depth);
        return current;
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code null} is a no-op -- {@link #suspend()} found nothing active in the first place, so
     * there is nothing on {@code suspendedStack} for this call to pop. A non-null handle must be
     * exactly what this manager's own {@link #suspend()} most recently returned; restoring it
     * sets it back as the active context (same {@code Context} instance, same depth, same staged
     * writes) and pops it off {@code suspendedStack}, removing the {@code ThreadLocal} entirely
     * once the stack is empty rather than leaving a dangling empty {@code Deque} behind.
     *
     * @throws IllegalArgumentException if {@code suspended} is non-null but was not produced by
     *                                   this manager's own {@link #suspend()}
     */
    @Override
    public void resume(Object suspended) {
        if (suspended == null) {
            return;
        }
        if (!(suspended instanceof Context)) {
            throw new IllegalArgumentException(
                    "resume() called with a handle this JsonTransactionManager (identity: " + identity
                            + ") did not produce: " + suspended);
        }
        Context ctx = (Context) suspended;
        Deque<Context> stack = suspendedStack.get();
        if (stack != null) {
            stack.remove(ctx);
            if (stack.isEmpty()) {
                suspendedStack.remove();
            }
        }
        contextHolder.set(ctx);
        LOGGER.fine("JSON transaction resumed for " + identity + ", depth: " + ctx.depth);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Maps the {@link Connection} constants {@code TransactionInterceptor} passes:
     * {@code -1} ({@code Isolation.DEFAULT}), {@link Connection#TRANSACTION_READ_UNCOMMITTED} and
     * {@link Connection#TRANSACTION_READ_COMMITTED} lock a table at the transaction's first write to
     * it (read uncommitted is served at read committed, which the SQL standard allows);
     * {@link Connection#TRANSACTION_REPEATABLE_READ} and {@link Connection#TRANSACTION_SERIALIZABLE}
     * also lock it at the first read, which gives repeatable, phantom-free reads at table
     * granularity. With no active transaction a valid value does nothing, as on
     * {@link DataSourceTransactionManager}.
     *
     * @throws IllegalArgumentException for any other value
     */
    @Override
    public void setIsolationLevel(int level) {
        boolean lockAtFirstRead;
        switch (level) {
            case -1:
            case Connection.TRANSACTION_READ_UNCOMMITTED:
            case Connection.TRANSACTION_READ_COMMITTED:
                lockAtFirstRead = false;
                break;
            case Connection.TRANSACTION_REPEATABLE_READ:
            case Connection.TRANSACTION_SERIALIZABLE:
                lockAtFirstRead = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown transaction isolation level " + level
                        + " for JsonTransactionManager (identity: " + identity + "); expected one of the "
                        + "java.sql.Connection TRANSACTION_* constants or -1 for the default.");
        }
        Context ctx = contextHolder.get();
        if (ctx != null) {
            ctx.transaction.setLockAtFirstRead(lockAtFirstRead);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * A read-only JSON transaction rejects every write with a
     * {@code DataAccessException} before the write locks or stages anything; reads work as usual.
     * With no active transaction this does nothing, as on {@link DataSourceTransactionManager}.
     */
    @Override
    public void setReadOnly(boolean readOnly) {
        Context ctx = contextHolder.get();
        if (ctx != null) {
            ctx.transaction.setReadOnly(readOnly);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Records a deadline {@code seconds} from now on the active transaction, exactly as
     * {@link DataSourceTransactionManager#setTimeout(int)} does. Every later operation of the
     * transaction checks it first and fails with a {@code DataAccessException} once it has passed,
     * and every lock wait ends at the sooner of it and the 5-second default bound; the caller's
     * failure path (for {@code @Transactional}, {@code TransactionInterceptor}) then rolls the
     * transaction back. A non-positive value is inert. With no active transaction this logs a
     * warning and records nothing.
     */
    @Override
    public void setTimeout(int seconds) {
        Context ctx = contextHolder.get();
        if (ctx == null) {
            LOGGER.warning("setTimeout called but no active JSON transaction for " + identity);
            return;
        }
        if (seconds > 0) {
            ctx.transaction.setDeadlineNanos(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The active transaction's deadline, carried along when the transaction is suspended and
     * resumed; {@code null} with no active transaction or no positive timeout.
     */
    @Override
    public Long getTimeoutDeadlineNanos() {
        Context ctx = contextHolder.get();
        return ctx == null ? null : ctx.transaction.getDeadlineNanos();
    }

    /**
     * The {@link JsonTransaction} active on the current thread, or {@code null} if none is.
     * Public because the JSON operators live in another package; not part of the supported API.
     *
     * @return the current thread's transaction for this identity, or {@code null}
     */
    @ApiStatus.Internal
    public JsonTransaction currentTransaction() {
        Context ctx = contextHolder.get();
        return ctx == null ? null : ctx.transaction;
    }

    /**
     * Runs {@code read} under the visibility read side. A JSON operator collects references to
     * committed (and, inside a transaction, staged) entries here, and evaluates conditions,
     * detaches and fires entity hooks only after this returns. Waits at most for one in-memory
     * publication. Not part of the supported API.
     *
     * @param read the in-memory collection step
     * @param <R>  the collected result
     * @return what {@code read} returned
     */
    @ApiStatus.Internal
    public <R> R readUnderVisibilityLock(Supplier<R> read) {
        ReentrantReadWriteLock.ReadLock lock = visibility.readLock();
        lock.lock();
        try {
            return read.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code apply} under the visibility write side: a commit's apply of every operator's
     * staged writes, or a write outside a transaction. {@code apply} updates in-memory maps only
     * and runs no module code. Not part of the supported API.
     *
     * @param apply the in-memory publication step
     * @param <R>   the step's result
     * @return what {@code apply} returned
     * @throws IllegalStateException if the current thread holds the read side, which would
     *                               otherwise wait for itself forever
     */
    @ApiStatus.Internal
    public <R> R publishUnderVisibilityLock(Supplier<R> apply) {
        if (visibility.getReadHoldCount() > 0) {
            throw new IllegalStateException("JSON visibility write lock requested while this thread holds its read "
                    + "lock (identity: " + identity + ")");
        }
        ReentrantReadWriteLock.WriteLock lock = visibility.writeLock();
        lock.lock();
        try {
            return apply.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Internal class to hold JSON transaction state -- the JSON analogue of
     * {@link DataSourceTransactionManager.TransactionContext}, minus anything JDBC-specific.
     */
    private static final class Context {
        int depth;

        /**
         * D-08: set by an inner {@link #rollback()} at depth &gt; 1 instead of tearing the
         * context down. The outer {@link #commit()} at depth 1 checks this before committing --
         * if set, it discards the staged writes and throws {@link UnexpectedRollbackException}
         * rather than silently committing (or silently returning) as if the inner rollback never
         * happened.
         */
        boolean rollbackOnly;

        /** The staged writes of this context; carried along when the context is suspended. */
        final JsonTransaction transaction;

        Context(JsonTransaction transaction) {
            this.transaction = transaction;
        }
    }
}
