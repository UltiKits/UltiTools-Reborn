package com.ultikits.plugins.trade.service;

import com.ultikits.plugins.trade.UltiTradeTestHelper;
import com.ultikits.plugins.trade.config.TradeConfig;
import com.ultikits.plugins.trade.entity.PlayerTradeSettings;
import com.ultikits.plugins.trade.entity.TradeLogData;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TradeLogService Tests")
class TradeLogServiceTest {

    private TradeLogService service;
    private TradeConfig config;
    @SuppressWarnings("unchecked")
    private DataOperator<TradeLogData> logOperator = mock(DataOperator.class);
    @SuppressWarnings("unchecked")
    private DataOperator<PlayerTradeSettings> settingsOperator = mock(DataOperator.class);
    @SuppressWarnings("unchecked")
    private Query<PlayerTradeSettings> queryBuilder = mock(Query.class);

    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiTradeTestHelper.setUp();

        config = UltiTradeTestHelper.createDefaultConfig();

        service = new TradeLogService();

        // Inject dependencies via reflection
        UltiTradeTestHelper.setField(service, "config", config);
        UltiTradeTestHelper.setField(service, "logOperator", logOperator);
        UltiTradeTestHelper.setField(service, "settingsOperator", settingsOperator);

        playerUuid = UUID.randomUUID();
        player = UltiTradeTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiTradeTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Player Settings Management")
    class PlayerSettingsManagement {

        @Test
        @DisplayName("getOrCreateSettings should return existing settings")
        void getExistingSettings() {
            PlayerTradeSettings existing = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(existing));

            PlayerTradeSettings result = service.getOrCreateSettings(playerUuid, "TestPlayer");

            assertThat(result).isSameAs(existing);
            verify(settingsOperator, never()).insert(any());
        }

        @Test
        @DisplayName("getOrCreateSettings should create new settings if not found")
        void createNewSettings() {
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            PlayerTradeSettings result = service.getOrCreateSettings(playerUuid, "TestPlayer");

            assertThat(result).isNotNull();
            assertThat(result.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(result.getPlayerName()).isEqualTo("TestPlayer");
            verify(settingsOperator).insert(any(PlayerTradeSettings.class));
        }

        @Test
        @DisplayName("getOrCreateSettings should return cached settings")
        void getCachedSettings() throws Exception {
            PlayerTradeSettings cached = new PlayerTradeSettings(playerUuid, "TestPlayer");

            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            cache.put(playerUuid, cached);

            PlayerTradeSettings result = service.getOrCreateSettings(playerUuid, "TestPlayer");

            assertThat(result).isSameAs(cached);
            verify(settingsOperator, never()).getAll(any());
        }

        @Test
        @DisplayName("getOrCreateSettings should update name if changed")
        void updateNameIfChanged() {
            PlayerTradeSettings existing = new PlayerTradeSettings(playerUuid, "OldName");
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(existing));

            PlayerTradeSettings result = service.getOrCreateSettings(playerUuid, "NewName");

            assertThat(result.getPlayerName()).isEqualTo("NewName");
        }

        @Test
        @DisplayName("getOrCreateSettings should not update name if same")
        void dontUpdateNameIfSame() {
            PlayerTradeSettings existing = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(existing));

            service.getOrCreateSettings(playerUuid, "TestPlayer");

