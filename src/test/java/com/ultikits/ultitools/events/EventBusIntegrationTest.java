package com.ultikits.ultitools.events;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.ModuleEventHandler;
import com.ultikits.ultitools.entities.Subscription;

@DisplayName("EventBus Integration Tests")
class EventBusIntegrationTest {

    private EventBus eventBus;

    static class BalanceChangeEvent extends ModuleEvent {
        private final double amount;
        BalanceChangeEvent(double amount) { this.amount = amount; }
        double getAmount() { return amount; }
    }

    static class PlayerTeleportEvent extends ModuleEvent {}

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    @DisplayName("cross-module communication: Module A publishes, Module B receives")
    void crossModuleCommunication() {
        List<Double> received = new ArrayList<>();

        // Module B subscribes
        eventBus.subscribe(BalanceChangeEvent.class, EventPriority.NORMAL, false, "ModuleB",
                e -> received.add(e.getAmount()));

        // Module A publishes
        BalanceChangeEvent event = new BalanceChangeEvent(100.0);
        event.setSourceModule("ModuleA");
        eventBus.publish(event);

        assertThat(received).containsExactly(100.0);
        assertThat(event.getSourceModule()).isEqualTo("ModuleA");
    }

    @Test
    @DisplayName("unregistering Module B stops delivery without affecting Module C")
    void unregisterModuleSelectivity() {
        List<String> calls = new ArrayList<>();
        eventBus.subscribe(BalanceChangeEvent.class, EventPriority.NORMAL, false, "ModuleB",
                e -> calls.add("B"));
        eventBus.subscribe(BalanceChangeEvent.class, EventPriority.NORMAL, false, "ModuleC",
                e -> calls.add("C"));

        eventBus.unregisterAll("ModuleB");
        eventBus.publish(new BalanceChangeEvent(50.0));

        assertThat(calls).containsExactly("C");
    }

    @Test
    @DisplayName("different event types are independent")
    void differentEventTypesIndependent() {
        List<String> calls = new ArrayList<>();
        eventBus.subscribe(BalanceChangeEvent.class, e -> calls.add("balance"));
        eventBus.subscribe(PlayerTeleportEvent.class, e -> calls.add("teleport"));

        eventBus.publish(new BalanceChangeEvent(10.0));
        assertThat(calls).containsExactly("balance");
    }

    @Test
    @DisplayName("mixed annotation and programmatic handlers both fire")
    void mixedAnnotationAndProgrammatic() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());

        // Annotation-based
        Object handler = new Object() {
            @ModuleEventHandler
            public void onBalance(BalanceChangeEvent e) { calls.add("annotation"); }
        };
        java.lang.reflect.Method m = handler.getClass().getMethod("onBalance", BalanceChangeEvent.class);
        eventBus.register(BalanceChangeEvent.class, EventPriority.NORMAL, false, "ModuleA", m, handler);

        // Programmatic
        eventBus.subscribe(BalanceChangeEvent.class, e -> calls.add("programmatic"));

        eventBus.publish(new BalanceChangeEvent(25.0));
        assertThat(calls).containsExactlyInAnyOrder("annotation", "programmatic");
    }
}
