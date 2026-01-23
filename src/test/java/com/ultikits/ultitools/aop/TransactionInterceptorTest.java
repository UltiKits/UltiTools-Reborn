package com.ultikits.ultitools.aop;

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
        @DisplayName("Should commit for non-matching rollbackFor exception")
        void shouldCommitForNonMatchingRollbackForException() throws Throwable {
            Method method = RollbackService.class.getMethod("rollbackFor");
            Object target = new RollbackService();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new NullPointerException("Error"));
            when(transactionManager.hasActiveTransaction()).thenReturn(false);

            assertThrows(NullPointerException.class, () -> interceptor.invoke(mockInvocation));

            verify(transactionManager).commit();
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
}
