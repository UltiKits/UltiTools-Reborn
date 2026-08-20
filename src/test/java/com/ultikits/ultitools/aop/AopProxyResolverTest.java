package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Final;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.exceptions.ContainerException;

@DisplayName("AopProxyResolver Tests")
class AopProxyResolverTest {

    private AopProxyResolver resolver;
    private List<String> log;

    public static class Plain {
        public String work() { return "plain"; }
    }

    public static class Managed {
        @Transactional
        public String work() { return "managed:" + helper(); }
        @Transactional
        public String helper() { return "helper"; }
    }

    public static final class FinalManaged {
        @Transactional
        public String work() { return "nope"; }
    }

    @Final
    public static class AnnotatedFinalManaged {
        @Transactional
        public String work() { return "ok"; }
    }

    @BeforeEach
    void setUp() {
        log = new ArrayList<>();
        resolver = new AopProxyResolver();
        MethodInterceptor recorder = inv -> {
            log.add(inv.getMethod().getName());
            return inv.proceed();
        };
        resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, recorder, 100));
    }

    @Nested
    @DisplayName("Advisor registry")
    class Registry {

        @Test
        @DisplayName("Should expose registered advisors")
        void shouldExposeAdvisors() {
            assertEquals(1, resolver.getAdvisors().size());
        }

        @Test
        @DisplayName("Should remove a registered advisor")
        void shouldRemoveAdvisor() {
            AopAdvisor advisor = resolver.getAdvisors().get(0);
            assertTrue(resolver.removeAdvisor(advisor));
            assertTrue(resolver.getAdvisors().isEmpty());
        }

        @Test
        @DisplayName("Should report false when removing an advisor that was never added")
        void shouldReportFalseWhenRemovingUnknownAdvisor() {
            AopAdvisor neverAdded = AopAdvisor.forAnnotation(Transactional.class,
                    MethodInvocation::proceed, 50);
            assertFalse(resolver.removeAdvisor(neverAdded));
            assertEquals(1, resolver.getAdvisors().size());
        }

        @Test
        @DisplayName("Should keep advisors sorted by order")
        void shouldSortByOrder() {
            MethodInterceptor noop = MethodInvocation::proceed;
            resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, noop, 10));
            assertEquals(10, resolver.getAdvisors().get(0).getOrder());
            assertEquals(100, resolver.getAdvisors().get(1).getOrder());
        }

        @Test
        @DisplayName("getAdvisors() should return a defensive copy")
        void shouldReturnDefensiveCopy() {
            List<AopAdvisor> exposed = resolver.getAdvisors();
            exposed.clear();
            exposed.add(AopAdvisor.forAnnotation(Transactional.class, MethodInvocation::proceed, 1));

            // Mutating the returned list must not leak back into the resolver's internal state.
            assertEquals(1, resolver.getAdvisors().size());
            assertEquals(100, resolver.getAdvisors().get(0).getOrder());
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolving {

        @Test
        @DisplayName("Should return the original class when no advisor matches")
        void shouldReturnOriginalForPlainClass() {
            assertSame(Plain.class, resolver.resolve(Plain.class));
        }

        @Test
        @DisplayName("Should return the original class when no advisors are registered")
        void shouldReturnOriginalWhenNoAdvisors() {
            AopProxyResolver empty = new AopProxyResolver();
            assertSame(Managed.class, empty.resolve(Managed.class));
        }

        @Test
        @DisplayName("Should return a proxy subclass for a managed class")
        void shouldReturnProxyForManagedClass() {
            Class<?> resolved = resolver.resolve(Managed.class);
            assertTrue(ProxyFactory.isProxyClass(resolved));
            assertTrue(Managed.class.isAssignableFrom(resolved));
        }

        @Test
        @DisplayName("Should intercept self-invocation on the resolved class")
        void shouldInterceptSelfInvocation() throws Exception {
            Class<?> resolved = resolver.resolve(Managed.class);
            Managed bean = (Managed) resolved.getDeclaredConstructor().newInstance();

            assertEquals("managed:helper", bean.work());
            assertEquals(Arrays.asList("work", "helper"), log);
        }

        @Test
        @DisplayName("Should reject a final class with an actionable message")
        void shouldRejectFinalClass() {
            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> resolver.resolve(FinalManaged.class));
            assertTrue(thrown.getMessage().contains(FinalManaged.class.getName()),
                    thrown.getMessage());
            assertTrue(thrown.getMessage().contains("@Final"), thrown.getMessage());
        }

        @Test
        @DisplayName("Should accept a class marked @Final")
        void shouldAcceptAnnotatedFinalClass() {
            Class<?> resolved = resolver.resolve(AnnotatedFinalManaged.class);
            assertTrue(ProxyFactory.isProxyClass(resolved));
        }
    }

    @Nested
    @DisplayName("Annotations declared unavailable")
    class Unavailable {

        @Test
        @DisplayName("Should reject a bean whose method uses an unavailable annotation")
        void shouldRejectMethodLevelUnavailable() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addUnavailableAnnotation(Transactional.class,
                    "requires a TransactionManager bound to a DataSource, see #195/#196");

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> bare.resolve(Managed.class));

            assertTrue(thrown.getMessage().contains("Transactional"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("#195"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("work"),
                    "the message must name the offending method: " + thrown.getMessage());
        }

        @Test
        @DisplayName("Should check unavailable annotations even with no advisors registered")
        void shouldCheckBeforeAdvisorShortCircuit() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addUnavailableAnnotation(Transactional.class, "unavailable");
            assertThrows(ContainerException.class, () -> bare.resolve(Managed.class));
        }

        @Test
        @DisplayName("Should leave beans not using the unavailable annotation alone")
        void shouldIgnoreUnrelatedBeans() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addUnavailableAnnotation(Transactional.class, "unavailable");
            assertSame(Plain.class, bare.resolve(Plain.class));
        }
    }
}
