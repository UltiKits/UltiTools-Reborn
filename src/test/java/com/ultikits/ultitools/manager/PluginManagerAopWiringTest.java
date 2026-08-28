package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopEligibility;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataStore;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@DisplayName("PluginManager AOP wiring")
class PluginManagerAopWiringTest {

    // wireAop now resolves a DataSource through UltiTools.getInstance().getDataStore() (D-01).
    // Every test in this file except the JSON-backend-specific ones below exercises
    // @ExceptionCatch wiring or annotation-coverage plumbing, not @Transactional -- the JSON
    // store is the stand-in DataStore for all of them, and since 02-05 it is genuinely wired
    // rather than declared unavailable (D-03).
    private MockedStatic<UltiTools> ultiToolsMock;
    private DataScope scope;
    private JsonStore jsonStore;

    // Real H2 DataSource backing the TimedTransactionally tests below (D-10 self-invocation
    // proof) -- same "fake JDBC-capable DataStore" pattern TransactionalRollbackEndToEndTest
    // uses for its own tracer test, needed here because a mocked JdbcTransactionManager cannot
    // run a real begin()/setTimeout()/commit() cycle.
    private static DataSource timedH2DataSource;

    @BeforeAll
    static void initTimedH2DataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pluginmanageraopwiringtest_timed;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // Deliberately no setPassword(): the in-memory DB is created password-less on first
        // connection; an empty-string password reads as a hardcoded credential to static
        // analysis. There is no credential here.
        timedH2DataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUpDataStore() {
        UltiTools mockUltiTools = mock(UltiTools.class);
        jsonStore = new JsonStore("build/test-wireaop-json");
        when(mockUltiTools.getDataStore()).thenReturn(jsonStore);
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        scope = DataScope.forExternal("PluginManagerAopWiringTest", new File("build/test-wireaop-json"),
                Collections.emptySet());
    }

    @AfterEach
    void tearDownDataStore() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    /**
     * 02-12 Task 2: {@code JsonStore.getOperator(File, Class)} now calls {@code checkOwnership}
     * as its first statement, so the two JSON-backend tests below (which call it directly,
     * bypassing {@code getOperator(DataScope, Class)}) need a {@code PluginManager} whose {@code
     * findScopeForDataFolder} resolves {@code "build/test-wireaop-json"} back to a scope that owns
     * {@link JsonTestEntity} -- otherwise the call refuses before ever reaching the write it is
     * actually testing. {@link #scope} itself is left with its pre-existing empty owned-entity set
     * (it is also used by every other test in this file for {@code wireAop}, which does not
     * examine that set); a separate scope carries the ownership these two tests specifically need.
     */
    private void stubJsonTestFolderOwnership() {
        File dataFolder = new File("build/test-wireaop-json");
        DataScope entityScope = DataScope.forExternal("PluginManagerAopWiringTest", dataFolder,
                Collections.singleton(JsonTestEntity.class));
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.findScopeForDataFolder(dataFolder)).thenReturn(entityScope);

