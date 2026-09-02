package com.ultikits.ultitools.interfaces;

import java.sql.Connection;

/**
 * JDBC-specific extension of {@link TransactionManager}.
 * <p>
 * Carries the three members that only make sense against a JDBC {@link javax.sql.DataSource} -
 * raw {@link Connection} access and the two connection-level knobs a transaction can tune before
 * any statement runs. A non-JDBC backend (a JSON snapshot manager, for instance) implements
 * {@link TransactionManager} directly and never has to answer these three, instead of inheriting
 * abstract methods it can only lie about (D-04).
 * <p>
 * Framework internals that genuinely need a {@link Connection} - {@code TransactionAwareDataSource},
 * {@code AbstractRelationalDataOperator} - should program against this sub-interface, not against
 * {@link TransactionManager} directly.
 *
 * @author wisdomme
 * @since 6.3.0
 */
public interface JdbcTransactionManager extends TransactionManager {

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
}
