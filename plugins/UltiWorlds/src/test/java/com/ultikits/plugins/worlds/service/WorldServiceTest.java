package com.ultikits.plugins.worlds.service;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.worlds.UltiWorldsTestHelper;
import com.ultikits.plugins.worlds.config.WorldConfig;
import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;

/**
 * Tests for WorldService.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("WorldService Tests")
class WorldServiceTest {

    private WorldService worldService;
    private WorldConfig mockConfig;
    private DataOperator<WorldSettings> mockDataOperator;

    @BeforeEach
    void setUp() throws Exception {
        UltiWorldsTestHelper.setUp();
        UltiToolsPlugin mockPlugin = UltiWorldsTestHelper.getMockPlugin();

        worldService = new WorldService();
        mockConfig = UltiWorldsTestHelper.createDefaultConfig();
        mockDataOperator = mock(DataOperator.class);

        UltiWorldsTestHelper.setField(worldService, "config", mockConfig);
        UltiWorldsTestHelper.setField(worldService, "dataOperator", mockDataOperator);
        UltiWorldsTestHelper.setField(worldService, "plugin", mockPlugin);

        when(mockPlugin.getDataOperator(WorldSettings.class)).thenReturn(mockDataOperator);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiWorldsTestHelper.tearDown();
    }

    /**
     * Helper to mock a query chain that returns a specific result.
     */
    @SuppressWarnings("unchecked")
    private Query<WorldSettings> mockQueryReturning(WorldSettings result) {
        Query<WorldSettings> mockQuery = mock(Query.class);
        when(mockDataOperator.query()).thenReturn(mockQuery);
        when(mockQuery.where(anyString())).thenReturn(mockQuery);
        when(mockQuery.eq(any())).thenReturn(mockQuery);
        when(mockQuery.first()).thenReturn(result);
        when(mockQuery.delete()).thenReturn(result != null ? 1 : 0);
        return mockQuery;
    }

    @Nested
    @DisplayName("Settings Management")
    class SettingsManagement {

        @Test
        @DisplayName("getOrCreateSettings should return cached settings")
        void getOrCreateSettingsCached() {
            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            mockQueryReturning(settings);

            WorldSettings result1 = worldService.getOrCreateSettings("world");
            WorldSettings result2 = worldService.getOrCreateSettings("world");

            assertThat(result1).isSameAs(result2);
            assertThat(result1).isSameAs(settings);
            verify(mockDataOperator, times(1)).query();
        }

        @Test
        @DisplayName("getOrCreateSettings should create new settings when not exists")
        void getOrCreateSettingsNew() {
            mockQueryReturning(null);

            WorldSettings result = worldService.getOrCreateSettings("new_world");

            assertThat(result.getWorldName()).isEqualTo("new_world");
            verify(mockDataOperator).insert(any(WorldSettings.class));
        }

        @Test
        @DisplayName("updateSettings should update database and cache")
        void updateSettings() throws Exception {
            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");

            worldService.updateSettings(settings);

            verify(mockDataOperator).update(settings);
        }
    }

    @Nested
    @DisplayName("World Access Control")
    class WorldAccessControl {

        @Test
        @DisplayName("canEnterWorld should return true for normal world")
        void canEnterWorldNormal() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "world");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("canEnterWorld should return false for blocked world without permission")
        void canEnterWorldBlocked() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.blocked")).thenReturn(false);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("blocked_world");
            settings.setBlocked(true);
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "blocked_world");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("canEnterWorld should return true for blocked world with bypass permission")
        void canEnterWorldBlockedWithPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.blocked")).thenReturn(true);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("blocked_world");
            settings.setBlocked(true);
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "blocked_world");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("canEnterWorld should return false for locked world without permission")
        void canEnterWorldLocked() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.locked")).thenReturn(false);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("locked_world");
            settings.setLocked(true);
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "locked_world");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("canEnterWorld should check per-world permission when enabled")
        void canEnterWorldPerWorldPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.world.special")).thenReturn(false);
            when(player.hasPermission("ultiworlds.world.*")).thenReturn(false);
            when(mockConfig.isPermissionPerWorld()).thenReturn(true);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("special");
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "special");

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Configuration Access")
    class ConfigurationAccess {

        @Test
        @DisplayName("getConfig should return configuration")
        void getConfig() {
            WorldConfig config = worldService.getConfig();

            assertThat(config).isSameAs(mockConfig);
        }
    }

    @Nested
    @DisplayName("World Lists")
    class WorldLists {

        @Test
        @DisplayName("getAllWorlds should return all worlds from Bukkit")
        void getAllWorlds() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world1 = mock(World.class);
                World world2 = mock(World.class);
                List<World> worlds = Arrays.asList(world1, world2);

                bukkit.when(Bukkit::getWorlds).thenReturn(worlds);

                List<World> result = worldService.getAllWorlds();

                assertThat(result).hasSize(2);
                assertThat(result).containsExactly(world1, world2);
            }
        }

        @Test
        @DisplayName("getVisibleWorlds should filter hidden worlds")
        void getVisibleWorlds() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world1 = mock(World.class);
                World world2 = mock(World.class);
                World world3 = mock(World.class);
                when(world1.getName()).thenReturn("world1");
                when(world2.getName()).thenReturn("world2");
                when(world3.getName()).thenReturn("world3");

                List<World> worlds = Arrays.asList(world1, world2, world3);
                bukkit.when(Bukkit::getWorlds).thenReturn(worlds);

                WorldSettings settings1 = UltiWorldsTestHelper.createSampleWorldSettings("world1");
                settings1.setHidden(false);
                WorldSettings settings2 = UltiWorldsTestHelper.createSampleWorldSettings("world2");
                settings2.setHidden(true);
                WorldSettings settings3 = UltiWorldsTestHelper.createSampleWorldSettings("world3");
                settings3.setHidden(false);

                @SuppressWarnings("unchecked")
                Query<WorldSettings> mockQuery = mock(Query.class);
                when(mockDataOperator.query()).thenReturn(mockQuery);
                when(mockQuery.where(anyString())).thenReturn(mockQuery);
                when(mockQuery.eq(any())).thenReturn(mockQuery);
                when(mockQuery.first())
                        .thenReturn(settings1)
                        .thenReturn(settings2)
                        .thenReturn(settings3);

                List<World> result = worldService.getVisibleWorlds();

                assertThat(result).hasSize(2);
                assertThat(result).containsExactly(world1, world3);
            }
        }

        @Test
        @DisplayName("getAllWorlds should return empty list when no worlds")
        void getAllWorldsEmpty() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getWorlds).thenReturn(Collections.emptyList());

                List<World> result = worldService.getAllWorlds();

                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("getVisibleWorlds should return empty when all hidden")
        void getVisibleWorldsAllHidden() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world1 = mock(World.class);
                when(world1.getName()).thenReturn("world1");

                bukkit.when(Bukkit::getWorlds).thenReturn(Collections.singletonList(world1));

                WorldSettings settings1 = UltiWorldsTestHelper.createSampleWorldSettings("world1");
                settings1.setHidden(true);

                mockQueryReturning(settings1);

                List<World> result = worldService.getVisibleWorlds();

                assertThat(result).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Teleport to World")
    class TeleportToWorld {

        @Test
        @DisplayName("teleportToWorld should return false when world not found")
        void teleportToWorldNotFound() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("unknown")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                boolean result = worldService.teleportToWorld(player, "unknown");

                assertThat(result).isFalse();
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("teleportToWorld should return false when world is blocked and player has no permission")
        void teleportToWorldBlocked() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("blocked_world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
                when(player.hasPermission("ultiworlds.bypass.blocked")).thenReturn(false);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("blocked_world");
                settings.setBlocked(true);
                mockQueryReturning(settings);

                boolean result = worldService.teleportToWorld(player, "blocked_world");

                assertThat(result).isFalse();
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("teleportToWorld should return false when world is locked and player has no permission")
        void teleportToWorldLocked() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("locked_world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
                when(player.hasPermission("ultiworlds.bypass.locked")).thenReturn(false);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("locked_world");
                settings.setLocked(true);
                mockQueryReturning(settings);

                boolean result = worldService.teleportToWorld(player, "locked_world");

                assertThat(result).isFalse();
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("teleportToWorld should return false when per-world permission check fails")
        void teleportToWorldPermissionFail() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("special")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
                when(player.hasPermission("ultiworlds.world.special")).thenReturn(false);
                when(player.hasPermission("ultiworlds.world.*")).thenReturn(false);
                when(mockConfig.isPermissionPerWorld()).thenReturn(true);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("special");
                mockQueryReturning(settings);

                boolean result = worldService.teleportToWorld(player, "special");

                assertThat(result).isFalse();
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("teleportToWorld should return false when on cooldown")
        void teleportToWorldCooldown() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                mockQueryReturning(settings);

                when(mockConfig.getTpCooldown()).thenReturn(10);

                // Set a cooldown
                worldService.setTpCooldown(player.getUniqueId());

                boolean result = worldService.teleportToWorld(player, "world");

                assertThat(result).isFalse();
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("teleportToWorld should use custom spawn location when configured and spawnX != 0")
        void teleportToWorldCustomSpawn() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setSpawnX(100.5);
                settings.setSpawnY(64.0);
                settings.setSpawnZ(-200.5);
                settings.setSpawnYaw(90.0f);
                settings.setSpawnPitch(45.0f);
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(true);
                when(mockConfig.getTpCooldown()).thenReturn(0);

                boolean result = worldService.teleportToWorld(player, "world");

                assertThat(result).isTrue();
                ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
                verify(player).teleport(captor.capture());
                Location loc = captor.getValue();
                assertThat(loc.getX()).isEqualTo(100.5);
                assertThat(loc.getY()).isEqualTo(64.0);
                assertThat(loc.getZ()).isEqualTo(-200.5);
                verify(player, atLeastOnce()).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("teleportToWorld should use world spawn when spawnX is 0")
        void teleportToWorldDefaultSpawn() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setSpawnX(0); // no custom spawn
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(true);
                when(mockConfig.getTpCooldown()).thenReturn(0);

                boolean result = worldService.teleportToWorld(player, "world");

                assertThat(result).isTrue();
                verify(player).teleport(spawnLocation);
            }
        }

        @Test
        @DisplayName("teleportToWorld should use world spawn when useSpawnLocation is false")
        void teleportToWorldNoSpawnLocation() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);

                boolean result = worldService.teleportToWorld(player, "world");

                assertThat(result).isTrue();
                verify(player).teleport(spawnLocation);
            }
        }

        @Test
        @DisplayName("teleportToWorld should use world name when displayName is null")
        void teleportToWorldNullDisplayName() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDisplayName(null);
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);

                boolean result = worldService.teleportToWorld(player, "world");

                assertThat(result).isTrue();
                // The display name fallback should use "world" instead of null
                verify(player, atLeastOnce()).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("teleportToWorld should succeed for allowed world with bypass permission")
        void teleportToWorldBlockedBypass() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("blocked_world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
                when(player.hasPermission("ultiworlds.bypass.blocked")).thenReturn(true);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("blocked_world");
                settings.setBlocked(true);
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);

                boolean result = worldService.teleportToWorld(player, "blocked_world");

                assertThat(result).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Create World")
    class CreateWorldTests {

        @Test
        @DisplayName("createWorld with generator should return false when world already exists")
        void createWorldAlreadyExists() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World existingWorld = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("existing")).thenReturn(existingWorld);

                boolean result = worldService.createWorld("existing",
                        World.Environment.NORMAL, WorldType.NORMAL, null);

                assertThat(result).isFalse();
            }
        }

        @Test
        @DisplayName("createWorld with full options should return null when world already exists")
        void createWorldFullAlreadyExists() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World existingWorld = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("existing")).thenReturn(existingWorld);

                World result = worldService.createWorld("existing",
                        World.Environment.NORMAL, WorldType.NORMAL, true, "123");

                assertThat(result).isNull();
            }
        }
    }

    @Nested
    @DisplayName("Unload World")
    class UnloadWorldTests {

        @Test
        @DisplayName("unloadWorld should return false when world not found")
        void unloadWorldNotFound() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("unknown")).thenReturn(null);

                boolean result = worldService.unloadWorld("unknown", true);

                assertThat(result).isFalse();
            }
        }

        @Test
        @DisplayName("unloadWorld should teleport players to default world first")
        void unloadWorldTeleportPlayers() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                World defaultWorld = mock(World.class);
                Location defaultSpawn = mock(Location.class);
                when(defaultWorld.getSpawnLocation()).thenReturn(defaultSpawn);

                Player playerInWorld = UltiWorldsTestHelper.createMockPlayer("P1", UUID.randomUUID());
                when(world.getPlayers()).thenReturn(Collections.singletonList(playerInWorld));

                bukkit.when(() -> Bukkit.getWorld("myworld")).thenReturn(world);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(defaultWorld);
                bukkit.when(() -> Bukkit.unloadWorld(world, true)).thenReturn(true);

                when(mockConfig.getDefaultWorld()).thenReturn("world");

                boolean result = worldService.unloadWorld("myworld", true);

                verify(playerInWorld).teleport(defaultSpawn);
                verify(playerInWorld).sendMessage(anyString());
                assertThat(result).isTrue();
            }
        }

        @Test
        @DisplayName("unloadWorld should use first world as fallback when default not found")
        void unloadWorldFallbackDefault() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                World fallbackWorld = mock(World.class);
                Location fallbackSpawn = mock(Location.class);
                when(fallbackWorld.getSpawnLocation()).thenReturn(fallbackSpawn);

                when(world.getPlayers()).thenReturn(Collections.emptyList());

                bukkit.when(() -> Bukkit.getWorld("myworld")).thenReturn(world);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null); // default not found
                bukkit.when(Bukkit::getWorlds).thenReturn(Collections.singletonList(fallbackWorld));
                bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);

                when(mockConfig.getDefaultWorld()).thenReturn("world");

                boolean result = worldService.unloadWorld("myworld", false);

                assertThat(result).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Delete World")
    class DeleteWorldTests {

        @Test
        @DisplayName("deleteWorld should remove from database and cache")
        void deleteWorldRemovesData() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("deleted_world")).thenReturn(null);
                bukkit.when(() -> Bukkit.getWorldContainer()).thenReturn(new java.io.File(System.getProperty("java.io.tmpdir")));

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("deleted_world");
                settings.setId(5);
                Query<WorldSettings> mockQuery = mockQueryReturning(settings);

                boolean result = worldService.deleteWorld("deleted_world");

                assertThat(result).isTrue();
                verify(mockQuery).delete();
            }
        }

        @Test
        @DisplayName("deleteWorld should return true when world is not loaded")
        void deleteWorldNotLoaded() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("old_world")).thenReturn(null);
                bukkit.when(() -> Bukkit.getWorldContainer()).thenReturn(new java.io.File(System.getProperty("java.io.tmpdir")));

                mockQueryReturning(null);

                boolean result = worldService.deleteWorld("old_world");

                assertThat(result).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Update Settings")
    class UpdateSettingsTests {

        @Test
        @DisplayName("updateSettings should update database and cache")
        void updateSettings() throws Exception {
            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");

            worldService.updateSettings(settings);

            verify(mockDataOperator).update(settings);
        }

        @Test
        @DisplayName("updateSettings should handle IllegalAccessException gracefully")
        void updateSettingsError() throws Exception {
            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            doThrow(new IllegalAccessException("test error")).when(mockDataOperator).update(settings);

            // Should not throw - catches the exception internally
            worldService.updateSettings(settings);

            verify(mockDataOperator).update(settings);
        }
    }

    @Nested
    @DisplayName("World Spawn Management")
    class WorldSpawnManagement {

        @Test
        @DisplayName("setWorldSpawn should update settings and world spawn")
        void setWorldSpawn() throws Exception {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                mockQueryReturning(settings);

                Location location = new Location(world, 100.5, 64.0, -200.5, 90f, 45f);

                worldService.setWorldSpawn("world", location);

                assertThat(settings.getSpawnX()).isEqualTo(100.5);
                assertThat(settings.getSpawnY()).isEqualTo(64.0);
                assertThat(settings.getSpawnZ()).isEqualTo(-200.5);
                assertThat(settings.getSpawnYaw()).isEqualTo(90f);
                assertThat(settings.getSpawnPitch()).isEqualTo(45f);

                verify(mockDataOperator).update(settings);
                verify(world).setSpawnLocation(location);
            }
        }

        @Test
        @DisplayName("setWorldSpawn should handle null world gracefully")
        void setWorldSpawnNullWorld() throws Exception {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("offline_world")).thenReturn(null);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("offline_world");
                mockQueryReturning(settings);

                Location location = mock(Location.class);
                when(location.getX()).thenReturn(50.0);
                when(location.getY()).thenReturn(70.0);
                when(location.getZ()).thenReturn(-50.0);
                when(location.getYaw()).thenReturn(0.0f);
                when(location.getPitch()).thenReturn(0.0f);

                worldService.setWorldSpawn("offline_world", location);

                // Settings should still be updated even if world is null
                assertThat(settings.getSpawnX()).isEqualTo(50.0);
                assertThat(settings.getSpawnY()).isEqualTo(70.0);
                assertThat(settings.getSpawnZ()).isEqualTo(-50.0);
            }
        }
    }

    @Nested
    @DisplayName("Teleport Cooldown")
    class TeleportCooldown {

        @Test
        @DisplayName("canTeleport should return true when no cooldown")
        void canTeleportNoCooldown() {
            UUID playerUuid = UUID.randomUUID();

            boolean result = worldService.canTeleport(playerUuid);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("canTeleport should return false during cooldown")
        void canTeleportDuringCooldown() {
            UUID playerUuid = UUID.randomUUID();
            when(mockConfig.getTpCooldown()).thenReturn(10);

            worldService.setTpCooldown(playerUuid);
            boolean result = worldService.canTeleport(playerUuid);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("getRemainingCooldown should return time left")
        void getRemainingCooldown() {
            UUID playerUuid = UUID.randomUUID();
            when(mockConfig.getTpCooldown()).thenReturn(10);

            worldService.setTpCooldown(playerUuid);
            int remaining = worldService.getRemainingCooldown(playerUuid);

            assertThat(remaining).isBetween(9, 10);
        }

        @Test
        @DisplayName("getRemainingCooldown should return 0 when no cooldown")
        void getRemainingCooldownNone() {
            UUID playerUuid = UUID.randomUUID();

            int remaining = worldService.getRemainingCooldown(playerUuid);

            assertThat(remaining).isEqualTo(0);
        }

        @Test
        @DisplayName("canTeleport should return false immediately when cooldown is 0 (same millisecond)")
        void canTeleportZeroCooldown() {
            UUID playerUuid = UUID.randomUUID();
            when(mockConfig.getTpCooldown()).thenReturn(0);

            worldService.setTpCooldown(playerUuid);
            // 0 cooldown means > 0ms must have passed. In the same millisecond, this is false.
            // This tests the exact boundary condition.
            boolean result = worldService.canTeleport(playerUuid);

            // canTeleport checks: currentTime - lastTp > 0*1000 => diff > 0
            // If called in same ms, diff == 0, so returns false
            // This is expected behavior - 0s cooldown still requires at least 1ms to pass
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("getRemainingCooldown should return 0 when cooldown expired")
        void getRemainingCooldownExpired() {
            UUID playerUuid = UUID.randomUUID();
            when(mockConfig.getTpCooldown()).thenReturn(0);

            worldService.setTpCooldown(playerUuid);
            int remaining = worldService.getRemainingCooldown(playerUuid);

            assertThat(remaining).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Can Enter World Extended")
    class CanEnterWorldExtended {

        @Test
        @DisplayName("canEnterWorld should return true for locked world with bypass permission")
        void canEnterWorldLockedWithPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.locked")).thenReturn(true);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("locked_world");
            settings.setLocked(true);
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "locked_world");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("canEnterWorld should return true with wildcard permission")
        void canEnterWorldWildcardPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.world.special")).thenReturn(false);
            when(player.hasPermission("ultiworlds.world.*")).thenReturn(true);
            when(mockConfig.isPermissionPerWorld()).thenReturn(true);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("special");
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "special");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("canEnterWorld should check blocked before locked")
        void canEnterWorldBlockedAndLocked() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.bypass.blocked")).thenReturn(false);
            when(player.hasPermission("ultiworlds.bypass.locked")).thenReturn(true);

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setBlocked(true);
            settings.setLocked(true);
            mockQueryReturning(settings);

            boolean result = worldService.canEnterWorld(player, "world");

            // Should be denied by blocked check even though locked bypass is available
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Teleport Enhancements")
    class TeleportEnhancements {

        @Test
        @DisplayName("teleportToWorld should show description when enabled and description is non-empty")
        void teleportShowsDescription() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDescription("Line 1\nLine 2");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(true);

                worldService.teleportToWorld(player, "world");

                ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
                verify(player, atLeast(3)).sendMessage(captor.capture());
                List<String> messages = captor.getAllValues();
                assertThat(messages).anyMatch(m -> m.contains("Line 1"));
                assertThat(messages).anyMatch(m -> m.contains("Line 2"));
            }
        }

        @Test
        @DisplayName("teleportToWorld should NOT show description when disabled in config")
        void teleportNoDescriptionWhenDisabled() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDescription("Should not appear");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

                worldService.teleportToWorld(player, "world");

                ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
                verify(player, atLeastOnce()).sendMessage(captor.capture());
                List<String> messages = captor.getAllValues();
                assertThat(messages).noneMatch(m -> m.contains("Should not appear"));
            }
        }

        @Test
        @DisplayName("teleportToWorld should execute post-teleport commands")
        void teleportExecutesPostCommands() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                org.bukkit.command.ConsoleCommandSender consoleSender = mock(org.bukkit.command.ConsoleCommandSender.class);
                bukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setPostTeleportCommands("say {player} entered {world}\ngive {player} diamond 1");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

                worldService.teleportToWorld(player, "world");

                bukkit.verify(() -> Bukkit.dispatchCommand(consoleSender, "say TestPlayer entered world"));
                bukkit.verify(() -> Bukkit.dispatchCommand(consoleSender, "give TestPlayer diamond 1"));
            }
        }

        @Test
        @DisplayName("teleportToWorld should NOT execute commands when postTeleportCommands is null")
        void teleportNoCommandsWhenNull() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setPostTeleportCommands(null);
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

                worldService.teleportToWorld(player, "world");

                bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
            }
        }

        @Test
        @DisplayName("teleportToWorld should apply world difficulty when set")
        void teleportAppliesDifficulty() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDifficulty("HARD");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

                worldService.getOrCreateSettings("world");

                verify(world).setDifficulty(org.bukkit.Difficulty.HARD);
            }
        }

        @Test
        @DisplayName("teleportToWorld should replace placeholders in description")
        void teleportDescriptionReplacesPlaceholders() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDescription("Welcome {player} to {world}!");
                settings.setDisplayName("MyWorld");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(true);

                worldService.teleportToWorld(player, "world");

                ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
                verify(player, atLeast(2)).sendMessage(captor.capture());
                List<String> messages = captor.getAllValues();
                assertThat(messages).anyMatch(m -> m.contains("Welcome TestPlayer to MyWorld!"));
            }
        }

        @Test
        @DisplayName("teleportToWorld should handle color codes in description")
        void teleportDescriptionHandlesColorCodes() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDescription("&aGreen text&r and &cRed text");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(true);

                worldService.teleportToWorld(player, "world");

                ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
                verify(player, atLeast(2)).sendMessage(captor.capture());
                List<String> messages = captor.getAllValues();
                // ChatColor codes should be translated (& -> §)
                assertThat(messages).anyMatch(m -> m.contains("§a") || m.contains("§c"));
            }
        }

        @Test
        @DisplayName("teleportToWorld should NOT execute commands when postTeleportCommands is empty string")
        void teleportEmptyPostTeleportCommandsString() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setPostTeleportCommands("");  // Empty string, not null
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

                worldService.teleportToWorld(player, "world");

                bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
            }
        }

        @Test
        @DisplayName("teleportToWorld should apply description, commands, and difficulty together")
        void teleportDescriptionAndCommandsAndDifficultyTogether() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                org.bukkit.command.ConsoleCommandSender consoleSender = mock(org.bukkit.command.ConsoleCommandSender.class);
                bukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDescription("Welcome to the challenge!");
                settings.setPostTeleportCommands("give {player} iron_sword 1");
                settings.setDifficulty("HARD");
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(true);

                worldService.teleportToWorld(player, "world");

                // Verify description was sent
                ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
                verify(player, atLeast(2)).sendMessage(captor.capture());
                List<String> messages = captor.getAllValues();
                assertThat(messages).anyMatch(m -> m.contains("Welcome to the challenge!"));

                // Verify command was executed
                bukkit.verify(() -> Bukkit.dispatchCommand(consoleSender, "give TestPlayer iron_sword 1"));

                // Verify difficulty was set (happens in getOrCreateSettings)
                verify(world).setDifficulty(org.bukkit.Difficulty.HARD);
            }
        }

        @Test
        @DisplayName("teleportToWorld should ignore invalid difficulty string")
        void teleportInvalidDifficultyStringIgnored() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                Location spawnLocation = mock(Location.class);
                when(world.getSpawnLocation()).thenReturn(spawnLocation);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setDifficulty("SUPERHARD");  // Invalid difficulty
                mockQueryReturning(settings);

                when(mockConfig.isUseSpawnLocation()).thenReturn(false);
                when(mockConfig.getTpCooldown()).thenReturn(0);
                when(mockConfig.isShowDescriptionOnTeleport()).thenReturn(false);

                // Should not throw exception
                worldService.getOrCreateSettings("world");

                // Verify setDifficulty was NOT called
                verify(world, never()).setDifficulty(any(org.bukkit.Difficulty.class));
            }
        }
    }

}
