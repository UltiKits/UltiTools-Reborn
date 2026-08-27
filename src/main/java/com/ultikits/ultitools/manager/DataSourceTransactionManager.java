package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.TransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * DataSource-based implementation of {@link TransactionManager}.
 * <p>
 * Uses ThreadLocal to associate transactions with threads, ensuring thread safety.
 * Each thread can have at most one active transaction at a time.
 *
 * @author wisdomme
 * @since 6.2.0
 */
@ApiStatus.Internal
public class DataSourceTransactionManager implements TransactionManager {

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

        try {
            ctx.connection.rollback();
            LOGGER.fine("Transaction rolled back");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to rollback transaction", e);
        } finally {
            // Always cleanup on rollback, regardless of depth
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
    }
}
