package com.ultikits.plugins.worlds.commands;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.worlds.UltiWorldsTestHelper;
import com.ultikits.plugins.worlds.config.WorldConfig;
import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.plugins.worlds.service.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for WorldCommand.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("WorldCommand Tests")
class WorldCommandTest {

    private WorldCommand command;
    private WorldService mockWorldService;
    private WorldConfig mockConfig;
    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() throws Exception {
        UltiWorldsTestHelper.setUp();
        mockPlugin = UltiWorldsTestHelper.getMockPlugin();

        command = new WorldCommand();
        mockWorldService = mock(WorldService.class);
        mockConfig = UltiWorldsTestHelper.createDefaultConfig();

        UltiWorldsTestHelper.setField(command, "worldService", mockWorldService);
        UltiWorldsTestHelper.setField(command, "plugin", mockPlugin);

        when(mockWorldService.getConfig()).thenReturn(mockConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiWorldsTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Suggestion Methods")
    class SuggestionMethods {

        @Test
        @DisplayName("suggestWorlds should return world names")
        void suggestWorlds() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world1 = mock(World.class);
                World world2 = mock(World.class);
                when(world1.getName()).thenReturn("world");
                when(world2.getName()).thenReturn("world_nether");

                bukkit.when(Bukkit::getWorlds).thenReturn(Arrays.asList(world1, world2));

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
                List<String> suggestions = command.suggestWorlds(player, "");

                assertThat(suggestions).containsExactly("world", "world_nether");
            }
        }

        @Test
        @DisplayName("suggestWorlds should filter by input")
        void suggestWorldsFilter() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world1 = mock(World.class);
                World world2 = mock(World.class);
                World world3 = mock(World.class);
                when(world1.getName()).thenReturn("world");
                when(world2.getName()).thenReturn("world_nether");
                when(world3.getName()).thenReturn("pvp");

                bukkit.when(Bukkit::getWorlds).thenReturn(Arrays.asList(world1, world2, world3));

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
                List<String> suggestions = command.suggestWorlds(player, "world");

                assertThat(suggestions).containsExactly("world", "world_nether");
            }
        }

        @Test
        @DisplayName("suggestWorldTypes should return environment types")
        void suggestWorldTypes() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            List<String> suggestions = command.suggestWorldTypes(player, "");

            assertThat(suggestions).containsExactly("NORMAL", "NETHER", "THE_END");
        }

        @Test
        @DisplayName("suggestOptions should return setting options")
        void suggestOptions() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            List<String> suggestions = command.suggestOptions(player, "");

            assertThat(suggestions).contains("pvp", "monsters", "animals", "weather", "hidden",
                    "locked", "blocked", "displayname", "description", "icon");
        }

        @Test
        @DisplayName("suggestBooleans should return boolean values")
        void suggestBooleans() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            List<String> suggestions = command.suggestBooleans(player, "");

            assertThat(suggestions).containsExactly("true", "false", "on", "off");
        }
    }

    @Nested
    @DisplayName("List Command")
    class ListCommand {

        @Test
        @DisplayName("listWorlds should display all worlds")
        void listWorlds() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world1 = mock(World.class);
                World world2 = mock(World.class);
                when(world1.getName()).thenReturn("world");
                when(world2.getName()).thenReturn("pvp");
                when(world1.getPlayers()).thenReturn(Collections.emptyList());
                when(world2.getPlayers()).thenReturn(Collections.emptyList());

                when(mockWorldService.getAllWorlds()).thenReturn(Arrays.asList(world1, world2));

                WorldSettings settings1 = UltiWorldsTestHelper.createSampleWorldSettings("world");
                WorldSettings settings2 = UltiWorldsTestHelper.createSampleWorldSettings("pvp");

                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings1);
                when(mockWorldService.getOrCreateSettings("pvp")).thenReturn(settings2);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.listWorlds(player);

                verify(player, atLeast(2)).sendMessage(anyString());
            }
        }
    }

    @Nested
    @DisplayName("Teleport Command")
    class TeleportCommand {

        @Test
        @DisplayName("teleportToWorld should call service when enabled")
        void teleportToWorld() {
            when(mockConfig.isTpToWorldEnabled()).thenReturn(true);
            when(mockWorldService.teleportToWorld(any(), anyString())).thenReturn(true);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            command.teleportToWorld(player, "world");

            verify(mockWorldService).teleportToWorld(player, "world");
        }

        @Test
        @DisplayName("teleportToWorld should deny when disabled")
        void teleportToWorldDisabled() {
            when(mockConfig.isTpToWorldEnabled()).thenReturn(false);

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            command.teleportToWorld(player, "world");

            verify(mockWorldService, never()).teleportToWorld(any(), anyString());
            verify(player).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("World Settings Command")
    class WorldSettingsCommand {

        @Test
        @DisplayName("setWorldOption should update PVP setting")
        void setWorldOptionPvp() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.setWorldOption(player, "world", "pvp", "false");

                assertThat(settings.isPvpEnabled()).isFalse();
                verify(mockWorldService).updateSettings(settings);
                verify(world).setPVP(false);
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("setWorldOption should update display name")
        void setWorldOptionDisplayName() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.setWorldOption(player, "world", "displayname", "Custom World");

                assertThat(settings.getDisplayName()).isEqualTo("Custom World");
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should handle invalid option")
        void setWorldOptionInvalid() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.setWorldOption(player, "world", "invalid_option", "value");

                verify(mockWorldService, never()).updateSettings(any());
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("setWorldOption should handle world not found")
        void setWorldOptionWorldNotFound() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("unknown")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.setWorldOption(player, "unknown", "pvp", "true");

                verify(mockWorldService, never()).updateSettings(any());
                verify(player).sendMessage(anyString());
            }
        }
    }

    @Nested
    @DisplayName("Protection Commands")
    class ProtectionCommands {

        @Test
        @DisplayName("protectWorld should enable full protection")
        void protectWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.protectWorld(player, "world");

                assertThat(settings.hasProtection()).isTrue();
                verify(mockWorldService).updateSettings(settings);
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("unprotectWorld should disable all protection")
        void unprotectWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.enableFullProtection();
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.unprotectWorld(player, "world");

                assertThat(settings.hasProtection()).isFalse();
                verify(mockWorldService).updateSettings(settings);
            }
        }
    }

    @Nested
    @DisplayName("Block Commands")
    class BlockCommands {

        @Test
        @DisplayName("blockWorld should block world and kick players")
        void blockWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                World defaultWorld = mock(World.class);
                Player playerInWorld = UltiWorldsTestHelper.createMockPlayer("PlayerInWorld", UUID.randomUUID());

                when(world.getName()).thenReturn("blocked_world");
                when(world.getPlayers()).thenReturn(Collections.singletonList(playerInWorld));
                when(defaultWorld.getSpawnLocation()).thenReturn(mock(org.bukkit.Location.class));

                bukkit.when(() -> Bukkit.getWorld("blocked_world")).thenReturn(world);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(defaultWorld);

                when(mockConfig.getDefaultWorld()).thenReturn("world");

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("blocked_world");
                when(mockWorldService.getOrCreateSettings("blocked_world")).thenReturn(settings);

                Player admin = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.blockWorld(admin, "blocked_world");

                assertThat(settings.isBlocked()).isTrue();
                verify(mockWorldService).updateSettings(settings);
                verify(playerInWorld).teleport(any(org.bukkit.Location.class));
                verify(playerInWorld).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("unblockWorld should unblock world")
        void unblockWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                settings.setBlocked(true);
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

                command.unblockWorld(player, "world");

                assertThat(settings.isBlocked()).isFalse();
                verify(mockWorldService).updateSettings(settings);
            }
        }
    }

    @Nested
    @DisplayName("Create Commands")
    class CreateCommands {

        @Test
        @DisplayName("createWorld should create world when player has permission")
        void createWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("new_world")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
                when(player.hasPermission("ultiworlds.admin.create")).thenReturn(true);
                when(mockWorldService.createWorld("new_world", World.Environment.NORMAL,
                        org.bukkit.WorldType.NORMAL, null)).thenReturn(true);

                command.createWorld(player, "new_world");

                verify(mockWorldService).createWorld("new_world", World.Environment.NORMAL,
                        org.bukkit.WorldType.NORMAL, null);
            }
        }

        @Test
        @DisplayName("createWorld should deny when player has no permission")
        void createWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.create")).thenReturn(false);

            command.createWorld(player, "new_world");

            verify(mockWorldService, never()).createWorld(anyString(), any(), any(), anyString());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("createWorld should warn when world already exists")
        void createWorldExists() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World existing = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("existing")).thenReturn(existing);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
                when(player.hasPermission("ultiworlds.admin.create")).thenReturn(true);

                command.createWorld(player, "existing");

                verify(mockWorldService, never()).createWorld(anyString(), any(), any(), anyString());
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("createWorld should send failure message when creation fails")
        void createWorldFails() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("failed")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
                when(player.hasPermission("ultiworlds.admin.create")).thenReturn(true);
                when(mockWorldService.createWorld("failed", World.Environment.NORMAL,
                        org.bukkit.WorldType.NORMAL, null)).thenReturn(false);

                command.createWorld(player, "failed");

                verify(player, atLeast(2)).sendMessage(anyString()); // creating + failed messages
            }
        }

        @Test
        @DisplayName("createWorldWithType should create NETHER world")
        void createWorldWithType() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.create")).thenReturn(true);
            when(mockWorldService.createWorld("nether_world", World.Environment.NETHER,
                    org.bukkit.WorldType.NORMAL, null)).thenReturn(true);

            command.createWorldWithType(player, "nether_world", "NETHER");

            verify(mockWorldService).createWorld("nether_world", World.Environment.NETHER,
                    org.bukkit.WorldType.NORMAL, null);
        }

        @Test
        @DisplayName("createWorldWithType should deny when no permission")
        void createWorldWithTypeNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.create")).thenReturn(false);

            command.createWorldWithType(player, "new_world", "NORMAL");

            verify(mockWorldService, never()).createWorld(anyString(), any(), any(), anyString());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("createWorldWithType should handle invalid type")
        void createWorldWithInvalidType() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.create")).thenReturn(true);

            command.createWorldWithType(player, "new_world", "INVALID_TYPE");

            verify(mockWorldService, never()).createWorld(anyString(), any(), any(), anyString());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("createWorldWithType should send failure message when creation fails")
        void createWorldWithTypeFails() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.create")).thenReturn(true);
            when(mockWorldService.createWorld("failed", World.Environment.NORMAL,
                    org.bukkit.WorldType.NORMAL, null)).thenReturn(false);

            command.createWorldWithType(player, "failed", "NORMAL");

            verify(player, atLeast(2)).sendMessage(anyString()); // creating + failed
        }
    }

    @Nested
    @DisplayName("Load/Unload Commands")
    class LoadUnloadCommands {

        @Test
        @DisplayName("loadWorld should load when player has permission")
        void loadWorld() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.load")).thenReturn(true);
            when(mockWorldService.loadWorld("myworld")).thenReturn(true);

            command.loadWorld(player, "myworld");

            verify(mockWorldService).loadWorld("myworld");
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("loadWorld should deny when no permission")
        void loadWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.load")).thenReturn(false);

            command.loadWorld(player, "myworld");

            verify(mockWorldService, never()).loadWorld(anyString());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("loadWorld should send failure message when loading fails")
        void loadWorldFails() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.load")).thenReturn(true);
            when(mockWorldService.loadWorld("missing")).thenReturn(false);

            command.loadWorld(player, "missing");

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("unloadWorld should unload when player has permission")
        void unloadWorld() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.unload")).thenReturn(true);
            when(mockConfig.getDefaultWorld()).thenReturn("world");
            when(mockWorldService.unloadWorld("myworld", true)).thenReturn(true);

            command.unloadWorld(player, "myworld");

            verify(mockWorldService).unloadWorld("myworld", true);
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("unloadWorld should deny when no permission")
        void unloadWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.unload")).thenReturn(false);

            command.unloadWorld(player, "myworld");

            verify(mockWorldService, never()).unloadWorld(anyString(), anyBoolean());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("unloadWorld should deny unloading default world")
        void unloadWorldDefault() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.unload")).thenReturn(true);
            when(mockConfig.getDefaultWorld()).thenReturn("world");

            command.unloadWorld(player, "world");

            verify(mockWorldService, never()).unloadWorld(anyString(), anyBoolean());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("unloadWorld should send failure message when unload fails")
        void unloadWorldFails() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.unload")).thenReturn(true);
            when(mockConfig.getDefaultWorld()).thenReturn("world");
            when(mockWorldService.unloadWorld("myworld", true)).thenReturn(false);

            command.unloadWorld(player, "myworld");

            verify(player).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("Delete Command")
    class DeleteCommandTests {

        @Test
        @DisplayName("deleteWorld should delete when player has permission")
        void deleteWorld() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.delete")).thenReturn(true);
            when(mockConfig.getDefaultWorld()).thenReturn("world");
            when(mockWorldService.deleteWorld("old_world")).thenReturn(true);

            command.deleteWorld(player, "old_world");

            verify(mockWorldService).deleteWorld("old_world");
        }

        @Test
        @DisplayName("deleteWorld should deny when no permission")
        void deleteWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.delete")).thenReturn(false);

            command.deleteWorld(player, "old_world");

            verify(mockWorldService, never()).deleteWorld(anyString());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("deleteWorld should deny deleting default world")
        void deleteWorldDefault() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.delete")).thenReturn(true);
            when(mockConfig.getDefaultWorld()).thenReturn("world");

            command.deleteWorld(player, "world");

            verify(mockWorldService, never()).deleteWorld(anyString());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("deleteWorld should send failure message when delete fails")
        void deleteWorldFails() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.delete")).thenReturn(true);
            when(mockConfig.getDefaultWorld()).thenReturn("world");
            when(mockWorldService.deleteWorld("old_world")).thenReturn(false);

            command.deleteWorld(player, "old_world");

            verify(player, atLeast(2)).sendMessage(anyString()); // deleting + failed
        }
    }

    @Nested
    @DisplayName("Settings Commands Extended")
    class SettingsCommandsExtended {

        @Test
        @DisplayName("setWorldOption should deny when no permission")
        void setWorldOptionNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.settings")).thenReturn(false);

            command.setWorldOption(player, "world", "pvp", "true");

            verify(mockWorldService, never()).updateSettings(any());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("setWorldOption should update monsters setting")
        void setWorldOptionMonsters() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "monsters", "false");

                assertThat(settings.isMonstersEnabled()).isFalse();
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update animals setting")
        void setWorldOptionAnimals() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "animals", "false");

                assertThat(settings.isAnimalsEnabled()).isFalse();
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update weather setting")
        void setWorldOptionWeather() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "weather", "false");

                assertThat(settings.isWeatherEnabled()).isFalse();
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update hidden setting")
        void setWorldOptionHidden() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "hidden", "true");

                assertThat(settings.isHidden()).isTrue();
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update locked setting")
        void setWorldOptionLocked() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "locked", "on");

                assertThat(settings.isLocked()).isTrue();
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update blocked setting")
        void setWorldOptionBlocked() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "blocked", "1");

                assertThat(settings.isBlocked()).isTrue();
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update description with desc alias")
        void setWorldOptionDesc() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "desc", "My description");

                assertThat(settings.getDescription()).isEqualTo("My description");
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update description with description option")
        void setWorldOptionDescription() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "description", "Test desc");

                assertThat(settings.getDescription()).isEqualTo("Test desc");
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update name alias for displayname")
        void setWorldOptionName() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "name", "My World");

                assertThat(settings.getDisplayName()).isEqualTo("My World");
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("setWorldOption should update icon in uppercase")
        void setWorldOptionIcon() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
                when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.setWorldOption(player, "world", "icon", "diamond_block");

                assertThat(settings.getIcon()).isEqualTo("DIAMOND_BLOCK");
                verify(mockWorldService).updateSettings(settings);
            }
        }
    }

    @Nested
    @DisplayName("Protection Commands Extended")
    class ProtectionCommandsExtended {

        @Test
        @DisplayName("protectWorld should deny when no permission")
        void protectWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.protect")).thenReturn(false);

            command.protectWorld(player, "world");

            verify(mockWorldService, never()).updateSettings(any());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("protectWorld should deny when world not found")
        void protectWorldNotFound() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("unknown")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.protectWorld(player, "unknown");

                verify(mockWorldService, never()).updateSettings(any());
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("unprotectWorld should deny when no permission")
        void unprotectWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.protect")).thenReturn(false);

            command.unprotectWorld(player, "world");

            verify(mockWorldService, never()).updateSettings(any());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("unprotectWorld should deny when world not found")
        void unprotectWorldNotFound() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("unknown")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.unprotectWorld(player, "unknown");

                verify(mockWorldService, never()).updateSettings(any());
                verify(player).sendMessage(anyString());
            }
        }
    }

    @Nested
    @DisplayName("Block Commands Extended")
    class BlockCommandsExtended {

        @Test
        @DisplayName("blockWorld should deny when no permission")
        void blockWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.block")).thenReturn(false);

            command.blockWorld(player, "world");

            verify(mockWorldService, never()).updateSettings(any());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("blockWorld should deny when world not found")
        void blockWorldNotFound() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("unknown")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.blockWorld(player, "unknown");

                verify(mockWorldService, never()).updateSettings(any());
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("blockWorld should use fallback world when default not found")
        void blockWorldFallbackDefault() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                World fallbackWorld = mock(World.class);
                Location fallbackSpawn = mock(org.bukkit.Location.class);
                when(fallbackWorld.getSpawnLocation()).thenReturn(fallbackSpawn);

                when(world.getName()).thenReturn("blocked_world");
                when(world.getPlayers()).thenReturn(Collections.emptyList());

                bukkit.when(() -> Bukkit.getWorld("blocked_world")).thenReturn(world);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
                bukkit.when(Bukkit::getWorlds).thenReturn(Collections.singletonList(fallbackWorld));

                when(mockConfig.getDefaultWorld()).thenReturn("world");

                WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("blocked_world");
                when(mockWorldService.getOrCreateSettings("blocked_world")).thenReturn(settings);

                Player admin = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.blockWorld(admin, "blocked_world");

                assertThat(settings.isBlocked()).isTrue();
                verify(mockWorldService).updateSettings(settings);
            }
        }

        @Test
        @DisplayName("unblockWorld should deny when no permission")
        void unblockWorldNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.block")).thenReturn(false);

            command.unblockWorld(player, "world");

            verify(mockWorldService, never()).updateSettings(any());
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("unblockWorld should deny when world not found")
        void unblockWorldNotFound() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("unknown")).thenReturn(null);

                Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());

                command.unblockWorld(player, "unknown");

                verify(mockWorldService, never()).updateSettings(any());
                verify(player).sendMessage(anyString());
            }
        }
    }

    @Nested
    @DisplayName("Spawn Commands")
    class SpawnCommands {

        @Test
        @DisplayName("setWorldSpawn should set spawn location")
        void setWorldSpawn() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            command.setWorldSpawn(player);

            verify(mockWorldService).setWorldSpawn(eq("world"), any(org.bukkit.Location.class));
            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("setWorldSpawn should deny when no permission")
        void setWorldSpawnNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.setspawn")).thenReturn(false);

            command.setWorldSpawn(player);

            verify(mockWorldService, never()).setWorldSpawn(anyString(), any());
            verify(player).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("Info Command")
    class InfoCommand {

        @Test
        @DisplayName("worldInfo should display world information")
        void worldInfo() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            World world = player.getWorld();
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getSeed()).thenReturn(12345L);
            when(world.getPlayers()).thenReturn(Collections.emptyList());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            command.worldInfo(player);

            verify(player, atLeast(8)).sendMessage(anyString());
        }

        @Test
        @DisplayName("worldInfo should handle null display name")
        void worldInfoNullDisplayName() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            World world = player.getWorld();
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getSeed()).thenReturn(12345L);
            when(world.getPlayers()).thenReturn(Collections.emptyList());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setDisplayName(null);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            command.worldInfo(player);

            verify(player, atLeast(8)).sendMessage(anyString());
        }

        @Test
        @DisplayName("worldInfo should show blocked and protected status")
        void worldInfoBlockedProtected() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            World world = player.getWorld();
            when(world.getEnvironment()).thenReturn(World.Environment.NETHER);
            when(world.getSeed()).thenReturn(99999L);
            when(world.getPlayers()).thenReturn(Collections.emptyList());

            WorldSettings settings = UltiWorldsTestHelper.createSampleWorldSettings("world");
            settings.setBlocked(true);
            settings.enableFullProtection();
            settings.setPvpEnabled(false);
            settings.setMonstersEnabled(false);
            when(mockWorldService.getOrCreateSettings("world")).thenReturn(settings);

            command.worldInfo(player);

            verify(player, atLeast(8)).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("Help Command")
    class HelpCommand {

        @Test
        @DisplayName("help should display basic commands")
        void helpBasic() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin")).thenReturn(false);

            command.help(player);

            verify(player, atLeast(4)).sendMessage(anyString());
        }

        @Test
        @DisplayName("help should display admin commands for admins")
        void helpAdmin() {
            Player player = UltiWorldsTestHelper.createMockPlayer("Admin", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin")).thenReturn(true);

            command.help(player);

            verify(player, atLeast(10)).sendMessage(anyString());
        }

        @Test
        @DisplayName("handleHelp should call help for Player sender")
        void handleHelpPlayer() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin")).thenReturn(false);

            command.handleHelp(player);

            verify(player, atLeast(4)).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("Suggestion Methods Extended")
    class SuggestionMethodsExtended {

        @Test
        @DisplayName("suggestWorldTypes should filter by input")
        void suggestWorldTypesFilter() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            List<String> suggestions = command.suggestWorldTypes(player, "ne");

            assertThat(suggestions).containsExactly("NETHER");
        }

        @Test
        @DisplayName("suggestOptions should filter by input")
        void suggestOptionsFilter() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            List<String> suggestions = command.suggestOptions(player, "p");

            assertThat(suggestions).contains("pvp");
        }

        @Test
        @DisplayName("suggestBooleans should filter by input")
        void suggestBooleansFilter() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            List<String> suggestions = command.suggestBooleans(player, "t");

            assertThat(suggestions).containsExactly("true");
        }
    }

    @Nested
    @DisplayName("Wizard Command")
    class WizardCommand {

        @Test
        @DisplayName("startWizard should deny when no permission")
        void startWizardNoPermission() {
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(player.hasPermission("ultiworlds.admin.create")).thenReturn(false);

            command.startWizard(player);

            verify(player).sendMessage(anyString());
        }
    }
}
