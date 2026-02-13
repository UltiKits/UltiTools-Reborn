package com.ultikits.plugins.sidebar.service;

import com.ultikits.plugins.sidebar.UltiSideBar;
import com.ultikits.plugins.sidebar.UltiSideBarTestHelper;
import com.ultikits.plugins.sidebar.config.SideBarConfig;
import com.ultikits.plugins.sidebar.data.SideBarPreference;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SideBarService.
 */
@DisplayName("SideBarService Tests")
class SideBarServiceTest {

    private SideBarService service;
    private SideBarConfig config;
    @SuppressWarnings("unchecked")
    private DataOperator<SideBarPreference> dataOperator = mock(DataOperator.class);
    @SuppressWarnings("unchecked")
    private Query<SideBarPreference> query = mock(Query.class);

    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiSideBarTestHelper.setUp();

        config = mock(SideBarConfig.class);
        lenient().when(config.isEnabled()).thenReturn(true);
        lenient().when(config.isDefaultEnabled()).thenReturn(true);
        lenient().when(config.getTitle()).thenReturn("&6&lTest Server");
        lenient().when(config.getUpdateInterval()).thenReturn(20);
        lenient().when(config.getLines()).thenReturn(Arrays.asList("Line 1", "Line 2"));
        lenient().when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

        // Setup query chain mocking
        lenient().when(dataOperator.query()).thenReturn(query);
        lenient().when(query.where(anyString())).thenReturn(query);
        lenient().when(query.eq(any())).thenReturn(query);
        lenient().when(query.list()).thenReturn(Collections.emptyList());

        service = new SideBarService();

        // Inject dependencies via reflection
        UltiSideBarTestHelper.setField(service, "plugin", UltiSideBarTestHelper.getMockPlugin());
        UltiSideBarTestHelper.setField(service, "config", config);
        UltiSideBarTestHelper.setField(service, "dataOperator", dataOperator);

