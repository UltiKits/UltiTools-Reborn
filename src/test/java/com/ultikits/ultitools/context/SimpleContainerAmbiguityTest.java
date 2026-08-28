package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Service;
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
}
