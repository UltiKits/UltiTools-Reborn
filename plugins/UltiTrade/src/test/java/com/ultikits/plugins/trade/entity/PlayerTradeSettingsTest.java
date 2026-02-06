package com.ultikits.plugins.trade.entity;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PlayerTradeSettings Tests")
class PlayerTradeSettingsTest {

    private UUID playerUuid;
    private PlayerTradeSettings settings;

    @BeforeEach
    void setUp() {
        playerUuid = UUID.randomUUID();
        settings = new PlayerTradeSettings(playerUuid, "TestPlayer");
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should set player UUID")
        void setPlayerUuid() {
            assertThat(settings.getPlayerUuid()).isEqualTo(playerUuid.toString());
        }

        @Test
        @DisplayName("Should set player name")
        void setPlayerName() {
            assertThat(settings.getPlayerName()).isEqualTo("TestPlayer");
        }

        @Test
        @DisplayName("Should initialize trade enabled as true")
        void initialTradeEnabled() {
            assertThat(settings.isTradeEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should initialize statistics as zero")
        void initialStatistics() {
            assertThat(settings.getTotalTrades()).isZero();
            assertThat(settings.getTotalMoneyTraded()).isZero();
            assertThat(settings.getTotalExpTraded()).isZero();
            assertThat(settings.getLastTradeTime()).isZero();
        }

        @Test
        @DisplayName("Should initialize blocked players as empty")
        void initialBlockedPlayers() {
            assertThat(settings.getBlockedPlayers()).isEmpty();
        }

        @Test
        @DisplayName("NoArgsConstructor should work")
        void noArgsConstructor() {
            PlayerTradeSettings empty = new PlayerTradeSettings();
            assertThat(empty).isNotNull();
        }
    }

    @Nested
    @DisplayName("Blocked Players Management")
    class BlockedPlayersManagement {

        @Test
        @DisplayName("blockPlayer should add player to list")
        void blockPlayer() {
            String targetUuid = UUID.randomUUID().toString();

            boolean result = settings.blockPlayer(targetUuid);

            assertThat(result).isTrue();
            assertThat(settings.getBlockedPlayers()).contains(targetUuid);
        }

        @Test
        @DisplayName("blockPlayer should return false for duplicate")
        void blockPlayerDuplicate() {
            String targetUuid = UUID.randomUUID().toString();

            settings.blockPlayer(targetUuid);
            boolean result = settings.blockPlayer(targetUuid);

            assertThat(result).isFalse();
            assertThat(settings.getBlockedPlayers()).hasSize(1);
        }

        @Test
        @DisplayName("unblockPlayer should remove player from list")
        void unblockPlayer() {
            String targetUuid = UUID.randomUUID().toString();
            settings.blockPlayer(targetUuid);

            boolean result = settings.unblockPlayer(targetUuid);

            assertThat(result).isTrue();
            assertThat(settings.getBlockedPlayers()).doesNotContain(targetUuid);
        }

        @Test
        @DisplayName("unblockPlayer should return false for non-blocked")
        void unblockPlayerNotBlocked() {
            String targetUuid = UUID.randomUUID().toString();

            boolean result = settings.unblockPlayer(targetUuid);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("isBlocked should return true for blocked player")
        void isBlockedTrue() {
            String targetUuid = UUID.randomUUID().toString();
            settings.blockPlayer(targetUuid);

            assertThat(settings.isBlocked(targetUuid)).isTrue();
        }

        @Test
        @DisplayName("isBlocked should return false for non-blocked player")
        void isBlockedFalse() {
            String targetUuid = UUID.randomUUID().toString();

            assertThat(settings.isBlocked(targetUuid)).isFalse();
        }

        @Test
        @DisplayName("Should handle multiple blocked players")
        void multipleBlockedPlayers() {
            String uuid1 = UUID.randomUUID().toString();
            String uuid2 = UUID.randomUUID().toString();
            String uuid3 = UUID.randomUUID().toString();

            settings.blockPlayer(uuid1);
            settings.blockPlayer(uuid2);
            settings.blockPlayer(uuid3);

            assertThat(settings.getBlockedPlayers()).hasSize(3);
            assertThat(settings.getBlockedPlayers()).containsExactly(uuid1, uuid2, uuid3);
        }

        @Test
        @DisplayName("setBlockedPlayers should replace list")
        void setBlockedPlayers() {
            List<String> newList = Arrays.asList(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString()
            );

            settings.setBlockedPlayers(newList);

            assertThat(settings.getBlockedPlayers()).isEqualTo(newList);
        }

        @Test
        @DisplayName("getBlockedPlayers should handle null JSON")
        void getBlockedPlayersNullJson() {
            settings.setBlockedPlayersJson(null);

            assertThat(settings.getBlockedPlayers()).isEmpty();
        }

        @Test
        @DisplayName("getBlockedPlayers should handle empty JSON")
        void getBlockedPlayersEmptyJson() {
            settings.setBlockedPlayersJson("");

            assertThat(settings.getBlockedPlayers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Trade Statistics")
    class TradeStatistics {

        @Test
        @DisplayName("incrementTradeStats should increment all values")
        void incrementTradeStats() {
            long beforeTime = System.currentTimeMillis();

            settings.incrementTradeStats(100.5, 50);

            assertThat(settings.getTotalTrades()).isEqualTo(1);
            assertThat(settings.getTotalMoneyTraded()).isEqualTo(100.5);
            assertThat(settings.getTotalExpTraded()).isEqualTo(50);
            assertThat(settings.getLastTradeTime()).isGreaterThanOrEqualTo(beforeTime);
        }

        @Test
        @DisplayName("incrementTradeStats should accumulate values")
        void accumulateStats() {
            settings.incrementTradeStats(100.0, 50);
            settings.incrementTradeStats(200.0, 75);

            assertThat(settings.getTotalTrades()).isEqualTo(2);
            assertThat(settings.getTotalMoneyTraded()).isEqualTo(300.0);
            assertThat(settings.getTotalExpTraded()).isEqualTo(125);
        }

        @Test
        @DisplayName("incrementTradeStats should handle zero values")
        void zeroValues() {
            settings.incrementTradeStats(0.0, 0);

            assertThat(settings.getTotalTrades()).isEqualTo(1);
            assertThat(settings.getTotalMoneyTraded()).isZero();
            assertThat(settings.getTotalExpTraded()).isZero();
        }

        @Test
        @DisplayName("incrementTradeStats should update last trade time")
        void updateLastTradeTime() {
            long time1 = System.currentTimeMillis();
            settings.incrementTradeStats(100.0, 50);
            long time2 = settings.getLastTradeTime();

            assertThat(time2).isGreaterThanOrEqualTo(time1);
        }
    }

    @Nested
    @DisplayName("Trade Toggle")
    class TradeToggle {

        @Test
        @DisplayName("Should set trade enabled")
        void setTradeEnabled() {
            settings.setTradeEnabled(false);

            assertThat(settings.isTradeEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should toggle trade enabled")
        void toggleTradeEnabled() {
            assertThat(settings.isTradeEnabled()).isTrue();

            settings.setTradeEnabled(false);
            assertThat(settings.isTradeEnabled()).isFalse();

            settings.setTradeEnabled(true);
            assertThat(settings.isTradeEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Setters and Getters")
    class SettersAndGetters {

        @Test
        @DisplayName("Should set and get total trades")
        void totalTrades() {
            settings.setTotalTrades(42);
            assertThat(settings.getTotalTrades()).isEqualTo(42);
        }

        @Test
        @DisplayName("Should set and get total money traded")
        void totalMoneyTraded() {
            settings.setTotalMoneyTraded(12345.67);
            assertThat(settings.getTotalMoneyTraded()).isEqualTo(12345.67);
        }

        @Test
        @DisplayName("Should set and get total exp traded")
        void totalExpTraded() {
            settings.setTotalExpTraded(9999);
            assertThat(settings.getTotalExpTraded()).isEqualTo(9999);
        }

        @Test
        @DisplayName("Should set and get last trade time")
        void lastTradeTime() {
            long time = System.currentTimeMillis();
            settings.setLastTradeTime(time);
            assertThat(settings.getLastTradeTime()).isEqualTo(time);
        }

        @Test
        @DisplayName("Should set and get player name")
        void playerName() {
            settings.setPlayerName("NewName");
            assertThat(settings.getPlayerName()).isEqualTo("NewName");
        }
    }
}
