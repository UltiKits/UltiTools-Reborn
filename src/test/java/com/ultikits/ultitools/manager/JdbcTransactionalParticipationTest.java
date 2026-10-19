package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.interfaces.impl.data.AbstractRelationalDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;

/**
 * The participant rule of 20.4-DECISIONS.md Question 9 (answer A, Spring's
 * {@code globalRollbackOnParticipationFailure = true}) on the two JDBC operators, through the
 * production {@link PluginManager#wireAop(SimpleContainer, DataScope)}.
 * <p>
 * A {@code @Transactional} method that joins a transaction already active on the thread and throws
 * an exception its own rules accept marks the whole transaction rollback-only, even when the caller
 * catches the exception; the outermost commit then rolls everything back and throws
 * {@link UnexpectedRollbackException}.
 * <p>
 * Every case runs on both stand-ins, and every assertion message names the backend: "sqlite" is a
 * {@link SQLiteDataOperator} over an H2 file database in MySQL mode, "mysql" a
 * {@link MysqlDataOperator} over an in-memory H2 database in MySQL mode (the SQLite JDBC driver is
 * supplied by Paper at runtime and is not on the test classpath, as {@code TransactionFailurePathsTest}
 * records). Each backend gets its own container, wired while {@code UltiTools.getDataStore()} answers
 * a fake JDBC store over that backend's data source; the two operators join the
 * {@link TransactionManager} the container registered. The proxy is the bean's subclass, so each
 * outer method calls its inner method on itself. Rows are counted through the operators after each
 * case, with no transaction active.
 */
@DisplayName("A joined @Transactional method's failure marks the transaction rollback-only on SQLite and MySQL (20.4-13)")
class JdbcTransactionalParticipationTest {

    private static final String SQLITE = "sqlite";
    private static final String MYSQL = "mysql";
    private static final String ROWS = "participation_rows";
    private static final String OTHERS = "participation_others";
    private static final String OUTER_ID = "outer";
    private static final String INNER_ID = "inner";

    @TempDir
    Path tempDir;

