package com.ultikits.plugins.cleaner.events;

import com.ultikits.plugins.cleaner.UltiCleanerTestHelper;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PreChunkUnloadEvent Tests")
class PreChunkUnloadEventTest {

    private World world;
    private Chunk chunk;

    @BeforeEach
    void setUp() throws Exception {
        UltiCleanerTestHelper.setUp();
        world = UltiCleanerTestHelper.createMockWorld("world");
        chunk = UltiCleanerTestHelper.createMockChunk(world, 10, 20);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiCleanerTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGetters {

        @Test
        @DisplayName("Should create event with all properties")
        void createEvent() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.DISTANCE
            );

            assertThat(event.getChunk()).isEqualTo(chunk);
            assertThat(event.getReason()).isEqualTo(PreChunkUnloadEvent.UnloadReason.DISTANCE);
        }

        @Test
        @DisplayName("Should return world name")
        void getWorldName() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.IDLE
            );

            assertThat(event.getWorldName()).isEqualTo("world");
        }

        @Test
        @DisplayName("Should return chunk X coordinate")
        void getChunkX() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.MANUAL
            );

            assertThat(event.getChunkX()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should return chunk Z coordinate")
        void getChunkZ() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.MANUAL
            );

            assertThat(event.getChunkZ()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("Cancellable Behavior")
    class CancellableBehavior {

        @Test
        @DisplayName("Should not be cancelled by default")
        void notCancelledByDefault() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.DISTANCE
            );

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should be cancellable")
        void canBeCancelled() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.DISTANCE
            );

            event.setCancelled(true);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("Should allow uncancelling")
        void canBeUncancelled() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.DISTANCE
            );

            event.setCancelled(true);
            event.setCancelled(false);

            assertThat(event.isCancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Enum Values")
    class EnumValues {

        @Test
        @DisplayName("UnloadReason should have all values")
        void unloadReasonValues() {
            assertThat(PreChunkUnloadEvent.UnloadReason.values())
                .containsExactly(
                    PreChunkUnloadEvent.UnloadReason.DISTANCE,
                    PreChunkUnloadEvent.UnloadReason.IDLE,
                    PreChunkUnloadEvent.UnloadReason.MANUAL
                );
        }
    }

    @Nested
    @DisplayName("Handler List")
    class HandlerList {

        @Test
        @DisplayName("Should have handler list")
        void hasHandlerList() {
            PreChunkUnloadEvent event = new PreChunkUnloadEvent(
                chunk,
                PreChunkUnloadEvent.UnloadReason.DISTANCE
            );

            assertThat(event.getHandlers()).isNotNull();
            assertThat(PreChunkUnloadEvent.getHandlerList()).isNotNull();
        }
    }
}
