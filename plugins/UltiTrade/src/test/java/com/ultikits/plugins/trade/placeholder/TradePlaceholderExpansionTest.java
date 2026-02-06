package com.ultikits.plugins.trade.placeholder;

import com.ultikits.plugins.trade.entity.PlayerTradeSettings;
import com.ultikits.plugins.trade.service.TradeLogService;
import com.ultikits.plugins.trade.service.TradeService;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TradePlaceholderExpansion Tests")
class TradePlaceholderExpansionTest {

    private TradePlaceholderExpansion expansion;
    private TradeService tradeService;
    private TradeLogService logService;
    private OfflinePlayer player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        tradeService = mock(TradeService.class);
        logService = mock(TradeLogService.class);
        expansion = new TradePlaceholderExpansion(tradeService, logService);

        playerUuid = UUID.randomUUID();
        player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
    }

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("getIdentifier should return ultitrade")
        void getIdentifier() {
            assertThat(expansion.getIdentifier()).isEqualTo("ultitrade");
        }

        @Test
        @DisplayName("getAuthor should return wisdomme")
        void getAuthor() {
            assertThat(expansion.getAuthor()).isEqualTo("wisdomme");
        }

        @Test
        @DisplayName("getVersion should return 1.0.0")
        void getVersion() {
            assertThat(expansion.getVersion()).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("persist should return true")
        void persist() {
            assertThat(expansion.persist()).isTrue();
        }
    }

    @Nested
    @DisplayName("onRequest with null player")
    class NullPlayer {

        @Test
        @DisplayName("Should return empty string for null player")
        void nullPlayer() {
            String result = expansion.onRequest(null, "total_trades");
            assertThat(result).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("Total Trades Placeholder")
    class TotalTrades {

        @Test
        @DisplayName("Should return total trades count")
        void totalTrades() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTotalTrades(42);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "total_trades");

            assertThat(result).isEqualTo("42");
        }

        @Test
        @DisplayName("Should return zero for new player")
        void totalTradesZero() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "total_trades");

            assertThat(result).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("Total Money Placeholder")
    class TotalMoney {

        @Test
        @DisplayName("Should return total money traded with total_money")
        void totalMoney() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTotalMoneyTraded(12345.67);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "total_money");

            assertThat(result).isEqualTo("12345.67");
        }

        @Test
        @DisplayName("Should return total money traded with total_money_traded alias")
        void totalMoneyAlias() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTotalMoneyTraded(500.0);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "total_money_traded");

            assertThat(result).isEqualTo("500.00");
        }
    }

    @Nested
    @DisplayName("Total Exp Placeholder")
    class TotalExp {

        @Test
        @DisplayName("Should return total exp traded with total_exp")
        void totalExp() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTotalExpTraded(9999);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "total_exp");

            assertThat(result).isEqualTo("9999");
        }

        @Test
        @DisplayName("Should return total exp traded with total_exp_traded alias")
        void totalExpAlias() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTotalExpTraded(1234);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "total_exp_traded");

            assertThat(result).isEqualTo("1234");
        }
    }

    @Nested
    @DisplayName("Trade Enabled Placeholder")
    class TradeEnabled {

        @Test
        @DisplayName("Should return true when trade enabled")
        void tradeEnabledTrue() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTradeEnabled(true);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "trade_enabled");

            assertThat(result).isEqualTo("true");
        }

        @Test
        @DisplayName("Should return false when trade disabled")
        void tradeEnabledFalse() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTradeEnabled(false);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "trade_enabled");

            assertThat(result).isEqualTo("false");
        }

        @Test
        @DisplayName("Should work with enabled alias")
        void enabledAlias() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTradeEnabled(true);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "enabled");

            assertThat(result).isEqualTo("true");
        }

        @Test
        @DisplayName("Should return localized display for trade_enabled_display")
        void tradeEnabledDisplay() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTradeEnabled(true);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "trade_enabled_display");

            assertThat(result).isEqualTo("\u5F00\u542F"); // "开启"
        }

        @Test
        @DisplayName("Should return localized display for disabled")
        void tradeDisabledDisplay() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTradeEnabled(false);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "enabled_display");

            assertThat(result).isEqualTo("\u5173\u95ED"); // "关闭"
        }
    }

    @Nested
    @DisplayName("Is Trading Placeholder")
    class IsTrading {

        @Test
        @DisplayName("Should return true when trading")
        void isTradingTrue() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);
            when(tradeService.isTrading(playerUuid)).thenReturn(true);

            String result = expansion.onRequest(player, "is_trading");

            assertThat(result).isEqualTo("true");
        }

        @Test
        @DisplayName("Should return false when not trading")
        void isTradingFalse() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);
            when(tradeService.isTrading(playerUuid)).thenReturn(false);

            String result = expansion.onRequest(player, "is_trading");

            assertThat(result).isEqualTo("false");
        }

        @Test
        @DisplayName("Should work with in_trade alias")
        void inTradeAlias() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);
            when(tradeService.isTrading(playerUuid)).thenReturn(true);

            String result = expansion.onRequest(player, "in_trade");

            assertThat(result).isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("Last Trade Time Placeholder")
    class LastTradeTime {

        @Test
        @DisplayName("Should return formatted time for last trade")
        void lastTradeTimeFormatted() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setLastTradeTime(1706745600000L); // some timestamp
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "last_trade_time");

            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
            assertThat(result).doesNotContain("\u4ECE\u672A\u4EA4\u6613"); // Not "从未交易"
        }

        @Test
        @DisplayName("Should return never traded message when no trades")
        void lastTradeTimeNever() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setLastTradeTime(0);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "last_trade_time");

            assertThat(result).isEqualTo("\u4ECE\u672A\u4EA4\u6613"); // "从未交易"
        }
    }

    @Nested
    @DisplayName("Last Trade Ago Placeholder")
    class LastTradeAgo {

        @Test
        @DisplayName("Should return never for no trades")
        void lastTradeAgoNever() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setLastTradeTime(0);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "last_trade_ago");

            assertThat(result).isEqualTo("\u4ECE\u672A"); // "从未"
        }

        @Test
        @DisplayName("Should return just now for very recent trade")
        void lastTradeAgoJustNow() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setLastTradeTime(System.currentTimeMillis() - 5000); // 5 seconds ago
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "last_trade_ago");

            assertThat(result).isEqualTo("\u521A\u521A"); // "刚刚"
        }

        @Test
        @DisplayName("Should return minutes for recent trade")
        void lastTradeAgoMinutes() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setLastTradeTime(System.currentTimeMillis() - 300000); // 5 minutes ago
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "last_trade_ago");

            assertThat(result).contains("\u5206\u949F\u524D"); // "分钟前"
        }

        @Test
        @DisplayName("Should return hours for older trade")
        void lastTradeAgoHours() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setLastTradeTime(System.currentTimeMillis() - 7200000); // 2 hours ago
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "last_trade_ago");

            assertThat(result).contains("\u5C0F\u65F6\u524D"); // "小时前"
        }

        @Test
        @DisplayName("Should return days for old trade")
        void lastTradeAgoDays() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setLastTradeTime(System.currentTimeMillis() - 172800000L); // 2 days ago
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "last_trade_ago");

            assertThat(result).contains("\u5929\u524D"); // "天前"
        }
    }

    @Nested
    @DisplayName("Blocked Count Placeholder")
    class BlockedCount {

        @Test
        @DisplayName("Should return blocked player count")
        void blockedCount() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.blockPlayer(UUID.randomUUID().toString());
            stats.blockPlayer(UUID.randomUUID().toString());
            stats.blockPlayer(UUID.randomUUID().toString());
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "blocked_count");

            assertThat(result).isEqualTo("3");
        }

        @Test
        @DisplayName("Should return zero for no blocked players")
        void blockedCountZero() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "blocked_count");

            assertThat(result).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("Unknown Placeholder")
    class UnknownPlaceholder {

        @Test
        @DisplayName("Should return null for unknown parameter")
        void unknownParam() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "unknown_param");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should return null for empty parameter")
        void emptyParam() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Case Insensitivity")
    class CaseInsensitivity {

        @Test
        @DisplayName("Should handle uppercase params")
        void uppercaseParams() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTotalTrades(5);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "TOTAL_TRADES");

            assertThat(result).isEqualTo("5");
        }

        @Test
        @DisplayName("Should handle mixed case params")
        void mixedCaseParams() {
            PlayerTradeSettings stats = new PlayerTradeSettings(playerUuid, "TestPlayer");
            stats.setTradeEnabled(true);
            when(logService.getPlayerStats(playerUuid)).thenReturn(stats);

            String result = expansion.onRequest(player, "Trade_Enabled");

            assertThat(result).isEqualTo("true");
        }
    }
}
