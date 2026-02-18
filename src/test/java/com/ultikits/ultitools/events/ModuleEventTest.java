package com.ultikits.ultitools.events;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModuleEvent Tests")
class ModuleEventTest {

    // Concrete subclass for testing
    static class TestEvent extends ModuleEvent {}

    static class CancellableTestEvent extends ModuleEvent implements Cancellable {
        private boolean cancelled;
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    @Test
    @DisplayName("timestamp is set on construction")
    void timestampSetOnConstruction() {
        long before = System.currentTimeMillis();
        TestEvent event = new TestEvent();
        long after = System.currentTimeMillis();
        assertThat(event.getTimestamp()).isBetween(before, after);
    }

    @Test
    @DisplayName("sourceModule is null by default, settable")
    void sourceModuleDefaultNull() {
        TestEvent event = new TestEvent();
        assertThat(event.getSourceModule()).isNull();
        event.setSourceModule("UltiEconomy");
        assertThat(event.getSourceModule()).isEqualTo("UltiEconomy");
    }

    @Test
    @DisplayName("cancellable event starts uncancelled")
    void cancellableStartsUncancelled() {
        CancellableTestEvent event = new CancellableTestEvent();
        assertThat(event.isCancelled()).isFalse();
        event.setCancelled(true);
        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("EventPriority has 6 values in order")
    void priorityValues() {
        EventPriority[] values = EventPriority.values();
        assertThat(values).hasSize(6);
        assertThat(values[0]).isEqualTo(EventPriority.LOWEST);
        assertThat(values[5]).isEqualTo(EventPriority.MONITOR);
    }
}
