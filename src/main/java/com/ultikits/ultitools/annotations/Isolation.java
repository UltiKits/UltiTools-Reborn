package com.ultikits.ultitools.annotations;

import java.sql.Connection;

/**
 * Enum representing transaction isolation levels.
 * <p>
 * Isolation level determines how changes made by one transaction are visible
 * to other concurrent transactions.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public enum Isolation {

    /**
     * Use the default isolation level of the underlying database.
     */
    DEFAULT(-1),

    /**
     * Allows dirty reads, non-repeatable reads, and phantom reads.
     * <p>
     * Lowest isolation level. A transaction can read uncommitted changes
     * made by other transactions.
     */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    /**
     * Prevents dirty reads; allows non-repeatable reads and phantom reads.
     * <p>
     * A transaction can only read committed changes made by other transactions.
     */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    /**
     * Prevents dirty reads and non-repeatable reads; allows phantom reads.
     * <p>
     * Ensures that if a row is read twice in a transaction, the values
     * will be the same.
     */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    /**
     * Prevents dirty reads, non-repeatable reads, and phantom reads.
     * <p>
     * Highest isolation level. Transactions are completely isolated.
     * May significantly impact performance.
     */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int level;

    Isolation(int level) {
        this.level = level;
    }

    /**
     * Gets the JDBC isolation level constant.
     *
     * @return the isolation level, or -1 for DEFAULT
     */
    public int getLevel() {
        return level;
    }
}
