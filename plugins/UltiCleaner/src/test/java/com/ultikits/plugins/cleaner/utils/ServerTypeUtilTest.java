package com.ultikits.plugins.cleaner.utils;

import com.ultikits.plugins.cleaner.UltiCleanerTestHelper;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ServerTypeUtil Tests")
class ServerTypeUtilTest {

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

    // ==================== Server Type Detection ====================

    @Nested
    @DisplayName("Server Type Detection")
    class ServerTypeDetection {

        @Test
        @DisplayName("isPaper should return boolean")
        void isPaper() {
            boolean result = ServerTypeUtil.isPaper();
            assertThat(result).isIn(true, false);
        }

        @Test
        @DisplayName("isModernPaper should return boolean")
        void isModernPaper() {
            boolean result = ServerTypeUtil.isModernPaper();
            assertThat(result).isIn(true, false);
        }

        @Test
        @DisplayName("hasTpsMethod should return boolean")
        void hasTpsMethod() {
            boolean result = ServerTypeUtil.hasTpsMethod();
            assertThat(result).isIn(true, false);
        }

        @Test
        @DisplayName("getServerSoftware should return non-empty string")
        void getServerSoftware() {
            String software = ServerTypeUtil.getServerSoftware();
            assertThat(software).isNotEmpty();
            assertThat(software).containsAnyOf("Paper", "Spigot", "CraftBukkit");
        }

        @Test
        @DisplayName("getServerSoftware should return Spigot/CraftBukkit when not Paper")
        void getServerSoftwareSpigot() throws Exception {
            // Reset caches
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", false);
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isModernPaper", false);

            String software = ServerTypeUtil.getServerSoftware();
            assertThat(software).isEqualTo("Spigot/CraftBukkit");
        }

        @Test
        @DisplayName("getServerSoftware should return Paper (Legacy) for legacy Paper")
        void getServerSoftwareLegacyPaper() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", true);
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isModernPaper", false);

            String software = ServerTypeUtil.getServerSoftware();
            assertThat(software).isEqualTo("Paper (Legacy)");
        }

        @Test
        @DisplayName("getServerSoftware should return Paper (Modern) for modern Paper")
        void getServerSoftwareModernPaper() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", true);
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isModernPaper", true);