        UltiTools mockUltiTools = mock(UltiTools.class);
        when(mockUltiTools.getDataStore()).thenReturn(jsonStore);
        when(mockUltiTools.getPluginManager()).thenReturn(pluginManager);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
    }

    public static class Guarded {
        @ExceptionCatch(silent = true)
        public String boom() { throw new IllegalStateException("boom"); }
    }

    public static class Transactionally {
        @Transactional
        public void work() { }
    }

    public static class Plain {
        public String work() { return "plain"; }
    }

    /**
     * Exercises {@code @Transactional(timeout=)} (D-10, 02-09) through both an external call and
     * a self-invocation call, per the phase's standing obligation for AOP semantic changes.
     * Reports the deadline the interceptor recorded via {@link TransactionManager#setTimeout} --
     * the manager-level half of D-10; {@code TransactionDataOperatorTest.PerStatementTimeoutTests}
     * (in scope for this same plan) separately proves the deadline flows through to an actual
     * {@code PreparedStatement}'s {@code getQueryTimeout()} once read off the SAME kind of
     * manager. Self-invocation dispatches through the identical {@code
     * TransactionInterceptor.executeInNewTransaction} call site as an external call -- this
     * repository's proxy is a subclass of the bean itself, not a delegate, so there is no second
     * code path for self-invocation to diverge on (see CLAUDE.md's AOP section).
     */
    public static class TimedTransactionally {
        private final TransactionManager txManager;

        public TimedTransactionally(TransactionManager txManager) {
            this.txManager = txManager;
        }

        @Transactional(timeout = 10)
        public Long externalDeadline() {
            return txManager.getTimeoutDeadlineNanos();
        }

        /** External caller invokes this; it self-invokes {@link #innerDeadline()}. */
        public Long outerCallsInnerDeadline() {
            return innerDeadline();
        }

        @Transactional(timeout = 10)
        public Long innerDeadline() {
            return txManager.getTimeoutDeadlineNanos();
        }

        /** No timeout requested -- the default. Neither call shape should record a deadline. */
        @Transactional
        public Long externalDeadlineNoTimeout() {
            return txManager.getTimeoutDeadlineNanos();
        }
    }

    @Table("plugin_manager_aop_wiring_test")
    public static class JsonTestEntity extends BaseDataEntity<String> {
        private String name;

        public JsonTestEntity() {
        }

        public JsonTestEntity(String id, String name) {
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

    /** A bean carrying a real @Transactional write against a real JSON-backed DataOperator. */
    public static class JsonTransactionally {
        private final DataOperator<JsonTestEntity> operator;

        public JsonTransactionally(DataOperator<JsonTestEntity> operator) {
            this.operator = operator;
        }

        @Transactional
        public void writeThenFail() {
            operator.insert(new JsonTestEntity("json-tx-fail", "before-rollback"));
            throw new RuntimeException("boom - json backend");
        }

        @Transactional
        public void writeThenSucceed() {
            operator.insert(new JsonTestEntity("json-tx-success", "after-commit"));
        }
    }

    @Test
    @DisplayName("Should register both advisors -- @ExceptionCatch and @Transactional -- for the JSON backend")
    void shouldRegisterBothAdvisorsForJsonBackend() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);

        AopProxyResolver resolver = context.getAopProxyResolver();
        assertNotNull(resolver, "wireAop must attach a resolver");
        List<AopAdvisor> advisors = resolver.getAdvisors();
        assertEquals(2, advisors.size(),
                "@Transactional is wired on the JSON backend since 02-05 (D-03), so both advisors register");
        Set<Class<? extends Annotation>> annotationTypes =
                advisors.stream().map(AopAdvisor::getAnnotationType).collect(Collectors.toSet());
        assertTrue(annotationTypes.contains(ExceptionCatch.class), annotationTypes.toString());
        assertTrue(annotationTypes.contains(Transactional.class), annotationTypes.toString());
    }

    @Test
    @DisplayName("Should proxy a bean using @ExceptionCatch")
    void shouldProxyExceptionCatchBean() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerBean(Guarded.class);
        context.refresh();

        Guarded bean = context.getBean(Guarded.class);

        assertTrue(ProxyFactory.isProxyClass(bean.getClass()));
    }

    @Test
    @DisplayName("Should swallow the exception through the wired @ExceptionCatch interceptor")
    void shouldSwallowThroughWiredInterceptor() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerBean(Guarded.class);
        context.refresh();

        assertEquals(null, context.getBean(Guarded.class).boom(),
                "@ExceptionCatch must actually take effect, not merely be present");
    }

    @Test
    @DisplayName("A @Transactional method on the JSON backend that writes then throws leaves "
            + "the operator's contents unchanged (D-03, flipped from the pre-02-05 rejection case)")
    void shouldRollBackOnJsonBackendWhenTransactionalMethodThrows() {
        stubJsonTestFolderOwnership();
        DataOperator<JsonTestEntity> operator =
                jsonStore.getOperator(new File("build/test-wireaop-json"), JsonTestEntity.class);

        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerType(DataOperator.class, operator);
        context.registerBean(JsonTransactionally.class);
        context.refresh();

        JsonTransactionally bean = context.getBean(JsonTransactionally.class);

        assertThrows(RuntimeException.class, bean::writeThenFail);
        assertNull(operator.getById("json-tx-fail"), "the insert must have been rolled back");
    }

    @Test
    @DisplayName("A @Transactional method on the JSON backend that writes and returns normally "
            + "leaves the write committed")
    void shouldCommitOnJsonBackendWhenTransactionalMethodSucceeds() {
        stubJsonTestFolderOwnership();
        DataOperator<JsonTestEntity> operator =
                jsonStore.getOperator(new File("build/test-wireaop-json"), JsonTestEntity.class);

        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerType(DataOperator.class, operator);
        context.registerBean(JsonTransactionally.class);
        context.refresh();

        JsonTransactionally bean = context.getBean(JsonTransactionally.class);

        bean.writeThenSucceed();
        assertNotNull(operator.getById("json-tx-success"), "the insert must have been committed");
    }

    @Test
    @DisplayName("Should declare @Transactional unavailable for any DataStore whose getDataSource(DataScope) "
            + "throws UnsupportedOperationException, naming the configured backend (D-01, decoupled from JsonStore)")
    void shouldRejectTransactionalBeanForAnyUnsupportedDataSource() {
        // This is deliberately independent of JsonStore (see shouldRejectTransactionalBean above,
        // which exercises the same fallback branch indirectly through JSON's real behavior at
        // HEAD). 02-05 replaces JsonStore.getDataSource(DataScope) with a real snapshot-based
        // implementation, at which point shouldRejectTransactionalBean's JSON-backed assertion
        // stops applying -- this test pins the fallback branch itself, against a store built to
        // throw regardless of what any concrete backend does, so it survives that change (02-04
        // Task 3 action item: "so 02-05 has a test to flip rather than a behaviour to discover").
        DataStore unsupportedStore = mock(DataStore.class, Answers.CALLS_REAL_METHODS);
        when(unsupportedStore.getStoreType()).thenReturn("mystery-backend");
        DataScope unsupportedScope = DataScope.forExternal("shouldRejectTransactionalBeanForAnyUnsupportedDataSource",
                new File("build/test-wireaop-unsupported"), Collections.emptySet());

        // Reuse the class-level static mock (setUpDataStore() already opened one for UltiTools on
        // this thread) rather than opening a second one -- Mockito forbids two concurrent static
        // mocks for the same class on the same thread.
        UltiTools mockUltiTools = mock(UltiTools.class);
        when(mockUltiTools.getDataStore()).thenReturn(unsupportedStore);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);

        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, unsupportedScope);
        context.registerBean(Transactionally.class);

        RuntimeException thrown = assertThrows(RuntimeException.class, context::refresh);
        String message = rootMessage(thrown);
        assertTrue(message.contains("Transactional"), message);
        assertTrue(message.contains("mystery-backend"), message);
    }

    // ===== Single-instance TransactionManager property (02-09, T-02-TAM-11) =====

    /**
     * {@code SQLiteDataStore}/{@code MysqlDataStore} are concrete classes -- {@code
     * mock(...)} bypasses their real constructors entirely (no pool, no connection, no
     * {@code org.sqlite.JDBC}/live MySQL needed), while still satisfying {@code PluginManager}'s
     * {@code instanceof SQLiteDataStore}/{@code instanceof MysqlDataStore} checks, which a
     * hand-written {@code DataStore} stand-in (as most of this file's tests use) could not: those
     * checks are on the concrete class, not the interface.
     */
    @Test
    @DisplayName("Should bind the @Transactional advisor to the SAME JdbcTransactionManager "
            + "SQLiteDataStore wires onto the operators it hands out, not an independent second "
            + "instance (T-02-TAM-11)")
    void shouldBindTransactionalAdvisorToSqliteStoreSharedManager() {
        SQLiteDataStore sqliteStore = mock(SQLiteDataStore.class);
        DataSource fakeDataSource = mock(DataSource.class);
        JdbcTransactionManager sharedManager = mock(JdbcTransactionManager.class);
        DataScope sqliteScope = DataScope.forExternal("shouldBindTransactionalAdvisorToSqliteStoreSharedManager",
                new File("build/test-wireaop-sqlite"), Collections.emptySet());
        when(sqliteStore.getStoreType()).thenReturn("sqlite");
        when(sqliteStore.getDataSource(sqliteScope)).thenReturn(fakeDataSource);
        when(sqliteStore.transactionManagerFor(sqliteScope)).thenReturn(sharedManager);

        UltiTools mockUltiTools = mock(UltiTools.class);
        when(mockUltiTools.getDataStore()).thenReturn(sqliteStore);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);

        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, sqliteScope);
        context.refresh();

        assertSame(sharedManager, context.getBean(TransactionManager.class),
                "wireAop must bind the interceptor to the exact manager SQLiteDataStore itself "
                        + "resolves for this scope, not a second, independently-constructed one");
    }

    @Test
    @DisplayName("Should bind the @Transactional advisor to the SAME JdbcTransactionManager "
            + "MysqlDataStore wires onto the operators it hands out, not an independent second "
            + "instance (T-02-TAM-11)")
    void shouldBindTransactionalAdvisorToMysqlStoreSharedManager() {
        MysqlDataStore mysqlStore = mock(MysqlDataStore.class);
        DataSource fakeDataSource = mock(DataSource.class);
        JdbcTransactionManager sharedManager = mock(JdbcTransactionManager.class);
        DataScope mysqlScope = DataScope.forExternal("shouldBindTransactionalAdvisorToMysqlStoreSharedManager",
                new File("build/test-wireaop-mysql"), Collections.emptySet());
        when(mysqlStore.getStoreType()).thenReturn("mysql");
        when(mysqlStore.getDataSource(mysqlScope)).thenReturn(fakeDataSource);
        when(mysqlStore.transactionManagerFor(mysqlScope)).thenReturn(sharedManager);

        UltiTools mockUltiTools = mock(UltiTools.class);
        when(mockUltiTools.getDataStore()).thenReturn(mysqlStore);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);

        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, mysqlScope);
        context.refresh();

        assertSame(sharedManager, context.getBean(TransactionManager.class),
                "wireAop must bind the interceptor to the exact manager MysqlDataStore itself "
                        + "resolves for this scope, not a second, independently-constructed one");
    }

    // ===== @Transactional(timeout=) via external call and self-invocation (D-10, 02-09) =====

    /** Wires a real, working DataSourceTransactionManager -- see {@link #timedH2DataSource}. */
    private TimedTransactionally buildTimedTransactionallyBean() {
        DataStore fakeJdbcStore = new DataStore() {
            @Override
            public String getStoreType() {
                return "fake-jdbc-timed";
            }

            @Override
            public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(
                    com.ultikits.ultitools.abstracts.UltiToolsPlugin plugin, Class<T> dataEntity) {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public DataSource getDataSource(DataScope scope) {
                return timedH2DataSource;
            }

            @Override
            public void destroyAllOperators() {
                // intentional no-op: this fake DataStore has no operator pool for this test to tear down
            }
        };
        UltiTools mockUltiTools = mock(UltiTools.class);
        when(mockUltiTools.getDataStore()).thenReturn(fakeJdbcStore);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);

        DataScope timedScope = DataScope.forExternal("TimedTransactionally", new File("build/test-wireaop-timed"),
                Collections.emptySet());
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, timedScope);
        context.registerBean(TimedTransactionally.class);
        context.refresh();
        return context.getBean(TimedTransactionally.class);
    }

    @Test
    @DisplayName("@Transactional(timeout=10) via an external call records a real deadline")
    void externalCallRecordsTimeoutDeadline() {
        TimedTransactionally bean = buildTimedTransactionallyBean();

        Long deadline = bean.externalDeadline();

        assertNotNull(deadline, "an external call to a timed @Transactional method must record a deadline");
    }

    @Test
    @DisplayName("@Transactional(timeout=10) via self-invocation records a real deadline too")
    void selfInvocationRecordsTimeoutDeadline() {
        TimedTransactionally bean = buildTimedTransactionallyBean();

        Long deadline = bean.outerCallsInnerDeadline();

        assertNotNull(deadline, "self-invocation dispatches through the same "
                + "executeInNewTransaction call site as an external call -- see the class javadoc "
                + "on TimedTransactionally");
    }

    @Test
    @DisplayName("@Transactional() with no timeout (the default) records no deadline, externally called")
    void externalCallWithNoTimeoutRecordsNoDeadline() {
        TimedTransactionally bean = buildTimedTransactionallyBean();

        Long deadline = bean.externalDeadlineNoTimeout();

        assertNull(deadline, "the interceptor never calls setTimeout for the default (-1)");
    }

    @Test
    @DisplayName("Should leave plain beans unproxied")
    void shouldLeavePlainBeansAlone() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerBean(Plain.class);
        context.refresh();

        assertSame(Plain.class, context.getBean(Plain.class).getClass());
    }

    @Test
    @DisplayName("Should produce a resolver that passes annotation coverage validation")
    void shouldPassAnnotationCoverageValidation() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);

        assertDoesNotThrow(() -> context.getAopProxyResolver().validateAnnotationCoverage());
    }

    @Test
    @DisplayName("Should actually invoke validateAnnotationCoverage, not merely leave it callable")
    void shouldInvokeAnnotationCoverageValidation() {
        // AopEligibility.getAopAnnotations() is stubbed to report a third, hypothetical AOP
        // annotation that wireAop does not know about (neither an advisor nor
        // addUnavailableAnnotation covers it). If PluginManager.wireAop ever stops calling
        // resolver.validateAnnotationCoverage() before returning, this stays silent instead of
        // throwing here -- deleting that one call line leaves every other test in this file green.
        try (MockedStatic<AopEligibility> eligibility = mockStatic(AopEligibility.class)) {
            List<Class<? extends Annotation>> withHypotheticalThirdAnnotation =
                    Arrays.asList(Transactional.class, ExceptionCatch.class, Deprecated.class);
            eligibility.when(AopEligibility::getAopAnnotations)
                    .thenReturn(withHypotheticalThirdAnnotation);

            SimpleContainer context = new SimpleContainer();

            ContainerException thrown =
                    assertThrows(ContainerException.class, () -> PluginManager.wireAop(context, scope));
            assertTrue(thrown.getMessage().contains("Deprecated"), thrown.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            sb.append(current.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
