package com.ultikits.ultitools.annotations;

/**
 * Enum representing transaction propagation behaviors.
 * <p>
 * Propagation determines how a transaction behaves when it encounters
 * an existing transaction context.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public enum Propagation {

    /**
     * Support a current transaction; create a new one if none exists.
     * <p>
     * This is the default propagation behavior. If a transaction exists,
     * the method joins it. Otherwise, a new transaction is created.
     */
    REQUIRED,

    /**
     * Create a new transaction, suspending the current transaction if one exists.
     * <p>
     * Always creates a new independent transaction, even if one already exists.
     * The existing transaction (if any) is suspended and resumed after completion.
     */
    REQUIRES_NEW,

    /**
     * Support a current transaction; execute non-transactionally if none exists.
     * <p>
     * If a transaction exists, the method joins it. Otherwise, it executes
     * without a transaction.
     */
    SUPPORTS,

    /**
     * Execute non-transactionally, suspending the current transaction if one exists.
     * <p>
     * Always executes without a transaction, suspending any existing one.
     */
    NOT_SUPPORTED,

    /**
     * Support a current transaction; throw an exception if no transaction exists.
     * <p>
     * Requires an existing transaction. If none exists, an exception is thrown.
     */
    MANDATORY,

    /**
     * Execute non-transactionally; throw an exception if a transaction exists.
     * <p>
     * Cannot execute within a transaction. If one exists, an exception is thrown.
     */
    NEVER,

    /**
     * Execute within a nested transaction if a current transaction exists.
     * <p>
     * Creates a savepoint within the existing transaction. If no transaction
     * exists, behaves like REQUIRED.
     */
    NESTED
}
