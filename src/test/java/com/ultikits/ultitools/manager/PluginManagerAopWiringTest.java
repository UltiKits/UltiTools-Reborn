package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopEligibility;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ContainerException;

@DisplayName("PluginManager AOP wiring")
class PluginManagerAopWiringTest {

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

    @Test
    @DisplayName("Should register exactly one advisor, for @ExceptionCatch")
    void shouldRegisterExceptionCatchAdvisorOnly() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context);

        AopProxyResolver resolver = context.getAopProxyResolver();
        assertNotNull(resolver, "wireAop must attach a resolver");
        List<AopAdvisor> advisors = resolver.getAdvisors();
        assertEquals(1, advisors.size(),
                "@Transactional is declared unavailable this release, so only one advisor");
        assertEquals(ExceptionCatch.class, advisors.get(0).getAnnotationType(),
                "the one registered advisor must actually be the one that serves @ExceptionCatch");
    }

    @Test
    @DisplayName("Should proxy a bean using @ExceptionCatch")
    void shouldProxyExceptionCatchBean() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context);
        context.registerBean(Guarded.class);
        context.refresh();

        Guarded bean = context.getBean(Guarded.class);

        assertTrue(ProxyFactory.isProxyClass(bean.getClass()));
    }

    @Test
    @DisplayName("Should swallow the exception through the wired @ExceptionCatch interceptor")
    void shouldSwallowThroughWiredInterceptor() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context);
        context.registerBean(Guarded.class);
        context.refresh();

        assertEquals(null, context.getBean(Guarded.class).boom(),
                "@ExceptionCatch must actually take effect, not merely be present");
    }

    @Test
    @DisplayName("Should reject a bean using @Transactional with a message naming #195/#196")
    void shouldRejectTransactionalBean() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context);
        context.registerBean(Transactionally.class);

        RuntimeException thrown = assertThrows(RuntimeException.class, context::refresh);
        String message = rootMessage(thrown);
        assertTrue(message.contains("Transactional"), message);
        assertTrue(message.contains("#195"), message);
    }

    @Test
    @DisplayName("Should leave plain beans unproxied")
    void shouldLeavePlainBeansAlone() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context);
        context.registerBean(Plain.class);
        context.refresh();

        assertSame(Plain.class, context.getBean(Plain.class).getClass());
    }

    @Test
    @DisplayName("Should produce a resolver that passes annotation coverage validation")
    void shouldPassAnnotationCoverageValidation() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context);

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
                    assertThrows(ContainerException.class, () -> PluginManager.wireAop(context));
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
