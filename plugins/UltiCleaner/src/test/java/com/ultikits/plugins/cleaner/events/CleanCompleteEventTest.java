package com.ultikits.plugins.cleaner.events;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CleanCompleteEvent Tests")
class CleanCompleteEventTest {

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGetters {

        @Test
        @DisplayName("Should create event with all properties")
        void createEvent() {
            CleanCompleteEvent event = new CleanCompleteEvent(
                CleanCompleteEvent.CleanType.ITEMS,
                100,
                1500L,
                CleanCompleteEvent.CleanTrigger.MANUAL
            );

            assertThat(event.getCleanType()).isEqualTo(CleanCompleteEvent.CleanType.ITEMS);
            assertThat(event.getCleanedCount()).isEqualTo(100);
            assertThat(event.getDurationMs()).isEqualTo(1500L);
            assertThat(event.getTrigger()).isEqualTo(CleanCompleteEvent.CleanTrigger.MANUAL);
        }

        @Test
        @DisplayName("Should be async event")
        void isAsync() {
            CleanCompleteEvent event = new CleanCompleteEvent(
                CleanCompleteEvent.CleanType.ENTITIES,
                50,
                2000L,
                CleanCompleteEvent.CleanTrigger.SCHEDULED
            );

            assertThat(event.isAsynchronous()).isTrue();
        }
    }

    @Nested
    @DisplayName("Formatted Duration")
    class FormattedDuration {

        @Test
        @DisplayName("Should format milliseconds")
        void formatMilliseconds() {
            CleanCompleteEvent event = new CleanCompleteEvent(
                CleanCompleteEvent.CleanType.ITEMS,
                100,
                500L,
                CleanCompleteEvent.CleanTrigger.MANUAL
            );

            assertThat(event.getFormattedDuration()).isEqualTo("500ms");
        }

        @Test
        @DisplayName("Should format seconds")
        void formatSeconds() {
            CleanCompleteEvent event = new CleanCompleteEvent(
                CleanCompleteEvent.CleanType.ITEMS,
                100,
                2500L,
                CleanCompleteEvent.CleanTrigger.MANUAL
            );

            assertThat(event.getFormattedDuration()).contains("2.50s");
        }
    }

    @Nested
    @DisplayName("Enum Values")
    class EnumValues {

        @Test
        @DisplayName("CleanType should have all values")
        void cleanTypeValues() {
            assertThat(CleanCompleteEvent.CleanType.values())
                .containsExactly(
                    CleanCompleteEvent.CleanType.ITEMS,
                    CleanCompleteEvent.CleanType.ENTITIES,
                    CleanCompleteEvent.CleanType.CHUNKS,
                    CleanCompleteEvent.CleanType.ALL
                );
        }

        @Test
        @DisplayName("CleanTrigger should have all values")
        void cleanTriggerValues() {
            assertThat(CleanCompleteEvent.CleanTrigger.values())
                .containsExactly(
                    CleanCompleteEvent.CleanTrigger.SCHEDULED,
                    CleanCompleteEvent.CleanTrigger.SMART,
                    CleanCompleteEvent.CleanTrigger.MANUAL
                );
        }
    }

    @Nested
    @DisplayName("Handler List")
    class HandlerList {

        @Test
        @DisplayName("Should have handler list")
        void hasHandlerList() {
            CleanCompleteEvent event = new CleanCompleteEvent(
                CleanCompleteEvent.CleanType.ITEMS,
                100,
                1000L,
                CleanCompleteEvent.CleanTrigger.MANUAL
            );

            assertThat(event.getHandlers()).isNotNull();
            assertThat(CleanCompleteEvent.getHandlerList()).isNotNull();
        }
    }
}
