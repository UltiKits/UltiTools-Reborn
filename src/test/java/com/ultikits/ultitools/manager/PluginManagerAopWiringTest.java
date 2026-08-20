package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.context.SimpleContainer;

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

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            sb.append(current.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
