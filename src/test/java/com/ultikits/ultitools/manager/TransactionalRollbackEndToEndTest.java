package com.ultikits.ultitools.manager;

// NOTE: PLAN DEVIATION (Rule 3 - blocking issue). 02-01-PLAN.md's frontmatter names this file's
// path as src/test/java/com/ultikits/ultitools/aop/TransactionalRollbackEndToEndTest.java, but the
// test must call PluginManager.wireAop(SimpleContainer, DataScope) and DataScope.forExternal(...),
// both package-private to com.ultikits.ultitools.manager. A test class in the aop package cannot
// see either member (package-private access is not inherited across sibling packages), so the file
// could not compile there without widening internal API visibility purely for test convenience.
// Placed in the manager package instead, following the existing precedent of
// PluginManagerAopWiringTest (also manager-package, also drives wireAop directly). Recorded in the
// plan's SUMMARY as a deviation.

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * End-to-end proof for Phase 2 Plan 1's tracer task: a {@code @Transactional} method on a bean
 * built by {@link PluginManager#wireAop(SimpleContainer, DataScope)} opens a real JDBC transaction
 * through the real proxy and really rolls it back, exercised through both an external call and a
 * self-invocation call.
 * <p>
 * This is deliberately one end-to-end test, not four unit tests: it drives the whole path
 * {@code DataScope -> DataStore -> DataSource -> DataSourceTransactionManager -> wireAop advisor
 * -> TransactionInterceptor -> real JDBC rollback}, using an in-memory H2 database standing in for
 * SQLite (the SQLite JDBC driver is supplied by Paper at runtime and is not on the test classpath).
 */
@DisplayName("wireAop -> @Transactional -> real JDBC rollback (Phase 2 Plan 1 tracer)")
class TransactionalRollbackEndToEndTest {

    private static DataSource h2DataSource;

    private DataStore fakeDataStore;
    private MockedStatic<UltiTools> ultiToolsMock;
    private DataScope scope;

    @BeforeAll
    static void initDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:txe2e;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // Deliberately no setPassword() call: the in-memory DB is created password-less on first
        // connection, and passing an empty string reads as a hardcoded credential to static
        // analysis (Codacy/Semgrep). There is no credential here.
        h2DataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = h2DataSource.getConnection();
                Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS tx_test");
            st.execute("CREATE TABLE tx_test (id INT PRIMARY KEY)");
        }

        // The DataStore extension point this task's D-17 seam exists for: a third-party
        // implementation whose getDataSource(DataScope) hands back a real DataSource, exercised
        // exactly the way SQLiteDataStore's own override will be.
        fakeDataStore = new DataStore() {
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
                return h2DataSource;
            }

            @Override
            public void destroyAllOperators() {
            }
        };

        UltiTools mockUltiTools = mock(UltiTools.class);
        when(mockUltiTools.getDataStore()).thenReturn(fakeDataStore);
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);

        scope = DataScope.forExternal("tracer-plugin", new File("."), Collections.emptySet());
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    private int countRows() throws SQLException {
        try (Connection conn = h2DataSource.getConnection();
                Statement st = conn.createStatement();
                java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_test")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** A bean carrying both a self-invoking and an externally-called {@code @Transactional} path. */
    public static class TxBean {
        private final TransactionManager txManager;

        public TxBean(TransactionManager txManager) {
            this.txManager = txManager;
        }

        @Transactional
        public void writeThenFail() {
            insert(1);
            throw new RuntimeException("boom - external call");
        }

        @Transactional
        public void writeThenSucceed() {
            insert(2);
        }

        /** External caller invokes this; it self-invokes {@link #innerWriteThenFail()}. */
        public void outerCallsInnerThenFails() {
            innerWriteThenFail();
        }

        @Transactional
        public void innerWriteThenFail() {
            insert(3);
            throw new RuntimeException("boom - self-invocation");
        }

        private void insert(int id) {
            try (Statement st = txManager.getConnection().createStatement()) {
                st.execute("INSERT INTO tx_test (id) VALUES (" + id + ")");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    @DisplayName("A @Transactional method that writes then throws leaves the row count at 0")
    void shouldRollBackOnException() throws Exception {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerBean(TxBean.class);
        context.refresh();

        TxBean bean = context.getBean(TxBean.class);

        assertThrows(RuntimeException.class, bean::writeThenFail);
        assertThat(countRows()).isZero();
    }

    @Test
    @DisplayName("A @Transactional method that writes and returns normally leaves the row committed")
    void shouldCommitOnSuccess() throws Exception {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerBean(TxBean.class);
        context.refresh();

        TxBean bean = context.getBean(TxBean.class);

        bean.writeThenSucceed();
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("Self-invocation of a @Transactional method rolls back exactly like an external call")
    void shouldRollBackOnSelfInvocation() throws Exception {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerBean(TxBean.class);
        context.refresh();

        TxBean bean = context.getBean(TxBean.class);

        assertThrows(RuntimeException.class, bean::outerCallsInnerThenFails);
        assertThat(countRows()).isZero();
    }
}
