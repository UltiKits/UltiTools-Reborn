package com.ultikits.ultitools.interfaces;

import java.sql.Connection;

/**
 * Interface for managing database transactions.
 * <p>
 * Implementations handle transaction boundaries (begin, commit, rollback)
 * and provide connections that are aware of the current transaction context.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public interface TransactionManager {

    /**
     * Gets the current connection, creating a new one if necessary.
     * <p>
     * If a transaction is active, returns the connection associated with it.
     * Otherwise, returns a new connection from the data source.
     *
     * @return the current connection
     */
    Connection getConnection();

    /**
     * Begins a new transaction.
     * <p>
     * Creates a new connection, disables auto-commit, and associates it
     * with the current thread.
     *
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if transaction cannot be started
     */
    void begin();

    /**
     * Commits the current transaction.
     * <p>
     * Commits all changes made during the transaction and releases the connection.
     *
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if commit fails
     */
    void commit();

    /**
     * Rolls back the current transaction.
     * <p>
     * Discards all changes made during the transaction and releases the connection.
     */
    void rollback();

    /**
     * Checks if there is an active transaction on the current thread.
     *
     * @return true if a transaction is active
     */
    boolean hasActiveTransaction();

    /**
     * Sets the isolation level for the current transaction.
     * <p>
     * Must be called after begin() and before any statements are executed.
     *
     * @param level the JDBC isolation level constant
     */
    void setIsolationLevel(int level);

    /**
     * Sets read-only mode for the current transaction.
     *
     * @param readOnly true for read-only mode
     */
    void setReadOnly(boolean readOnly);

    /**
     * Sets the timeout for the current transaction.
     * <p>
     * Note: Not all databases/drivers support transaction timeout.
     *
     * @param seconds the timeout in seconds
     */
    void setTimeout(int seconds);

    /**
     * Gets the current transaction depth (for nested transactions).
     *
     * @return the transaction depth (0 if no transaction)
     */
    int getTransactionDepth();
}
