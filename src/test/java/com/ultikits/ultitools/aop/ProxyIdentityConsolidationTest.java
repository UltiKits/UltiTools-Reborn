package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.finalviolation.validator.SealedBase;
import com.ultikits.ultitools.context.FinalContractValidator;
import com.ultikits.ultitools.manager.TaskManager;

/**
 * Invariant test for the proxy-identity consolidation (D-35).
 * <p>
 * Three call sites used to derive proxy identity independently: {@code
 * TaskManager.getTargetClass}, {@code ExceptionInterceptor.beanClassOf}, and {@code
 * FinalContractValidator}'s proxy-skip check. Each now asks {@link ProxyFactory} instead, and
 * this test pins that they agree - including on the one case the three previous implementations
 * disagreed on: a proxy of a proxy.
 * <p>
 * This test compares <b>outcomes</b>, not implementations, so it goes red the moment a future
 * change reintroduces a local derivation at any one of the three sites, without needing to know
 * in advance what shape that derivation would take.
 *
 * @author wisdomme
 * @since 6.3.0
 */
@DisplayName("Proxy identity consolidation (@ProxyOf)")
class ProxyIdentityConsolidationTest {

    public static class ConsolidationTarget {
        public String value() {
            return "v";
        }
    }

    /** Reflectively invokes {@code TaskManager}'s private unwrap site. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Class<?> taskManagerResolves(Class<?> clazz) throws Exception {
        Method getTargetClass = TaskManager.class.getDeclaredMethod("getTargetClass", Class.class);
        getTargetClass.setAccessible(true);
        // hostPlugin is irrelevant to getTargetClass; null is safe here.
        return (Class<?>) getTargetClass.invoke(new TaskManager(null), clazz);
    }

    /** Reflectively invokes {@code ExceptionInterceptor}'s private unwrap site. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Class<?> exceptionInterceptorResolves(Object target) throws Exception {
        Method beanClassOf = ExceptionInterceptor.class
                .getDeclaredMethod("beanClassOf", MethodInvocation.class, Method.class);
        beanClassOf.setAccessible(true);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getTarget()).thenReturn(target);
        // beanClassOf only reads the Method argument when target is null (see the null-target
        // test below), so any real Method works here.
        Method anyMethod = Object.class.getMethod("toString");
        return (Class<?>) beanClassOf.invoke(null, invocation, anyMethod);
    }

    @Test
    @DisplayName("Should agree on a plain, non-proxied target")
    void shouldAgreeOnPlainTarget() throws Exception {
        ConsolidationTarget target = new ConsolidationTarget();

        assertEquals(ConsolidationTarget.class, taskManagerResolves(ConsolidationTarget.class));
        assertEquals(ConsolidationTarget.class, exceptionInterceptorResolves(target));
        assertTrue(FinalContractValidator.validate(ConsolidationTarget.class).isEmpty());
    }

    @Test
    @DisplayName("Should agree on a single proxy of a @Final target")
    void shouldAgreeOnASingleProxy() throws Exception {
        ProxyFactory factory = new ProxyFactory(Collections.emptyList());
        Class<? extends SealedBase> proxyClass =
                factory.createProxyClass(SealedBase.class, Collections.emptySet());
        SealedBase proxyInstance = proxyClass.getDeclaredConstructor().newInstance();

        assertEquals(SealedBase.class, taskManagerResolves(proxyClass));
        assertEquals(SealedBase.class, exceptionInterceptorResolves(proxyInstance));
        // Without the proxy-skip, this would report "extends SealedBase, which is @Final" - an
        // empty result here IS the proxy-skip decision, not a coincidence of the fixture.
        List<String> violations = FinalContractValidator.validate(proxyClass);
        assertTrue(violations.isEmpty(),
                "a proxy of a @Final class must be recognised and skipped: " + violations);
    }

    @Test
    @DisplayName("Should agree on a proxy of a proxy - the case the three former implementations disagreed on")
    void shouldAgreeOnAProxyOfAProxy() throws Exception {
        ProxyFactory factory = new ProxyFactory(Collections.emptyList());
        Class<? extends SealedBase> proxyClass =
                factory.createProxyClass(SealedBase.class, Collections.emptySet());
        @SuppressWarnings("unchecked")
        Class<? extends SealedBase> proxyOfProxyClass = (Class<? extends SealedBase>)
                factory.createProxyClass(proxyClass, Collections.emptySet());
        SealedBase proxyOfProxyInstance = proxyOfProxyClass.getDeclaredConstructor().newInstance();

        assertEquals(SealedBase.class, taskManagerResolves(proxyOfProxyClass),
                "TaskManager must resolve a proxy of a proxy to the original target in one step");
        assertEquals(SealedBase.class, exceptionInterceptorResolves(proxyOfProxyInstance),
                "ExceptionInterceptor must resolve a proxy of a proxy to the original target in one step");
        List<String> violations = FinalContractValidator.validate(proxyOfProxyClass);
        assertTrue(violations.isEmpty(),
                "a proxy of a proxy of a @Final class must still be recognised and skipped: " + violations);
    }

    @Test
    @DisplayName("Should preserve ExceptionInterceptor's null-target fallback to the method's declaring class")
    void shouldPreserveNullTargetFallback() throws Exception {
        Class<?> resolved = exceptionInterceptorResolves(null);

        assertEquals(Object.class, resolved,
                "a null invocation target must still resolve to the method's declaring class, "
                        + "not throw - this branch is not proxy identity and is out of scope for D-35");
    }
}
