package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;

@DisplayName("HandlerEntry Tests")
class HandlerEntryTest {

    static class TestEvent extends ModuleEvent {}

    public void dummyHandler(TestEvent e) {}

    @Test
    @DisplayName("annotation-based entry stores method and instance")
    void annotationBased() throws Exception {
        Method m = getClass().getMethod("dummyHandler", TestEvent.class);
        HandlerEntry entry = new HandlerEntry(TestEvent.class, EventPriority.HIGH, true, "TestModule", m, this);
        assertThat(entry.isProgrammatic()).isFalse();
        assertThat(entry.getMethod()).isEqualTo(m);
        assertThat(entry.getInstance()).isEqualTo(this);
        assertThat(entry.getConsumer()).isNull();
        assertThat(entry.getOwnerModule()).isEqualTo("TestModule");
    }

    @Test
    @DisplayName("programmatic entry stores consumer")
    void programmatic() {
        HandlerEntry entry = new HandlerEntry(TestEvent.class, EventPriority.NORMAL, false, "TestModule",
                (java.util.function.Consumer<TestEvent>) e -> {});
        assertThat(entry.isProgrammatic()).isTrue();
        assertThat(entry.getConsumer()).isNotNull();
        assertThat(entry.getMethod()).isNull();
    }

    @Test
    @DisplayName("compareTo sorts by priority ordinal")
    void compareToSortsByPriority() throws Exception {
        Method m = getClass().getMethod("dummyHandler", TestEvent.class);
        HandlerEntry low = new HandlerEntry(TestEvent.class, EventPriority.LOW, false, "A", m, this);
        HandlerEntry high = new HandlerEntry(TestEvent.class, EventPriority.HIGH, false, "B", m, this);
        HandlerEntry normal = new HandlerEntry(TestEvent.class, EventPriority.NORMAL, false, "C", m, this);

        List<HandlerEntry> entries = Arrays.asList(high, low, normal);
        Collections.sort(entries);
        assertThat(entries).extracting(HandlerEntry::getPriority)
                .containsExactly(EventPriority.LOW, EventPriority.NORMAL, EventPriority.HIGH);
    }
}
