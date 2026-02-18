package com.ultikits.ultitools.events;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.entities.Subscription;

@DisplayName("EventBus Tests")
class EventBusTest {

    private EventBus eventBus;

    static class TestEvent extends ModuleEvent {}
    static class ChildEvent extends TestEvent {}

    static class CancellableEvent extends ModuleEvent implements Cancellable {
        private boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Nested
    @DisplayName("Sync Publish")
    class SyncPublish {

        @Test
        @DisplayName("handler receives published event")
        void handlerReceivesEvent() {
            AtomicBoolean received = new AtomicBoolean(false);
            eventBus.subscribe(TestEvent.class, e -> received.set(true));
            eventBus.publish(new TestEvent());
            assertThat(received).isTrue();
        }

        @Test
        @DisplayName("handlers execute in priority order")
        void priorityOrder() {
            List<String> order = new ArrayList<>();
            eventBus.subscribe(TestEvent.class, EventPriority.HIGH, false, "A", e -> order.add("HIGH"));
            eventBus.subscribe(TestEvent.class, EventPriority.LOW, false, "B", e -> order.add("LOW"));
            eventBus.subscribe(TestEvent.class, EventPriority.NORMAL, false, "C", e -> order.add("NORMAL"));
            eventBus.publish(new TestEvent());
            assertThat(order).containsExactly("LOW", "NORMAL", "HIGH");
        }

        @Test
        @DisplayName("multiple handlers on same event all execute")
        void multipleHandlers() {
            List<String> calls = new ArrayList<>();
            eventBus.subscribe(TestEvent.class, e -> calls.add("A"));
            eventBus.subscribe(TestEvent.class, e -> calls.add("B"));
            eventBus.publish(new TestEvent());
            assertThat(calls).containsExactly("A", "B");
        }