    private final List<Backend> opened = new ArrayList<>();

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
        opened.clear();
    }

    @AfterEach
    void close() throws SQLException {
        for (Backend backend : opened) {
            backend.close();
        }
    }

    // ===== REQUIRED =====

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined REQUIRED method's failure caught by the outer marks the transaction rollback-only: the outer commit throws UnexpectedRollbackException and stores nothing")
    void joinedRequiredFailureCaughtByTheOuterMarksItRollbackOnly(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::outerCatchesAJoinedRequiredFailure);

        assertThat(thrown).as("%s: the outer call", backend.label)
                .isInstanceOf(UnexpectedRollbackException.class);
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined REQUIRED method that returns normally: the outer commit stores both writes")
    void joinedRequiredSuccessCommitsBoth(String backendName) {
        Backend backend = backend(backendName);

        backend.bean.outerCallsAJoinedRequiredThatSucceeds();

        assertStored(backend, 1, 1);
        assertNoTransactionLeft(backend);
    }

    // ===== Helpers =====

    /**
     * Opens the named stand-in: {@value #SQLITE} is a {@link SQLiteDataOperator} over an H2 file
     * database, {@value #MYSQL} a {@link MysqlDataOperator} over an in-memory H2 database, both in
     * MySQL mode. Closed after the case.
     */
    private Backend backend(String name) {
        boolean sqlite = SQLITE.equals(name);
        String url = sqlite
                ? "jdbc:h2:file:" + tempDir.resolve("sqlite-standin-" + UUID.randomUUID()).toAbsolutePath()
                        + ";MODE=MySQL"
                : "jdbc:h2:mem:participation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        Backend backend = new Backend(name, url, sqlite, tempDir.resolve(name).toFile());
        opened.add(backend);
        return backend;
    }

    private static void assertStored(Backend backend, int expectedRows, int expectedOthers) {
        assertThat(backend.manager.hasActiveTransaction())
                .as("%s: counting runs with no transaction active", backend.label).isFalse();
        assertThat(backend.rows.getAll()).as("%s: rows stored in %s", backend.label, ROWS).hasSize(expectedRows);
        assertThat(backend.others.getAll()).as("%s: rows stored in %s", backend.label, OTHERS)
                .hasSize(expectedOthers);
    }

    private static void assertNoTransactionLeft(Backend backend) {
        assertThat(backend.manager.hasActiveTransaction())
                .as("%s: a transaction is still active on the test thread", backend.label).isFalse();
        assertThat(backend.manager.getTransactionDepth())
                .as("%s: the test thread's transaction depth", backend.label).isZero();
    }

    private static <T extends BaseDataEntity<String>> AbstractRelationalDataOperator<T> operator(
            boolean sqlite, DataSource dataSource, Class<T> type) {
        return sqlite ? new SQLiteDataOperator<>(dataSource, type) : new MysqlDataOperator<>(dataSource, type);
    }

    /** One backend: its database, a container wired over it, the bean, and the two operators. */
    static final class Backend {
        final String label;
        final JdbcDataSource dataSource;
        final TransactionManager manager;
        final DataOperator<ParticipationRow> rows;
        final DataOperator<ParticipationOther> others;
        final ParticipationBean bean;

        Backend(String label, String url, boolean sqlite, File dataFolder) {
            this.label = label;
            dataSource = new JdbcDataSource();
            dataSource.setURL(url);
            dataSource.setUser("sa");

            SimpleContainer context = new SimpleContainer();
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                UltiTools mockUltiTools = mock(UltiTools.class);
                when(mockUltiTools.getDataStore()).thenReturn(new FakeJdbcStore(dataSource));
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                PluginManager.wireAop(context,
                        DataScope.forExternal("participation-" + label, dataFolder, Collections.emptySet()));
            }
            context.registerBean(ParticipationBean.class);
            context.refresh();
            manager = context.getBean(TransactionManager.class);

            AbstractRelationalDataOperator<ParticipationRow> rowOperator =
                    operator(sqlite, dataSource, ParticipationRow.class);
            rowOperator.setTransactionManager(manager);
            AbstractRelationalDataOperator<ParticipationOther> otherOperator =
                    operator(sqlite, dataSource, ParticipationOther.class);
            otherOperator.setTransactionManager(manager);
            rows = rowOperator;
            others = otherOperator;

            bean = context.getBean(ParticipationBean.class);
            bean.rows = rows;
            bean.others = others;
        }

        void close() throws SQLException {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    /** A third-party JDBC store whose data source is the backend's, as in TransactionalRollbackEndToEndTest. */
    static final class FakeJdbcStore implements DataStore {
        private final DataSource dataSource;

        FakeJdbcStore(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public String getStoreType() {
            return "fake-jdbc";
        }

        @Override
        public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin,
                Class<T> dataEntity) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public DataSource getDataSource(DataScope scope) {
            return dataSource;
        }

        @Override
        public void destroyAllOperators() {
            // intentional no-op: the operators are built and closed by the test itself
        }
    }

    // ===== Fixtures =====

    @Table(ROWS)
    public static class ParticipationRow extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("name")
        private String name;

        public ParticipationRow() {
        }

        public ParticipationRow(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @Table(OTHERS)
    public static class ParticipationOther extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("label")
        private String label;

        public ParticipationOther() {
        }

        public ParticipationOther(String id, String label) {
            setId(id);
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * The participation fixture. Declared at the top level of the test (not inside a
     * {@code @Nested} class) for the Java 8 reason {@code TransactionInterceptorTest} documents on
     * its {@code SelfInvocationBean}. Every outer method calls its inner method on itself; the
     * proxy is the bean's subclass, so the call is intercepted.
     */
    public static class ParticipationBean {
        volatile DataOperator<ParticipationRow> rows;
        volatile DataOperator<ParticipationOther> others;

        // --- REQUIRED ---

        @Transactional
        public void outerCatchesAJoinedRequiredFailure() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                innerRequiredWritesThenFails();
            } catch (IllegalStateException expected) {
                // The joined method's failure has marked the transaction rollback-only.
            }
        }

        @Transactional
        public void innerRequiredWritesThenFails() {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
            throw new IllegalStateException("the joined REQUIRED method fails");
        }

        @Transactional
        public void outerCallsAJoinedRequiredThatSucceeds() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            innerRequiredWrites();
        }

        @Transactional
        public void innerRequiredWrites() {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
        }
    }
}
