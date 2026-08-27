package com.ultikits.ultitools.interfaces.impl.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.commons.dbutils.QueryRunner;

/**
 * A {@link QueryRunner} that applies a per-statement JDBC query timeout, computed against a
 * shared transaction deadline rather than a fixed value (D-10 -- Spring's approach: each
 * statement gets the time <em>remaining</em> in the transaction's budget, not a fresh full
 * allowance).
 * <p>
 * Overrides {@link org.apache.commons.dbutils.AbstractQueryRunner#prepareStatement(Connection,
 * String)} -- the one override point {@code AbstractQueryRunner}'s own javadoc documents for
 * exactly this purpose ("Subclasses can override this method to provide special
 * PreparedStatement configuration if needed"), confirmed by reading
 * {@code commons-dbutils-1.8.1-sources.jar}. Every other {@code QueryRunner} behavior --
 * parameter binding, result-set handling, resource cleanup -- is untouched.
 * <p>
 * {@link #deadlineSupplier} is read fresh on every statement, not captured once: the operator
 * holding this runner outlives any single transaction, so the deadline it should honor changes
 * across calls. A {@code null} result means "no timeout configured" -- no active transaction, or
 * {@code @Transactional(timeout = 0)} (the default) -- and {@link PreparedStatement#setQueryTimeout}
 * is not called at all, leaving the statement at the driver's own default.
 * <p>
 * <b>The floor.</b> An already-exhausted budget produces {@link #MIN_QUERY_TIMEOUT_SECONDS}
 * (currently 1), never 0 and never a negative number. In the JDBC contract,
 * {@code setQueryTimeout(0)} means "no limit" -- silently handing an exhausted budget an
 * unlimited timeout would be the opposite of what a timeout is for. A negative value is rejected
 * by the driver outright. Flooring at the smallest real limit is the only choice that fails fast
 * instead of either of those.
 * <p>
 * Deliberately does not attempt to cancel or interrupt a statement already in flight when its
 * budget expires -- that would be a wall-clock bound on the surrounding method body, which
 * REQUIREMENTS.md rules out as not implementable on plain JDBC (WIRE-14). The bound here is
 * per-statement, by construction, applied before the statement is ever executed.
 *
 * @author wisdomme
 * @since 6.3.0
 */
public class TimeoutAwareQueryRunner extends QueryRunner {

    /**
     * The smallest query timeout, in seconds, this runner will ever apply. {@code
     * setQueryTimeout(0)} means "no limit" in the JDBC contract, so an exhausted deadline must
     * never round down to it -- 1 is the smallest value that still means "a real, enforced
     * limit."
     */
    static final int MIN_QUERY_TIMEOUT_SECONDS = 1;

    /**
     * Supplies the active transaction's deadline as a {@link System#nanoTime()} value, or {@code
     * null} when no timeout is configured for the current statement (no active transaction, or a
     * zero/negative {@code @Transactional(timeout=)}). Read fresh per statement -- see the class
     * javadoc.
     */
    private final Supplier<Long> deadlineSupplier;

    /**
     * Creates a new timeout-aware query runner.
     *
     * @param dataSource       the data source to use for connections
     * @param deadlineSupplier supplies the current transaction's deadline ({@link
     *                         System#nanoTime()} value), or {@code null} when no timeout applies
     */
    public TimeoutAwareQueryRunner(DataSource dataSource, Supplier<Long> deadlineSupplier) {
        super(dataSource);
        this.deadlineSupplier = deadlineSupplier;
    }

    @Override
    protected PreparedStatement prepareStatement(Connection conn, String sql) throws SQLException {
        PreparedStatement statement = super.prepareStatement(conn, sql);
        Long deadlineNanos = deadlineSupplier.get();
        if (deadlineNanos != null) {
            statement.setQueryTimeout(remainingSecondsFloored(deadlineNanos));
        }
        return statement;
    }

    /**
     * Converts a {@link System#nanoTime()} deadline into the whole seconds remaining, floored at
     * {@link #MIN_QUERY_TIMEOUT_SECONDS} so an exhausted or already-passed deadline still
     * produces a real, enforced limit rather than {@code 0} ("no limit") or a negative value
     * (which the JDBC driver rejects).
     * <p>
     * Package-visible (not {@code private}) so {@link AbstractRelationalDataOperator}'s two
     * direct {@code PreparedStatement} call sites -- {@code insertAll}/{@code updateAll}, which
     * bypass {@code QueryRunner} entirely -- apply the identical floor this class does, rather
     * than a second, potentially-diverging copy of the same arithmetic.
     *
     * @param deadlineNanos the deadline, as a {@link System#nanoTime()} value
     * @return the query timeout to apply, in seconds, always {@code >= MIN_QUERY_TIMEOUT_SECONDS}
     */
    static int remainingSecondsFloored(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        long remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
        return (int) Math.max(MIN_QUERY_TIMEOUT_SECONDS, Math.min(Integer.MAX_VALUE, remainingSeconds));
    }
}
