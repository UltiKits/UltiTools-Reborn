package com.ultikits.ultitools.interfaces;

import java.sql.Connection;

/**
 * Interface for managing database transactions.
 * <p>
 * Implementations handle transaction boundaries (begin, commit, rollback)
 * and provide connections that are aware of the current transaction context.
 * <p>
 * JDBC-specific members -- {@code getConnection()}, {@code setIsolationLevel(int)} and
 * {@code setReadOnly(boolean)} -- live on {@link JdbcTransactionManager}; their deprecated
 * defaults on this interface were removed in 6.4.0.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public interface TransactionManager {

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
     * <p>
     * What a failed commit means depends on the implementation:
     * <ul>
     *   <li>For the JDBC implementation used by SQLite and MySQL
     *       ({@code DataSourceTransactionManager}), a
     *       {@link com.ultikits.ultitools.exceptions.DataAccessException} ({@code TRANSACTION_FAILED})
     *       raised because {@link Connection#commit()} failed leaves the outcome unknown: the
     *       database may have applied the commit before the failure reached the framework.</li>
     *   <li>For the JSON implementation ({@code JsonTransactionManager}), a failed commit commits
     *       nothing, as its {@code commit()} javadoc states.</li>
     *   <li>On every implementation, an
     *       {@link com.ultikits.ultitools.exceptions.UnexpectedRollbackException} means the
     *       transaction had been marked rollback-only and was rolled back: none of its writes was
     *       stored.</li>
     * </ul>
     * Implementations do not retry the commit. A caller that must not apply a write twice re-reads
     * the data, or makes the write conditional ({@code DataOperator#updateIf}), before trying again
     * after an unknown outcome.
     *
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if commit fails; on SQLite and
     *         MySQL, when {@link Connection#commit()} failed, whether the database stored the
     *         transaction is unknown
     * @throws com.ultikits.ultitools.exceptions.UnexpectedRollbackException if the transaction had
     *         been marked rollback-only; it was rolled back instead of committed
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
     * Sets the timeout for the current transaction.
     * <p>
     * Note: Not all databases/drivers support transaction timeout.
     *
     * @param seconds the timeout in seconds
     */
    void setTimeout(int seconds);

    /**
     * Returns the {@link System#nanoTime()} value at which the active transaction's timeout
     * budget expires, or {@code null} when no timeout is configured for the active transaction
     * -- no active transaction at all, or {@code @Transactional(timeout=0)} (the default).
     * <p>
     * D-10: consulted per statement (by {@code TimeoutAwareQueryRunner} and the two direct
     * {@code PreparedStatement} call sites {@code AbstractRelationalDataOperator.insertAll}/
     * {@code updateAll} build themselves) to compute the time <em>remaining</em> in the budget,
     * not a value captured once -- the caller re-reads this on every statement.
     * <p>
     * The default implementation always returns {@code null}, meaning "never enforced" -- the
     * same posture a backend whose {@code setTimeout(int)} refuses any positive value outright
     * (rather than ever recording a deadline) naturally has, since it never has anything to
     * report here either. A backend that wants per-statement enforcement overrides this together
     * with {@link #setTimeout(int)} -- overriding only one half of the pair leaves the other
     * silently inert, the same caution {@link #suspend()}'s javadoc gives for its pair.
     *
     * @return the deadline, as a {@link System#nanoTime()} value, or {@code null} if none is
     *         active
     * @since 6.3.0
     */
    default Long getTimeoutDeadlineNanos() {
        return null;
    }

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
     * context type: a JDBC-backed manager suspends a {@link Connection}-holding context, the JSON
     * manager suspends the transaction's context, which carries its staged writes and its table
     * lock holds - neither shape belongs on this base interface. Pass whatever this returns to {@link #resume(Object)} to restore it;
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
