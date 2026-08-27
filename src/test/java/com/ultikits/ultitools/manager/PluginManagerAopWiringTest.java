package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopEligibility;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;

@DisplayName("PluginManager AOP wiring")
class PluginManagerAopWiringTest {

    // wireAop now resolves a DataSource through UltiTools.getInstance().getDataStore() (D-01).
    // Every test in this file exercises @ExceptionCatch wiring or annotation-coverage plumbing,
    // not @Transactional's JDBC path, so the JSON store's UnsupportedOperationException branch
    // (declare-unavailable, matching pre-6.3.0 behavior) is the right stand-in here.
    private MockedStatic<UltiTools> ultiToolsMock;
    private DataScope scope;

    @BeforeEach
    void setUpDataStore() {
        UltiTools mockUltiTools = mock(UltiTools.class);
        DataStore jsonStore = new JsonStore("build/test-wireaop-json");
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
        PluginManager.wireAop(context, scope);

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
    @DisplayName("Should reject a bean using @Transactional when the backend has no DataSource yet (JSON)")
    void shouldRejectTransactionalBean() {
        SimpleContainer context = new SimpleContainer();
        PluginManager.wireAop(context, scope);
        context.registerBean(Transactionally.class);

        RuntimeException thrown = assertThrows(RuntimeException.class, context::refresh);
        String message = rootMessage(thrown);
        assertTrue(message.contains("Transactional"), message);
        assertTrue(message.contains("datasource.type"), message);
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
