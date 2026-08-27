package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Measures, through a real proxied bean built via the normal proxy path (never a mocked
 * interceptor), what actually happens when a custom {@link ExceptionHandler} deliberately
 * re-throws from {@code handleException}.
 * <p>
 * This settles RESEARCH.md's Assumption A2 - whether ByteBuddy's {@code InvocationHandlerAdapter}
 * wraps an undeclared checked {@link Throwable} escaping a subclass override the way
 * {@code java.lang.reflect.Proxy} does - by running it rather than assuming either answer (Test 1
 * vs Test 2), and pins the current (pre-narrowing) swallow-all behaviour of
 * {@link ExceptionInterceptor}'s custom-handler catch as the documented RED state that plan
 * 01-07's Task 2 narrows.
 * <p>
 * Test 6 also measures, rather than assumes, whether this codebase's specific inheritance-based
 * proxy design (the generated subclass instantiated directly as the bean - see
 * {@link AopSelfInvocationFixture}'s class javadoc) is subject to the classic delegating-proxy
 * self-invocation gap. It is not: {@code AopActivationTest#shouldInterceptSelfInvocation} already
 * pins the same fact for two intercepted methods calling each other on the same bean, and this
 * class's Test 6 pins it again through an un-annotated caller method reaching an annotated one -
 * the shape most "self-invocation bypasses AOP" folklore assumes behaves differently. It does not,
 * because there is only one object and {@code this.annotated()} is a plain virtual dispatch that
 * resolves to whatever overrides {@code annotated()} at runtime, regardless of which method's
 * bytecode issues the call.
 * <p>
 * 通过真实代理对象（而非被 mock 的拦截器）测量自定义 {@link ExceptionHandler} 主动重新抛出时的实际行为，
 * 以及本代码库继承式代理设计下自调用是否真的会绕开拦截器。
 *
 * @author wisdomme
 * @since 6.3.0
 */
@DisplayName("Custom ExceptionHandler rethrow semantics (measured against a real proxy)")
class ExceptionHandlerRethrowTest {

    /** Checked exception type a custom handler deliberately re-throws in these tests. */
    static class HandlerCheckedException extends Exception {
        HandlerCheckedException(String message) {
            super(message);
        }
    }

    // ---- Handler beans ----

    /** Deliberately re-throws a checked exception - Tests 1, 2 and 6 exercise this. */
    @Service
    public static class CheckedRethrowingHandler implements ExceptionHandler {
        @Override
        public Object handleException(Throwable exception, Object target, Method method, Object[] args)
                throws Throwable {
            throw new HandlerCheckedException("handler-rethrow");
        }
    }

    /** Deliberately re-throws an unchecked exception - Test 3 exercises this. */
    @Service
    public static class UncheckedRethrowingHandler implements ExceptionHandler {
        @Override
        public Object handleException(Throwable exception, Object target, Method method, Object[] args)
                throws Throwable {
            throw new IllegalStateException("handler-unchecked-rethrow");
        }
    }

    /** Deliberately NOT an {@link ExceptionHandler} - Test 5 exercises resolving to the wrong bean type. */
    @Service
    public static class NotAHandler {
    }

    // ---- Targets ----

    /** Test 1: the target declares no checked exception; the handler re-throws a checked one. */
    @Service
    public static class NoThrowsClauseTarget {
        @ExceptionCatch(handler = "checkedRethrowingHandler", defaultValue = "fallback")
        public String call() {
            throw new RuntimeException("target-trigger");
        }
    }

    /** Test 2: same handler, but the target method DOES declare the checked exception. */
    @Service
    public static class DeclaredThrowsClauseTarget {
        @ExceptionCatch(handler = "checkedRethrowingHandler", defaultValue = "fallback")
        public String call() throws HandlerCheckedException {
            throw new RuntimeException("target-trigger");
        }
    }

    /** Test 3: the handler re-throws an unchecked exception. */
    @Service
    public static class UncheckedHandlerTarget {
        @ExceptionCatch(handler = "uncheckedRethrowingHandler", defaultValue = "fallback")
        public String call() {
            throw new RuntimeException("target-trigger");
        }
    }

    /** Test 4: the configured handler name does not resolve to any registered bean. */
    @Service
    public static class UnresolvableHandlerTarget {
        @ExceptionCatch(handler = "doesNotExist", defaultValue = "fallback")
        public String call() {
            throw new RuntimeException("target-trigger");
        }
    }

    /** Test 5: the configured handler name resolves, but to a bean that is not an ExceptionHandler. */
    @Service
    public static class WrongTypeHandlerTarget {
        @ExceptionCatch(handler = "notAHandler", defaultValue = "fallback")
        public String call() {
            throw new RuntimeException("target-trigger");
        }
    }

    /** Test 6: the annotated method reached only through {@link AopSelfInvocationFixture}'s self-invocation path. */
    @Service
    public static class SelfInvocationTarget extends AopSelfInvocationFixture {
        @ExceptionCatch(handler = "checkedRethrowingHandler", defaultValue = "fallback")
        @Override
        public Object annotated() {
            throw new RuntimeException("target-trigger");
        }
    }

    private static SimpleContainer wiredContainer(Class<?>... beans) {
        SimpleContainer context = new SimpleContainer();
        invokeWireAop(context);
        for (Class<?> bean : beans) {
            context.registerBean(bean);
        }
        context.refresh();
        return context;
    }

    /**
     * {@link PluginManager#wireAop(SimpleContainer, DataScope)} is package-private in a different
     * package by deliberate design (see {@code AopActivationTest}'s identical helper, which this
     * mirrors rather than duplicates logic for) - reflection reaches the exact production wiring
     * code instead of a stand-in that would pass regardless of whether {@code wireAop} itself is
     * broken. {@code UltiTools.getInstance().getDataStore()} is stubbed to a {@link JsonStore} for
     * the duration of the call: this file is about {@code @ExceptionCatch}'s custom-handler
     * rethrow semantics, not the {@code @Transactional} JDBC path 02-01 added.
     */
    private static void invokeWireAop(SimpleContainer context) {
        try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
            UltiTools mockUltiTools = mock(UltiTools.class);
            DataStore jsonStore = new JsonStore("build/test-exception-rethrow-json");
            when(mockUltiTools.getDataStore()).thenReturn(jsonStore);
            ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);

            Method wireAop = PluginManager.class.getDeclaredMethod("wireAop", SimpleContainer.class, DataScope.class);
            wireAop.setAccessible(true);
            wireAop.invoke(null, context, newDataScope());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke PluginManager.wireAop via reflection", e);
        }
    }

    private static DataScope newDataScope() {
        try {
            Constructor<DataScope> ctor = DataScope.class.getDeclaredConstructor(String.class, File.class, Set.class);
            ctor.setAccessible(true);
            return ctor.newInstance("exception-handler-rethrow-test", new File("."), Collections.emptySet());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to construct DataScope via reflection", e);
        }
    }

    @Test
    @DisplayName("Test 1: undeclared checked rethrow reaches an external caller as its original type")
    void undeclaredCheckedRethrowReachesExternalCaller() {
        NoThrowsClauseTarget bean =
                wiredContainer(CheckedRethrowingHandler.class, NoThrowsClauseTarget.class)
                        .getBean(NoThrowsClauseTarget.class);

        HandlerCheckedException thrown = assertThrows(HandlerCheckedException.class, bean::call,
                "the target method declares no checked exception, so this measures whether "
                        + "ByteBuddy's InvocationHandlerAdapter wraps an undeclared checked "
                        + "Throwable the way java.lang.reflect.Proxy does");
        assertEquals("handler-rethrow", thrown.getMessage());
    }

    @Test
    @DisplayName("Test 2: declared checked rethrow reaches an external caller as its original type")
    void declaredCheckedRethrowReachesExternalCaller() {
        DeclaredThrowsClauseTarget bean =
                wiredContainer(CheckedRethrowingHandler.class, DeclaredThrowsClauseTarget.class)
                        .getBean(DeclaredThrowsClauseTarget.class);

        HandlerCheckedException thrown = assertThrows(HandlerCheckedException.class, bean::call,
                "the throws-clause variant must not produce a different caller-visible outcome "
                        + "than Test 1");
        assertEquals("handler-rethrow", thrown.getMessage());
    }

    @Test
    @DisplayName("Test 3: an unchecked rethrow reaches the caller unchanged")
    void uncheckedRethrowReachesCallerUnchanged() {
        UncheckedHandlerTarget bean =
                wiredContainer(UncheckedRethrowingHandler.class, UncheckedHandlerTarget.class)
                        .getBean(UncheckedHandlerTarget.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, bean::call);
        assertEquals("handler-unchecked-rethrow", thrown.getMessage());
    }

    @Test
    @DisplayName("Test 4: an unresolvable handler bean falls back to the default value gracefully")
    void unresolvableHandlerFallsBackToDefault() {
        UnresolvableHandlerTarget bean =
                wiredContainer(UnresolvableHandlerTarget.class).getBean(UnresolvableHandlerTarget.class);

        assertEquals("fallback", bean.call());
    }

    @Test
    @DisplayName("Test 5: a handler bean of the wrong type falls back to the default value gracefully")
    void wrongTypeHandlerFallsBackToDefault() {
        WrongTypeHandlerTarget bean =
                wiredContainer(NotAHandler.class, WrongTypeHandlerTarget.class)
                        .getBean(WrongTypeHandlerTarget.class);

        assertEquals("fallback", bean.call());
    }

    @Test
    @DisplayName("Test 6: self-invocation through an un-annotated caller is still intercepted")
    void selfInvocationIsInterceptedByThisProxyDesign() {
        SelfInvocationTarget bean =
                wiredContainer(CheckedRethrowingHandler.class, SelfInvocationTarget.class)
                        .getBean(SelfInvocationTarget.class);

        // Measured, not assumed - see AopSelfInvocationFixture's class javadoc. This codebase's
        // proxy is instantiated directly as the bean (no separate delegate target instance), so
        // this.annotated() called from viaSelfInvocation() - an un-annotated, never-overridden
        // method - is a plain virtual dispatch that still resolves to the overridden, intercepted
        // annotated(). The classic "self-invocation bypasses AOP" limitation is specific to
        // delegating proxies (JDK dynamic proxies, and Spring's CGLIB proxies, both of which
        // invoke the target's own methods on a *separate* target instance); it does not apply
        // here, and REQUIREMENTS.md/RESEARCH.md's blanket statement that self-invocation "never
        // goes through the proxy at all" is corrected by this measurement for this codebase - see
        // this plan's SUMMARY.
        HandlerCheckedException thrown = assertThrows(HandlerCheckedException.class,
                bean::viaSelfInvocation,
                "self-invocation through an unannotated caller still resolves virtually to the "
                        + "overridden, intercepted annotated() on this single-instance proxy design");
        assertEquals("handler-rethrow", thrown.getMessage());
    }
}
