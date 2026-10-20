package com.ultikits.ultitools.annotations;

/**
 * Enum representing transaction propagation behaviors.
 * <p>
 * Propagation determines how a transaction behaves when it encounters
 * an existing transaction context.
 * <p>
 * This is exactly Jakarta Transactions 2.0's {@code TxType} set - six constants, no {@code NESTED}.
 * A {@code NESTED} propagation (a savepoint within the existing transaction) was removed in 6.3.0
 * (D-09): it is implementable on JDBC via {@code Connection.setSavepoint()}, but savepoint
 * behaviour depends on whichever sqlite-jdbc version the server's Paper build happens to ship, and
 * this project cannot pin or test across that. The removal used the {@code COMPATIBILITY.md}
 * clause-2 same-release exception - {@code Propagation} shipped in 6.2.5 but no released version
 * ever executed it.
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
    NEVER
}
