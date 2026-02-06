package com.ultikits.plugins.cleaner.events;

import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PreEntityCleanEvent Tests")
class PreEntityCleanEventTest {

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGetters {

        @Test
        @DisplayName("Should create event with all properties")
        void createEvent() {
            List<UUID> uuids = Arrays.asList(UUID.randomUUID(), UUID.randomUUID());
            World world = mock(World.class);
            Map<EntityType, Integer> typeCounts = new HashMap<>();
            typeCounts.put(EntityType.ZOMBIE, 5);
            typeCounts.put(EntityType.SKELETON, 3);

            PreEntityCleanEvent event = new PreEntityCleanEvent(
                uuids,
                world,
                PreEntityCleanEvent.CleanTrigger.SCHEDULED,
                typeCounts
            );

            assertThat(event.getEntityUuids()).isEqualTo(uuids);
            assertThat(event.getWorld()).isEqualTo(world);
            assertThat(event.getTrigger()).isEqualTo(PreEntityCleanEvent.CleanTrigger.SCHEDULED);
            assertThat(event.getEntityTypeCounts()).isEqualTo(typeCounts);
        }

        @Test
        @DisplayName("Should allow null world")
        void nullWorld() {
            List<UUID> uuids = Arrays.asList(UUID.randomUUID());
            Map<EntityType, Integer> typeCounts = new HashMap<>();

            PreEntityCleanEvent event = new PreEntityCleanEvent(
                uuids,
                null,
                PreEntityCleanEvent.CleanTrigger.MANUAL,
                typeCounts
            );

            assertThat(event.getWorld()).isNull();
        }

        @Test
        @DisplayName("Should return entity count")
        void getEntityCount() {
            List<UUID> uuids = Arrays.asList(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            Map<EntityType, Integer> typeCounts = new HashMap<>();

            PreEntityCleanEvent event = new PreEntityCleanEvent(
                uuids,
                null,
                PreEntityCleanEvent.CleanTrigger.SMART,
                typeCounts
            );

            assertThat(event.getEntityCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should return entity type counts")
        void getEntityTypeCounts() {
            List<UUID> uuids = new ArrayList<>();
            Map<EntityType, Integer> typeCounts = new HashMap<>();
            typeCounts.put(EntityType.ZOMBIE, 10);
            typeCounts.put(EntityType.CREEPER, 7);

            PreEntityCleanEvent event = new PreEntityCleanEvent(
                uuids,
                null,
                PreEntityCleanEvent.CleanTrigger.MANUAL,
                typeCounts
            );

            assertThat(event.getEntityTypeCounts()).containsEntry(EntityType.ZOMBIE, 10);
            assertThat(event.getEntityTypeCounts()).containsEntry(EntityType.CREEPER, 7);
        }
    }

    @Nested
    @DisplayName("Cancellable Behavior")
    class CancellableBehavior {

        @Test
        @DisplayName("Should not be cancelled by default")
        void notCancelledByDefault() {
            PreEntityCleanEvent event = new PreEntityCleanEvent(
                new ArrayList<>(),
                null,
                PreEntityCleanEvent.CleanTrigger.SCHEDULED,
                new HashMap<>()
            );

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should be cancellable")
        void canBeCancelled() {
            PreEntityCleanEvent event = new PreEntityCleanEvent(
                new ArrayList<>(),
                null,
                PreEntityCleanEvent.CleanTrigger.SCHEDULED,
                new HashMap<>()
            );

            event.setCancelled(true);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("Should allow uncancelling")
        void canBeUncancelled() {
            PreEntityCleanEvent event = new PreEntityCleanEvent(
                new ArrayList<>(),
                null,
                PreEntityCleanEvent.CleanTrigger.SCHEDULED,
                new HashMap<>()
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
            Map<EntityType, Integer> typeCounts = new HashMap<>();

            PreEntityCleanEvent event = new PreEntityCleanEvent(
                uuids,
                null,
                PreEntityCleanEvent.CleanTrigger.MANUAL,
                typeCounts
            );

            UUID newUuid = UUID.randomUUID();
            event.getEntityUuids().add(newUuid);

            assertThat(event.getEntityCount()).isEqualTo(3);
            assertThat(event.getEntityUuids()).contains(newUuid);
        }
    }

    @Nested
    @DisplayName("Enum Values")
    class EnumValues {

        @Test
        @DisplayName("CleanTrigger should have all values")
        void cleanTriggerValues() {
            assertThat(PreEntityCleanEvent.CleanTrigger.values())
                .containsExactly(
                    PreEntityCleanEvent.CleanTrigger.SCHEDULED,
                    PreEntityCleanEvent.CleanTrigger.SMART,
                    PreEntityCleanEvent.CleanTrigger.MANUAL
                );
        }
    }

    @Nested
    @DisplayName("Handler List")
    class HandlerList {

        @Test
        @DisplayName("Should have handler list")
        void hasHandlerList() {
            PreEntityCleanEvent event = new PreEntityCleanEvent(
                new ArrayList<>(),
                null,
                PreEntityCleanEvent.CleanTrigger.SCHEDULED,
                new HashMap<>()
            );

            assertThat(event.getHandlers()).isNotNull();
            assertThat(PreEntityCleanEvent.getHandlerList()).isNotNull();
        }
    }
}