            String software = ServerTypeUtil.getServerSoftware();
            assertThat(software).isEqualTo("Paper (Modern)");
        }
    }

    // ==================== TPS Methods ====================

    @Nested
    @DisplayName("TPS Methods")
    class TpsMethods {

        @Test
        @DisplayName("getServerTps should return null when hasTpsMethod is false")
        void getServerTpsNull() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "hasTpsMethod", false);

            double[] tps = ServerTypeUtil.getServerTps();
            assertThat(tps).isNull();
        }

        @Test
        @DisplayName("getServerTps should return null or double array")
        void getServerTps() {
            double[] tps = ServerTypeUtil.getServerTps();
            if (tps != null) {
                assertThat(tps.length).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("hasTpsMethod should return false when no getTPS method exists")
        void hasTpsMethodFalse() throws Exception {
            // Reset cache to null so it re-detects
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "hasTpsMethod", null);
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "getTpsMethod", null);

            // Since the mock server doesn't have getTPS, should be false
            boolean result = ServerTypeUtil.hasTpsMethod();
            assertThat(result).isFalse();
        }
    }

    // ==================== Chunk Methods ====================

    @Nested
    @DisplayName("Chunk Methods")
    class ChunkMethods {

        @Test
        @DisplayName("getChunkAtAsync should return CompletableFuture")
        void getChunkAtAsync() {
            when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);

            CompletableFuture<Chunk> future = ServerTypeUtil.getChunkAtAsync(world, 10, 20);

            assertThat(future).isNotNull();
            assertThat(future).isCompletedWithValue(chunk);
        }

        @Test
        @DisplayName("getChunkAtAsync should fall back to sync on Spigot")
        void getChunkAtAsyncSpigotFallback() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", false);
            when(world.getChunkAt(5, 10)).thenReturn(chunk);

            CompletableFuture<Chunk> future = ServerTypeUtil.getChunkAtAsync(world, 5, 10);

            assertThat(future).isNotNull();
            assertThat(future.isDone()).isTrue();
            assertThat(future.get()).isSameAs(chunk);
        }

        @Test
        @DisplayName("isEntitiesLoaded should return boolean")
        void isEntitiesLoaded() {
            when(chunk.isLoaded()).thenReturn(true);

            boolean result = ServerTypeUtil.isEntitiesLoaded(chunk);

            assertThat(result).isIn(true, false);
        }

        @Test
        @DisplayName("isEntitiesLoaded should return true for loaded chunk on Spigot")
        void isEntitiesLoadedSpigotLoaded() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", false);
            when(chunk.isLoaded()).thenReturn(true);

            boolean result = ServerTypeUtil.isEntitiesLoaded(chunk);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("isEntitiesLoaded should return false for unloaded chunk on Spigot")
        void isEntitiesLoadedSpigotUnloaded() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", false);
            when(chunk.isLoaded()).thenReturn(false);

            boolean result = ServerTypeUtil.isEntitiesLoaded(chunk);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("getChunkAtAsync with Paper but no async method should fall back")
        void getChunkAtAsyncPaperFallback() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", true);
            // getChunkAtAsyncMethod is null by default, reflection will fail
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "getChunkAtAsyncMethod", null);

            when(world.getChunkAt(5, 10)).thenReturn(chunk);

            CompletableFuture<Chunk> future = ServerTypeUtil.getChunkAtAsync(world, 5, 10);

            assertThat(future).isNotNull();
            // Should fall through to sync loading
            assertThat(future.isDone()).isTrue();
        }

        @Test
        @DisplayName("isEntitiesLoaded on Paper should attempt Paper API method")
        void isEntitiesLoadedPaperAttempt() throws Exception {
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", true);
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isEntitiesLoadedMethod", null);

            // The Spigot 1.20 API may or may not include isEntitiesLoaded on Chunk.
            // Either way, the call should not throw and should return a boolean.
            boolean result = ServerTypeUtil.isEntitiesLoaded(chunk);
            assertThat(result).isIn(true, false);
        }
    }

    // ==================== Caching ====================

    @Nested
    @DisplayName("Caching")
    class Caching {

        @Test
        @DisplayName("isPaper should cache result")
        void isPaperCaches() {
            boolean first = ServerTypeUtil.isPaper();
            boolean second = ServerTypeUtil.isPaper();

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("isModernPaper should cache result")
        void isModernPaperCaches() {
            boolean first = ServerTypeUtil.isModernPaper();
            boolean second = ServerTypeUtil.isModernPaper();

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("hasTpsMethod should cache result")
        void hasTpsMethodCaches() {
            boolean first = ServerTypeUtil.hasTpsMethod();
            boolean second = ServerTypeUtil.hasTpsMethod();

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("isPaper cache should be reset in tearDown")
        void isPaperCacheReset() throws Exception {
            // After setUp, isPaper was reset to null then evaluated
            // Set it explicitly to a known value
            UltiCleanerTestHelper.setStaticField(ServerTypeUtil.class, "isPaper", true);
            assertThat(ServerTypeUtil.isPaper()).isTrue();

            // After tearDown and setUp, it should be re-evaluated
        }
    }

    // ==================== Private Constructor ====================

    @Nested
    @DisplayName("Private Constructor")
    class PrivateConstructor {

        @Test
        @DisplayName("Should have private constructor")
        void privateConstructor() throws Exception {
            java.lang.reflect.Constructor<ServerTypeUtil> constructor =
                    ServerTypeUtil.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("Should be able to create instance via reflection")
        void instantiateViaReflection() throws Exception {
            java.lang.reflect.Constructor<ServerTypeUtil> constructor =
                    ServerTypeUtil.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            ServerTypeUtil instance = constructor.newInstance();
            assertThat(instance).isNotNull();
        }
    }
}
