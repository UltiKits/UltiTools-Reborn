package com.ultikits.plugins.cleaner.events;

import org.bukkit.World;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PreItemCleanEvent Tests")
class PreItemCleanEventTest {

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGetters {

        @Test
        @DisplayName("Should create event with all properties")
        void createEvent() {
            List<UUID> uuids = Arrays.asList(UUID.randomUUID(), UUID.randomUUID());
            World world = mock(World.class);

            PreItemCleanEvent event = new PreItemCleanEvent(
                uuids,
                world,
                PreItemCleanEvent.CleanTrigger.SCHEDULED
            );

            assertThat(event.getItemUuids()).isEqualTo(uuids);
            assertThat(event.getWorld()).isEqualTo(world);
            assertThat(event.getTrigger()).isEqualTo(PreItemCleanEvent.CleanTrigger.SCHEDULED);
        }

        @Test
        @DisplayName("Should allow null world")
        void nullWorld() {
            List<UUID> uuids = Arrays.asList(UUID.randomUUID());

            PreItemCleanEvent event = new PreItemCleanEvent(
                uuids,
                null,
                PreItemCleanEvent.CleanTrigger.MANUAL
            );

            assertThat(event.getWorld()).isNull();
        }

        @Test
        @DisplayName("Should return item count")
        void getItemCount() {
            List<UUID> uuids = Arrays.asList(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

            PreItemCleanEvent event = new PreItemCleanEvent(
                uuids,
                null,
                PreItemCleanEvent.CleanTrigger.SMART
            );

            assertThat(event.getItemCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Cancellable Behavior")
    class CancellableBehavior {

        @Test
        @DisplayName("Should not be cancelled by default")
        void notCancelledByDefault() {
            PreItemCleanEvent event = new PreItemCleanEvent(
                new ArrayList<>(),
                null,
                PreItemCleanEvent.CleanTrigger.SCHEDULED
            );

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should be cancellable")
        void canBeCancelled() {
            PreItemCleanEvent event = new PreItemCleanEvent(
                new ArrayList<>(),
                null,
                PreItemCleanEvent.CleanTrigger.SCHEDULED
            );

            event.setCancelled(true);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("Should allow uncancelling")
        void canBeUncancelled() {
            PreItemCleanEvent event = new PreItemCleanEvent(
                new ArrayList<>(),
                null,
                PreItemCleanEvent.CleanTrigger.SCHEDULED
            );

            event.setCancelled(true);
            event.setCancelled(false);

            assertThat(event.isCancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("List Modification")
    class ListModification {

        @Test
        @DisplayName("Should allow modifying UUID list")
        void modifyList() {
            List<UUID> uuids = new ArrayList<>(Arrays.asList(UUID.randomUUID(), UUID.randomUUID()));

            PreItemCleanEvent event = new PreItemCleanEvent(
                uuids,
                null,
                PreItemCleanEvent.CleanTrigger.MANUAL
            );

            UUID newUuid = UUID.randomUUID();
            event.getItemUuids().add(newUuid);

            assertThat(event.getItemCount()).isEqualTo(3);
            assertThat(event.getItemUuids()).contains(newUuid);
        }
    }

    @Nested
    @DisplayName("Enum Values")
    class EnumValues {

        @Test
        @DisplayName("CleanTrigger should have all values")
        void cleanTriggerValues() {
            assertThat(PreItemCleanEvent.CleanTrigger.values())
                .containsExactly(
                    PreItemCleanEvent.CleanTrigger.SCHEDULED,
                    PreItemCleanEvent.CleanTrigger.SMART,
                    PreItemCleanEvent.CleanTrigger.MANUAL
                );
        }
    }

    @Nested
    @DisplayName("Handler List")
    class HandlerList {

        @Test
        @DisplayName("Should have handler list")
        void hasHandlerList() {
            PreItemCleanEvent event = new PreItemCleanEvent(
                new ArrayList<>(),
                null,
                PreItemCleanEvent.CleanTrigger.SCHEDULED
            );

            assertThat(event.getHandlers()).isNotNull();
            assertThat(PreItemCleanEvent.getHandlerList()).isNotNull();
        }
    }
}
