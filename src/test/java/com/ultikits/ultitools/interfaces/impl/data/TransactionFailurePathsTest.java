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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.MethodInvocation;
import com.ultikits.ultitools.aop.TransactionInterceptor;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.ultikits.ultitools.manager.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * UltiTools-Reborn#634: the transaction manager never commits what it was asked to roll back, and
 * {@code DataOperator#transaction(Callable)} rolls back on an {@link Error}. JDBC commits an open
 * transaction when auto-commit is switched on ({@code Connection#setAutoCommit}), so a connection
 * whose rollback or commit failed must be closed -- and discarded by a pool -- without that switch.
 * <p>
 * Faults are injected below the operator, where a driver raises them: a {@link DataSource} wrapper
 * whose connections throw {@link SQLException} from {@code rollback()} or {@code commit()} a set
 * number of times and otherwise delegate, recording what was called on them. Rows are counted on an
 * independent connection that bypasses the wrapper. The SQLite operator runs over an H2 database
 * file in MySQL mode (the SQLite JDBC driver is not on the test classpath and this plan adds no
 * dependency); the MySQL operator over an in-memory H2 database in MySQL mode.
 * <br>
 * #634：回滚或提交失败后不再打开自动提交（JDBC 会因此提交事务），连接被关闭并从连接池中剔除；
 * {@code transaction(Callable)} 遇到 {@link Error} 时回滚并原样抛出。
 */
@DisplayName("#634 transaction failure paths never end in an implicit commit")
class TransactionFailurePathsTest {

    @TempDir
    Path tempDir;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    /** An {@link Error} the tests own, so no JVM condition has to be provoked. */
    static final class TestError extends Error {
        private static final long serialVersionUID = 1L;

        TestError(String message) {
            super(message);
        }
    }

    @Table("tfp_row")
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

    /** Failures to inject and the calls observed, shared by every connection of one wrapper. */
    static final class Faults {
        final AtomicInteger rollbackFailures = new AtomicInteger();
        final AtomicInteger commitFailures = new AtomicInteger();
        /** The id-assignment UPDATE number (1-based) that throws, or 0. */
        volatile int failIdAssignment;
        final AtomicInteger idAssignments = new AtomicInteger();
        final List<String> events = new CopyOnWriteArrayList<>();

        /** The calls recorded after the first injected failure. */
        List<String> afterFirstFailure() {
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).endsWith("-failed")) {
                    return new ArrayList<>(events.subList(i + 1, events.size()));
                }
            }
            return new ArrayList<>();
        }

        long count(String event) {
            return events.stream().filter(event::equals).count();
        }
    }

    /** A data source whose connections fail as {@link Faults} says and record their calls. */
    static final class FaultyDataSource implements DataSource {
        private final DataSource target;
        final Faults faults = new Faults();

        FaultyDataSource(DataSource target) {
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
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                boolean noArgs = args == null || args.length == 0;
                if ("rollback".equals(name) && noArgs) {
                    if (faults.rollbackFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        faults.events.add("rollback-failed");
                        throw new SQLException("injected rollback failure");
                    }
                    faults.events.add("rollback");
                } else if ("commit".equals(name)) {
                    if (faults.commitFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        faults.events.add("commit-failed");
                        throw new SQLException("injected commit failure");
                    }
                    faults.events.add("commit");
                } else if ("setAutoCommit".equals(name)) {
                    faults.events.add("setAutoCommit(" + args[0] + ")");
                } else if ("close".equals(name)) {
                    faults.events.add("close");
                } else if ("abort".equals(name)) {
                    faults.events.add("abort");
                }
                Object result = invoke(connection, method, args);
                if ("prepareStatement".equals(name) && result instanceof PreparedStatement
                        && String.valueOf(args[0]).contains("SET `id` = ?")) {
                    return failingIdAssignment((PreparedStatement) result);
                }
                return result;
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement failingIdAssignment(PreparedStatement statement) {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("executeUpdate".equals(method.getName()) && (args == null || args.length == 0)) {
                    int number = faults.idAssignments.incrementAndGet();
                    if (number == faults.failIdAssignment) {
                        faults.events.add("id-assignment-failed");
                        throw new SQLException("injected id assignment failure");
                    }
                }
                return invoke(statement, method, args);
            };
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
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

    /** One backend: the raw database, the faulty wrapper over it, and the operator under test. */
    final class Backend {
        final String label;
        final JdbcDataSource raw;
        final FaultyDataSource faulty;
        final DataSource operatorSource;
        final DataSourceTransactionManager manager;
        final AbstractRelationalDataOperator<Row> operator;
        final HikariDataSource pool;

        Backend(String label, String url, boolean pooled,
                Function<DataSource, AbstractRelationalDataOperator<Row>> factory) {
            this.label = label;
            raw = new JdbcDataSource();
            raw.setURL(url);
            raw.setUser("sa");
            faulty = new FaultyDataSource(raw);
            if (pooled) {
                HikariConfig config = new HikariConfig();
                config.setDataSource(faulty);
                config.setMaximumPoolSize(2);
                config.setPoolName("tfp-" + label);
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
            faulty.faults.events.clear();
        }

        int rows() throws SQLException {
            try (Connection connection = raw.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM tfp_row")) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    @BeforeAll
    static void bukkit() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class);
            when(server.getLogger()).thenReturn(mock(Logger.class));
            Bukkit.setServer(server);
        }
    }

    @BeforeEach
    void reset() {
        closeables.clear();
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable each : closeables) {
            each.close();
        }
    }

    private Backend sqlite(boolean pooled) {
        String url = "jdbc:h2:file:" + tempDir.resolve("sqlite-standin-" + UUID.randomUUID()).toAbsolutePath()
                + ";MODE=MySQL";
        return new Backend("sqlite" + (pooled ? "+pool" : ""), url, pooled,
                source -> new SQLiteDataOperator<>(source, Row.class));
    }

    private Backend mysql(boolean pooled) {
        String url = "jdbc:h2:mem:tfp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        return new Backend("mysql" + (pooled ? "+pool" : ""), url, pooled,
                source -> new MysqlDataOperator<>(source, Row.class));
    }

    private List<Backend> backends(boolean pooled) {
        List<Backend> backends = new ArrayList<>();
        backends.add(sqlite(pooled));
        backends.add(mysql(pooled));
        return backends;
    }

    private static void assertNoImplicitCommit(Backend backend) {
        List<String> after = backend.faulty.faults.afterFirstFailure();
        assertThat(after).as("%s: calls after the injected failure", backend.label)
                .doesNotContain("setAutoCommit(true)", "commit");
    }

    @Nested
    @DisplayName("a failed rollback")
    class FailedRollback {

        @Test
        @DisplayName("the written row is not stored, the caller gets its exception, auto-commit is not switched on, the connection is closed")
        void failedRollbackDoesNotCommit() throws Exception {
            for (Backend backend : backends(false)) {
                backend.faulty.faults.rollbackFailures.set(1);
                IllegalStateException boom = new IllegalStateException("action failed");

                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r1", "written"));
                    throw boom;
                }));

                assertThat(thrown).as(backend.label).isSameAs(boom);
                assertThat(backend.rows()).as("%s: rows stored", backend.label).isZero();
                assertNoImplicitCommit(backend);
                assertThat(backend.faulty.faults.afterFirstFailure()).as(backend.label).contains("close");
                assertThat(backend.manager.hasActiveTransaction()).as(backend.label).isFalse();
            }
        }

        @Test
        @DisplayName("with a HikariCP pool: nothing stored, zero active connections, and the next transaction commits only its own row")
        void failedRollbackUnderAPoolLeavesNothing() throws Exception {
            for (Backend backend : backends(true)) {
                backend.faulty.faults.rollbackFailures.set(1);

                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r1", "written"));
                    throw new IllegalStateException("action failed");
                }));

                assertThat(thrown).as(backend.label).isInstanceOf(IllegalStateException.class);
                // No event assertion here: HikariCP's own close() rolls the connection back and then
                // resets its auto-commit, and its housekeeping opens connections in the background;
                // what counts is that nothing was stored.
                assertThat(backend.rows()).as("%s: rows stored", backend.label).isZero();
                assertThat(backend.pool.getHikariPoolMXBean().getActiveConnections())
                        .as("%s: active connections", backend.label).isZero();

                backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r2", "next"));
                    return null;
                });
                assertThat(backend.rows()).as("%s: rows after the next transaction", backend.label).isEqualTo(1);
                assertThat(backend.operator.getById("r1")).as(backend.label).isNull();
            }
        }
    }

    @Nested
    @DisplayName("a failed commit, and the commit of a transaction a nested scope marked rollback-only")
    class FailedCommitAndRollbackOnly {

        @Test
        @DisplayName("a commit that fails once: the caller gets the failure, nothing is stored, no second commit follows")
        void failedCommitDoesNotCommitLater() throws Exception {
            for (Backend backend : backends(false)) {
                backend.faulty.faults.commitFailures.set(1);

                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r1", "written"));
                    return null;
                }));

                assertThat(thrown).as(backend.label).isInstanceOf(DataAccessException.class);
                assertThat(backend.rows()).as("%s: rows stored", backend.label).isZero();
                assertThat(backend.faulty.faults.count("commit")).as("%s: successful commits", backend.label).isZero();
                assertNoImplicitCommit(backend);
                assertThat(backend.manager.hasActiveTransaction()).as(backend.label).isFalse();
            }
        }

        @Test
        @DisplayName("rollback-only, its real rollback failing once: UnexpectedRollbackException, nothing stored")
        void rollbackOnlyWithAFailedRollbackStoresNothing() throws Exception {
            for (Backend backend : backends(false)) {
                backend.faulty.faults.rollbackFailures.set(1);

                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("outer", "outer"));
                    catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                        backend.operator.insert(new Row("inner", "inner"));
                        throw new IllegalStateException("inner failed");
                    }));
                    return null;
                }));

                assertThat(thrown).as(backend.label).isInstanceOf(UnexpectedRollbackException.class);
                assertThat(backend.rows()).as("%s: rows stored", backend.label).isZero();
                assertNoImplicitCommit(backend);
            }
        }

        @Test
        @DisplayName("control: rollback-only without an injected failure rolls back as before")
        void rollbackOnlyWithoutFailure() throws Exception {
            for (Backend backend : backends(false)) {
                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("outer", "outer"));
                    catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                        throw new IllegalStateException("inner failed");
                    }));
                    return null;
                }));

                assertThat(thrown).as(backend.label).isInstanceOf(UnexpectedRollbackException.class);
                assertThat(backend.rows()).as(backend.label).isZero();
            }
        }
    }

    @Nested
    @DisplayName("an Error inside transaction(...) on the relational operators")
    class ErrorOnRelational {

        @Test
        @DisplayName("rolls back, rethrows the same Error, leaves no context; the next transaction starts at depth 1")
        void errorRollsBackAndIsRethrown() throws Exception {
            for (Backend backend : backends(false)) {
                TestError error = new TestError("out of something");

                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r1", "written"));
                    throw error;
                }));

                assertThat(thrown).as(backend.label).isSameAs(error);
                assertThat(backend.manager.hasActiveTransaction()).as("%s: context after the Error", backend.label)
                        .isFalse();
                assertThat(backend.rows()).as("%s: rows stored", backend.label).isZero();

                int[] depth = new int[1];
                backend.operator.transaction((Callable<Object>) () -> {
                    depth[0] = backend.manager.getTransactionDepth();
                    backend.operator.insert(new Row("r2", "next"));
                    return null;
                });
                assertThat(depth[0]).as("%s: depth of the next transaction", backend.label).isEqualTo(1);
                assertThat(backend.rows()).as(backend.label).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the Runnable overload lets the Error through unwrapped, after the rollback")
        void runnableOverloadRethrowsTheError() throws Exception {
            for (Backend backend : backends(false)) {
                TestError error = new TestError("out of something");

                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Runnable) () -> {
                    backend.operator.insert(new Row("r1", "written"));
                    throw error;
                }));

                assertThat(thrown).as(backend.label).isSameAs(error);
                assertThat(backend.manager.hasActiveTransaction()).as(backend.label).isFalse();
                assertThat(backend.rows()).as(backend.label).isZero();
            }
        }

        @Test
        @DisplayName("a rollback that throws while an Error or an exception is handled: the original reaches the caller, the rollback failure is suppressed")
        void rollbackFailureIsSuppressed() throws Exception {
            Backend backend = mysql(false);
            IllegalStateException rollbackBroke = new IllegalStateException("rollback broke");
            DataSourceTransactionManager throwing = new DataSourceTransactionManager(backend.operatorSource) {
                @Override
                public void rollback() {
                    super.rollback();
                    throw rollbackBroke;
                }
            };
            backend.operator.setTransactionManager(throwing);

            TestError error = new TestError("out of something");
            Throwable fromError = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                throw error;
            }));
            assertThat(fromError).isSameAs(error);
            assertThat(fromError.getSuppressed()).containsExactly(rollbackBroke);

            IllegalStateException boom = new IllegalStateException("action failed");
            Throwable fromException = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                throw boom;
            }));
            assertThat(fromException).isSameAs(boom);
            assertThat(fromException.getSuppressed()).containsExactly(rollbackBroke);
        }
    }

    @Nested
    @DisplayName("an Error inside transaction(...) on the JSON operator")
    class ErrorOnJson {

        @Test
        @DisplayName("restores the snapshot and rethrows the same Error, on both overloads")
        void jsonRestoresTheSnapshot() {
            SimpleJsonDataOperator<Row> operator = new SimpleJsonDataOperator<>(
                    tempDir.resolve("json").toString(), Row.class);
            operator.insert(new Row("kept", "kept"));
            TestError error = new TestError("out of something");

            Throwable thrown = catchThrowable(() -> operator.transaction((Callable<Object>) () -> {
                operator.insert(new Row("written", "written"));
                throw error;
            }));
            assertThat(thrown).isSameAs(error);
            assertThat(operator.getById("written")).as("entry written before the Error").isNull();

            Throwable fromRunnable = catchThrowable(() -> operator.transaction((Runnable) () -> {
                operator.insert(new Row("written", "written"));
                throw error;
            }));
            assertThat(fromRunnable).isSameAs(error);
            assertThat(operator.getById("written")).as("entry written before the Error (Runnable)").isNull();
            assertThat(operator.getById("kept")).isNotNull();
        }
    }

    @Nested
    @DisplayName("the startup id backfill")
    class StartupIdBackfill {

        @Test
        @DisplayName("an id assignment fails and the rollback fails: no partial assignment is stored")
        void failedBackfillRollbackStoresNoPartialAssignment() throws Exception {
            JdbcDataSource raw = new JdbcDataSource();
            raw.setURL("jdbc:h2:file:" + tempDir.resolve("backfill").toAbsolutePath() + ";MODE=MySQL");
            raw.setUser("sa");
            try (Connection connection = raw.getConnection(); Statement statement = connection.createStatement()) {
                // The table as 6.2.0 left it on SQLite: no NOT NULL on the id (H2 refuses NULL in a
                // PRIMARY KEY, so the key is left out, as NullIdRowsTest does).
                statement.execute("CREATE TABLE tfp_row (`id` VARCHAR(255), `name` VARCHAR(255))");
                statement.execute("INSERT INTO tfp_row (`id`, `name`) VALUES (NULL, 'a'), (NULL, 'b'), (NULL, 'c')");
            }
            FaultyDataSource faulty = new FaultyDataSource(raw);
            faulty.faults.failIdAssignment = 2;
            faulty.faults.rollbackFailures.set(1);

            new SQLiteDataOperator<>(faulty, Row.class);

            try (Connection connection = raw.getConnection(); Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM tfp_row WHERE `id` IS NOT NULL")) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).as("rows given an id").isZero();
            }
            assertThat(faulty.faults.events).contains("id-assignment-failed", "rollback-failed");
            assertThat(faulty.faults.afterFirstFailure()).doesNotContain("commit");
            List<String> afterRollback = new ArrayList<>(faulty.faults.events.subList(
                    faulty.faults.events.indexOf("rollback-failed") + 1, faulty.faults.events.size()));
            assertThat(afterRollback).as("calls after the failed rollback").doesNotContain("setAutoCommit(true)");
        }
    }

    @Nested
    @DisplayName("controls")
    class Controls {

        @Test
        @DisplayName("no injected failure: an exception rolls back; a success commits, restores auto-commit and closes")
        void withoutFailuresAsBefore() throws Exception {
            for (Backend backend : backends(false)) {
                Throwable thrown = catchThrowable(() -> backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r1", "written"));
                    throw new IllegalStateException("action failed");
                }));
                assertThat(thrown).as(backend.label).isInstanceOf(IllegalStateException.class);
                assertThat(backend.rows()).as(backend.label).isZero();
                List<String> afterRollback = new ArrayList<>(backend.faulty.faults.events.subList(
                        backend.faulty.faults.events.indexOf("rollback"), backend.faulty.faults.events.size()));
                assertThat(afterRollback).as(backend.label).containsSubsequence("rollback", "setAutoCommit(true)", "close");

                backend.faulty.faults.events.clear();
                backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r2", "kept"));
                    return null;
                });
                assertThat(backend.rows()).as(backend.label).isEqualTo(1);
                assertThat(backend.faulty.faults.events).as(backend.label)
                        .containsSubsequence("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");
            }
        }

        @Test
        @DisplayName("with a pool and no failure, a transaction leaves zero active connections")
        void pooledWithoutFailures() throws Exception {
            for (Backend backend : backends(true)) {
                backend.operator.transaction((Callable<Object>) () -> {
                    backend.operator.insert(new Row("r1", "kept"));
                    return null;
                });
                assertThat(backend.rows()).as(backend.label).isEqualTo(1);
                assertThat(backend.pool.getHikariPoolMXBean().getActiveConnections()).as(backend.label).isZero();
            }
        }

        @Test
        @DisplayName("@Transactional already rolls back on an Error, on a real H2 data source")
        void transactionalRollsBackOnError() throws Throwable {
            Backend backend = mysql(false);
            TransactionInterceptor interceptor = new TransactionInterceptor(backend.manager);
            Method method = Service.class.getMethod("write");
            TestError error = new TestError("out of something");
            MethodInvocation invocation = new MethodInvocation() {
                @Override
                public Object getTarget() {
                    return new Service();
                }

                @Override
                public Method getMethod() {
                    return method;
                }

                @Override
                public Object[] getArguments() {
                    return new Object[0];
                }

                @Override
                public Object proceed() {
                    backend.operator.insert(new Row("r1", "written"));
                    throw error;
                }
            };

            Throwable thrown = catchThrowable(() -> interceptor.invoke(invocation));

            assertThat(thrown).isSameAs(error);
            assertThat(backend.rows()).isZero();
            assertThat(backend.manager.hasActiveTransaction()).isFalse();
        }
    }

    /** A {@code @Transactional} method for the interceptor control. */
    public static class Service {
        @Transactional
        public void write() {
            // the invocation above stands in for the body
        }
    }
}
