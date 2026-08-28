package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.MethodInterceptor;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.exceptions.ContainerException;

/**
 * Covers {@code SimpleContainer#getBean(Class)}'s by-type ambiguity adjudication (D-11/D-12):
 * priority-ordered resolution between assignable candidates, a load-time refusal naming both
 * candidates on a genuine tie, cache invalidation when a later implementation is registered, and
 * the dependency priority ordering silently rests on -- that {@code ProxyFactory} copies the
 * target's annotations onto the generated AOP subclass.
 */
@DisplayName("SimpleContainer by-type ambiguity adjudication")
class SimpleContainerAmbiguityTest {

    // === Fixture types ===

    interface Greeter {
        String greet();
    }

    @Service(priority = 10)
    static class HighPriorityGreeter implements Greeter {
        @Override
        public String greet() {
            return "high";
        }
    }

    @Service(priority = 5)
    static class LowPriorityGreeter implements Greeter {
        @Override
        public String greet() {
            return "low";
        }
    }

    @Service(priority = 5)
    static class AnotherLowPriorityGreeter implements Greeter {
        @Override
        public String greet() {
            return "low-2";
        }
    }

    @Service
    static class DefaultPriorityGreeterA implements Greeter {
        @Override
        public String greet() {
            return "default-a";
        }
    }

    @Service
    static class DefaultPriorityGreeterB implements Greeter {
        @Override
        public String greet() {
            return "default-b";
        }
    }

    @Service(priority = 7)
    static class EqualPriorityGreeterA implements Greeter {
        @Override
        public String greet() {
            return "equal-a";
        }
    }

    @Service(priority = 7)
    static class EqualPriorityGreeterB implements Greeter {
        @Override
        public String greet() {
            return "equal-b";
        }
    }

    @Service
    static class ParentGreeterImpl implements Greeter {
        @Override
        public String greet() {
            return "parent";
        }
    }

    @Service
    static class ChildGreeterImpl implements Greeter {
        @Override
        public String greet() {
            return "child";
        }
    }

    interface Marker {
        String id();
    }

    static class MarkerImpl implements Marker {
        @Override
        public String id() {
            return "marker";
        }
    }

    @Service(priority = 20)
    static class LateHigherPriorityGreeter implements Greeter {
        @Override
        public String greet() {
            return "late-higher";
        }
    }

    @Service(priority = 0)
    static class LateEqualPriorityGreeter implements Greeter {
        @Override
        public String greet() {
            return "late-equal";
        }
    }

    static class UnrelatedSingleton {
    }

    static class UnrelatedBean {
    }

    @Service(priority = 10)
    static class ProxiedHighPriorityGreeter implements Greeter {
        @Transactional
        @Override
        public String greet() {
            return "proxied-high";
        }
    }

    @Service(priority = 5)
    static class PlainLowPriorityGreeter implements Greeter {
        @Override
        public String greet() {
            return "plain-low";
        }
    }

