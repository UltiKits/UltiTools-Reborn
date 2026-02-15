package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link TempEventHandler} interface.
 */
@DisplayName("TempEventHandler Interface Tests")
class TempEventHandlerTest {

    @Nested
    @DisplayName("Interface Structure Tests")
    class InterfaceStructureTests {

        @Test
        @DisplayName("Should be an interface")
        void shouldBeInterface() {
            assertThat(TempEventHandler.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("Should be a functional interface")
        void shouldBeFunctionalInterface() {
            assertThat(TempEventHandler.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
        }

        @Test
        @DisplayName("Should have generic type parameter E")
        void shouldHaveGenericTypeParameter() {
            TypeVariable<?>[] typeParams = TempEventHandler.class.getTypeParameters();
            assertThat(typeParams).hasSize(1);
            assertThat(typeParams[0].getName()).isEqualTo("E");
        }

        @Test
        @DisplayName("Type parameter E should extend Event")
        void typeParameterShouldExtendEvent() {
            TypeVariable<?>[] typeParams = TempEventHandler.class.getTypeParameters();
            Type[] bounds = typeParams[0].getBounds();
            assertThat(bounds).hasSize(1);
            assertThat(bounds[0]).isEqualTo(Event.class);
        }

        @Test
        @DisplayName("Should have handle method")
        void shouldHaveHandleMethod() throws NoSuchMethodException {
            Method method = TempEventHandler.class.getMethod("handle", Event.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handle method should return boolean")
        void handleMethodShouldReturnBoolean() throws NoSuchMethodException {
            Method method = TempEventHandler.class.getMethod("handle", Event.class);
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("handle method should accept generic type E")
        void handleMethodShouldAcceptGenericType() throws NoSuchMethodException {
            Method method = TempEventHandler.class.getMethod("handle", Event.class);
            Type[] paramTypes = method.getGenericParameterTypes();
            assertThat(paramTypes).hasSize(1);
            assertThat(paramTypes[0]).isInstanceOf(TypeVariable.class);
            assertThat(((TypeVariable<?>) paramTypes[0]).getName()).isEqualTo("E");
        }

        @Test
        @DisplayName("Should have exactly 1 abstract method")
        void shouldHaveExactlyOneAbstractMethod() {
            long abstractMethods = java.util.Arrays.stream(TempEventHandler.class.getDeclaredMethods())
                    .filter(m -> Modifier.isAbstract(m.getModifiers()))
                    .count();
            assertThat(abstractMethods).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Lambda Implementation Tests")
    class LambdaImplementationTests {

        @Test
        @DisplayName("Should be usable as lambda - always unregister")
        void shouldBeUsableAsLambdaAlwaysUnregister() {
            TempEventHandler<Event> handler = event -> true;

            Event mockEvent = mock(Event.class);
            boolean shouldUnregister = handler.handle(mockEvent);

            assertThat(shouldUnregister).isTrue();
        }

        @Test
        @DisplayName("Should be usable as lambda - never unregister")
        void shouldBeUsableAsLambdaNeverUnregister() {
            TempEventHandler<Event> handler = event -> false;

            Event mockEvent = mock(Event.class);
            boolean shouldUnregister = handler.handle(mockEvent);

            assertThat(shouldUnregister).isFalse();
        }

        @Test
        @DisplayName("Should be usable with conditional unregister")
        void shouldBeUsableWithConditionalUnregister() {
            AtomicInteger callCount = new AtomicInteger(0);

            TempEventHandler<Event> handler = event -> {
                int count = callCount.incrementAndGet();
                return count >= 3; // Unregister after 3 calls
            };

            Event mockEvent = mock(Event.class);

            assertThat(handler.handle(mockEvent)).isFalse();
            assertThat(handler.handle(mockEvent)).isFalse();
            assertThat(handler.handle(mockEvent)).isTrue();
        }
    }

    @Nested
    @DisplayName("Anonymous Class Implementation Tests")
    class AnonymousClassImplementationTests {

        @Test
        @DisplayName("Should work with anonymous class")
        void shouldWorkWithAnonymousClass() {
            AtomicBoolean handled = new AtomicBoolean(false);

            TempEventHandler<Event> handler = new TempEventHandler<Event>() {
                @Override
                public boolean handle(Event event) {
                    handled.set(true);
                    return true;
                }
            };

            Event mockEvent = mock(Event.class);
            boolean result = handler.handle(mockEvent);

            assertThat(handled.get()).isTrue();
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should capture external variables")
        void shouldCaptureExternalVariables() {
            final String targetMessage = "test";
            AtomicBoolean matched = new AtomicBoolean(false);

            TempEventHandler<Event> handler = new TempEventHandler<Event>() {
                @Override
                public boolean handle(Event event) {
                    if (targetMessage.equals("test")) {
                        matched.set(true);
                        return true;
                    }
                    return false;
                }
            };

            Event mockEvent = mock(Event.class);
            handler.handle(mockEvent);

            assertThat(matched.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("Type-Specific Handler Tests")
    class TypeSpecificHandlerTests {

        @Test
        @DisplayName("Should work with PlayerJoinEvent type")
        void shouldWorkWithPlayerJoinEventType() {
            AtomicBoolean handled = new AtomicBoolean(false);

            TempEventHandler<PlayerJoinEvent> handler = event -> {
                handled.set(true);
                return false;
            };

            PlayerJoinEvent mockEvent = mock(PlayerJoinEvent.class);
            boolean result = handler.handle(mockEvent);

            assertThat(handled.get()).isTrue();
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should work with PlayerQuitEvent type")
        void shouldWorkWithPlayerQuitEventType() {
            AtomicBoolean handled = new AtomicBoolean(false);

            TempEventHandler<PlayerQuitEvent> handler = event -> {
                handled.set(true);
                return true;
            };

            PlayerQuitEvent mockEvent = mock(PlayerQuitEvent.class);
            boolean result = handler.handle(mockEvent);

            assertThat(handled.get()).isTrue();
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Unregister Behavior Tests")
    class UnregisterBehaviorTests {

        @Test
        @DisplayName("Return true should indicate unregister")
        void returnTrueShouldIndicateUnregister() {
            TempEventHandler<Event> handler = event -> true;

            Event mockEvent = mock(Event.class);
            boolean shouldUnregister = handler.handle(mockEvent);

            assertThat(shouldUnregister)
                    .as("true should indicate listener should be unregistered")
                    .isTrue();
        }

        @Test
        @DisplayName("Return false should indicate keep registered")
        void returnFalseShouldIndicateKeepRegistered() {
            TempEventHandler<Event> handler = event -> false;

            Event mockEvent = mock(Event.class);
            boolean shouldUnregister = handler.handle(mockEvent);

            assertThat(shouldUnregister)
                    .as("false should indicate listener should stay registered")
                    .isFalse();
        }

        @Test
        @DisplayName("Handler can track event count and unregister after threshold")
        void handlerCanTrackEventCountAndUnregisterAfterThreshold() {
            AtomicInteger eventCount = new AtomicInteger(0);
            final int threshold = 5;

            TempEventHandler<Event> handler = event -> {
                return eventCount.incrementAndGet() >= threshold;
            };

            Event mockEvent = mock(Event.class);

            for (int i = 0; i < threshold - 1; i++) {
                assertThat(handler.handle(mockEvent))
                        .as("Should not unregister before threshold")
                        .isFalse();
            }

            assertThat(handler.handle(mockEvent))
                    .as("Should unregister at threshold")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("State Management Tests")
    class StateManagementTests {

        @Test
        @DisplayName("Handler can maintain internal state")
        void handlerCanMaintainInternalState() {
            class StatefulHandler implements TempEventHandler<Event> {
                private int eventCount = 0;
                private boolean completed = false;

                @Override
                public boolean handle(Event event) {
                    eventCount++;
                    if (eventCount >= 3) {
                        completed = true;
                        return true;
                    }
                    return false;
                }

                public int getEventCount() {
                    return eventCount;
                }

                public boolean isCompleted() {
                    return completed;
                }
            }

            StatefulHandler handler = new StatefulHandler();
            Event mockEvent = mock(Event.class);

            handler.handle(mockEvent);
            assertThat(handler.getEventCount()).isEqualTo(1);
            assertThat(handler.isCompleted()).isFalse();

            handler.handle(mockEvent);
            assertThat(handler.getEventCount()).isEqualTo(2);
            assertThat(handler.isCompleted()).isFalse();

            handler.handle(mockEvent);
            assertThat(handler.getEventCount()).isEqualTo(3);
            assertThat(handler.isCompleted()).isTrue();
        }

        @Test
        @DisplayName("Handler can use external state container")
        void handlerCanUseExternalStateContainer() {
            AtomicInteger sharedCounter = new AtomicInteger(0);
            AtomicBoolean shouldStop = new AtomicBoolean(false);

            TempEventHandler<Event> handler = event -> {
                sharedCounter.incrementAndGet();
                return shouldStop.get();
            };

            Event mockEvent = mock(Event.class);

            handler.handle(mockEvent);
            handler.handle(mockEvent);
            assertThat(sharedCounter.get()).isEqualTo(2);
            assertThat(handler.handle(mockEvent)).isFalse();

            shouldStop.set(true);
            assertThat(handler.handle(mockEvent)).isTrue();
            assertThat(sharedCounter.get()).isEqualTo(4);
        }
    }
}
