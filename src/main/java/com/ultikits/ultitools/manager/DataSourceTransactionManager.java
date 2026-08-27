package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.interfaces.TransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * DataSource-based implementation of {@link TransactionManager}, and its JDBC-specific
 * sub-interface {@link JdbcTransactionManager}.
 * <p>
 * Uses ThreadLocal to associate transactions with threads, ensuring thread safety.
 * Each thread can have at most one active transaction at a time.
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
            try {
                ctx.connection.rollback();
                LOGGER.fine("Transaction rolled back (was marked rollback-only by a nested scope)");
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Failed to rollback rollback-only transaction", e);
            } finally {
                cleanup(ctx);
            }
            throw UnexpectedRollbackException.markedBy("a nested transaction scope");
        }

        try {
            ctx.connection.commit();
            LOGGER.fine("Transaction committed");
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED, "Failed to commit transaction", e);
        } finally {
            cleanup(ctx);
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

        try {
            ctx.connection.rollback();
            LOGGER.fine("Transaction rolled back");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to rollback transaction", e);
        } finally {
            cleanup(ctx);
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

    @Override
    public void setTimeout(int seconds) {
        // JDBC doesn't directly support transaction timeout
        // This could be implemented using a scheduled task to cancel the connection
        if (seconds > 0) {
            LOGGER.fine("Transaction timeout set to " + seconds + " seconds (note: not enforced by JDBC)");
        }
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
     * Cleans up the transaction context.
     */
    private void cleanup(TransactionContext ctx) {
        if (ctx == null) {
            return;
        }

        try {
            if (ctx.connection != null && !ctx.connection.isClosed()) {
                ctx.connection.setAutoCommit(true);
                ctx.connection.close();
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
    }
}
