package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.ultikits.ultitools.manager.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Characterizes what a failed SQLite or MySQL commit means for the data (UltiTools-Reborn#675,
 * expected behaviour 3; a documentation correction, no runtime change).
 * <p>
 * A commit can fail in two places that look the same to the caller: before the database applied it,
 * or after (the database committed, and the acknowledgement was lost on the way back, for example a
 * MySQL connection dropped after {@code COMMIT} executed). {@code DataOperator#transaction} raises
 * the same {@link DataAccessException} ({@link ErrorCode#TRANSACTION_FAILED}) for both, the
 * framework issues no second commit and never switches auto-commit on, and discarding or evicting
 * the connection cannot undo a commit the database already applied. The outcome of a failed
 * relational commit is therefore unknown to the caller; a rollback that succeeded is the confirmed
 * outcome, in which nothing is stored.
 * <p>
 * Faults are injected below the operator by a {@link DataSource} wrapper, as in
 * {@link TransactionFailurePathsTest}: its connections either refuse a commit without reaching the
 * database, or delegate the commit to the database and then throw. Rows are counted on a raw H2
 * connection that bypasses the wrapper. The SQLite operator runs over an H2 database file in MySQL
 * mode and the MySQL operator over an in-memory H2 database in MySQL mode; the outcome depends on
 * where the failure happens, not on the engine.
 * <br>
 * 提交失败时结果未知：数据库可能已经应用了提交、只是确认丢失；框架不重试，也不会再次提交。
 */
@DisplayName("#675 a failed SQLite or MySQL commit has an unknown outcome")
class CommitOutcomeUnknownTest {

    private static final String ROW_ID = "r1";

    @TempDir
    Path tempDir;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @Table("cou_row")
    public static class Row extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("name")
        private String name;

        public Row() {
        }

        Row(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** What the next commit on a wrapped connection does. */
    enum CommitMode {
        /** Delegates to the database. */
        NORMAL,
        /** Throws without reaching the database: the database never sees the commit. */
        REFUSE_BEFORE_DATABASE,
        /** Delegates to the database, which applies the commit, then throws: the acknowledgement is lost. */
        APPLY_THEN_LOSE_ACKNOWLEDGEMENT
    }

    /**
     * A data source whose connections fail their next commit as {@link #nextCommit} says (once, then
     * back to {@link CommitMode#NORMAL}) and record every call of interest as
     * {@code <connection number>:<call>}, in a thread-safe list.
     */
    static final class CommitFaultDataSource implements DataSource {
        private final DataSource target;
        final AtomicReference<CommitMode> nextCommit = new AtomicReference<>(CommitMode.NORMAL);
        final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger connectionNumbers = new AtomicInteger();

        CommitFaultDataSource(DataSource target) {
            this.target = target;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(target.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(target.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            String number = "c" + connectionNumbers.incrementAndGet();
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                boolean noArgs = args == null || args.length == 0;
                if ("commit".equals(name)) {
                    CommitMode mode = nextCommit.getAndSet(CommitMode.NORMAL);
                    if (mode == CommitMode.REFUSE_BEFORE_DATABASE) {
                        events.add(number + ":commit-refused");
                        throw new SQLException("injected: commit refused before it reached the database");
                    }
                    Object result = invoke(connection, method, args);
                    if (mode == CommitMode.APPLY_THEN_LOSE_ACKNOWLEDGEMENT) {
                        events.add(number + ":commit-applied-acknowledgement-lost");
                        throw new SQLException("injected: commit applied, acknowledgement lost");
                    }
                    events.add(number + ":commit");
                    return result;
                }
                if ("rollback".equals(name) && noArgs) {
                    events.add(number + ":rollback");
                } else if ("setAutoCommit".equals(name)) {
                    events.add(number + ":setAutoCommit(" + args[0] + ")");
                } else if ("close".equals(name)) {
                    events.add(number + ":close");
                }
                return invoke(connection, method, args);
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /** Every commit call, successful or not. */
        long commitCalls() {
            return events.stream().filter(e -> e.contains(":commit")).count();
        }

        /** The connection whose commit failed, or {@code null}. */
        String failedCommitConnection() {
            for (String event : events) {
                if (event.endsWith(":commit-refused") || event.endsWith(":commit-applied-acknowledgement-lost")) {
                    return event.substring(0, event.indexOf(':'));
                }
            }
            return null;
        }

        /** The calls made on {@code connection} after its failed commit. */
        List<String> afterFailedCommit(String connection) {
            List<String> after = new ArrayList<>();
            boolean seen = false;
            for (String event : events) {
                if (!event.startsWith(connection + ":")) {
                    continue;
                }
                if (seen) {
                    after.add(event.substring(event.indexOf(':') + 1));
                } else if (event.endsWith(":commit-refused") || event.endsWith(":commit-applied-acknowledgement-lost")) {
                    seen = true;
                }
            }
            return after;
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return target.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            target.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            target.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return target.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return target.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /** One backend: the raw database, the fault wrapper over it, the manager and the operator under test. */
    final class Backend {
        final String label;
        final JdbcDataSource raw;
        final CommitFaultDataSource faulty;
        final HikariDataSource pool;
        final DataSourceTransactionManager manager;
        final AbstractRelationalDataOperator<Row> operator;

        Backend(String label, String url, boolean pooled, Function<DataSource, AbstractRelationalDataOperator<Row>> factory) {
            this.label = label;
            raw = new JdbcDataSource();
            raw.setURL(url);
            raw.setUser("sa");
            faulty = new CommitFaultDataSource(raw);
            DataSource operatorSource;
            if (pooled) {
                HikariConfig config = new HikariConfig();
                config.setDataSource(faulty);
                config.setMaximumPoolSize(1);
                config.setPoolName("cou-" + label);
                pool = new HikariDataSource(config);
                closeables.add(pool);
                operatorSource = pool;
            } else {
                pool = null;
                operatorSource = faulty;
            }
            manager = new DataSourceTransactionManager(operatorSource);
            operator = factory.apply(operatorSource);
            operator.setTransactionManager(manager);
            faulty.events.clear();
        }

        /** Whether the row with {@code id} is stored, read on a connection that bypasses the wrapper. */
        boolean stored(String id) throws SQLException {
            try (Connection connection = raw.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM cou_row WHERE `id` = ?")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1) == 1;
                }
            }
        }

        /** Inserts one row in a transaction whose commit fails as {@code mode} says; returns what the caller got. */
        Throwable insertWithFailingCommit(CommitMode mode) {
            faulty.nextCommit.set(mode);
            return catchThrowable(() -> operator.transaction((Callable<Object>) () -> {
                operator.insert(new Row(ROW_ID, "written"));
                return null;
            }));
        }

        /** Waits for the pool's own background close of an evicted connection. */
        boolean closedWithin(String connection, long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (System.nanoTime() < deadline) {
                if (faulty.afterFailedCommit(connection).contains("close")) {
                    return true;
                }
                Thread.sleep(10);
            }
            return faulty.afterFailedCommit(connection).contains("close");
        }
    }

    @BeforeAll
    static void bukkit() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class);
            when(server.getLogger()).thenReturn(mock(Logger.class));
            org.mockito.Mockito.lenient().when(server.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(server.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
            Bukkit.setServer(server);
        }
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable each : closeables) {
            each.close();
        }
        closeables.clear();
    }

    private Backend sqlite(boolean pooled) {
        String url = "jdbc:h2:file:" + tempDir.resolve("sqlite-standin-" + UUID.randomUUID()).toAbsolutePath()
                + ";MODE=MySQL";
        return new Backend("sqlite" + (pooled ? "+pool1" : ""), url, pooled,
                source -> new SQLiteDataOperator<>(source, Row.class));
    }

    private Backend mysql(boolean pooled) {
        String url = "jdbc:h2:mem:cou-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        return new Backend("mysql" + (pooled ? "+pool1" : ""), url, pooled,
                source -> new MysqlDataOperator<>(source, Row.class));
    }

    private List<Backend> backends(boolean pooled) {
        List<Backend> backends = new ArrayList<>();
        backends.add(sqlite(pooled));
        backends.add(mysql(pooled));
        return backends;
    }

    @Test
    @DisplayName("acknowledgement lost after the database applied the commit: the row is stored, the caller gets TRANSACTION_FAILED")
    void commitAcknowledgementLostLeavesTheRowStored() throws Exception {
        for (Backend backend : backends(false)) {
            Throwable thrown = backend.insertWithFailingCommit(CommitMode.APPLY_THEN_LOSE_ACKNOWLEDGEMENT);

            assertThat(thrown).as(backend.label).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("Failed to commit transaction")
                    .hasCauseInstanceOf(SQLException.class);
            assertThat(((DataAccessException) thrown).getErrorCode()).as(backend.label)
                    .isEqualTo(ErrorCode.TRANSACTION_FAILED);
            assertThat(backend.stored(ROW_ID)).as("%s: row stored although the commit failed", backend.label).isTrue();
            assertThat(backend.manager.hasActiveTransaction()).as(backend.label).isFalse();
        }
    }

    @Test
    @DisplayName("acknowledgement lost under a HikariCP pool of one: evicting the connection does not undo the applied commit")
    void commitAcknowledgementLostUnderAPoolLeavesTheRowStored() throws Exception {
        for (Backend backend : backends(true)) {
            Throwable thrown = backend.insertWithFailingCommit(CommitMode.APPLY_THEN_LOSE_ACKNOWLEDGEMENT);

            assertThat(thrown).as(backend.label).isInstanceOf(DataAccessException.class)
                    .hasCauseInstanceOf(SQLException.class);
            assertThat(((DataAccessException) thrown).getErrorCode()).as(backend.label)
                    .isEqualTo(ErrorCode.TRANSACTION_FAILED);
            String failed = backend.faulty.failedCommitConnection();
            assertThat(failed).as(backend.label).isNotNull();
            assertThat(backend.closedWithin(failed, 5000)).as("%s: the evicted connection is closed", backend.label).isTrue();
            assertThat(backend.pool.getHikariPoolMXBean().getActiveConnections())
                    .as("%s: active connections", backend.label).isZero();
            assertThat(backend.stored(ROW_ID)).as("%s: row stored after eviction", backend.label).isTrue();
            assertThat(backend.faulty.commitCalls()).as("%s: commit calls", backend.label).isEqualTo(1);
            assertThat(backend.faulty.afterFailedCommit(failed)).as("%s: calls after the failed commit", backend.label)
                    .doesNotContain("setAutoCommit(true)", "commit");
        }
    }

    @Test
    @DisplayName("commit refused before it reached the database: the row is absent, the caller gets TRANSACTION_FAILED")
    void commitRefusedBeforeTheDatabaseLeavesNothing() throws Exception {
        for (Backend backend : backends(false)) {
            Throwable thrown = backend.insertWithFailingCommit(CommitMode.REFUSE_BEFORE_DATABASE);

            assertThat(thrown).as(backend.label).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("Failed to commit transaction")
                    .hasCauseInstanceOf(SQLException.class);
            assertThat(((DataAccessException) thrown).getErrorCode()).as(backend.label)
                    .isEqualTo(ErrorCode.TRANSACTION_FAILED);
            assertThat(backend.stored(ROW_ID)).as("%s: row stored", backend.label).isFalse();
            assertThat(backend.manager.hasActiveTransaction()).as(backend.label).isFalse();
        }
    }

    @Test
    @DisplayName("the caller cannot tell the two apart: same exception type, error code, message and cause type")
    void sameFailureEitherWay() throws Exception {
        List<Backend> applied = backends(false);
        List<Backend> refused = backends(false);
        for (int i = 0; i < applied.size(); i++) {
            Backend appliedBackend = applied.get(i);
            Backend refusedBackend = refused.get(i);
            Throwable afterApply = appliedBackend.insertWithFailingCommit(CommitMode.APPLY_THEN_LOSE_ACKNOWLEDGEMENT);
            Throwable afterRefusal = refusedBackend.insertWithFailingCommit(CommitMode.REFUSE_BEFORE_DATABASE);

            assertThat(afterApply).as(appliedBackend.label).isInstanceOf(DataAccessException.class);
            assertThat(afterRefusal).as(refusedBackend.label).isInstanceOf(DataAccessException.class);
            assertThat(afterApply.getClass()).as(appliedBackend.label).isEqualTo(afterRefusal.getClass());
            assertThat(((DataAccessException) afterApply).getErrorCode()).as(appliedBackend.label)
                    .isEqualTo(((DataAccessException) afterRefusal).getErrorCode());
            assertThat(afterApply.getMessage()).as(appliedBackend.label).isEqualTo(afterRefusal.getMessage());
            assertThat(afterApply.getCause().getClass()).as(appliedBackend.label)
                    .isEqualTo(afterRefusal.getCause().getClass());
            // ... while the stored data differs: that difference is what the caller cannot observe.
            assertThat(appliedBackend.stored(ROW_ID)).as(appliedBackend.label).isTrue();
            assertThat(refusedBackend.stored(ROW_ID)).as(refusedBackend.label).isFalse();
        }
    }

    @Test
    @DisplayName("either way: exactly one commit call, no auto-commit switched on after it, and the connection is discarded")
    void noRetryAndNoAutoCommitAfterAFailedCommit() throws Exception {
        for (CommitMode mode : new CommitMode[]{CommitMode.APPLY_THEN_LOSE_ACKNOWLEDGEMENT, CommitMode.REFUSE_BEFORE_DATABASE}) {
            for (Backend backend : backends(false)) {
                Throwable thrown = backend.insertWithFailingCommit(mode);

                String label = backend.label + " " + mode;
                assertThat(thrown).as(label).isInstanceOf(DataAccessException.class);
                assertThat(backend.faulty.commitCalls()).as("%s: commit calls", label).isEqualTo(1);
                String failed = backend.faulty.failedCommitConnection();
                assertThat(failed).as(label).isNotNull();
                List<String> after = backend.faulty.afterFailedCommit(failed);
                assertThat(after).as("%s: calls after the failed commit", label)
                        .doesNotContain("setAutoCommit(true)", "commit")
                        .contains("close");
                assertThat(backend.manager.hasActiveTransaction()).as(label).isFalse();
            }
        }
    }

    @Test
    @DisplayName("control, the confirmed outcome: the action fails and the rollback succeeds, nothing is stored, the caller gets the action's exception")
    void actionFailureWithSuccessfulRollbackStoresNothing() throws Exception {
        for (Backend backend : backends(false)) {
            IllegalStateException boom = new IllegalStateException("action failed");

            Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                backend.operator.insert(new Row(ROW_ID, "written"));
                throw boom;
            }));

            assertThat(thrown).as(backend.label).isSameAs(boom);
            assertThat(backend.stored(ROW_ID)).as("%s: row stored", backend.label).isFalse();
            assertThat(backend.faulty.events).as(backend.label).anyMatch(e -> e.endsWith(":rollback"));
            assertThat(backend.faulty.commitCalls()).as("%s: commit calls", backend.label).isZero();
            assertThat(backend.manager.hasActiveTransaction()).as(backend.label).isFalse();
        }
    }
}