        @Test
        @DisplayName("publish with no handlers is no-op")
        void noHandlers() {
            assertThatCode(() -> eventBus.publish(new TestEvent())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Cancellable Events")
    class CancellableEvents {

        @Test
        @DisplayName("handler can cancel event")
        void handlerCancelsEvent() {
            eventBus.subscribe(CancellableEvent.class, EventPriority.NORMAL, false, "A",
                    e -> e.setCancelled(true));
            CancellableEvent event = new CancellableEvent();
            eventBus.publish(event);
            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("ignoreCancelled=true skips cancelled events")
        void ignoreCancelledSkips() {
            AtomicBoolean reached = new AtomicBoolean(false);
            eventBus.subscribe(CancellableEvent.class, EventPriority.LOW, false, "A",
                    e -> e.setCancelled(true));
            eventBus.subscribe(CancellableEvent.class, EventPriority.HIGH, true, "B",
                    e -> reached.set(true));
            eventBus.publish(new CancellableEvent());
            assertThat(reached).isFalse();
        }

        @Test
        @DisplayName("ignoreCancelled=false still receives cancelled events")
        void ignoreCancelledFalseStillReceives() {
            AtomicBoolean reached = new AtomicBoolean(false);
            eventBus.subscribe(CancellableEvent.class, EventPriority.LOW, false, "A",
                    e -> e.setCancelled(true));
            eventBus.subscribe(CancellableEvent.class, EventPriority.HIGH, false, "B",
                    e -> reached.set(true));
            eventBus.publish(new CancellableEvent());
            assertThat(reached).isTrue();
        }
    }

    @Nested
    @DisplayName("Async Publish")
    class AsyncPublish {

        @Test
        @DisplayName("async handler runs on different thread")
        void asyncRunsOnDifferentThread() throws Exception {
            AtomicReference<String> threadName = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe(TestEvent.class, e -> {
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            });
            eventBus.publishAsync(new TestEvent());
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).contains("EventBus-Async");
        }

        @Test
        @DisplayName("async rejects Cancellable events")
        void asyncRejectsCancellable() {
            assertThatThrownBy(() -> eventBus.publishAsync(new CancellableEvent()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cancellable");
        }
    }

    @Nested
    @DisplayName("Superclass Matching")
    class SuperclassMatching {

        @Test
        @DisplayName("handler for parent receives child events")
        void parentHandlerReceivesChild() {
            AtomicBoolean received = new AtomicBoolean(false);
            eventBus.subscribe(TestEvent.class, e -> received.set(true));
            eventBus.publish(new ChildEvent());
            assertThat(received).isTrue();
        }

        @Test
        @DisplayName("handler for child does NOT receive parent events")
        void childHandlerIgnoresParent() {
            AtomicBoolean received = new AtomicBoolean(false);
            eventBus.subscribe(ChildEvent.class, e -> received.set(true));
            eventBus.publish(new TestEvent());
            assertThat(received).isFalse();
        }

        @Test
        @DisplayName("handler for ModuleEvent catches all events")
        void baseClassCatchesAll() {
            List<String> received = new ArrayList<>();
            eventBus.subscribe(ModuleEvent.class, e -> received.add(e.getClass().getSimpleName()));
            eventBus.publish(new TestEvent());
            eventBus.publish(new ChildEvent());
            assertThat(received).containsExactly("TestEvent", "ChildEvent");
        }
    }

    @Nested
    @DisplayName("Programmatic Subscribe")
    class ProgrammaticSubscribe {

        @Test
        @DisplayName("subscription.unsubscribe stops delivery")
        void unsubscribeStopsDelivery() {
            List<String> calls = new ArrayList<>();
            Subscription sub = eventBus.subscribe(TestEvent.class, e -> calls.add("hit"));
            eventBus.publish(new TestEvent());
            sub.unsubscribe();
            eventBus.publish(new TestEvent());
            assertThat(calls).hasSize(1);
        }

        @Test
        @DisplayName("subscription.isActive reflects state")
        void isActiveReflectsState() {
            Subscription sub = eventBus.subscribe(TestEvent.class, e -> {});
            assertThat(sub.isActive()).isTrue();
            sub.unsubscribe();
            assertThat(sub.isActive()).isFalse();
        }

        @Test
        @DisplayName("double unsubscribe is safe")
        void doubleUnsubscribeSafe() {
            Subscription sub = eventBus.subscribe(TestEvent.class, e -> {});
            sub.unsubscribe();
            assertThatCode(sub::unsubscribe).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("unregisterAll removes all handlers for a module")
        void unregisterAllRemovesModule() {
            List<String> calls = new ArrayList<>();
            eventBus.subscribe(TestEvent.class, EventPriority.NORMAL, false, "ModuleA", e -> calls.add("A"));
            eventBus.subscribe(TestEvent.class, EventPriority.NORMAL, false, "ModuleB", e -> calls.add("B"));
            eventBus.unregisterAll("ModuleA");
            eventBus.publish(new TestEvent());
            assertThat(calls).containsExactly("B");
        }

        @Test
        @DisplayName("unregisterAll with unknown module is no-op")
        void unregisterAllUnknownModule() {
            assertThatCode(() -> eventBus.unregisterAll("NonExistent")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Error Isolation")
    class ErrorIsolation {

        @Test
        @DisplayName("throwing handler does not break other handlers")
        void throwingHandlerIsolated() {
            List<String> calls = new ArrayList<>();
            eventBus.subscribe(TestEvent.class, EventPriority.LOW, false, "A", e -> {
                throw new RuntimeException("boom");
            });
            eventBus.subscribe(TestEvent.class, EventPriority.HIGH, false, "B", e -> calls.add("B"));
            eventBus.publish(new TestEvent());
            assertThat(calls).containsExactly("B");
        }
    }

    @Nested
    @DisplayName("Annotation-based Registration")
    class AnnotationBasedRegistration {

        @Test
        @DisplayName("annotation-based handler receives events")
        void annotationHandler() throws Exception {
            List<String> calls = new ArrayList<>();
            Object handler = new Object() {
                public void onTest(TestEvent e) { calls.add("received"); }
            };
            Method m = handler.getClass().getMethod("onTest", TestEvent.class);
            eventBus.register(TestEvent.class, EventPriority.NORMAL, false, "TestModule", m, handler);
            eventBus.publish(new TestEvent());
            assertThat(calls).containsExactly("received");
        }
    }
}
