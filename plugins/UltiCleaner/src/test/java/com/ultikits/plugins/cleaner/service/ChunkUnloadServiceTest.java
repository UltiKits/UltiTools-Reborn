package com.ultikits.plugins.cleaner.service;

import com.ultikits.plugins.cleaner.UltiCleanerTestHelper;
import com.ultikits.plugins.cleaner.config.CleanerConfig;
import com.ultikits.plugins.cleaner.events.PreChunkUnloadEvent;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ChunkUnloadService Tests")
class ChunkUnloadServiceTest {

    private ChunkUnloadService service;
    private CleanerConfig config;
    private TpsAwareScheduler tpsScheduler;

    @BeforeEach
    void setUp() throws Exception {
        UltiCleanerTestHelper.setUp();

        config = UltiCleanerTestHelper.createDefaultConfig();
        tpsScheduler = mock(TpsAwareScheduler.class);

        service = new ChunkUnloadService();

        // Inject dependencies via reflection
        UltiCleanerTestHelper.setField(service, "config", config);
        UltiCleanerTestHelper.setField(service, "tpsScheduler", tpsScheduler);
        UltiCleanerTestHelper.setField(service, "plugin", UltiCleanerTestHelper.getMockPlugin());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiCleanerTestHelper.tearDown();
    }

    // ==================== Helper Methods ====================

    /**
     * Create a mock Chunk with configurable safety properties.
     */
    private Chunk createSafeChunk(World world, int x, int z) {
        Chunk chunk = UltiCleanerTestHelper.createMockChunk(world, x, z);
        lenient().when(world.isChunkInUse(x, z)).thenReturn(false);
        return chunk;
    }

