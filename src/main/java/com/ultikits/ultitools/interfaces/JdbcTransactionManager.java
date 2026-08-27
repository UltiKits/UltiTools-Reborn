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
 * <p>
 * {@code TransactionManager} 的 JDBC 专属扩展，携带只对 JDBC {@link javax.sql.DataSource}
 * 有意义的三个成员——裸 {@link Connection} 访问，以及事务开始后、任何语句执行前可调的两个
 * 连接级开关。非 JDBC 后端（例如 JSON 快照管理器）直接实现 {@link TransactionManager}，
 * 永远不必回答这三个问题，而不是被迫继承只能撒谎的抽象方法（D-04）。
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