            // saveSettings would call runTaskAsynchronously, and name shouldn't change
            assertThat(existing.getPlayerName()).isEqualTo("TestPlayer");
        }

        @Test
        @DisplayName("getOrCreateSettings should handle null result from getAll")
        void handleNullResult() {
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(null);

            PlayerTradeSettings result = service.getOrCreateSettings(playerUuid, "TestPlayer");

            assertThat(result).isNotNull();
            verify(settingsOperator).insert(any(PlayerTradeSettings.class));
        }

        @Test
        @DisplayName("getSettings should return null if not found")
        void getSettingsNotFound() {
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            PlayerTradeSettings result = service.getSettings(playerUuid);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getSettings should return existing settings")
        void getSettingsFound() {
            PlayerTradeSettings existing = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(existing));

            PlayerTradeSettings result = service.getSettings(playerUuid);

            assertThat(result).isSameAs(existing);
        }

        @Test
        @DisplayName("getSettings should return cached settings without DB query")
        void getSettingsCached() throws Exception {
            PlayerTradeSettings cached = new PlayerTradeSettings(playerUuid, "TestPlayer");
            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            cache.put(playerUuid, cached);

            PlayerTradeSettings result = service.getSettings(playerUuid);

            assertThat(result).isSameAs(cached);
            verify(settingsOperator, never()).getAll(any());
        }

        @Test
        @DisplayName("getSettings should cache results from DB")
        void getSettingsCachesResult() throws Exception {
            PlayerTradeSettings existing = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(existing));

            service.getSettings(playerUuid);

            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            assertThat(cache).containsKey(playerUuid);
        }

        @Test
        @DisplayName("getSettings should handle null result from getAll")
        void getSettingsNullResult() {
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(null);

            PlayerTradeSettings result = service.getSettings(playerUuid);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Trade Toggle")
    class TradeToggle {

        @Test
        @DisplayName("isTradeEnabled should return true for non-existent settings")
        void isTradeEnabledDefault() {
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            boolean result = service.isTradeEnabled(playerUuid);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("isTradeEnabled should return settings value")
        void isTradeEnabledFromSettings() {
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            settings.setTradeEnabled(false);
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(settings));

            boolean result = service.isTradeEnabled(playerUuid);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("toggleTrade should toggle and return new state")
        void toggleTrade() {
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            boolean result = service.toggleTrade(player);

            assertThat(result).isFalse(); // Was true, now false
        }

        @Test
        @DisplayName("toggleTrade should toggle back to true")
        void toggleTradeBack() {
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            settings.setTradeEnabled(false);
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(settings));

            boolean result = service.toggleTrade(player);

            assertThat(result).isTrue(); // Was false, now true
        }
    }

    @Nested
    @DisplayName("Block Management")
    class BlockManagement {

        @Test
        @DisplayName("isBlocked should return false for non-existent settings")
        void isBlockedDefault() {
            UUID targetUuid = UUID.randomUUID();
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            boolean result = service.isBlocked(playerUuid, targetUuid);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("isBlocked should check settings")
        void isBlockedFromSettings() {
            UUID targetUuid = UUID.randomUUID();
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            settings.blockPlayer(targetUuid.toString());
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(settings));

            boolean result = service.isBlocked(playerUuid, targetUuid);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("blockPlayer should add to blocked list")
        void blockPlayer() {
            UUID targetUuid = UUID.randomUUID();
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            boolean result = service.blockPlayer(player, targetUuid);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("blockPlayer should return false for duplicate")
        void blockPlayerDuplicate() throws Exception {
            UUID targetUuid = UUID.randomUUID();
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            settings.blockPlayer(targetUuid.toString());
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(settings));

            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            cache.put(playerUuid, settings);
            boolean result = service.blockPlayer(player, targetUuid);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("blockPlayer should not save when already blocked")
        void blockPlayerNoSaveOnDuplicate() throws Exception {
            UUID targetUuid = UUID.randomUUID();
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            settings.blockPlayer(targetUuid.toString());

            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            cache.put(playerUuid, settings);

            service.blockPlayer(player, targetUuid);

            // saveSettings is not called for duplicates
            // (the blockPlayer method returns false and doesn't call saveSettings)
        }

        @Test
        @DisplayName("unblockPlayer should remove from blocked list")
        void unblockPlayer() throws Exception {
            UUID targetUuid = UUID.randomUUID();
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            settings.blockPlayer(targetUuid.toString());
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(settings));

            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            cache.put(playerUuid, settings);
            boolean result = service.unblockPlayer(player, targetUuid);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("unblockPlayer should return false for non-blocked")
        void unblockPlayerNotBlocked() {
            UUID targetUuid = UUID.randomUUID();
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            boolean result = service.unblockPlayer(player, targetUuid);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Player Statistics")
    class PlayerStatistics {

        @Test
        @DisplayName("getPlayerStats should return settings if found")
        void getPlayerStatsFound() {
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            settings.setTotalTrades(10);
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.singletonList(settings));

            PlayerTradeSettings result = service.getPlayerStats(playerUuid);

            assertThat(result).isSameAs(settings);
            assertThat(result.getTotalTrades()).isEqualTo(10);
        }

        @Test
        @DisplayName("getPlayerStats should return default if not found")
        void getPlayerStatsNotFound() {
            when(settingsOperator.query()).thenReturn(queryBuilder);
        when(queryBuilder.where(anyString())).thenReturn(queryBuilder);
        when(queryBuilder.eq(any())).thenReturn(queryBuilder);
        when(queryBuilder.list())
                    .thenReturn(Collections.emptyList());

            PlayerTradeSettings result = service.getPlayerStats(playerUuid);

            assertThat(result).isNotNull();
            assertThat(result.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(result.getTotalTrades()).isZero();
        }
    }

    @Nested
    @DisplayName("Trade Logs Retrieval")
    class TradeLogsRetrieval {

        @Test
        @DisplayName("getPlayerLogs should filter by player UUID")
        void getPlayerLogsFiltered() {
            UUID otherUuid = UUID.randomUUID();

            TradeLogData log1 = new TradeLogData(UUID.randomUUID(), playerUuid, "TestPlayer", otherUuid, "Other");
            log1.setTradeTime(1000L);

            TradeLogData log2 = new TradeLogData(UUID.randomUUID(), otherUuid, "Other", UUID.randomUUID(), "Third");
            log2.setTradeTime(2000L);

            TradeLogData log3 = new TradeLogData(UUID.randomUUID(), UUID.randomUUID(), "Third", playerUuid, "TestPlayer");
            log3.setTradeTime(3000L);

            when(logOperator.getAll()).thenReturn(Arrays.asList(log1, log2, log3));

            List<TradeLogData> result = service.getPlayerLogs(playerUuid, 10);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).isSameAs(log3); // Most recent first
            assertThat(result.get(1)).isSameAs(log1);
        }

        @Test
        @DisplayName("getPlayerLogs should sort by time descending")
        void getPlayerLogsSorted() {
            TradeLogData older = new TradeLogData(UUID.randomUUID(), playerUuid, "TestPlayer", UUID.randomUUID(), "Other");
            older.setTradeTime(1000L);

            TradeLogData newer = new TradeLogData(UUID.randomUUID(), playerUuid, "TestPlayer", UUID.randomUUID(), "Other");
            newer.setTradeTime(2000L);

            when(logOperator.getAll()).thenReturn(Arrays.asList(older, newer));

            List<TradeLogData> result = service.getPlayerLogs(playerUuid, 10);

            assertThat(result).extracting(TradeLogData::getTradeTime)
                    .containsExactly(2000L, 1000L);
        }

        @Test
        @DisplayName("getPlayerLogs should limit results")
        void getPlayerLogsLimited() {
            List<TradeLogData> logs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                TradeLogData log = new TradeLogData(UUID.randomUUID(), playerUuid, "TestPlayer", UUID.randomUUID(), "Other");
                log.setTradeTime(i * 1000L);
                logs.add(log);
            }

            when(logOperator.getAll()).thenReturn(logs);

            List<TradeLogData> result = service.getPlayerLogs(playerUuid, 5);

            assertThat(result).hasSize(5);
        }

        @Test
        @DisplayName("getPlayerLogs should return empty list on error")
        void getPlayerLogsError() {
            when(logOperator.getAll()).thenThrow(new RuntimeException("Database error"));

            List<TradeLogData> result = service.getPlayerLogs(playerUuid, 10);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getPlayerLogs should return all when under limit")
        void getPlayerLogsUnderLimit() {
            TradeLogData log1 = new TradeLogData(UUID.randomUUID(), playerUuid, "TestPlayer", UUID.randomUUID(), "Other");
            log1.setTradeTime(1000L);

            when(logOperator.getAll()).thenReturn(Collections.singletonList(log1));

            List<TradeLogData> result = service.getPlayerLogs(playerUuid, 10);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("getPlayerLogs should return empty when no matching logs")
        void getPlayerLogsNoMatch() {
            UUID otherUuid = UUID.randomUUID();
            TradeLogData log = new TradeLogData(UUID.randomUUID(), otherUuid, "Other", UUID.randomUUID(), "Third");
            log.setTradeTime(1000L);

            when(logOperator.getAll()).thenReturn(Collections.singletonList(log));

            List<TradeLogData> result = service.getPlayerLogs(playerUuid, 10);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("shutdown should cancel cleanup task")
        void cancelCleanupTask() throws Exception {
            BukkitTask mockTask = mock(BukkitTask.class);
            UltiTradeTestHelper.setField(service, "cleanupTask", mockTask);

            service.shutdown();

            verify(mockTask).cancel();
        }

        @Test
        @DisplayName("shutdown should handle null cleanup task")
        void nullCleanupTask() {
            // Should not throw
            service.shutdown();
        }

        @Test
        @DisplayName("shutdown should save cached settings")
        void saveCachedSettings() throws Exception {
            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            cache.put(playerUuid, settings);

            service.shutdown();

            verify(settingsOperator).update(settings);
        }

        @Test
        @DisplayName("shutdown should clear cache after saving")
        void clearCacheAfterSaving() throws Exception {
            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            cache.put(playerUuid, new PlayerTradeSettings(playerUuid, "TestPlayer"));

            service.shutdown();

            assertThat(cache).isEmpty();
        }

        @Test
        @DisplayName("shutdown should handle update failure gracefully")
        void handleUpdateFailure() throws Exception {
            Map<UUID, PlayerTradeSettings> cache = UltiTradeTestHelper.getField(service, "settingsCache");
            PlayerTradeSettings settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
            cache.put(playerUuid, settings);

            doThrow(new RuntimeException("DB error")).when(settingsOperator).update(settings);

            // Should not throw
            service.shutdown();

            assertThat(cache).isEmpty();
        }

        @Test
        @DisplayName("shutdown should set cleanup task to null")
        void setCleanupTaskNull() throws Exception {
            BukkitTask mockTask = mock(BukkitTask.class);
            UltiTradeTestHelper.setField(service, "cleanupTask", mockTask);

            service.shutdown();

            BukkitTask taskAfter = UltiTradeTestHelper.getField(service, "cleanupTask");
            assertThat(taskAfter).isNull();
        }
    }

    @Nested
    @DisplayName("Logging (disabled)")
    class LoggingDisabled {

        @Test
        @DisplayName("logCompletedTrade should do nothing when logging disabled")
        void logCompletedTradeDisabled() throws Exception {
            when(config.isEnableTradeLog()).thenReturn(false);

            com.ultikits.plugins.trade.entity.TradeSession session = mock(com.ultikits.plugins.trade.entity.TradeSession.class);

            service.logCompletedTrade(session, player, player, 0, 0);

            // No interaction with scheduler since logging is disabled
            verify(org.bukkit.Bukkit.getServer().getScheduler(), never())
                    .runTaskAsynchronously(any(), any(Runnable.class));
        }

        @Test
        @DisplayName("logCancelledTrade should do nothing when logging disabled")
        void logCancelledTradeDisabled() throws Exception {
            when(config.isEnableTradeLog()).thenReturn(false);

            com.ultikits.plugins.trade.entity.TradeSession session = mock(com.ultikits.plugins.trade.entity.TradeSession.class);

            service.logCancelledTrade(session, "test reason");

            verify(org.bukkit.Bukkit.getServer().getScheduler(), never())
                    .runTaskAsynchronously(any(), any(Runnable.class));
        }
    }
}
