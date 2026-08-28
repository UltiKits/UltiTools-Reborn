package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.dbutils.handlers.MapListHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * First test in the codebase to assert on {@link PreparedStatement#getQueryTimeout()}. Exercises
 * {@link TimeoutAwareQueryRunner} against a real H2 {@link HikariDataSource}, following {@code
 * SQLiteDataOperatorTest}'s H2-in-MySQL-compatibility-mode setup.
 * <p>
 * Every deadline case is driven by a fixed-nanosecond {@link java.util.function.Supplier}, not a
 * sleep -- a test that sleeps to cross a second boundary is a flaky test (Wave 0 gap 3,
 * 02-VALIDATION.md).
 * <p>
 * Each test gets its own dedicated, uniquely-named in-memory database rather than sharing one
 * across the class. This is not just isolation hygiene: H2 stores a JDBC {@code Statement}'s
 * {@code queryTimeout} as session (connection) state, confirmed empirically against
 * {@code h2-2.2.224.jar} -- once ANY statement on a connection calls
 * {@code setQueryTimeout(N)}, every later statement on that same connection (even a brand-new
 * {@code PreparedStatement} that never had it called) reports {@code N}, and H2's own
 * {@code SET QUERY_TIMEOUT 0} command does not reset that client-side JDBC state. A shared
 * connection pool would make {@link #noDeadlineLeavesDriverDefault()} pass or fail depending on
 * test execution order and which pooled physical connection HikariCP happened to hand out.
 */
@DisplayName("TimeoutAwareQueryRunner")
class TimeoutAwareQueryRunnerTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        // A fresh, uniquely-named in-memory database per test -- see the class javadoc for why
        // this must not be shared.
        config.setJdbcUrl("jdbc:h2:mem:timeoutawareqrtest" + DB_COUNTER.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // Deliberately no setPassword(): the in-memory DB is created password-less on first
        // connection; an empty-string password reads as a hardcoded credential to static
        // analysis. There is no credential here.
        dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE timeout_probe (id INT PRIMARY KEY, name VARCHAR(50))");
            st.execute("INSERT INTO timeout_probe (id, name) VALUES (1, 'alpha')");
        }
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    /**
     * Captures the {@link PreparedStatement} a {@link TimeoutAwareQueryRunner} builds, via a
     * {@link org.apache.commons.dbutils.ResultSetHandler} run inside {@code query(...)} -- the
     * runner does not otherwise expose the statement it prepared. {@code getQueryTimeout()} is
     * read from the live {@code ResultSet}'s owning statement, since it is still open at that
     * point.
     */
    private int capturedQueryTimeout(TimeoutAwareQueryRunner runner) throws Exception {
        AtomicReference<Integer> timeout = new AtomicReference<>();
        runner.query("SELECT * FROM timeout_probe WHERE id = ?", rs -> {
            timeout.set(rs.getStatement().getQueryTimeout());
            return null;
        }, 1);
        return timeout.get();
    }

    @Test
    @DisplayName("a 10-second deadline yields a statement timeout in the 9-10 range")
    void tenSecondDeadlineYieldsRemainingBudget() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        TimeoutAwareQueryRunner runner = new TimeoutAwareQueryRunner(dataSource, () -> deadline);

        int timeout = capturedQueryTimeout(runner);

        assertThat(timeout).isBetween(9, 10);
    }

    @Test
    @DisplayName("an already-passed deadline floors at 1, never 0 and never negative")
    void passedDeadlineFloorsAtOne() throws Exception {
        long deadline = System.nanoTime() - TimeUnit.SECONDS.toNanos(30);
        TimeoutAwareQueryRunner runner = new TimeoutAwareQueryRunner(dataSource, () -> deadline);

        int timeout = capturedQueryTimeout(runner);

        assertThat(timeout).isEqualTo(1);
    }

    @Test
    @DisplayName("no deadline (null supplier result) leaves setQueryTimeout uncalled")
    void noDeadlineLeavesDriverDefault() throws Exception {
        TimeoutAwareQueryRunner runner = new TimeoutAwareQueryRunner(dataSource, () -> null);

        int timeout = capturedQueryTimeout(runner);

        // A fresh H2 connection's own default is 0 ("no limit"), confirmed empirically -- see the
        // class javadoc for why this database is not shared with any test that sets a timeout.
        assertThat(timeout).isZero();
    }

    @Test
    @DisplayName("a real SELECT executed through the runner returns the expected rows unchanged")
    void queryRunnerBehaviorIsUnchanged() throws Exception {
        TimeoutAwareQueryRunner runner = new TimeoutAwareQueryRunner(dataSource, () -> null);

        List<Map<String, Object>> rows = runner.query(
                "SELECT * FROM timeout_probe WHERE id = ?", new MapListHandler(), 1);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("NAME")).isEqualTo("alpha");
    }

    @Test
    @DisplayName("a second statement later in the same transaction gets a strictly smaller budget")
    void secondStatementGetsSmallerBudgetWhenClockAdvances() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        TimeoutAwareQueryRunner runner = new TimeoutAwareQueryRunner(dataSource, () -> deadline);

        int first = capturedQueryTimeout(runner);
        // Simulate the clock having advanced between two statements of the same transaction by
        // computing the second statement's remaining budget against the same fixed deadline but
        // an effectively later "now" -- exactly what System.nanoTime() advancing between two real
        // statements would produce, without sleeping to cross a second boundary.
        long laterDeadline = deadline - TimeUnit.SECONDS.toNanos(3);
        TimeoutAwareQueryRunner laterRunner = new TimeoutAwareQueryRunner(dataSource, () -> laterDeadline);
        int second = capturedQueryTimeout(laterRunner);

        assertThat(second).isLessThan(first);
    }
}