    private static SimpleContainer containerWithAop() {
        SimpleContainer container = new SimpleContainer();
        AopProxyResolver resolver = new AopProxyResolver();
        MethodInterceptor passthrough = invocation -> invocation.proceed();
        resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, passthrough, 100));
        container.setAopProxyResolver(resolver);
        return container;
    }

    // === Task 1: priority ordering and the tie refusal ===

    @Nested
    @DisplayName("Priority-ordered resolution")
    class PriorityOrderingTests {

        @Test
        @DisplayName("A single assignable candidate resolves without error")
        void singleCandidateResolves() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(HighPriorityGreeter.class);
            container.refresh();

            Greeter bean = container.getBean(Greeter.class);

            assertNotNull(bean);
            assertEquals("high", bean.greet());
        }

        @Test
        @DisplayName("Priority 10 beats priority 5 -- registration order A, B")
        void higherPriorityWinsRegisteredHighFirst() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(HighPriorityGreeter.class);
            container.registerBean(LowPriorityGreeter.class);
            container.refresh();

            Greeter bean = container.getBean(Greeter.class);

            assertEquals("high", bean.greet(),
                    "priority 10 must win regardless of registration order");
        }

        @Test
        @DisplayName("Priority 10 beats priority 5 -- registration order reversed (B, A)")
        void higherPriorityWinsRegisteredLowFirst() {
            // Inert-case guard: a single-order test can pass under first-match-wins hash
            // iteration purely by luck. Reversing registration order is what proves priority,
            // not registration order, decided the outcome.
            SimpleContainer container = new SimpleContainer();
            container.registerBean(LowPriorityGreeter.class);
            container.registerBean(HighPriorityGreeter.class);
            container.refresh();

            Greeter bean = container.getBean(Greeter.class);

            assertEquals("high", bean.greet(),
                    "priority 10 must win regardless of registration order");
        }

        @Test
        @DisplayName("Direction matches @Service's own javadoc: higher value wins, not Spring's lower-wins")
        void directionMatchesServiceJavadocNotSpring() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(LowPriorityGreeter.class);
            container.registerBean(HighPriorityGreeter.class);
            container.refresh();

            Greeter bean = container.getBean(Greeter.class);

            // If the framework silently adopted Spring's opposite (lower-wins) direction, this
            // would return "low" instead -- deterministic, but inverted from what every
            // 6.2.5-era javadoc reader expects.
            assertEquals("high", bean.greet());
        }

        @Test
        @DisplayName("A tie among lower-ranked candidates does not throw when the top candidate is unambiguous")
        void tieAmongLowerCandidatesDoesNotThrow() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(HighPriorityGreeter.class);
            container.registerBean(LowPriorityGreeter.class);
            container.registerBean(AnotherLowPriorityGreeter.class);
            container.refresh();

            Greeter bean = assertDoesNotThrow(() -> container.getBean(Greeter.class),
                    "priorities 10/5/5 must resolve to the unambiguous top candidate");

            assertEquals("high", bean.greet());
        }

        @Test
        @DisplayName("Two candidates at the default priority (0) throw, naming both")
        void defaultPriorityTieThrowsNamingBoth() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(DefaultPriorityGreeterA.class);
            container.registerBean(DefaultPriorityGreeterB.class);
            container.refresh();

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> container.getBean(Greeter.class));

            assertTrue(thrown.getMessage().contains(DefaultPriorityGreeterA.class.getName()));
            assertTrue(thrown.getMessage().contains(DefaultPriorityGreeterB.class.getName()));
        }

        @Test
        @DisplayName("Two candidates at an equal explicit priority (7) throw, naming both")
        void equalExplicitPriorityTieThrowsNamingBoth() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(EqualPriorityGreeterA.class);
            container.registerBean(EqualPriorityGreeterB.class);
            container.refresh();

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> container.getBean(Greeter.class));

            assertTrue(thrown.getMessage().contains(EqualPriorityGreeterA.class.getName()));
            assertTrue(thrown.getMessage().contains(EqualPriorityGreeterB.class.getName()));
        }

        @Test
        @DisplayName("An exact bean-name match still short-circuits ahead of assignability resolution")
        void exactNameMatchStillShortCircuits() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(MarkerImpl.class);
            container.refresh();

            // MarkerImpl is registered under its own concrete-class name, so requesting it by
            // its own concrete class resolves via the exact-name path, unaffected by any
            // assignability adjudication.
            MarkerImpl bean = container.getBean(MarkerImpl.class);

            assertEquals("marker", bean.id());
        }
    }

    @Nested
    @DisplayName("Parent-child delegation (D-13)")
    class ParentDelegationTests {

        @Test
        @DisplayName("Zero candidates in the child fall through to the parent's bean")
        void zeroCandidatesFallsThroughToParent() {
            SimpleContainer parent = new SimpleContainer();
            parent.registerBean(ParentGreeterImpl.class);
            parent.refresh();

            SimpleContainer child = new SimpleContainer();
            child.setParent(parent);
            child.refresh();

            Greeter bean = child.getBean(Greeter.class);

            assertEquals("parent", bean.greet());
        }

        @Test
        @DisplayName("One candidate in the child is returned, and the parent is not consulted")
        void oneCandidateInChildIsNotOverriddenByParent() {
            SimpleContainer parent = new SimpleContainer();
            parent.registerBean(ParentGreeterImpl.class);
            parent.refresh();

            SimpleContainer child = new SimpleContainer();
            child.setParent(parent);
            child.registerBean(ChildGreeterImpl.class);
            child.refresh();

            Greeter bean = child.getBean(Greeter.class);

            assertEquals("child", bean.greet(),
                    "the child's own candidate must win; the parent must not be consulted");
        }

        @Test
        @DisplayName("Two ambiguous candidates in the child throw and never fall through to the parent")
        void ambiguousChildDoesNotFallThroughToParent() {
            SimpleContainer parent = new SimpleContainer();
            parent.registerBean(ParentGreeterImpl.class);
            parent.refresh();

            SimpleContainer child = new SimpleContainer();
            child.setParent(parent);
            child.registerBean(DefaultPriorityGreeterA.class);
            child.registerBean(DefaultPriorityGreeterB.class);
            child.refresh();

            assertThrows(ContainerException.class, () -> child.getBean(Greeter.class));
        }
    }

    // === Task 2: cache invalidation ===

    @Nested
    @DisplayName("Resolution cache invalidation (D-12)")
    class CacheInvalidationTests {

        @Test
        @DisplayName("A late higher-priority implementation is not masked by an earlier resolution")
        void lateHigherPriorityImplementationParticipatesInNextResolution() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(LowPriorityGreeter.class);
            container.refresh();

            Greeter firstResolution = container.getBean(Greeter.class);
            assertEquals("low", firstResolution.greet());

            container.registerBean(LateHigherPriorityGreeter.class);
            container.refresh();

            Greeter secondResolution = container.getBean(Greeter.class);

            // Inert-case guard: without invalidation the first resolution stays cached
            // permanently and this would still return "low".
            assertEquals("late-higher", secondResolution.greet());
        }

        @Test
        @DisplayName("A late equal-priority implementation throws on the next resolution")
        void lateEqualPriorityImplementationThrowsOnNextResolution() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(DefaultPriorityGreeterA.class);
            container.refresh();

            Greeter firstResolution = container.getBean(Greeter.class);
            assertEquals("default-a", firstResolution.greet());

            container.registerBean(LateEqualPriorityGreeter.class);
            container.refresh();

            // Inert-case guard: this is precisely the scenario D-12 exists for. Without
            // invalidation the cached first resolution is returned and the ambiguity check
            // never fires here -- indistinguishable from there being no check at all.
            assertThrows(ContainerException.class, () -> container.getBean(Greeter.class));
        }

        @Test
        @DisplayName("An explicit registerType binding survives an unrelated registerSingleton and registerBeanDefinition")
        void explicitTypeBindingSurvivesUnrelatedRegistrations() {
            SimpleContainer container = new SimpleContainer();
            MarkerImpl explicitInstance = new MarkerImpl();
            container.registerType(Marker.class, explicitInstance);

            container.registerSingleton("unrelatedSingleton", new UnrelatedSingleton());
            container.registerBean(UnrelatedBean.class);
            container.refresh();

            Marker resolved = container.getBean(Marker.class);

            // Inert-case guard: a blanket typeMappings.clear() invalidation strategy would
            // satisfy every other assertion in this class while silently dropping this
            // author-declared binding.
            assertSame(explicitInstance, resolved);
        }

        @Test
        @DisplayName("Repeated resolution with no intervening registration returns the same cached instance")
        void repeatedResolutionWithoutRegistrationReturnsCachedInstance() {
            SimpleContainer container = new SimpleContainer();
            container.registerBean(HighPriorityGreeter.class);
            container.refresh();

            Greeter first = container.getBean(Greeter.class);
            Greeter second = container.getBean(Greeter.class);

            assertSame(first, second);
        }
    }

    // === Task 3: the proxy-annotation-copy dependency ===

    @Nested
    @DisplayName("Priority ordering for a proxied bean (D-11's dependency on ProxyFactory)")
    class ProxyPriorityTests {

        @Test
        @DisplayName("A proxied @Service(priority) bean reports its true priority, not 0")
        void proxiedBeanReportsTruePriority() {
            SimpleContainer container = containerWithAop();
            container.registerBean(ProxiedHighPriorityGreeter.class);
            container.refresh();

            Greeter bean = container.getBean(ProxiedHighPriorityGreeter.class);

            // Ordering matters: assert the bean is genuinely proxied FIRST. Without this guard,
            // a harness that silently failed to wire AOP would hand back the plain class, and
            // the remaining assertions would pass without ever exercising the behaviour this
            // test exists to pin.
            assertTrue(ProxyFactory.isProxyClass(bean.getClass()),
                    "the harness must be exercising the proxied path, not a plain instance");

            Service annotation = bean.getClass().getAnnotation(Service.class);
            assertNotNull(annotation,
                    "ProxyFactory must copy the target's @Service annotation onto the generated subclass");
            assertEquals(10, annotation.priority());
        }

        @Test
        @DisplayName("A proxied higher-priority bean wins a real getBean(Interface.class) resolution against an unproxied lower-priority one")
        void proxiedHigherPriorityBeanWinsRealResolution() {
            SimpleContainer container = containerWithAop();
            container.registerBean(ProxiedHighPriorityGreeter.class);
            container.registerBean(PlainLowPriorityGreeter.class);
            container.refresh();

            Greeter proxiedCandidate = container.getBean(ProxiedHighPriorityGreeter.class);
            assertTrue(ProxyFactory.isProxyClass(proxiedCandidate.getClass()));

            // The assertion that matters: the proxied bean must win a real interface-typed
            // resolution, not merely report the right value from an isolated getter call.
            Greeter resolved = container.getBean(Greeter.class);

            assertEquals("proxied-high", resolved.greet());
            assertFalse(resolved instanceof PlainLowPriorityGreeter);
        }
    }
}
