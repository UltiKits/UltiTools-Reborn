package com.ultikits.ultitools.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.annotations.Isolation;
import com.ultikits.ultitools.annotations.Propagation;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.manager.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Unit tests for TransactionInterceptor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionInterceptor Tests")
@SuppressWarnings("PMD.UnusedLocalVariable") // Some test variables exist for context clarity
class TransactionInterceptorTest {

    @Mock
    private TransactionManager transactionManager;

    @Mock
    private MethodInvocation mockInvocation;

    private TransactionInterceptor interceptor;

    // Test classes with various @Transactional configurations
    public static class NonTransactionalService {
        public String action() {
            return "result";
        }
    }

    public static class BasicTransactionalService {
        @Transactional
        public String save() {
            return "saved";
        }

        public String nonTransactional() {
            return "non-tx";
        }
    }

    public static class PropagationService {
        @Transactional(propagation = Propagation.REQUIRED)
        public String required() {
            return "required";
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public String requiresNew() {
            return "requires_new";
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public String supports() {
            return "supports";
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public String mandatory() {
            return "mandatory";
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public String notSupported() {
            return "not_supported";
        }

        @Transactional(propagation = Propagation.NEVER)
        public String never() {
            return "never";
        }

        @Transactional(propagation = Propagation.NESTED)
        public String nested() {
            return "nested";
        }
    }

    public static class TransactionSettingsService {
        @Transactional(isolation = Isolation.SERIALIZABLE)
        public String withIsolation() {
            return "isolated";
        }

        @Transactional(readOnly = true)
        public String readOnly() {
            return "read";
        }

        @Transactional(timeout = 30)
        public String withTimeout() {
            return "timeout";
        }

        @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true, timeout = 60)
        public String withAllSettings() {
            return "all_settings";
        }
    }

    public static class RollbackService {
        @Transactional
        public String defaultRollback() {
            return "default";
        }

        @Transactional(rollbackFor = {IllegalArgumentException.class})
        public String rollbackFor() {
            return "rollback_for";
        }

        @Transactional(noRollbackFor = {RuntimeException.class})
        public String noRollbackFor() {
            return "no_rollback";
        }

        @Transactional(rollbackFor = {Exception.class}, noRollbackFor = {IllegalStateException.class})
        public String mixedRollback() {
            return "mixed";
        }
    }

    @Transactional
    public static class ClassLevelTransactionalService {
        public String method1() {
            return "method1";
        }

        public String method2() {
            return "method2";
        }
    }

    /**
     * Fixture for {@link SelfInvocationAndExternalCallTests}. Un-annotated {@code outerCallsInner()}
     * self-invokes annotated {@code inner()}, mirroring {@link AopSelfInvocationFixture}'s shape:
     * the caller method is never overridden by the proxy, so its call to {@code this.inner()} is a
     * plain virtual dispatch that must still resolve to the proxy's override. Declared at the
     * top level (not inside the {@code @Nested} class) because JUnit 5 {@code @Nested} classes are
     * non-static inner classes, and a {@code static} member class cannot be declared inside one on
     * this project's Java 8 target.
     */
    public static class SelfInvocationBean {
        private final TransactionManager txManager;

        public SelfInvocationBean(TransactionManager txManager) {
            this.txManager = txManager;
        }

        public void outerCallsInner() {
            inner();
        }

        @Transactional
        public void inner() {
            write(1);
            throw new RuntimeException("boom - self-invocation");
        }

        @Transactional
        public void external() {
            write(2);
            throw new RuntimeException("boom - external call");
        }

        /** Negative control: not @Transactional, so its write must survive the throw. */
        public void plainWriteThenThrow() {
            write(3);
            throw new RuntimeException("boom - not transactional");
        }

        private void write(int id) {
            try (Statement st = txManager.getConnection().createStatement()) {
                st.execute("INSERT INTO tx_test (id) VALUES (" + id + ")");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Marker exception standing in for {@code Transactional.java}'s own
     * {@code rollbackFor = {BusinessException.class}} javadoc example (D-06).
     */
    public static class BusinessException extends Exception {
        public BusinessException(String message) {
            super(message);
        }
    }

    /**
     * Fixture for {@link RollbackRuleCombinationTests} (D-06/D-07): additive {@code rollbackFor}
     * plus the shallowest-inheritance-depth tiebreak against {@code noRollbackFor}, each case
     * proven through both an external call and a self-invocation call (WIRE-13). Declared at the
     * top level for the same Java 8 / JUnit 5 {@code @Nested} reason documented on
     * {@link SelfInvocationBean}.
     */
    public static class RollbackRuleBean {
        private final TransactionManager txManager;

        public RollbackRuleBean(TransactionManager txManager) {
            this.txManager = txManager;
        }

        // --- D-06: an unmatched rollbackFor no longer commits; it falls through to the default. ---

        @Transactional(rollbackFor = BusinessException.class)
        public void unmatchedRollbackForExternal() {
            write(101);
            throw new NullPointerException("boom - unmatched rollbackFor, external");
        }

        public void outerCallsUnmatchedRollbackFor() {
            unmatchedRollbackForSelf();
        }

        @Transactional(rollbackFor = BusinessException.class)
        public void unmatchedRollbackForSelf() {
            write(102);
            throw new NullPointerException("boom - unmatched rollbackFor, self-invocation");
        }

        // --- D-07: a narrower rollbackFor rule wins over a broader noRollbackFor rule. ---

        @Transactional(noRollbackFor = Exception.class, rollbackFor = IllegalStateException.class)
        public void narrowerRollbackForWinsExternal() {
            write(103);
            throw new IllegalStateException("boom - narrower rollbackFor wins, external");
        }

        public void outerCallsNarrowerRollbackForWins() {
            narrowerRollbackForWinsSelf();
        }

        @Transactional(noRollbackFor = Exception.class, rollbackFor = IllegalStateException.class)
        public void narrowerRollbackForWinsSelf() {
            write(104);
            throw new IllegalStateException("boom - narrower rollbackFor wins, self-invocation");
        }

        // --- D-07 mirror: a narrower noRollbackFor rule wins over a broader rollbackFor rule. ---

        @Transactional(rollbackFor = Exception.class, noRollbackFor = IllegalStateException.class)
        public void narrowerNoRollbackForWinsExternal() {
            write(109);
            throw new IllegalStateException("boom - narrower noRollbackFor wins, external");
        }

        public void outerCallsNarrowerNoRollbackForWins() {
            narrowerNoRollbackForWinsSelf();
        }

        @Transactional(rollbackFor = Exception.class, noRollbackFor = IllegalStateException.class)
        public void narrowerNoRollbackForWinsSelf() {
            write(110);
            throw new IllegalStateException("boom - narrower noRollbackFor wins, self-invocation");
        }

        // --- Sole noRollbackFor rule (no rollbackFor configured) still commits, unaffected. ---

        @Transactional(noRollbackFor = IllegalStateException.class)
        public void soleNoRollbackForCommitsExternal() {
            write(105);
            throw new IllegalStateException("boom - sole noRollbackFor commits, external");
        }

        public void outerCallsSoleNoRollbackForCommits() {
            soleNoRollbackForCommitsSelf();
        }

        @Transactional(noRollbackFor = IllegalStateException.class)
        public void soleNoRollbackForCommitsSelf() {
            write(106);
            throw new IllegalStateException("boom - sole noRollbackFor commits, self-invocation");
        }

        // --- D-07: an exact-depth tie (same class in both arrays) favors rollback. ---

        @Transactional(rollbackFor = IllegalStateException.class, noRollbackFor = IllegalStateException.class)
        public void exactTieRollsBackExternal() {
            write(107);
            throw new IllegalStateException("boom - exact-depth tie rolls back, external");
        }

        public void outerCallsExactTieRollsBack() {
            exactTieRollsBackSelf();
        }

        @Transactional(rollbackFor = IllegalStateException.class, noRollbackFor = IllegalStateException.class)
        public void exactTieRollsBackSelf() {
            write(108);
            throw new IllegalStateException("boom - exact-depth tie rolls back, self-invocation");
        }

        private void write(int id) {
            try (Statement st = txManager.getConnection().createStatement()) {
                st.execute("INSERT INTO tx_test (id) VALUES (" + id + ")");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @BeforeEach
    void setUp() {
        interceptor = new TransactionInterceptor(transactionManager);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create interceptor with transaction manager")
        void shouldCreateInterceptorWithTransactionManager() {
            TransactionInterceptor ti = new TransactionInterceptor(transactionManager);
            assertNotNull(ti);
        }
    }

    @Nested
    @DisplayName("Non-Transactional Method Tests")
    class NonTransactionalMethodTests {

        @Test
        @DisplayName("Should proceed without transaction for non-annotated method")
        void shouldProceedWithoutTransactionForNonAnnotatedMethod() throws Throwable {
            Method method = NonTransactionalService.class.getMethod("action");
            Object target = new NonTransactionalService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("result");

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("result", result);
            verify(transactionManager, never()).begin();
            verify(transactionManager, never()).commit();
        }
    }

    @Nested
    @DisplayName("REQUIRED Propagation Tests")
    class RequiredPropagationTests {

        @Test
        @DisplayName("Should start new transaction when none exists")
        void shouldStartNewTransactionWhenNoneExists() throws Throwable {
            Method method = PropagationService.class.getMethod("required");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("required");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("required", result);
            verify(transactionManager).begin();
            verify(transactionManager).commit();
        }

        @Test
        @DisplayName("Should join existing transaction")
        void shouldJoinExistingTransaction() throws Throwable {
            Method method = PropagationService.class.getMethod("required");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("required");
            when(transactionManager.hasActiveTransaction()).thenReturn(true);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("required", result);
            verify(transactionManager, never()).begin();
            verify(transactionManager, never()).commit();
        }
    }

    @Nested
    @DisplayName("REQUIRES_NEW Propagation Tests")
    class RequiresNewPropagationTests {

        @Test
        @DisplayName("Should always create new transaction")
        void shouldAlwaysCreateNewTransaction() throws Throwable {
            Method method = PropagationService.class.getMethod("requiresNew");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("requires_new");

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("requires_new", result);
            verify(transactionManager).begin();
            verify(transactionManager).commit();
        }

        @Test
        @DisplayName("Should create new transaction even when one exists")
        void shouldCreateNewTransactionEvenWhenOneExists() throws Throwable {
            Method method = PropagationService.class.getMethod("requiresNew");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("requires_new");
            when(transactionManager.hasActiveTransaction()).thenReturn(true);

            Object result = interceptor.invoke(mockInvocation);

            verify(transactionManager).begin();
            verify(transactionManager).commit();
        }
    }

    @Nested
    @DisplayName("SUPPORTS Propagation Tests")
    class SupportsPropagationTests {

        @Test
        @DisplayName("Should proceed without creating transaction")
        void shouldProceedWithoutCreatingTransaction() throws Throwable {
            Method method = PropagationService.class.getMethod("supports");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("supports");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("supports", result);
            verify(transactionManager, never()).begin();
        }

        @Test
        @DisplayName("Should join existing transaction if present")
        void shouldJoinExistingTransactionIfPresent() throws Throwable {
            Method method = PropagationService.class.getMethod("supports");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("supports");
            when(transactionManager.hasActiveTransaction()).thenReturn(true);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("supports", result);
            verify(transactionManager, never()).begin();
        }
    }

    @Nested
    @DisplayName("MANDATORY Propagation Tests")
    class MandatoryPropagationTests {

        @Test
        @DisplayName("Should throw when no transaction exists")
        void shouldThrowWhenNoTransactionExists() throws Throwable {
            Method method = PropagationService.class.getMethod("mandatory");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> interceptor.invoke(mockInvocation));
            assertTrue(ex.getMessage().contains("MANDATORY"));
        }

        @Test
        @DisplayName("Should proceed when transaction exists")
        void shouldProceedWhenTransactionExists() throws Throwable {
            Method method = PropagationService.class.getMethod("mandatory");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("mandatory");
            when(transactionManager.hasActiveTransaction()).thenReturn(true);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("mandatory", result);
        }
    }

    @Nested
    @DisplayName("NOT_SUPPORTED Propagation Tests")
    class NotSupportedPropagationTests {

        @Test
        @DisplayName("Should proceed without transaction")
        void shouldProceedWithoutTransaction() throws Throwable {
            Method method = PropagationService.class.getMethod("notSupported");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("not_supported");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("not_supported", result);
            verify(transactionManager, never()).begin();
        }
    }

    @Nested
    @DisplayName("NEVER Propagation Tests")
    class NeverPropagationTests {

        @Test
        @DisplayName("Should throw when transaction exists")
        void shouldThrowWhenTransactionExists() throws Throwable {
            Method method = PropagationService.class.getMethod("never");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(transactionManager.hasActiveTransaction()).thenReturn(true);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> interceptor.invoke(mockInvocation));
            assertTrue(ex.getMessage().contains("NEVER"));
        }

        @Test
        @DisplayName("Should proceed when no transaction exists")
        void shouldProceedWhenNoTransactionExists() throws Throwable {
            Method method = PropagationService.class.getMethod("never");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("never");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("never", result);
        }
    }

    @Nested
    @DisplayName("NESTED Propagation Tests")
    class NestedPropagationTests {

        @Test
        @DisplayName("Should start new transaction when none exists")
        void shouldStartNewTransactionWhenNoneExists() throws Throwable {
            Method method = PropagationService.class.getMethod("nested");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("nested");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("nested", result);
            verify(transactionManager).begin();
            verify(transactionManager).commit();
        }

        @Test
        @DisplayName("Should join existing transaction")
        void shouldJoinExistingTransaction() throws Throwable {
            Method method = PropagationService.class.getMethod("nested");
            Object target = new PropagationService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("nested");
            when(transactionManager.hasActiveTransaction()).thenReturn(true);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("nested", result);
            verify(transactionManager, never()).begin();
        }
    }

    @Nested
    @DisplayName("Transaction Settings Tests")
    class TransactionSettingsTests {

        @Test
        @DisplayName("Should set isolation level")
        void shouldSetIsolationLevel() throws Throwable {
            Method method = TransactionSettingsService.class.getMethod("withIsolation");
            Object target = new TransactionSettingsService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("isolated");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            interceptor.invoke(mockInvocation);

            verify(transactionManager).setIsolationLevel(Isolation.SERIALIZABLE.getLevel());
        }

        @Test
        @DisplayName("Should set read only")
        void shouldSetReadOnly() throws Throwable {
            Method method = TransactionSettingsService.class.getMethod("readOnly");
            Object target = new TransactionSettingsService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("read");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            interceptor.invoke(mockInvocation);

            verify(transactionManager).setReadOnly(true);
        }

        @Test
        @DisplayName("Should set timeout")
        void shouldSetTimeout() throws Throwable {
            Method method = TransactionSettingsService.class.getMethod("withTimeout");
            Object target = new TransactionSettingsService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("timeout");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            interceptor.invoke(mockInvocation);

            verify(transactionManager).setTimeout(30);
        }

        @Test
        @DisplayName("Should apply all settings")
        void shouldApplyAllSettings() throws Throwable {
            Method method = TransactionSettingsService.class.getMethod("withAllSettings");
            Object target = new TransactionSettingsService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("all_settings");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            interceptor.invoke(mockInvocation);

            verify(transactionManager).setIsolationLevel(Isolation.REPEATABLE_READ.getLevel());
            verify(transactionManager).setReadOnly(true);
            verify(transactionManager).setTimeout(60);
        }

        @Test
        @DisplayName("Should not set default isolation level")
        void shouldNotSetDefaultIsolationLevel() throws Throwable {
            Method method = BasicTransactionalService.class.getMethod("save");
            Object target = new BasicTransactionalService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("saved");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            interceptor.invoke(mockInvocation);

            verify(transactionManager, never()).setIsolationLevel(anyInt());
        }
    }

    @Nested
    @DisplayName("Rollback Tests")
    class RollbackTests {

        @Test
        @DisplayName("Should rollback on RuntimeException by default")
        void shouldRollbackOnRuntimeExceptionByDefault() throws Throwable {
            Method method = RollbackService.class.getMethod("defaultRollback");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(RuntimeException.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).rollback();
            verify(transactionManager, never()).commit();
        }

        @Test
        @DisplayName("Should rollback on Error by default")
        void shouldRollbackOnErrorByDefault() throws Throwable {
            Method method = RollbackService.class.getMethod("defaultRollback");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new Error("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(Error.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).rollback();
        }

        @Test
        @DisplayName("Should commit for checked exception by default")
        void shouldCommitForCheckedExceptionByDefault() throws Throwable {
            Method method = RollbackService.class.getMethod("defaultRollback");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new Exception("Checked"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(Exception.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).commit();
            verify(transactionManager, never()).rollback();
        }

        @Test
        @DisplayName("Should rollback for rollbackFor exception")
        void shouldRollbackForRollbackForException() throws Throwable {
            Method method = RollbackService.class.getMethod("rollbackFor");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new IllegalArgumentException("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(IllegalArgumentException.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).rollback();
        }

        @Test
        @DisplayName("D-06: an unmatched rollbackFor now falls through to the RuntimeException/Error "
                + "default and rolls back, instead of committing")
        void shouldRollbackForNonMatchingRollbackForExceptionViaDefaultFallthrough() throws Throwable {
            Method method = RollbackService.class.getMethod("rollbackFor");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new NullPointerException("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(NullPointerException.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).rollback();
            verify(transactionManager, never()).commit();
        }

        @Test
        @DisplayName("Should commit for noRollbackFor exception")
        void shouldCommitForNoRollbackForException() throws Throwable {
            Method method = RollbackService.class.getMethod("noRollbackFor");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(RuntimeException.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).commit();
            verify(transactionManager, never()).rollback();
        }

        @Test
        @DisplayName("Should respect noRollbackFor over rollbackFor")
        void shouldRespectNoRollbackForOverRollbackFor() throws Throwable {
            Method method = RollbackService.class.getMethod("mixedRollback");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new IllegalStateException("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(IllegalStateException.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).commit();
        }

        @Test
        @DisplayName("Should rollback for rollbackFor when not in noRollbackFor")
        void shouldRollbackForRollbackForWhenNotInNoRollbackFor() throws Throwable {
            Method method = RollbackService.class.getMethod("mixedRollback");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new IllegalArgumentException("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(IllegalArgumentException.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).rollback();
        }
    }

    @Nested
    @DisplayName("Class-Level Annotation Tests")
    class ClassLevelAnnotationTests {

        @Test
        @DisplayName("Should use class-level annotation")
        void shouldUseClassLevelAnnotation() throws Throwable {
            Method method = ClassLevelTransactionalService.class.getMethod("method1");
            Object target = new ClassLevelTransactionalService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("method1");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("method1", result);
            verify(transactionManager).begin();
            verify(transactionManager).commit();
        }

        @Test
        @DisplayName("Should apply class-level annotation to all methods")
        void shouldApplyClassLevelAnnotationToAllMethods() throws Throwable {
            Method method1 = ClassLevelTransactionalService.class.getMethod("method1");
            Method method2 = ClassLevelTransactionalService.class.getMethod("method2");
            Object target = new ClassLevelTransactionalService();

            when(mockInvocation.getMethod()).thenReturn(method1, method2);
            when(mockInvocation.proceed()).thenReturn("method1", "method2");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            interceptor.invoke(mockInvocation);
            interceptor.invoke(mockInvocation);

            verify(transactionManager, times(2)).begin();
            verify(transactionManager, times(2)).commit();
        }
    }

    @Nested
    @DisplayName("Commit/Rollback Behavior Tests")
    class CommitRollbackBehaviorTests {

        @Test
        @DisplayName("Should commit on success")
        void shouldCommitOnSuccess() throws Throwable {
            Method method = BasicTransactionalService.class.getMethod("save");
            Object target = new BasicTransactionalService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("saved");
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("saved", result);
            verify(transactionManager).begin();
            verify(transactionManager).commit();
            verify(transactionManager, never()).rollback();
        }

        @Test
        @DisplayName("Should rethrow exception after rollback")
        void shouldRethrowExceptionAfterRollback() throws Throwable {
            Method method = BasicTransactionalService.class.getMethod("save");
            Object target = new BasicTransactionalService();

            RuntimeException original = new RuntimeException("Original error");
            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(original);
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> interceptor.invoke(mockInvocation));

            assertSame(original, thrown);
            verify(transactionManager).rollback();
        }
    }

    /**
     * WIRE-15: the same rollback must be observed whether a {@code @Transactional} method is
     * reached by an external caller or by self-invocation from another method on the same bean.
     * Self-invocation <b>is</b> intercepted in this codebase - the ByteBuddy subclass proxy is
     * the bean, not a delegate wrapper - re-verified in Phase 1 against the four pre-existing
     * {@code shouldInterceptSelfInvocation} tests ({@link AopActivationTest},
     * {@link ProxyFactoryTest}, {@link AopProxyResolverTest}, {@code SimpleContainerAopTest}).
     * <p>
     * Unlike the rest of this file, these cases build a real {@link AopProxyResolver}-generated
     * proxy over a real {@link DataSourceTransactionManager} bound to an in-memory H2
     * {@link DataSource} - a mocked {@link TransactionManager} cannot distinguish self-invocation
     * from an external call (the interceptor's own {@code invoke()} body is identical either way;
     * only the proxy's dispatch differs), so only a real proxy + real JDBC connection can pin this.
     */
    @Nested
    @DisplayName("外部调用与自调用的回滚一致性（WIRE-15）")
    class SelfInvocationAndExternalCallTests {

        private DataSource h2DataSource;
        private DataSourceTransactionManager realManager;
        private AopProxyResolver resolver;

        @BeforeEach
        void setUp() throws Exception {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:txinterceptorselfinvoke" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
            config.setUsername("sa");
            // Deliberately no setPassword(): see TransactionalRollbackEndToEndTest's identical
            // comment - the in-memory DB has no credential to hardcode.
            h2DataSource = new HikariDataSource(config);
            try (Connection conn = h2DataSource.getConnection();
                    Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE tx_test (id INT PRIMARY KEY)");
            }

            realManager = new DataSourceTransactionManager(h2DataSource);
            TransactionInterceptor realInterceptor = new TransactionInterceptor(realManager);
            resolver = new AopProxyResolver();
            resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, realInterceptor, 100));
        }

        @AfterEach
        void tearDown() {
            if (h2DataSource instanceof HikariDataSource) {
                ((HikariDataSource) h2DataSource).close();
            }
        }

        private int countRows() throws SQLException {
            try (Connection conn = h2DataSource.getConnection();
                    Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_test")) {
                rs.next();
                return rs.getInt(1);
            }
        }

        private SelfInvocationBean newProxiedBean() throws ReflectiveOperationException {
            Class<?> proxyClass = resolver.resolve(SelfInvocationBean.class);
            return (SelfInvocationBean) proxyClass
                    .getDeclaredConstructor(TransactionManager.class)
                    .newInstance(realManager);
        }

        @Test
        @DisplayName("Should intercept self-invocation and roll back the same as an external call")
        void shouldInterceptSelfInvocation() throws Exception {
            SelfInvocationBean bean = newProxiedBean();

            assertThrows(RuntimeException.class, bean::outerCallsInner);

            assertThat(countRows()).isZero();
        }

        @Test
        @DisplayName("Should roll back identically when @Transactional is reached by an external call")
        void shouldRollBackOnExternalCall() throws Exception {
            SelfInvocationBean bean = newProxiedBean();

            assertThrows(RuntimeException.class, bean::external);

            assertThat(countRows()).isZero();
        }

        @Test
        @DisplayName("Negative control: a non-@Transactional write survives the throw, proving the "
                + "rollback above came from the interceptor, not an ambient rollback")
        void shouldCommitNonTransactionalWriteDespiteThrow() throws Exception {
            SelfInvocationBean bean = newProxiedBean();

            assertThrows(RuntimeException.class, bean::plainWriteThenThrow);

            assertThat(countRows()).isEqualTo(1);
        }
    }

    /**
     * D-06/D-07: additive {@code rollbackFor} plus the shallowest-inheritance-depth tiebreak
     * against {@code noRollbackFor}, each case proven through both an external call and a
     * self-invocation call (WIRE-13). Same real-proxy-plus-real-JDBC rationale as
     * {@link SelfInvocationAndExternalCallTests} - a mocked {@link TransactionManager} cannot
     * distinguish self-invocation from an external call.
     */
    @Nested
    @DisplayName("D-06/D-07: additive rollbackFor with shallowest-depth tiebreak（WIRE-13）")
    class RollbackRuleCombinationTests {

        private DataSource h2DataSource;
        private DataSourceTransactionManager realManager;
        private AopProxyResolver resolver;

        @BeforeEach
        void setUp() throws Exception {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:txinterceptorrollbackrule" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
            config.setUsername("sa");
            // Deliberately no setPassword(): see TransactionalRollbackEndToEndTest's identical
            // comment - the in-memory DB has no credential to hardcode.
            h2DataSource = new HikariDataSource(config);
            try (Connection conn = h2DataSource.getConnection();
                    Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE tx_test (id INT PRIMARY KEY)");
            }

            realManager = new DataSourceTransactionManager(h2DataSource);
            TransactionInterceptor realInterceptor = new TransactionInterceptor(realManager);
            resolver = new AopProxyResolver();
            resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, realInterceptor, 100));
        }

        @AfterEach
        void tearDown() {
            if (h2DataSource instanceof HikariDataSource) {
                ((HikariDataSource) h2DataSource).close();
            }
        }

        private int countRows() throws SQLException {
            try (Connection conn = h2DataSource.getConnection();
                    Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_test")) {
                rs.next();
                return rs.getInt(1);
            }
        }

        private RollbackRuleBean newProxiedBean() throws ReflectiveOperationException {
            Class<?> proxyClass = resolver.resolve(RollbackRuleBean.class);
            return (RollbackRuleBean) proxyClass
                    .getDeclaredConstructor(TransactionManager.class)
                    .newInstance(realManager);
        }

        @Test
        @DisplayName("D-06: unmatched rollbackFor rolls back on the RuntimeException default (external)")
        void unmatchedRollbackForRollsBackExternal() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(NullPointerException.class, bean::unmatchedRollbackForExternal);

            assertThat(countRows()).isZero();
        }

        @Test
        @DisplayName("D-06: unmatched rollbackFor rolls back on the RuntimeException default (self-invocation)")
        void unmatchedRollbackForRollsBackSelfInvocation() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(NullPointerException.class, bean::outerCallsUnmatchedRollbackFor);

            assertThat(countRows()).isZero();
        }

        @Test
        @DisplayName("D-07: a narrower rollbackFor wins over a broader noRollbackFor (external)")
        void narrowerRollbackForWinsExternal() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::narrowerRollbackForWinsExternal);

            assertThat(countRows()).isZero();
        }

        @Test
        @DisplayName("D-07: a narrower rollbackFor wins over a broader noRollbackFor (self-invocation)")
        void narrowerRollbackForWinsSelfInvocation() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::outerCallsNarrowerRollbackForWins);

            assertThat(countRows()).isZero();
        }

        @Test
        @DisplayName("D-07 mirror: a narrower noRollbackFor wins over a broader rollbackFor (external)")
        void narrowerNoRollbackForWinsExternal() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::narrowerNoRollbackForWinsExternal);

            assertThat(countRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("D-07 mirror: a narrower noRollbackFor wins over a broader rollbackFor (self-invocation)")
        void narrowerNoRollbackForWinsSelfInvocation() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::outerCallsNarrowerNoRollbackForWins);

            assertThat(countRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("A sole noRollbackFor rule still commits, unaffected by D-06 (external)")
        void soleNoRollbackForStillCommitsExternal() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::soleNoRollbackForCommitsExternal);

            assertThat(countRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("A sole noRollbackFor rule still commits, unaffected by D-06 (self-invocation)")
        void soleNoRollbackForStillCommitsSelfInvocation() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::outerCallsSoleNoRollbackForCommits);

            assertThat(countRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("D-07: an exact-depth tie between rollbackFor and noRollbackFor favors rollback (external)")
        void exactDepthTieFavorsRollbackExternal() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::exactTieRollsBackExternal);

            assertThat(countRows()).isZero();
        }

        @Test
        @DisplayName("D-07: an exact-depth tie between rollbackFor and noRollbackFor favors rollback "
                + "(self-invocation)")
        void exactDepthTieFavorsRollbackSelfInvocation() throws Exception {
            RollbackRuleBean bean = newProxiedBean();

            assertThrows(IllegalStateException.class, bean::outerCallsExactTieRollsBack);

            assertThat(countRows()).isZero();
        }
    }
}
