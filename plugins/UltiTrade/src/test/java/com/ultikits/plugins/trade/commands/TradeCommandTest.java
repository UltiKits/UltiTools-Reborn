package com.ultikits.plugins.trade.commands;

import com.ultikits.plugins.trade.UltiTradeTestHelper;
import com.ultikits.plugins.trade.service.TradeLogService;
import com.ultikits.plugins.trade.service.TradeService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TradeCommand Tests")
class TradeCommandTest {

    private TradeCommand command;
    private TradeService tradeService;
    private TradeLogService logService;
    private Player player;
    private Player target;
    private UUID playerUuid;
    private UUID targetUuid;
    private Server server;

    @BeforeEach
    void setUp() throws Exception {
        UltiTradeTestHelper.setUp();

        tradeService = mock(TradeService.class);
        logService = mock(TradeLogService.class);
        command = new TradeCommand(tradeService, logService);

        playerUuid = UUID.randomUUID();
        targetUuid = UUID.randomUUID();
        player = mock(Player.class);
        target = mock(Player.class);

        when(player.getName()).thenReturn("Player1");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(target.getName()).thenReturn("Target");
        when(target.getUniqueId()).thenReturn(targetUuid);

        // Get the server mock from Bukkit (set up by UltiTradeTestHelper)
        server = Bukkit.getServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiTradeTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should store trade service")
        void storeTradeService() {
            assertThat(command).isNotNull();
        }

        @Test
        @DisplayName("Should store log service")
        void storeLogService() {
            assertThat(command).isNotNull();
        }
    }

    @Nested
    @DisplayName("sendRequest")
    class SendRequest {

        @Test
        @DisplayName("Should send trade request to online player")
        void sendRequestOnline() {
            when(server.getPlayerExact("Target")).thenReturn(target);

            command.sendRequest(player, "Target");

            verify(tradeService).sendRequest(player, target);
        }

        @Test
        @DisplayName("Should fail for offline player")
        void sendRequestOffline() {
            when(server.getPlayerExact("Offline")).thenReturn(null);

            command.sendRequest(player, "Offline");

            verify(player).sendMessage(contains("不在线"));
            verify(tradeService, never()).sendRequest(any(), any());
        }

        @Test
        @DisplayName("Should fail for self-trade")
        void sendRequestSelf() {
            // getPlayerExact returns `player` itself, so target.equals(sender) is true
            when(server.getPlayerExact("Player1")).thenReturn(player);

            command.sendRequest(player, "Player1");

            verify(player).sendMessage(contains("不能和自己交易"));
            verify(tradeService, never()).sendRequest(any(), any());
        }
    }

    @Nested
    @DisplayName("accept")
    class Accept {

        @Test
        @DisplayName("Should accept trade request")
        void accept() {
            command.accept(player);

            verify(tradeService).acceptRequest(player);
        }
    }

    @Nested
    @DisplayName("deny")
    class Deny {

        @Test
        @DisplayName("Should deny trade request")
        void deny() {
            command.deny(player);

            verify(tradeService).denyRequest(player);
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("Should cancel trade")
        void cancelTrading() {
            when(tradeService.isTrading(playerUuid)).thenReturn(true);

            command.cancel(player);

            verify(tradeService).cancelTrade(player);
        }

        @Test
        @DisplayName("Should fail if not trading")
        void cancelNotTrading() {
            when(tradeService.isTrading(playerUuid)).thenReturn(false);

            command.cancel(player);

            verify(player).sendMessage(contains("没有在交易"));
            verify(tradeService, never()).cancelTrade(player);
        }
    }

    @Nested
    @DisplayName("toggle")
    class Toggle {

        @Test
        @DisplayName("Should toggle trade on")
        void toggleOn() {
            when(logService.toggleTrade(player)).thenReturn(true);

            command.toggle(player);

            verify(logService).toggleTrade(player);
            verify(player).sendMessage(contains("已开启交易功能"));
        }

        @Test
        @DisplayName("Should toggle trade off")
        void toggleOff() {
            when(logService.toggleTrade(player)).thenReturn(false);

            command.toggle(player);

            verify(logService).toggleTrade(player);
            verify(player).sendMessage(contains("已关闭交易功能"));
        }
    }

    @Nested
    @DisplayName("blockPlayer")
    class BlockPlayer {

        @Test
        @DisplayName("Should block online player")
        void blockOnline() {
            when(logService.isBlocked(playerUuid, targetUuid)).thenReturn(false);
            when(server.getPlayerExact("Target")).thenReturn(target);

            command.blockPlayer(player, "Target");

            verify(logService).blockPlayer(player, targetUuid);
            verify(player).sendMessage(contains("添加到交易黑名单"));
        }

        @Test
        @DisplayName("Should fail for offline player")
        void blockOffline() {
            when(server.getPlayerExact("Offline")).thenReturn(null);

            command.blockPlayer(player, "Offline");

            verify(player).sendMessage(contains("不在线"));
            verify(logService, never()).blockPlayer(any(), any());
        }

        @Test
        @DisplayName("Should fail for self-block")
        void blockSelf() {
            // getPlayerExact returns `player` itself, so target.equals(sender) is true
            when(server.getPlayerExact("Player1")).thenReturn(player);

            command.blockPlayer(player, "Player1");

            verify(player).sendMessage(contains("不能将自己添加到黑名单"));
            verify(logService, never()).blockPlayer(any(), any());
        }

        @Test
        @DisplayName("Should fail for already blocked")
        void blockAlreadyBlocked() {
            when(logService.isBlocked(playerUuid, targetUuid)).thenReturn(true);
            when(server.getPlayerExact("Target")).thenReturn(target);

            command.blockPlayer(player, "Target");

            verify(player).sendMessage(contains("已经在你的交易黑名单中"));
            verify(logService, never()).blockPlayer(any(), any());
        }
    }

    @Nested
    @DisplayName("unblockPlayer")
    class UnblockPlayer {

        @Test
        @DisplayName("Should unblock online player")
        void unblockOnline() {
            when(logService.isBlocked(playerUuid, targetUuid)).thenReturn(true);
            when(server.getPlayerExact("Target")).thenReturn(target);

            command.unblockPlayer(player, "Target");

            verify(logService).unblockPlayer(player, targetUuid);
            verify(player).sendMessage(contains("从交易黑名单中移除"));
        }

        @Test
        @DisplayName("Should fail for offline player")
        void unblockOffline() {
            when(server.getPlayerExact("Offline")).thenReturn(null);

            command.unblockPlayer(player, "Offline");

            verify(player).sendMessage(contains("不在线"));
            verify(logService, never()).unblockPlayer(any(), any());
        }

        @Test
        @DisplayName("Should fail for not blocked")
        void unblockNotBlocked() {
            when(logService.isBlocked(playerUuid, targetUuid)).thenReturn(false);
            when(server.getPlayerExact("Target")).thenReturn(target);

            command.unblockPlayer(player, "Target");

            verify(player).sendMessage(contains("不在你的交易黑名单中"));
            verify(logService, never()).unblockPlayer(any(), any());
        }
    }

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("Should show help message")
        void showHelp() {
            when(logService.isTradeEnabled(playerUuid)).thenReturn(true);

            command.help(player);

            verify(player, atLeastOnce()).sendMessage(contains("UltiTrade"));
        }

        @Test
        @DisplayName("Should show current trade status")
        void showTradeStatus() {
            when(logService.isTradeEnabled(playerUuid)).thenReturn(false);

            command.help(player);

            verify(player).sendMessage(contains("交易状态"));
        }
    }
}
