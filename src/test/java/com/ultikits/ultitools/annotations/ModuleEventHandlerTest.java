package com.ultikits.ultitools.annotations;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;

@DisplayName("@ModuleEventHandler Tests")
class ModuleEventHandlerTest {

    static class SampleEvent extends ModuleEvent {}

    static class SampleHandler {
        @ModuleEventHandler
        public void defaultHandler(SampleEvent event) { // no-op test handler
        }

        @ModuleEventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void customHandler(SampleEvent event) { // no-op test handler
        }
    }

    @Test
    @DisplayName("default values are NORMAL priority and ignoreCancelled=false")
    void defaultValues() throws Exception {
        Method method = SampleHandler.class.getMethod("defaultHandler", SampleEvent.class);
        ModuleEventHandler ann = method.getAnnotation(ModuleEventHandler.class);
        assertThat(ann).isNotNull();
        assertThat(ann.priority()).isEqualTo(EventPriority.NORMAL);
        assertThat(ann.ignoreCancelled()).isFalse();
    }

    @Test
    @DisplayName("custom values are respected")
    void customValues() throws Exception {
        Method method = SampleHandler.class.getMethod("customHandler", SampleEvent.class);
        ModuleEventHandler ann = method.getAnnotation(ModuleEventHandler.class);
        assertThat(ann.priority()).isEqualTo(EventPriority.HIGH);
        assertThat(ann.ignoreCancelled()).isTrue();
    }
}