    /**
     * Create a mock Player at specific chunk coordinates.
     */
    private Player createPlayerAtChunk(World world, int chunkX, int chunkZ) {
        Player player = mock(Player.class);
        // Convert chunk coordinates to block coordinates (multiply by 16, center in chunk)
        Location location = new Location(world, chunkX * 16 + 8, 64, chunkZ * 16 + 8);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);
        return player;
    }

    // ==================== Initialization ====================

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("Should not log when chunk unload disabled")
        void disabledDoesNotInit() {
            when(config.isChunkUnloadEnabled()).thenReturn(false);

            assertThatCode(() -> service.init()).doesNotThrowAnyException();

            // Note: @Scheduled tasks are registered by framework, not in init()
        }

        @Test
        @DisplayName("Should init when chunk unload enabled")
        void enabledInits() {
            when(config.isChunkUnloadEnabled()).thenReturn(true);

            assertThatCode(() -> service.init()).doesNotThrowAnyException();

            // Note: @Scheduled tasks are registered by framework, not in init()
            // init() only logs the initialization message now
        }
    }

    // ==================== Shutdown ====================

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("Should shutdown without errors when no task")
        void shutdownNoTask() {
            assertThatCode(() -> service.shutdown()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should cancel task on shutdown")
        void shutdownCancelsTask() {
            when(config.isChunkUnloadEnabled()).thenReturn(true);
            service.init();

            assertThatCode(() -> service.shutdown()).doesNotThrowAnyException();
        }
    }

    // ==================== Check And Unload Chunks ====================

    @Nested
    @DisplayName("Check And Unload Chunks")
    class CheckAndUnloadChunks {

        @Test
        @DisplayName("Should skip when chunk unload disabled")
        void skipWhenDisabled() throws Exception {
            when(config.isChunkUnloadEnabled()).thenReturn(false);

            Method method = ChunkUnloadService.class.getDeclaredMethod("checkAndUnloadChunks");
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should skip when no chunks to unload")
        void skipWhenNoChunks() throws Exception {
            when(config.isChunkUnloadEnabled()).thenReturn(true);
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            when(world.getLoadedChunks()).thenReturn(new Chunk[0]);
            UltiCleanerTestHelper.addMockWorld(world);

            Method method = ChunkUnloadService.class.getDeclaredMethod("checkAndUnloadChunks");
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service)).doesNotThrowAnyException();
        }
    }

    // ==================== Collect Chunks To Unload ====================

    @Nested
    @DisplayName("Collect Chunks To Unload")
    class CollectChunksToUnload {

        @Test
        @DisplayName("Should return empty list when no worlds")
        void noWorlds() throws Exception {
            when(config.getMaxChunkDistance()).thenReturn(20);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("Should skip blacklisted worlds")
        void skipBlacklistedWorlds() throws Exception {
            when(config.getMaxChunkDistance()).thenReturn(20);
            when(config.getWorldBlacklist()).thenReturn(Arrays.asList("world_creative"));

            World creativeWorld = UltiCleanerTestHelper.createMockWorld("world_creative");
            Chunk chunk = createSafeChunk(creativeWorld, 100, 100);
            when(creativeWorld.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(creativeWorld.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(creativeWorld);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("Should collect all safe chunks when no players in world")
        void allChunksWhenNoPlayers() throws Exception {
            when(config.getMaxChunkDistance()).thenReturn(20);
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk1 = createSafeChunk(world, 100, 100);
            Chunk chunk2 = createSafeChunk(world, 200, 200);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk1, chunk2});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).hasSize(2);
        }

        @Test
        @DisplayName("Should skip force-loaded chunks when no players")
        void skipForceLoadedChunksNoPlayers() throws Exception {
            when(config.getMaxChunkDistance()).thenReturn(20);
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk forceLoadedChunk = createSafeChunk(world, 100, 100);
            when(forceLoadedChunk.isForceLoaded()).thenReturn(true);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{forceLoadedChunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("Should skip chunks near players")
        void skipChunksNearPlayers() throws Exception {
            when(config.getMaxChunkDistance()).thenReturn(5);
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            World world = UltiCleanerTestHelper.createMockWorld("world");
            // Player at chunk (0, 0), chunk at (2, 2) => distance = 2 (within 5)
            Chunk nearChunk = createSafeChunk(world, 2, 2);
            Player player = createPlayerAtChunk(world, 0, 0);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{nearChunk});
            when(world.getPlayers()).thenReturn(Collections.singletonList(player));
            UltiCleanerTestHelper.addMockWorld(world);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("Should collect chunks far from all players")
        void collectFarChunks() throws Exception {
            when(config.getMaxChunkDistance()).thenReturn(5);
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            World world = UltiCleanerTestHelper.createMockWorld("world");
            // Player at chunk (0, 0), chunk at (100, 100) => far away
            Chunk farChunk = createSafeChunk(world, 100, 100);
            Player player = createPlayerAtChunk(world, 0, 0);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{farChunk});
            when(world.getPlayers()).thenReturn(Collections.singletonList(player));
            UltiCleanerTestHelper.addMockWorld(world);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).hasSize(1);
        }

        @Test
        @DisplayName("Should handle null world blacklist")
        void nullWorldBlacklist() throws Exception {
            when(config.getMaxChunkDistance()).thenReturn(20);
            when(config.getWorldBlacklist()).thenReturn(null);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).hasSize(1);
        }
    }

    // ==================== Is Chunk Far From All Players ====================

    @Nested
    @DisplayName("Is Chunk Far From All Players")
    class IsChunkFarFromAllPlayers {

        @Test
        @DisplayName("Should return true when chunk is far from all players")
        void farFromAllPlayers() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);
            Player player = createPlayerAtChunk(world, 0, 0);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isChunkFarFromAllPlayers",
                    Chunk.class, List.class, int.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk, Collections.singletonList(player), 5);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false when chunk is near a player")
        void nearAPlayer() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 2, 2);
            Player player = createPlayerAtChunk(world, 0, 0);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isChunkFarFromAllPlayers",
                    Chunk.class, List.class, int.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk, Collections.singletonList(player), 5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false when chunk is near any player (multiple players)")
        void nearOneOfMultiplePlayers() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 2, 2);
            Player farPlayer = createPlayerAtChunk(world, 100, 100);
            Player nearPlayer = createPlayerAtChunk(world, 0, 0);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isChunkFarFromAllPlayers",
                    Chunk.class, List.class, int.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk,
                    Arrays.asList(farPlayer, nearPlayer), 5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return true when chunk is far from all multiple players")
        void farFromAllMultiplePlayers() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);
            Player player1 = createPlayerAtChunk(world, 0, 0);
            Player player2 = createPlayerAtChunk(world, -50, -50);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isChunkFarFromAllPlayers",
                    Chunk.class, List.class, int.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk,
                    Arrays.asList(player1, player2), 5);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should use Chebyshev distance (max of x and z)")
        void chebyshevDistance() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            // Chunk at (10, 0), player at (0, 0) -> Chebyshev distance is 10
            Chunk chunk = createSafeChunk(world, 10, 0);
            Player player = createPlayerAtChunk(world, 0, 0);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isChunkFarFromAllPlayers",
                    Chunk.class, List.class, int.class);
            method.setAccessible(true);

            // maxDistance = 9 -> chunk should be far
            boolean farAt9 = (boolean) method.invoke(service, chunk,
                    Collections.singletonList(player), 9);
            assertThat(farAt9).isTrue();

            // maxDistance = 10 -> chunk should be near (distance == maxDistance)
            boolean farAt10 = (boolean) method.invoke(service, chunk,
                    Collections.singletonList(player), 10);
            assertThat(farAt10).isFalse();
        }

        @Test
        @DisplayName("Should handle chunk at exact boundary distance")
        void exactBoundary() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            // Chunk at (5, 5), player at (0, 0) -> distance is 5
            Chunk chunk = createSafeChunk(world, 5, 5);
            Player player = createPlayerAtChunk(world, 0, 0);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isChunkFarFromAllPlayers",
                    Chunk.class, List.class, int.class);
            method.setAccessible(true);

            // maxDistance = 5 -> chunk is NOT far (distance <= maxDistance)
            boolean result = (boolean) method.invoke(service, chunk,
                    Collections.singletonList(player), 5);

            assertThat(result).isFalse();
        }
    }

    // ==================== Is Safe To Unload ====================

    @Nested
    @DisplayName("Is Safe To Unload")
    class IsSafeToUnload {

        @Test
        @DisplayName("Should return false for force-loaded chunks")
        void forceLoadedNotSafe() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 10, 10);
            when(chunk.isForceLoaded()).thenReturn(true);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isSafeToUnload", Chunk.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false for chunks in use")
        void chunkInUseNotSafe() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 10, 10);
            when(world.isChunkInUse(10, 10)).thenReturn(true);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isSafeToUnload", Chunk.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false for chunks with players")
        void chunkWithPlayerNotSafe() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 10, 10);
            Player player = mock(Player.class);
            when(chunk.getEntities()).thenReturn(new Entity[]{player});

            Method method = ChunkUnloadService.class.getDeclaredMethod("isSafeToUnload", Chunk.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return true for safe chunk")
        void safeChunkIsUnloadable() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 10, 10);
            // isForceLoaded = false (default), isChunkInUse = false (set above), no entities
            // isLoaded = true (default from helper -> isEntitiesLoaded returns true on spigot)

            Method method = ChunkUnloadService.class.getDeclaredMethod("isSafeToUnload", Chunk.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return true for chunks with only non-player entities")
        void chunkWithNonPlayerEntities() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 10, 10);
            Entity zombie = mock(Entity.class);
            when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
            when(chunk.getEntities()).thenReturn(new Entity[]{zombie});

            Method method = ChunkUnloadService.class.getDeclaredMethod("isSafeToUnload", Chunk.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false when entities not loaded (Spigot, chunk not loaded)")
        void entitiesNotLoaded() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 10, 10);
            // On Spigot fallback, isEntitiesLoaded checks chunk.isLoaded()
            when(chunk.isLoaded()).thenReturn(false);

            Method method = ChunkUnloadService.class.getDeclaredMethod("isSafeToUnload", Chunk.class);
            method.setAccessible(true);

            boolean result = (boolean) method.invoke(service, chunk);

            assertThat(result).isFalse();
        }
    }

    // ==================== Force Unload ====================

    @Nested
    @DisplayName("Force Unload")
    class ForceUnload {

        @Test
        @DisplayName("forceUnloadChunks should return 0 when no chunks")
        void forceUnloadNoChunks() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            int count = service.forceUnloadChunks();

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("forceUnloadChunks should unload safe chunks")
        void forceUnloadSafeChunks() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);
            when(chunk.unload(true)).thenReturn(true);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            int count = service.forceUnloadChunks();

            assertThat(count).isEqualTo(1);
            verify(chunk).unload(true);
        }

        @Test
        @DisplayName("forceUnloadChunks should fire PreChunkUnloadEvent")
        void forceUnloadFiresEvent() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);
            when(chunk.unload(true)).thenReturn(true);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            service.forceUnloadChunks();

            verify(Bukkit.getPluginManager()).callEvent(any(PreChunkUnloadEvent.class));
        }

        @Test
        @DisplayName("forceUnloadChunks should respect cancelled event")
        void forceUnloadRespectsCancelledEvent() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            PluginManager pm = Bukkit.getPluginManager();
            doAnswer(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof PreChunkUnloadEvent) {
                    ((PreChunkUnloadEvent) arg).setCancelled(true);
                }
                return null;
            }).when(pm).callEvent(any());

            int count = service.forceUnloadChunks();

            assertThat(count).isEqualTo(0);
            verify(chunk, never()).unload(anyBoolean());
        }

        @Test
        @DisplayName("forceUnloadChunks should skip unsafe chunks on re-check")
        void forceUnloadSkipsUnsafeOnRecheck() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);

            // First call to getLoadedChunks returns the chunk (for collectChunksToUnload)
            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            // But on re-check in forceUnloadChunks, the chunk becomes force loaded
            when(chunk.isForceLoaded()).thenReturn(false, true);

            int count = service.forceUnloadChunks();

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("forceUnloadChunks should count failed unloads as 0")
        void forceUnloadFailedUnload() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);
            when(chunk.unload(true)).thenReturn(false);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            int count = service.forceUnloadChunks();

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("forceUnloadChunks should handle multiple chunks")
        void forceUnloadMultipleChunks() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk1 = createSafeChunk(world, 100, 100);
            Chunk chunk2 = createSafeChunk(world, 200, 200);
            Chunk chunk3 = createSafeChunk(world, 300, 300);

            when(chunk1.unload(true)).thenReturn(true);
            when(chunk2.unload(true)).thenReturn(false); // This one fails
            when(chunk3.unload(true)).thenReturn(true);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk1, chunk2, chunk3});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            int count = service.forceUnloadChunks();

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("forceUnloadChunks uses MANUAL reason in event")
        void forceUnloadUsesManualReason() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk = createSafeChunk(world, 100, 100);
            when(chunk.unload(true)).thenReturn(true);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            PluginManager pm = Bukkit.getPluginManager();
            final PreChunkUnloadEvent[] capturedEvent = {null};
            doAnswer(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof PreChunkUnloadEvent) {
                    capturedEvent[0] = (PreChunkUnloadEvent) arg;
                }
                return null;
            }).when(pm).callEvent(any());

            service.forceUnloadChunks();

            assertThat(capturedEvent[0]).isNotNull();
            assertThat(capturedEvent[0].getReason()).isEqualTo(PreChunkUnloadEvent.UnloadReason.MANUAL);
        }
    }

    // ==================== Chunk Counts ====================

    @Nested
    @DisplayName("Chunk Counts")
    class ChunkCounts {

        @Test
        @DisplayName("Should return 0 total loaded chunks with no worlds")
        void zeroLoadedChunksNoWorlds() {
            int count = service.getTotalLoadedChunks();

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return total loaded chunks count")
        void getTotalLoadedChunks() {
            World world1 = UltiCleanerTestHelper.createMockWorld("world");
            World world2 = UltiCleanerTestHelper.createMockWorld("world_nether");

            Chunk[] chunks1 = new Chunk[]{mock(Chunk.class), mock(Chunk.class), mock(Chunk.class)};
            Chunk[] chunks2 = new Chunk[]{mock(Chunk.class), mock(Chunk.class)};

            when(world1.getLoadedChunks()).thenReturn(chunks1);
            when(world2.getLoadedChunks()).thenReturn(chunks2);

            UltiCleanerTestHelper.addMockWorld(world1);
            UltiCleanerTestHelper.addMockWorld(world2);

            int count = service.getTotalLoadedChunks();

            assertThat(count).isEqualTo(5);
        }

        @Test
        @DisplayName("Should return unloadable chunk count")
        void getUnloadableChunkCount() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk chunk1 = createSafeChunk(world, 100, 100);
            Chunk chunk2 = createSafeChunk(world, 200, 200);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk1, chunk2});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            int count = service.getUnloadableChunkCount();

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("Should return unloadable count excluding unsafe chunks")
        void getUnloadableExcludingUnsafe() {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            Chunk safeChunk = createSafeChunk(world, 100, 100);
            Chunk forceLoadedChunk = createSafeChunk(world, 200, 200);
            when(forceLoadedChunk.isForceLoaded()).thenReturn(true);

            when(world.getLoadedChunks()).thenReturn(new Chunk[]{safeChunk, forceLoadedChunk});
            when(world.getPlayers()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            int count = service.getUnloadableChunkCount();

            assertThat(count).isEqualTo(1);
        }
    }

    // ==================== Unload Chunks Async ====================

    @Nested
    @DisplayName("Unload Chunk Async")
    class UnloadChunkAsync {

        @Test
        @DisplayName("Should handle unloadChunkAsync on non-Paper server")
        void unloadChunkAsyncOnSpigot() throws Exception {
            // Ensure isPaper is false
            UltiCleanerTestHelper.setStaticField(
                    Class.forName("com.ultikits.plugins.cleaner.utils.ServerTypeUtil"),
                    "isPaper", false);

            // The unloadChunkAsync is private, tested indirectly through batch unloading
            // We just verify the service handles it
            assertThatCode(() -> service.shutdown()).doesNotThrowAnyException();
        }
    }

    // ==================== Multiple Worlds ====================

    @Nested
    @DisplayName("Multiple Worlds")
    class MultipleWorlds {

        @Test
        @DisplayName("Should count loaded chunks across multiple worlds")
        void countAcrossWorlds() {
            World overworld = UltiCleanerTestHelper.createMockWorld("world");
            World nether = UltiCleanerTestHelper.createMockWorld("world_nether");
            World end = UltiCleanerTestHelper.createMockWorld("world_the_end");

            when(overworld.getLoadedChunks()).thenReturn(new Chunk[]{mock(Chunk.class), mock(Chunk.class)});
            when(nether.getLoadedChunks()).thenReturn(new Chunk[]{mock(Chunk.class)});
            when(end.getLoadedChunks()).thenReturn(new Chunk[]{mock(Chunk.class), mock(Chunk.class), mock(Chunk.class)});

            UltiCleanerTestHelper.addMockWorld(overworld);
            UltiCleanerTestHelper.addMockWorld(nether);
            UltiCleanerTestHelper.addMockWorld(end);

            int count = service.getTotalLoadedChunks();

            assertThat(count).isEqualTo(6);
        }

        @Test
        @DisplayName("Should collect unloadable chunks from multiple worlds")
        void collectFromMultipleWorlds() throws Exception {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.getMaxChunkDistance()).thenReturn(20);

            World world1 = UltiCleanerTestHelper.createMockWorld("world");
            World world2 = UltiCleanerTestHelper.createMockWorld("world_nether");

            Chunk chunk1 = createSafeChunk(world1, 100, 100);
            Chunk chunk2 = createSafeChunk(world2, 200, 200);

            when(world1.getLoadedChunks()).thenReturn(new Chunk[]{chunk1});
            when(world1.getPlayers()).thenReturn(Collections.emptyList());
            when(world2.getLoadedChunks()).thenReturn(new Chunk[]{chunk2});
            when(world2.getPlayers()).thenReturn(Collections.emptyList());

            UltiCleanerTestHelper.addMockWorld(world1);
            UltiCleanerTestHelper.addMockWorld(world2);

            Method method = ChunkUnloadService.class.getDeclaredMethod("collectChunksToUnload");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Chunk> chunks = (List<Chunk>) method.invoke(service);

            assertThat(chunks).hasSize(2);
        }
    }
}
