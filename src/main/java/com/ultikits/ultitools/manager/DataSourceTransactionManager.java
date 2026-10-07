package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * DataSource-based implementation of {@link TransactionManager}, and its JDBC-specific
 * sub-interface {@link JdbcTransactionManager}.
 * <p>
 * Uses ThreadLocal to associate transactions with threads, ensuring thread safety.
 * Each thread can have at most one active transaction at a time.
 * <p>
 * <b>A transaction is never committed after its commit or rollback failed</b> (as of 6.3.0,
 * UltiTools-Reborn#634). JDBC commits an open transaction when auto-commit is switched on
 * ({@link Connection#setAutoCommit(boolean)}), so auto-commit is restored only after a commit or
 * rollback that succeeded. A connection whose commit or rollback failed is discarded instead: a
 * HikariCP pool is told to evict it ({@link HikariDataSource#evictConnection(Connection)}, which
 * closes the physical connection, and the database rolls the open transaction back), and any other
 * connection is closed. Measured on HikariCP 5.1.0: a pooled connection merely closed after a
 * rollback that keeps failing goes back to the pool with auto-commit off and the transaction still
 * open, and the next borrower's work joins it; an evicted one is never handed out again.
 *
 * @author wisdomme
 * @since 6.2.0
 */
@ApiStatus.Internal
public class DataSourceTransactionManager implements JdbcTransactionManager {

    private static final Logger LOGGER = Logger.getLogger(DataSourceTransactionManager.class.getName());

    private final DataSource dataSource;

    /**
     * ThreadLocal holding the current transaction context for each thread.
     * <p>
     * Instance-scoped on purpose (FOUND-04) - one {@code DataSourceTransactionManager} per plugin
     * container, built once in {@code PluginManager.wireAop}. A {@code static} field here would
     * let two plugin containers' transactions cross: manager A's {@code rollback()} would be able
     * to reach a {@link TransactionContext} manager B set up for a completely different
     * {@link DataSource}, on the same thread. Still a {@code ThreadLocal} - the fix is "no longer
     * static", not "no longer ThreadLocal".
     */
    private final ThreadLocal<TransactionContext> contextHolder = new ThreadLocal<>();

    /**
     * ThreadLocal holding the stack of contexts {@link #suspend()} has detached and not yet
     * {@link #resume(Object)}d, most-recently-suspended on top (D-09).
     * <p>
     * Deliberately a sibling of {@link #contextHolder} rather than folding the active context
     * into the same structure: {@link #getTransactionDepth()}, {@link #hasActiveTransaction()},
     * {@link #commit()} and {@link #rollback()} all read the single active context and stay
     * completely unchanged by this field's existence. Only {@link #suspend()}/
     * {@link #resume(Object)} ever touch it. Never left holding an empty {@link Deque} - the last
     * pop calls {@code ThreadLocal.remove()} so a Bukkit worker thread returned to the pool does
     * not carry a stale frame (T-02-DOS-5).
     */
    private final ThreadLocal<Deque<TransactionContext>> suspendedStack = new ThreadLocal<>();

    /**
     * Creates a new DataSourceTransactionManager.
     *
     * @param dataSource the data source to use for connections
     */
    public DataSourceTransactionManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() {
        TransactionContext ctx = contextHolder.get();
        if (ctx != null && ctx.connection != null) {
            return ctx.connection;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.CONNECTION_FAILED, "Failed to get connection", e);
        }
    }

    @Override
    public void begin() {
        TransactionContext existing = contextHolder.get();
        if (existing != null && existing.active) {
            // Nested transaction - increment depth
            existing.depth++;
            LOGGER.fine("Nested transaction started, depth: " + existing.depth);
            return;
        }

        try {
            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            TransactionContext ctx = new TransactionContext();
            ctx.connection = conn;
            ctx.active = true;
            ctx.depth = 1;
            contextHolder.set(ctx);

            LOGGER.fine("Transaction started");
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED, "Failed to begin transaction", e);
        }
    }

    @Override
    public void commit() {
        TransactionContext ctx = contextHolder.get();
        if (ctx == null || !ctx.active) {
            LOGGER.warning("Commit called but no active transaction");
            return;
        }

        // Handle nested transactions
        if (ctx.depth > 1) {
            ctx.depth--;
            LOGGER.fine("Nested transaction committed, depth: " + ctx.depth);
            return;
        }

        // D-08: an inner rollback() at a depth this commit() has now unwound to may have marked
        // the context rollback-only instead of tearing it down. At depth 1 that marker must be
        // honoured with a real rollback, not silently overridden by a commit the caller never
        // asked to happen after all. cleanup(ctx) runs before the throw so a caller catching this
        // exception never observes a still-live context (T-02-TAM-2).
        if (ctx.rollbackOnly) {
            boolean rolledBack = false;
            try {
                ctx.connection.rollback();
                rolledBack = true;
                LOGGER.fine("Transaction rolled back (was marked rollback-only by a nested scope)");
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Failed to rollback rollback-only transaction; its connection is "
                        + "discarded, nothing is committed", e);
            } finally {
                cleanup(ctx, rolledBack);
            }
            throw UnexpectedRollbackException.markedBy("a nested transaction scope");
        }

        boolean committed = false;
        try {
            ctx.connection.commit();
            committed = true;
            LOGGER.fine("Transaction committed");
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED, "Failed to commit transaction", e);
        } finally {
            // #634: a failed commit discards the connection -- the cleanup's auto-commit switch would
            // otherwise commit the transaction the caller is told did not commit.
            cleanup(ctx, committed);
        }
    }

    @Override
    public void rollback() {
        TransactionContext ctx = contextHolder.get();
        if (ctx == null || !ctx.active) {
            LOGGER.warning("Rollback called but no active transaction");
            return;
        }

        // D-08: an inner rollback() must not tear down the whole context -- that would silently
        // discard whatever the outer scope(s) still have to do, and the outer commit() would then
        // proceed as if nothing had gone wrong. Mark rollback-only and decrement instead; only the
        // depth-1 rollback below performs a real connection.rollback() and cleanup, exactly as at
        // HEAD.
        if (ctx.depth > 1) {
            ctx.rollbackOnly = true;
            ctx.depth--;
            LOGGER.fine("Nested transaction marked rollback-only, depth: " + ctx.depth);
            return;
        }

        boolean rolledBack = false;
        try {
            ctx.connection.rollback();
            rolledBack = true;
            LOGGER.fine("Transaction rolled back");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to rollback transaction; its connection is discarded, nothing "
                    + "is committed", e);
        } finally {
            // #634: after a failed rollback the connection is discarded, never switched to
            // auto-commit (which would commit what the caller asked to roll back).
            cleanup(ctx, rolledBack);
        }
    }

    @Override
    public boolean hasActiveTransaction() {
        TransactionContext ctx = contextHolder.get();
        return ctx != null && ctx.active;
    }

    @Override
    public void setIsolationLevel(int level) {
        TransactionContext ctx = contextHolder.get();
        if (ctx != null && ctx.connection != null && level != -1) {
            try {
                ctx.connection.setTransactionIsolation(level);
            } catch (SQLException e) {
                throw new DataAccessException(ErrorCode.TRANSACTION_FAILED, 
                        "Failed to set isolation level: " + level, e);
            }
        }
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        TransactionContext ctx = contextHolder.get();
        if (ctx != null && ctx.connection != null) {
            try {
                ctx.connection.setReadOnly(readOnly);
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Failed to set read-only mode", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * D-10: records a deadline on the active {@link TransactionContext} rather than a wall-clock
     * bound on the transaction as a whole -- {@link #getTimeoutDeadlineNanos()} exposes it so
     * each statement issued afterward can be given the time <em>remaining</em>, not a fresh full
     * allowance. {@code seconds <= 0} is inert (no deadline recorded), matching
     * {@code @Transactional(timeout=0)}'s default -- {@code TransactionInterceptor} itself never
     * calls this method for a non-positive value in the first place, so this is a second,
     * defensive guard for any other caller. Called with no active transaction, this logs a
     * warning and records nothing, the same shape {@link #commit()}/{@link #rollback()} already
     * use for that case.
     */
    @Override
    public void setTimeout(int seconds) {
        TransactionContext ctx = contextHolder.get();
        if (ctx == null || !ctx.active) {
            LOGGER.warning("setTimeout called but no active transaction");
            return;
        }
        if (seconds > 0) {
            ctx.timeoutDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            LOGGER.fine("Transaction timeout set to " + seconds + " seconds");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reads {@link TransactionContext#timeoutDeadlineNanos} off the active context -- {@code
     * null} when no transaction is active, or when {@link #setTimeout(int)} was never called (or
     * called only with a non-positive value) for it. {@link #suspend()}/{@link #resume(Object)}
     * detach and restore the whole {@link TransactionContext} object, so a suspended-then-resumed
     * transaction's deadline travels with it automatically -- no separate handling needed here.
     */
    @Override
    public Long getTimeoutDeadlineNanos() {
        TransactionContext ctx = contextHolder.get();
        return ctx != null && ctx.active ? ctx.timeoutDeadlineNanos : null;
    }

    @Override
    public int getTransactionDepth() {
        TransactionContext ctx = contextHolder.get();
        return ctx != null ? ctx.depth : 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Detaches the current {@link TransactionContext} (connection included) from this thread and
     * pushes it onto {@link #suspendedStack}, leaving {@link #hasActiveTransaction()} {@code
     * false}. A {@code begin()} that runs while the original stays suspended opens a fully
     * independent transaction, on its own connection - the suspended one is never touched until
     * {@link #resume(Object)} restores it.
     */
    @Override
    public Object suspend() {
        TransactionContext current = contextHolder.get();
        if (current == null) {
            return null;
        }
        Deque<TransactionContext> stack = suspendedStack.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            suspendedStack.set(stack);
        }
        stack.push(current);
        contextHolder.remove();
        LOGGER.fine("Transaction suspended, depth: " + current.depth);
        return current;
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code null} is a no-op - {@link #suspend()} found nothing active in the first place, so
     * there is nothing on {@link #suspendedStack} for this call to pop. A non-null handle must be
     * exactly what this manager's own {@link #suspend()} most recently returned; restoring it sets
     * it back as the active context (same {@link TransactionContext} instance, same {@link
     * Connection}, same depth) and pops it off {@link #suspendedStack}, removing the {@code
     * ThreadLocal} entirely once the stack is empty rather than leaving a dangling empty {@link
     * Deque} behind.
     *
     * @throws IllegalArgumentException if {@code suspended} is non-null but was not produced by
     *                                   this manager's own {@link #suspend()}
     */
    @Override
    public void resume(Object suspended) {
        if (suspended == null) {
            return;
        }
        if (!(suspended instanceof TransactionContext)) {
            throw new IllegalArgumentException(
                    "resume() called with a handle this TransactionManager did not produce: " + suspended);
        }
        TransactionContext ctx = (TransactionContext) suspended;
        Deque<TransactionContext> stack = suspendedStack.get();
        if (stack != null) {
            stack.remove(ctx);
            if (stack.isEmpty()) {
                suspendedStack.remove();
            }
        }
        contextHolder.set(ctx);
        LOGGER.fine("Transaction resumed, depth: " + ctx.depth);
    }

    /**
     * Ends the transaction context: removes it from this thread and never throws.
     * <p>
     * {@code completed} -- the commit or rollback succeeded: auto-commit is restored and the
     * connection closed (back to the pool). Otherwise the connection is discarded through
     * {@link #discardConnection(DataSource, Connection)} and auto-commit is never switched on
     * (#634).
     *
     * @param ctx       the context to end
     * @param completed whether its commit or rollback succeeded
     */
    private void cleanup(TransactionContext ctx, boolean completed) {
        if (ctx == null) {
            return;
        }

        try {
            if (ctx.connection != null && !ctx.connection.isClosed()) {
                if (completed) {
                    ctx.connection.setAutoCommit(true);
                    ctx.connection.close();
                } else {
                    discardConnection(dataSource, ctx.connection);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to cleanup connection", e);
        } finally {
            ctx.active = false;
            ctx.depth = 0;
            contextHolder.remove();
        }
    }

    /**
     * Discards a connection whose commit or rollback failed, without ever switching auto-commit on
     * (UltiTools-Reborn#634): JDBC commits an open transaction when auto-commit is switched on. When
     * {@code dataSource} is, or wraps, a {@link HikariDataSource} and {@code connection} is one of its
     * connections, the pool evicts it -- the physical connection is closed, the database rolls the
     * open transaction back, and the connection is never handed out again; the caller must not use
     * it afterwards. Any other connection is closed. Never throws; a failure is logged.
     *
     * @param dataSource the data source {@code connection} came from, possibly {@code null}
     * @param connection the connection to discard, possibly {@code null}
     * @since 6.3.0
     */
    // PMD.AvoidCatchingGenericException: a pool or driver may throw anything while it discards a
    // broken connection, and this runs on a failure path whose own exception must reach the caller.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @ApiStatus.Internal
    public static void discardConnection(DataSource dataSource, Connection connection) {
        if (connection == null) {
            return;
        }
        HikariDataSource pool = hikariPool(dataSource);
        if (pool != null && connection.getClass().getName().startsWith("com.zaxxer.hikari.")) {
            try {
                pool.evictConnection(connection);
                return;
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to evict a connection whose transaction failed; closing it", e);
            }
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to close a connection whose transaction failed", e);
        }
    }

    // PMD.AvoidCatchingGenericException: unwrap of a foreign DataSource may throw anything; then
    // there is no pool to evict from and the connection is simply closed.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static HikariDataSource hikariPool(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        if (dataSource instanceof HikariDataSource) {
            return (HikariDataSource) dataSource;
        }
        try {
            return dataSource.isWrapperFor(HikariDataSource.class) ? dataSource.unwrap(HikariDataSource.class) : null;
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Gets the current transaction context (for testing).
     * <p>
     * Instance method, not static, per the same FOUND-04 fix as {@link #contextHolder} itself:
     * two {@code DataSourceTransactionManager} instances now have independent state.
     */
    TransactionContext getCurrentContext() {
        return contextHolder.get();
    }

    /**
     * Internal class to hold transaction state.
     */
    static class TransactionContext {
        Connection connection;
        boolean active;
        int depth;
        int originalIsolation = -1;
        boolean originalAutoCommit = true;

        /**
         * D-08: set by an inner {@code rollback()} at depth &gt; 1 instead of tearing the context
         * down. The outer {@code commit()} at depth 1 checks this before committing - if set, it
         * performs a real rollback, cleans up, and throws {@link UnexpectedRollbackException}
         * rather than silently committing (or silently returning) as if the inner rollback never
         * happened.
         */
        boolean rollbackOnly;

        /**
         * D-10: set by {@link #setTimeout(int)} to the {@link System#nanoTime()} value at which
         * this transaction's query-timeout budget expires. {@code null} means no timeout is
         * configured -- {@link #getTimeoutDeadlineNanos()} reports that as-is, never coercing it
         * to a sentinel like {@code 0} or {@code -1}.
         */
        Long timeoutDeadlineNanos;
    }
}
