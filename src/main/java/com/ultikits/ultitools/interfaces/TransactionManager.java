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
     * @deprecated JDBC-specific; not every {@code TransactionManager} backs a
     *             {@link javax.sql.DataSource}. Program against
     *             {@link JdbcTransactionManager#getConnection()} instead. This default throws
     *             {@link UnsupportedOperationException} for any backend that only implements the
     *             base interface.
     * @since 6.3.0 demoted from an abstract method to a {@code default} one (D-04); zero binary
     *        removal.
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    default Connection getConnection() {
        throw new UnsupportedOperationException(
                "getConnection() is JDBC-specific and not supported by this TransactionManager. "
                        + "Implement " + JdbcTransactionManager.class.getName() + " to provide it.");
    }

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
     * @deprecated JDBC-specific; program against
     *             {@link JdbcTransactionManager#setIsolationLevel(int)} instead. This default
     *             throws {@link UnsupportedOperationException} for any backend that only
     *             implements the base interface.
     * @since 6.3.0 demoted from an abstract method to a {@code default} one (D-04); zero binary
     *        removal.
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    default void setIsolationLevel(int level) {
        throw new UnsupportedOperationException(
                "setIsolationLevel(int) is JDBC-specific and not supported by this "
                        + "TransactionManager. Implement " + JdbcTransactionManager.class.getName()
                        + " to provide it.");
    }

    /**
     * Sets read-only mode for the current transaction.
     *
     * @param readOnly true for read-only mode
     * @deprecated JDBC-specific; program against
     *             {@link JdbcTransactionManager#setReadOnly(boolean)} instead. This default
     *             throws {@link UnsupportedOperationException} for any backend that only
     *             implements the base interface.
     * @since 6.3.0 demoted from an abstract method to a {@code default} one (D-04); zero binary
     *        removal.
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    default void setReadOnly(boolean readOnly) {
        throw new UnsupportedOperationException(
                "setReadOnly(boolean) is JDBC-specific and not supported by this "
                        + "TransactionManager. Implement " + JdbcTransactionManager.class.getName()
                        + " to provide it.");
    }

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

    /**
     * Suspends the current transaction, if any, detaching it from this thread so an independent
     * transaction can begin in its place. Backs {@code Propagation.REQUIRES_NEW} and
     * {@code Propagation.NOT_SUPPORTED} (D-09).
     * <p>
     * Deliberately typed to return an opaque {@code Object} rather than a backend-specific
     * context type: a JDBC-backed manager suspends a {@link Connection}-holding context, a JSON
     * snapshot manager (02-05) suspends a set of operator snapshots (D-03) - neither shape belongs
     * on this base interface. Pass whatever this returns to {@link #resume(Object)} to restore it;
     * the caller must not otherwise inspect or reuse the returned value.
     * <p>
     * The default implementation does not support suspension: it always returns {@code null},
     * meaning nothing was suspended. A backend that maintains thread-bound transaction state
     * (JDBC, JSON) must override this together with {@link #resume(Object)} - overriding only one
     * of the pair breaks the contract silently.
     *
     * @return an opaque handle to resume later, or {@code null} if no transaction was active
     * @since 6.3.0
     */
    default Object suspend() {
        return null;
    }

    /**
     * Restores a transaction previously detached by {@link #suspend()}.
     * <p>
     * {@code null} is a no-op - it means {@link #suspend()} found nothing active in the first
     * place, so there is nothing to restore. Passing a handle this {@code TransactionManager} did
     * not itself produce is a programming error.
     * <p>
     * The default implementation is a no-op, pairing with {@link #suspend()}'s default of always
     * returning {@code null}.
     *
     * @param suspended the handle returned by a prior {@link #suspend()} call, or {@code null}
     * @since 6.3.0
     */
    default void resume(Object suspended) {
        // No-op: the default suspend() never produces a non-null handle.
    }
}