        playerUuid = UUID.randomUUID();
        player = UltiSideBarTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiSideBarTestHelper.tearDown();
    }

    // ==================== init ====================

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("Should initialize with PlaceholderAPI not available")
        void initWithoutPlaceholderAPI() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                PluginManager pluginManager = mock(PluginManager.class);
                when(pluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);
                bukkitMock.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(Collections.emptyList());

                org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);

                service.init();

                verify(UltiSideBarTestHelper.getMockLogger()).warn("PlaceholderAPI not found! Variables will not work.");
            }
        }

        @Test
        @DisplayName("Should initialize for online players")
        void initForOnlinePlayers() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                PluginManager pluginManager = mock(PluginManager.class);
                when(pluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);
                bukkitMock.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(Collections.singletonList(player));

                org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);

                // init() reassigns dataOperator from the mock plugin, so stub
                // the DataOperator that the plugin returns
                @SuppressWarnings("unchecked")
                DataOperator<SideBarPreference> pluginDataOp =
                    UltiSideBarTestHelper.getMockPlugin().getDataOperator(SideBarPreference.class);
                @SuppressWarnings("unchecked")
                Query<SideBarPreference> pluginQuery = mock(Query.class);
                when(pluginDataOp.query()).thenReturn(pluginQuery);
                when(pluginQuery.where(anyString())).thenReturn(pluginQuery);
                when(pluginQuery.eq(any())).thenReturn(pluginQuery);
                when(pluginQuery.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), true)));

                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard scoreboard = mock(Scoreboard.class);
                Objective objective = mock(Objective.class);
                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getNewScoreboard()).thenReturn(scoreboard);
                when(scoreboard.registerNewObjective(anyString(), anyString(), anyString())).thenReturn(objective);
                when(scoreboard.getEntries()).thenReturn(Collections.emptySet());

                service.init();

                // Should query for player preferences
                verify(pluginDataOp, atLeastOnce()).query();
            }
        }
    }

    // ==================== shutdown ====================

    @Nested
    @DisplayName("shutdown")
    class Shutdown {

        @Test
        @DisplayName("Should clear all scoreboards on shutdown")
        void shutdownClearsScoreboards() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(Collections.singletonList(player));

                service.shutdown();

                // Verify player scoreboard cleanup would be attempted
                bukkitMock.verify(Bukkit::getOnlinePlayers);
            }
        }
    }

    // ==================== enableSidebar ====================

    @Nested
    @DisplayName("enableSidebar")
    class EnableSidebar {

        @Test
        @DisplayName("Should not enable when config disabled")
        void configDisabled() {
            when(config.isEnabled()).thenReturn(false);

            service.enableSidebar(player);

            verify(dataOperator, never()).query();
            verify(dataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("Should not enable in blacklisted world")
        void blacklistedWorld() {
            when(config.getWorldBlacklist()).thenReturn(Collections.singletonList("world"));
            when(query.list()).thenReturn(Collections.emptyList());

            service.enableSidebar(player);

            // Should update database but not show scoreboard
            verify(dataOperator).insert(any(SideBarPreference.class));
        }

        @Test
        @DisplayName("Should create scoreboard and set to player")
        void createsScoreboard() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard scoreboard = mock(Scoreboard.class);
                Objective objective = mock(Objective.class);
                Score score = mock(Score.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getNewScoreboard()).thenReturn(scoreboard);
                when(scoreboard.registerNewObjective(anyString(), anyString(), anyString())).thenReturn(objective);
                when(objective.getScore(anyString())).thenReturn(score);
                when(scoreboard.getEntries()).thenReturn(Collections.emptySet());

                when(query.list()).thenReturn(Collections.emptyList());

                service.enableSidebar(player);

                verify(player).setScoreboard(scoreboard);
                verify(objective).setDisplaySlot(DisplaySlot.SIDEBAR);
            }
        }

        @Test
        @DisplayName("Should update database with enabled state")
        void updatesDatabase() {
            when(query.list()).thenReturn(Collections.emptyList());

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard scoreboard = mock(Scoreboard.class);
                Objective objective = mock(Objective.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getNewScoreboard()).thenReturn(scoreboard);
                when(scoreboard.registerNewObjective(anyString(), anyString(), anyString())).thenReturn(objective);
                when(scoreboard.getEntries()).thenReturn(Collections.emptySet());

                service.enableSidebar(player);

                ArgumentCaptor<SideBarPreference> captor = ArgumentCaptor.forClass(SideBarPreference.class);
                verify(dataOperator).insert(captor.capture());
                assertThat(captor.getValue().getPlayerUuid()).isEqualTo(playerUuid.toString());
                assertThat(captor.getValue().getEnabled()).isTrue();
            }
        }
    }

    // ==================== disableSidebar ====================

    @Nested
    @DisplayName("disableSidebar")
    class DisableSidebar {

        @Test
        @DisplayName("Should update database and remove scoreboard")
        void disables() {
            when(query.list()).thenReturn(Collections.emptyList());

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard mainScoreboard = mock(Scoreboard.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getMainScoreboard()).thenReturn(mainScoreboard);

                service.disableSidebar(player);

                ArgumentCaptor<SideBarPreference> captor = ArgumentCaptor.forClass(SideBarPreference.class);
                verify(dataOperator).insert(captor.capture());
                assertThat(captor.getValue().getEnabled()).isFalse();
                verify(player).setScoreboard(mainScoreboard);
            }
        }
    }

    // ==================== toggleSidebar ====================

    @Nested
    @DisplayName("toggleSidebar")
    class ToggleSidebar {

        @Test
        @DisplayName("Should toggle from enabled to disabled")
        void toggleOff() {
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), true)));

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard mainScoreboard = mock(Scoreboard.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getMainScoreboard()).thenReturn(mainScoreboard);

                boolean result = service.toggleSidebar(player);

                assertThat(result).isFalse();
            }
        }

        @Test
        @DisplayName("Should toggle from disabled to enabled")
        void toggleOn() {
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), false)));

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard scoreboard = mock(Scoreboard.class);
                Objective objective = mock(Objective.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getNewScoreboard()).thenReturn(scoreboard);
                when(scoreboard.registerNewObjective(anyString(), anyString(), anyString())).thenReturn(objective);
                when(scoreboard.getEntries()).thenReturn(Collections.emptySet());

                boolean result = service.toggleSidebar(player);

                assertThat(result).isTrue();
            }
        }

        @Test
        @DisplayName("Should enable by default when no preference exists")
        void toggleDefaultEnabled() {
            when(query.list()).thenReturn(Collections.emptyList());
            when(config.isDefaultEnabled()).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard mainScoreboard = mock(Scoreboard.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getMainScoreboard()).thenReturn(mainScoreboard);

                boolean result = service.toggleSidebar(player);

                assertThat(result).isFalse();
            }
        }
    }

    // ==================== isSidebarEnabled ====================

    @Nested
    @DisplayName("isSidebarEnabled")
    class IsSidebarEnabled {

        @Test
        @DisplayName("Should return false when config disabled")
        void configDisabled() {
            when(config.isEnabled()).thenReturn(false);

            boolean result = service.isSidebarEnabled(player);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false in blacklisted world")
        void blacklistedWorld() {
            when(config.getWorldBlacklist()).thenReturn(Collections.singletonList("world"));
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), true)));

            boolean result = service.isSidebarEnabled(player);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return true when enabled in database")
        void enabledInDb() {
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), true)));

            boolean result = service.isSidebarEnabled(player);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false when disabled in database")
        void disabledInDb() {
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), false)));

            boolean result = service.isSidebarEnabled(player);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should use default enabled when no preference exists")
        void defaultEnabled() {
            when(query.list()).thenReturn(Collections.emptyList());
            when(config.isDefaultEnabled()).thenReturn(true);

            boolean result = service.isSidebarEnabled(player);

            assertThat(result).isTrue();
        }
    }

    // ==================== updateSidebar ====================

    @Nested
    @DisplayName("updateSidebar")
    class UpdateSidebar {

        @Test
        @DisplayName("Should do nothing when scoreboard not found")
        void noScoreboard() {
            service.updateSidebar(player);

            // No exception should be thrown
        }

        @Test
        @DisplayName("Should skip update when content unchanged")
        void skipWhenUnchanged() throws Exception {
            Scoreboard scoreboard = mock(Scoreboard.class);
            Objective objective = mock(Objective.class);
            when(scoreboard.getObjective("sidebar")).thenReturn(objective);
            when(scoreboard.getEntries()).thenReturn(Collections.emptySet());

            Map<UUID, Scoreboard> scoreboards = new HashMap<>();
            scoreboards.put(playerUuid, scoreboard);
            UltiSideBarTestHelper.setField(service, "playerScoreboards", scoreboards);

            Map<UUID, List<String>> cache = new HashMap<>();
            cache.put(playerUuid, Arrays.asList("Line 1", "Line 2"));
            UltiSideBarTestHelper.setField(service, "contentCache", cache);

            service.updateSidebar(player);

            // Should not clear scores since content is same
            verify(scoreboard, never()).resetScores(anyString());
        }
    }

    // ==================== clearCache ====================

    @Nested
    @DisplayName("clearCache")
    class ClearCache {

        @Test
        @DisplayName("Should clear content cache")
        void clearsCache() throws Exception {
            Map<UUID, List<String>> cache = new HashMap<>();
            cache.put(playerUuid, Arrays.asList("Line 1"));
            UltiSideBarTestHelper.setField(service, "contentCache", cache);

            service.clearCache();

            // Verify by reading the field back via reflection on SideBarService
            java.lang.reflect.Field cacheField = SideBarService.class.getDeclaredField("contentCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, List<String>> resultCache = (Map<UUID, List<String>>) cacheField.get(service);
            assertThat(resultCache).isEmpty();
        }
    }

    // ==================== reload ====================

    @Nested
    @DisplayName("reload")
    class Reload {

        @Test
        @DisplayName("Should shutdown and reinitialize")
        void reloads() {
            SideBarService spyService = spy(service);

            doNothing().when(spyService).shutdown();
            doNothing().when(spyService).init();

            spyService.reload();

            verify(spyService).shutdown();
            verify(spyService).init();
        }
    }

    // ==================== removeSidebar ====================

    @Nested
    @DisplayName("removeSidebar")
    class RemoveSidebar {

        @Test
        @DisplayName("Should reset to main scoreboard")
        void resetsScoreboard() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard mainScoreboard = mock(Scoreboard.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getMainScoreboard()).thenReturn(mainScoreboard);

                service.removeSidebar(player);

                verify(player).setScoreboard(mainScoreboard);
            }
        }

        @Test
        @DisplayName("Should handle null scoreboard manager")
        void nullScoreboardManager() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(null);

                service.removeSidebar(player);

                // Should not throw exception
                verify(player, never()).setScoreboard(any());
            }
        }
    }

    // ==================== onPlayerJoin ====================

    @Nested
    @DisplayName("onPlayerJoin")
    class OnPlayerJoin {

        @Test
        @DisplayName("Should schedule sidebar enable for player")
        void schedulesEnable() {
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), true)));

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);

                service.onPlayerJoin(player);

                verify(scheduler).runTaskLater(any(), any(Runnable.class), eq(10L));
            }
        }

        @Test
        @DisplayName("Should not schedule when disabled in database")
        void doesNotScheduleWhenDisabled() {
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), false)));

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);

                service.onPlayerJoin(player);

                verify(scheduler, never()).runTaskLater(any(), any(Runnable.class), anyLong());
            }
        }
    }

    // ==================== onPlayerQuit ====================

    @Nested
    @DisplayName("onPlayerQuit")
    class OnPlayerQuit {

        @Test
        @DisplayName("Should clean up player data")
        void cleansUpData() throws Exception {
            Map<UUID, Scoreboard> scoreboards = new HashMap<>();
            scoreboards.put(playerUuid, mock(Scoreboard.class));
            UltiSideBarTestHelper.setField(service, "playerScoreboards", scoreboards);

            Map<UUID, List<String>> cache = new HashMap<>();
            cache.put(playerUuid, Arrays.asList("test"));
            UltiSideBarTestHelper.setField(service, "contentCache", cache);

            service.onPlayerQuit(player);

            assertThat(scoreboards).doesNotContainKey(playerUuid);
            assertThat(cache).doesNotContainKey(playerUuid);
        }
    }

    // ==================== onWorldChange ====================

    @Nested
    @DisplayName("onWorldChange")
    class OnWorldChange {

        @Test
        @DisplayName("Should remove sidebar in blacklisted world")
        void removesInBlacklistedWorld() {
            when(config.getWorldBlacklist()).thenReturn(Collections.singletonList("world"));

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard mainScoreboard = mock(Scoreboard.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getMainScoreboard()).thenReturn(mainScoreboard);

                service.onWorldChange(player);

                verify(player).setScoreboard(mainScoreboard);
            }
        }

        @Test
        @DisplayName("Should enable sidebar in allowed world")
        void enablesInAllowedWorld() throws Exception {
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            when(config.isEnabled()).thenReturn(true);
            when(query.list()).thenReturn(Arrays.asList(new SideBarPreference(playerUuid.toString(), true)));

            Map<UUID, Scoreboard> scoreboards = new HashMap<>();
            UltiSideBarTestHelper.setField(service, "playerScoreboards", scoreboards);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
                Scoreboard scoreboard = mock(Scoreboard.class);
                Objective objective = mock(Objective.class);

                bukkitMock.when(Bukkit::getScoreboardManager).thenReturn(scoreboardManager);
                when(scoreboardManager.getNewScoreboard()).thenReturn(scoreboard);
                when(scoreboard.registerNewObjective(anyString(), anyString(), anyString())).thenReturn(objective);
                when(scoreboard.getEntries()).thenReturn(Collections.emptySet());

                service.onWorldChange(player);

                verify(player).setScoreboard(scoreboard);
            }
        }
    }
}
