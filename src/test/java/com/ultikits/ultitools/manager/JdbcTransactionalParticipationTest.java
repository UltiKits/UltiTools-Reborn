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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
import com.ultikits.ultitools.annotations.Propagation;
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
 * {@link MysqlDataOperator} over an in-memory H2 database in MySQL mode (H2 keeps these cases
 * independent of the SQLite driver; real-driver behaviour is covered by the {@code Relational*Test}
 * classes). Each backend gets its own container, wired while {@code UltiTools.getDataStore()} answers
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
            org.mockito.Mockito.lenient().when(server.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(server.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
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

    // ===== SUPPORTS and MANDATORY participate like REQUIRED =====

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined SUPPORTS method's failure caught by the outer marks the transaction: UnexpectedRollbackException, nothing stored")
    void joinedSupportsFailureCaughtByTheOuterMarksIt(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::outerCatchesAJoinedSupportsFailure);

        assertThat(thrown).as("%s: the outer call", backend.label)
                .isInstanceOf(UnexpectedRollbackException.class);
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined MANDATORY method's failure caught by the outer marks the transaction: UnexpectedRollbackException, nothing stored")
    void joinedMandatoryFailureCaughtByTheOuterMarksIt(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::outerCatchesAJoinedMandatoryFailure);

        assertThat(thrown).as("%s: the outer call", backend.label)
                .isInstanceOf(UnexpectedRollbackException.class);
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);
    }

    // ===== The joined method's own rules decide the mark =====

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined failure matching the inner method's rollbackFor (a checked exception) marks the transaction: UnexpectedRollbackException, nothing stored")
    void joinedFailureMatchingTheInnerRollbackForMarksIt(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::outerCatchesAMatchingRollbackForFailure);

        assertThat(thrown).as("%s: the outer call", backend.label)
                .isInstanceOf(UnexpectedRollbackException.class);
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined checked failure under the default rules leaves the transaction committable: both writes stored")
    void joinedCheckedFailureUnderDefaultRulesLeavesItCommittable(String backendName) {
        Backend backend = backend(backendName);

        backend.bean.outerCatchesACheckedFailureUnderDefaultRules();

        assertStored(backend, 1, 1);
        assertNoTransactionLeft(backend);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined failure listed in the inner method's noRollbackFor leaves the transaction committable: both writes stored")
    void joinedFailureInTheInnerNoRollbackForLeavesItCommittable(String backendName) {
        Backend backend = backend(backendName);

        backend.bean.outerCatchesANoRollbackForFailure();

        assertStored(backend, 1, 1);
        assertNoTransactionLeft(backend);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("A joined failure that leaves the outer method rolls everything back and reaches the caller as the original exception instance")
    void joinedFailureLeavingTheOuterRollsBackWithTheOriginalException(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::outerLetsAJoinedFailureLeave);

        assertThat(thrown).as("%s: the caller receives the inner method's exception itself", backend.label)
                .isNotNull().isSameAs(backend.bean.lastInnerFailure);
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);
    }

    // ===== The outermost commit completes once =====

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("The outer commit of a marked transaction completes it once: no 'no active transaction' WARNING after a joined failure or a failed nested transaction(...) scope")
    void outerCommitOfAMarkedTransactionCompletesOnce(String backendName) {
        Backend backend = backend(backendName);

        List<String> afterJoinedFailure = warningsDuring(
                () -> catchThrowable(backend.bean::outerCatchesAJoinedRequiredFailure));
        assertThat(afterJoinedFailure).as("%s: manager WARNINGs after a joined failure", backend.label)
                .noneMatch(message -> message.contains("no active"));
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);

        Throwable[] nested = new Throwable[1];
        List<String> afterNestedScope = warningsDuring(
                () -> nested[0] = catchThrowable(backend.bean::outerSwallowsAFailedNestedScope));
        assertThat(nested[0]).as("%s: the outer call after a failed nested scope", backend.label)
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(afterNestedScope).as("%s: manager WARNINGs after a failed nested scope", backend.label)
                .noneMatch(message -> message.contains("no active"));
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("An outer method whose own noRollbackFor commits despite its exception after a mark: the caller receives UnexpectedRollbackException with that exception as suppressed")
    void outerThatCommitsDespiteItsOwnExceptionAfterAMarkThrowsUnexpectedRollback(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::outerCommitsDespiteItsOwnExceptionAfterAMark);

        assertThat(thrown).as("%s: the outer call", backend.label)
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(thrown.getSuppressed()).as("%s: the outer method's own exception, kept as suppressed", backend.label)
                .containsExactly(backend.bean.lastOuterFailure);
        assertStored(backend, 0, 0);
        assertNoTransactionLeft(backend);
    }

    // ===== Without a transaction =====

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("SUPPORTS without a transaction marks nothing: its write stays and a following REQUIRED call commits normally")
    void supportsWithoutATransactionMarksNothing(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::innerSupportsWritesThenFails);
        assertThat(thrown).as("%s: the SUPPORTS method's own failure", backend.label)
                .isInstanceOf(IllegalStateException.class);

        backend.bean.requiredWritesAlone();

        assertStored(backend, 1, 1);
        assertNoTransactionLeft(backend);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SQLITE, MYSQL})
    @DisplayName("MANDATORY without a transaction is still refused and does not run")
    void mandatoryWithoutATransactionIsRefused(String backendName) {
        Backend backend = backend(backendName);

        Throwable thrown = catchThrowable(backend.bean::innerMandatoryWritesThenFails);

        assertThat(thrown).as("%s: the refusal", backend.label)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MANDATORY");
        assertStored(backend, 0, 0);
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

    /**
     * Runs {@code action} while a handler on {@link DataSourceTransactionManager}'s logger records
     * the messages of WARNING and more severe records; the handler is removed afterwards.
     */
    private static List<String> warningsDuring(Runnable action) {
        Logger logger = Logger.getLogger(DataSourceTransactionManager.class.getName());
        WarningRecorder recorder = new WarningRecorder();
        logger.addHandler(recorder);
        try {
            action.run();
        } finally {
            logger.removeHandler(recorder);
        }
        return recorder.messages;
    }

    /** Records the messages of WARNING and more severe log records. */
    static final class WarningRecorder extends Handler {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                messages.add(String.valueOf(logRecord.getMessage()));
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
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

    /** A checked exception the fixtures throw. */
    public static class CheckedTestException extends Exception {
        private static final long serialVersionUID = 1L;

        public CheckedTestException(String message) {
            super(message);
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
        volatile RuntimeException lastInnerFailure;
        volatile IllegalArgumentException lastOuterFailure;

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

        @Transactional
        public void requiredWritesAlone() {
            rows.insert(new ParticipationRow(OUTER_ID, "alone"));
        }

        // --- SUPPORTS and MANDATORY ---

        @Transactional
        public void outerCatchesAJoinedSupportsFailure() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                innerSupportsWritesThenFails();
            } catch (IllegalStateException expected) {
                // The joined SUPPORTS method's failure has marked the transaction rollback-only.
            }
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public void innerSupportsWritesThenFails() {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
            throw new IllegalStateException("the SUPPORTS method fails");
        }

        @Transactional
        public void outerCatchesAJoinedMandatoryFailure() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                innerMandatoryWritesThenFails();
            } catch (IllegalStateException expected) {
                // The joined MANDATORY method's failure has marked the transaction rollback-only.
            }
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public void innerMandatoryWritesThenFails() {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
            throw new IllegalStateException("the MANDATORY method fails");
        }

        // --- The joined method's own rules ---

        @Transactional
        public void outerCatchesAMatchingRollbackForFailure() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                innerRollbackForWritesThenFails();
            } catch (CheckedTestException expected) {
                // rollbackFor accepts it: the transaction is marked rollback-only.
            }
        }

        @Transactional(rollbackFor = CheckedTestException.class)
        public void innerRollbackForWritesThenFails() throws CheckedTestException {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
            throw new CheckedTestException("the joined method fails with a rollbackFor type");
        }

        @Transactional
        public void outerCatchesACheckedFailureUnderDefaultRules() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                innerDefaultRulesWritesThenFailsChecked();
            } catch (CheckedTestException expected) {
                // The default rules do not accept a checked exception: the transaction stays committable.
            }
        }

        @Transactional
        public void innerDefaultRulesWritesThenFailsChecked() throws CheckedTestException {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
            throw new CheckedTestException("the joined method fails with a checked exception");
        }

        @Transactional
        public void outerCatchesANoRollbackForFailure() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                innerNoRollbackForWritesThenFails();
            } catch (IllegalStateException expected) {
                // noRollbackFor lists it: the transaction stays committable.
            }
        }

        @Transactional(noRollbackFor = IllegalStateException.class)
        public void innerNoRollbackForWritesThenFails() {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
            throw new IllegalStateException("the joined method fails with a noRollbackFor type");
        }

        // --- A failure that leaves the outer method ---

        @Transactional
        public void outerLetsAJoinedFailureLeave() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            innerRequiredWritesThenFailsRemembered();
        }

        @Transactional
        public void innerRequiredWritesThenFailsRemembered() {
            others.insert(new ParticipationOther(INNER_ID, "inner"));
            IllegalStateException failure = new IllegalStateException("the joined method fails and its caller does not catch");
            lastInnerFailure = failure;
            throw failure;
        }

        // --- The outermost commit ---

        @Transactional
        public void outerSwallowsAFailedNestedScope() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                rows.transaction((Runnable) () -> {
                    others.insert(new ParticipationOther(INNER_ID, "inner"));
                    throw new IllegalStateException("the nested transaction(...) scope fails");
                });
            } catch (IllegalStateException expected) {
                // The nested scope's rollback has marked the transaction rollback-only.
            }
        }

        @Transactional(noRollbackFor = IllegalArgumentException.class)
        public void outerCommitsDespiteItsOwnExceptionAfterAMark() {
            rows.insert(new ParticipationRow(OUTER_ID, "outer"));
            try {
                innerRequiredWritesThenFails();
            } catch (IllegalStateException expected) {
                // The joined method's failure has marked the transaction rollback-only.
            }
            IllegalArgumentException failure = new IllegalArgumentException("the outer method fails after the mark");
            lastOuterFailure = failure;
            throw failure;
        }
    }
}
