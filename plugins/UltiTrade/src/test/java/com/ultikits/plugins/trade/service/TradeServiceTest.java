package com.ultikits.plugins.trade.service;

import com.ultikits.plugins.trade.UltiTradeTestHelper;
import com.ultikits.plugins.trade.config.TradeConfig;
import com.ultikits.plugins.trade.entity.TradeRequest;
import com.ultikits.plugins.trade.entity.TradeSession;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TradeService Tests")
class TradeServiceTest {

    private TradeService service;
    private TradeConfig config;
    private TradeLogService logService;
    private Economy economy;

    private Player player1;
    private Player player2;
    private UUID uuid1;
    private UUID uuid2;

    @BeforeEach
    void setUp() throws Exception {
        UltiTradeTestHelper.setUp();

        config = UltiTradeTestHelper.createDefaultConfig();
        logService = mock(TradeLogService.class);
        economy = UltiTradeTestHelper.createMockEconomy();

        service = new TradeService();

        // Inject dependencies via reflection
        UltiTradeTestHelper.setField(service, "config", config);
        UltiTradeTestHelper.setField(service, "logService", logService);
        UltiTradeTestHelper.setField(service, "economy", economy);

        uuid1 = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
        player1 = UltiTradeTestHelper.createMockPlayer("Player1", uuid1);
        player2 = UltiTradeTestHelper.createMockPlayer("Player2", uuid2);

        when(logService.isTradeEnabled(any())).thenReturn(true);
        when(logService.isBlocked(any(), any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiTradeTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Economy Integration")
    class EconomyIntegration {

        @Test
        @DisplayName("hasEconomy should return true when economy is set")
        void hasEconomyTrue() {
            assertThat(service.hasEconomy()).isTrue();
        }

        @Test
        @DisplayName("hasEconomy should return false when economy is null")
        void hasEconomyFalse() throws Exception {
            UltiTradeTestHelper.setField(service, "economy", null);

            assertThat(service.hasEconomy()).isFalse();
        }

        @Test
        @DisplayName("hasEconomy should return false when money trade disabled")
        void hasEconomyDisabled() {
            when(config.isEnableMoneyTrade()).thenReturn(false);

            assertThat(service.hasEconomy()).isFalse();
        }

        @Test
        @DisplayName("getEconomy should return economy instance")
        void getEconomy() {
            assertThat(service.getEconomy()).isSameAs(economy);
        }

        @Test
        @DisplayName("getEconomy should return null when not set")
        void getEconomyNull() throws Exception {
            UltiTradeTestHelper.setField(service, "economy", null);
            assertThat(service.getEconomy()).isNull();
        }
    }

    @Nested
    @DisplayName("Session Management")
    class SessionManagement {

        @Test
        @DisplayName("getSession should return null for non-trading player")
        void getSessionNull() {
            assertThat(service.getSession(uuid1)).isNull();
        }

        @Test
        @DisplayName("isTrading should return false for non-trading player")
        void isTradingFalse() {
            assertThat(service.isTrading(uuid1)).isFalse();
        }

        @Test
        @DisplayName("isTrading should return true for trading player")
        void isTradingTrue() throws Exception {
            TradeSession session = new TradeSession(player1, player2);

            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            playerSessionMap.put(uuid1, session.getSessionId());

            assertThat(service.isTrading(uuid1)).isTrue();
        }

        @Test
        @DisplayName("getSession should return session for trading player")
        void getSessionForTradingPlayer() throws Exception {
            TradeSession session = new TradeSession(player1, player2);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());

            TradeSession result = service.getSession(uuid1);
            assertThat(result).isSameAs(session);
        }

        @Test
        @DisplayName("getSession should return null when session ID mapped but session removed")
        void getSessionOrphanedMapping() throws Exception {
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            playerSessionMap.put(uuid1, UUID.randomUUID());

            assertThat(service.getSession(uuid1)).isNull();
        }
    }

    @Nested
    @DisplayName("Experience Calculation")
    class ExperienceCalculation {

        @Test
        @DisplayName("getTotalExperience should calculate for low levels (<=16)")
        void lowLevelExp() {
            when(player1.getLevel()).thenReturn(10);
            when(player1.getExp()).thenReturn(0.5f);
            when(player1.getExpToLevel()).thenReturn(20);

            int total = service.getTotalExperience(player1);

            // Level 10: 10*10 + 6*10 = 160, plus partial exp 0.5*20 = 10
            assertThat(total).isEqualTo(170);
        }

        @Test
        @DisplayName("getTotalExperience should calculate for level 0")
        void zeroLevelExp() {
            when(player1.getLevel()).thenReturn(0);
            when(player1.getExp()).thenReturn(0.0f);
            when(player1.getExpToLevel()).thenReturn(7);

            int total = service.getTotalExperience(player1);
            assertThat(total).isZero();
        }

        @Test
        @DisplayName("getTotalExperience should calculate for level 16 boundary")
        void level16Exp() {
            when(player1.getLevel()).thenReturn(16);
            when(player1.getExp()).thenReturn(0.0f);
            when(player1.getExpToLevel()).thenReturn(37);

            int total = service.getTotalExperience(player1);
            // Level 16: 16*16 + 6*16 = 256 + 96 = 352
            assertThat(total).isEqualTo(352);
        }

        @Test
        @DisplayName("getTotalExperience should calculate for mid levels (17-31)")
        void midLevelExp() {
            when(player1.getLevel()).thenReturn(20);
            when(player1.getExp()).thenReturn(0.0f);
            when(player1.getExpToLevel()).thenReturn(0);

            int total = service.getTotalExperience(player1);

            // Level 20: 2.5*400 - 40.5*20 + 360 = 1000 - 810 + 360 = 550
            assertThat(total).isEqualTo(550);
        }

        @Test
        @DisplayName("getTotalExperience should calculate for level 31 boundary")
        void level31Exp() {
            when(player1.getLevel()).thenReturn(31);
            when(player1.getExp()).thenReturn(0.0f);
            when(player1.getExpToLevel()).thenReturn(0);

            int total = service.getTotalExperience(player1);
            // Level 31: 2.5*961 - 40.5*31 + 360 = 2402.5 - 1255.5 + 360 = 1507
            assertThat(total).isEqualTo(1507);
        }

        @Test
        @DisplayName("getTotalExperience should calculate for high levels (>=32)")
        void highLevelExp() {
            when(player1.getLevel()).thenReturn(35);
            when(player1.getExp()).thenReturn(0.0f);
            when(player1.getExpToLevel()).thenReturn(0);

            int total = service.getTotalExperience(player1);

            // Level 35: 4.5*1225 - 162.5*35 + 2220 = 5512.5 - 5687.5 + 2220 = 2045
            assertThat(total).isPositive();
        }

        @Test
        @DisplayName("setTotalExperience should reset and set exp")
        void setTotalExperience() {
            service.setTotalExperience(player1, 1000);

            verify(player1).setExp(0);
            verify(player1).setLevel(0);
            verify(player1).setTotalExperience(0);
            verify(player1).giveExp(1000);
        }

        @Test
        @DisplayName("setTotalExperience should handle zero")
        void setTotalExperienceZero() {
            service.setTotalExperience(player1, 0);

            verify(player1).setExp(0);
            verify(player1).setLevel(0);
            verify(player1).setTotalExperience(0);
            verify(player1, never()).giveExp(anyInt());
        }

        @Test
        @DisplayName("setTotalExperience should handle negative (treat as zero)")
        void setTotalExperienceNegative() {
            service.setTotalExperience(player1, -5);

            verify(player1).setExp(0);
            verify(player1).setLevel(0);
            verify(player1).setTotalExperience(0);
            verify(player1, never()).giveExp(anyInt());
        }
    }

    @Nested
    @DisplayName("Config and Service Getters")
    class Getters {

        @Test
        @DisplayName("getConfig should return config")
        void getConfig() {
            assertThat(service.getConfig()).isSameAs(config);
        }

        @Test
        @DisplayName("getLogService should return log service")
        void getLogService() {
            assertThat(service.getLogService()).isSameAs(logService);
        }
    }

    @Nested
    @DisplayName("Send Request Validation")
    class SendRequestValidation {

        @Test
        @DisplayName("sendRequest should fail if sender trade disabled")
        void senderTradeDisabled() {
            when(logService.isTradeEnabled(uuid1)).thenReturn(false);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("已关闭交易功能"));
        }

        @Test
        @DisplayName("sendRequest should fail if target trade disabled")
        void targetTradeDisabled() {
            when(logService.isTradeEnabled(uuid2)).thenReturn(false);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("sendRequest should fail if sender blocked by target")
        void senderBlocked() {
            when(logService.isBlocked(uuid2, uuid1)).thenReturn(true);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("sendRequest should fail if target blocked by sender")
        void targetBlocked() {
            when(logService.isBlocked(uuid1, uuid2)).thenReturn(true);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("黑名单"));
        }

        @Test
        @DisplayName("sendRequest should fail if sender is already trading")
        void senderAlreadyTrading() throws Exception {
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            playerSessionMap.put(uuid1, UUID.randomUUID());

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("已经在交易中"));
        }

        @Test
        @DisplayName("sendRequest should fail if target is already trading")
        void targetAlreadyTrading() throws Exception {
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            playerSessionMap.put(uuid2, UUID.randomUUID());

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("正在交易中"));
        }

        @Test
        @DisplayName("sendRequest should fail if cross-world and not allowed")
        void crossWorldNotAllowed() {
            when(config.getMaxDistance()).thenReturn(50);
            when(config.isAllowCrossWorld()).thenReturn(false);

            World world1 = mock(World.class);
            World world2 = mock(World.class);
            when(player1.getWorld()).thenReturn(world1);
            when(player2.getWorld()).thenReturn(world2);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("跨世界交易"));
        }

        @Test
        @DisplayName("sendRequest should fail if distance too far")
        void distanceTooFar() {
            when(config.getMaxDistance()).thenReturn(10);

            World world = mock(World.class);
            Location loc1 = new Location(world, 0, 64, 0);
            Location loc2 = new Location(world, 100, 64, 0);
            when(player1.getWorld()).thenReturn(world);
            when(player2.getWorld()).thenReturn(world);
            when(player1.getLocation()).thenReturn(loc1);
            when(player2.getLocation()).thenReturn(loc2);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("距离太远"));
        }

        @Test
        @DisplayName("sendRequest should skip distance check when maxDistance is 0")
        void noDistanceLimit() {
            when(config.getMaxDistance()).thenReturn(0);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("sendRequest should fail if duplicate request exists")
        void duplicateRequest() throws Exception {
            when(config.getMaxDistance()).thenReturn(0); // Skip distance check

            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            TradeRequest existing = new TradeRequest(uuid1, uuid2);
            pendingRequests.put(uuid2, existing);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("已经向该玩家发送过交易请求"));
        }

        @Test
        @DisplayName("sendRequest should succeed and store request")
        void sendRequestSuccess() throws Exception {
            when(config.getMaxDistance()).thenReturn(0);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isTrue();
            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            assertThat(pendingRequests).containsKey(uuid2);
        }

        @Test
        @DisplayName("sendRequest should notify target with clickable buttons")
        void sendRequestNotifiesTarget() throws Exception {
            when(config.getMaxDistance()).thenReturn(0);
            when(config.isEnableClickableButtons()).thenReturn(true);

            service.sendRequest(player1, player2);

            verify(player2.spigot()).sendMessage(any(net.md_5.bungee.api.chat.TextComponent.class));
        }

        @Test
        @DisplayName("sendRequest should notify target with plain text when buttons disabled")
        void sendRequestPlainText() throws Exception {
            when(config.getMaxDistance()).thenReturn(0);
            when(config.isEnableClickableButtons()).thenReturn(false);
            when(config.isEnableBossbar()).thenReturn(false);

            service.sendRequest(player1, player2);

            verify(player2).sendMessage(anyString());
        }

        @Test
        @DisplayName("sendRequest should auto-accept reverse request")
        void autoAcceptReverseRequest() throws Exception {
            when(config.getMaxDistance()).thenReturn(0);

            // Mock Bukkit.getPlayer for startTrade's TradeGUI creation
            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            // Player2 already sent a request to player1
            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            TradeRequest reverseRequest = new TradeRequest(uuid2, uuid1);
            pendingRequests.put(uuid1, reverseRequest);

            boolean result = service.sendRequest(player1, player2);

            assertThat(result).isTrue();
            // The reverse request should be removed
            assertThat(pendingRequests).doesNotContainKey(uuid1);
        }
    }

    @Nested
    @DisplayName("Accept Request")
    class AcceptRequest {

        @Test
        @DisplayName("acceptRequest should fail when no pending request")
        void noRequest() {
            boolean result = service.acceptRequest(player1);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("没有待处理的交易请求"));
        }

        @Test
        @DisplayName("acceptRequest should fail when request expired")
        void expiredRequest() throws Exception {
            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            TradeRequest request = new TradeRequest(uuid2, uuid1);
            // Make it expired using reflection
            java.lang.reflect.Field timestampField = TradeRequest.class.getDeclaredField("timestamp");
            timestampField.setAccessible(true);
            timestampField.set(request, System.currentTimeMillis() - 60000L); // 60 seconds ago
            pendingRequests.put(uuid1, request);

            boolean result = service.acceptRequest(player1);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("没有待处理的交易请求"));
        }

        @Test
        @DisplayName("acceptRequest should fail when sender offline")
        void senderOffline() throws Exception {
            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            TradeRequest request = new TradeRequest(uuid2, uuid1);
            pendingRequests.put(uuid1, request);
            // Bukkit.getPlayer(uuid2) returns null by default

            boolean result = service.acceptRequest(player1);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("对方已离线"));
        }
    }

    @Nested
    @DisplayName("Deny Request")
    class DenyRequest {

        @Test
        @DisplayName("denyRequest should fail when no pending request")
        void noRequest() {
            boolean result = service.denyRequest(player1);

            assertThat(result).isFalse();
            verify(player1).sendMessage(contains("没有待处理的交易请求"));
        }

        @Test
        @DisplayName("denyRequest should succeed and notify sender")
        void denySuccess() throws Exception {
            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            TradeRequest request = new TradeRequest(uuid2, uuid1);
            pendingRequests.put(uuid1, request);

            // Mock Bukkit.getPlayer to return player2 for uuid2
            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid2)).thenReturn(player2);
            when(player2.isOnline()).thenReturn(true);

            boolean result = service.denyRequest(player1);

            assertThat(result).isTrue();
            verify(player1).sendMessage(contains("已拒绝交易请求"));
            verify(player2).sendMessage(contains("拒绝了你的交易请求"));
        }

        @Test
        @DisplayName("denyRequest should succeed even if sender offline")
        void denySenderOffline() throws Exception {
            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            TradeRequest request = new TradeRequest(uuid2, uuid1);
            pendingRequests.put(uuid1, request);
            // Bukkit.getPlayer(uuid2) returns null by default

            boolean result = service.denyRequest(player1);

            assertThat(result).isTrue();
            verify(player1).sendMessage(contains("已拒绝交易请求"));
        }
    }

    @Nested
    @DisplayName("Confirmation Logic")
    class ConfirmationLogic {

        @Test
        @DisplayName("cancelConfirmation should reset confirmation")
        void cancelConfirmation() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            session.setConfirmed(uuid1, true);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());

            service.cancelConfirmation(player1);

            assertThat(session.isConfirmed(uuid1)).isFalse();
        }

        @Test
        @DisplayName("cancelConfirmation should handle no session")
        void cancelConfirmationNoSession() {
            // Should not throw exception
            service.cancelConfirmation(player1);
        }

        @Test
        @DisplayName("confirmTrade should handle no session")
        void confirmTradeNoSession() {
            // Should not throw exception
            service.confirmTrade(player1);
        }

        @Test
        @DisplayName("confirmTrade should confirm when below threshold")
        void confirmBelowThreshold() throws Exception {
            when(config.getConfirmThreshold()).thenReturn(10000.0);

            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            service.confirmTrade(player1);

            assertThat(session.isConfirmed(uuid1)).isTrue();
        }

        @Test
        @DisplayName("confirmTrade should complete trade when both confirmed")
        void confirmBothCompletes() throws Exception {
            when(config.getConfirmThreshold()).thenReturn(10000.0);

            TradeSession session = new TradeSession(player1, player2);
            session.setConfirmed(uuid2, true);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            // Mock Bukkit.getPlayer for completeTrade
            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.confirmTrade(player1);

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.COMPLETED);
        }
    }

    @Nested
    @DisplayName("Complete Trade")
    class CompleteTrade {

        @Test
        @DisplayName("completeTrade should cancel if player1 offline")
        void player1Offline() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            // Both players offline (Bukkit.getPlayer returns null by default)
            service.completeTrade(session);

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.CANCELLED);
        }

        @Test
        @DisplayName("completeTrade should transfer items")
        void transferItems() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            ItemStack diamond = new ItemStack(Material.DIAMOND, 10);
            ItemStack gold = new ItemStack(Material.GOLD_INGOT, 5);
            session.setItem(uuid1, 0, diamond);
            session.setItem(uuid2, 0, gold);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            PlayerInventory inv1 = player1.getInventory();
            PlayerInventory inv2 = player2.getInventory();
            when(inv1.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
            when(inv2.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

            service.completeTrade(session);

            verify(inv2).addItem(diamond);
            verify(inv1).addItem(gold);
            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.COMPLETED);
        }

        @Test
        @DisplayName("completeTrade should handle money transfer with tax")
        void moneyTransferWithTax() throws Exception {
            when(config.getTradeTax()).thenReturn(0.1);
            when(config.isEnableExpTrade()).thenReturn(false);

            TradeSession session = new TradeSession(player1, player2);
            session.setMoney(uuid1, 100.0);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.completeTrade(session);

            // player1 offered 100, tax = 10, player2 gets 90
            verify(economy).withdrawPlayer(player1, 100.0);
            verify(economy).depositPlayer(player2, 90.0);
        }

        @Test
        @DisplayName("completeTrade should cancel if player has insufficient balance")
        void insufficientBalance() throws Exception {
            when(economy.getBalance(player1)).thenReturn(50.0);

            TradeSession session = new TradeSession(player1, player2);
            session.setMoney(uuid1, 100.0);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.completeTrade(session);

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.CANCELLED);
        }

        @Test
        @DisplayName("completeTrade should cancel if player2 has insufficient balance")
        void player2InsufficientBalance() throws Exception {
            when(economy.getBalance(player2)).thenReturn(5.0);

            TradeSession session = new TradeSession(player1, player2);
            session.setMoney(uuid2, 100.0);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.completeTrade(session);

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.CANCELLED);
        }

        @Test
        @DisplayName("completeTrade should transfer experience with tax")
        void expTransferWithTax() throws Exception {
            when(config.isEnableExpTrade()).thenReturn(true);
            when(config.getExpTaxRate()).thenReturn(0.1);
            when(config.isEnableMoneyTrade()).thenReturn(false);
            UltiTradeTestHelper.setField(service, "economy", null);

            TradeSession session = new TradeSession(player1, player2);
            session.setExp(uuid1, 100);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            // Player1 has enough exp
            when(player1.getLevel()).thenReturn(30);
            when(player1.getExp()).thenReturn(0.5f);
            when(player1.getExpToLevel()).thenReturn(50);

            service.completeTrade(session);

            // player2 should receive exp minus tax
            verify(player2).giveExp(90); // 100 - 10% tax = 90
            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.COMPLETED);
        }

        @Test
        @DisplayName("completeTrade should cancel if player1 has insufficient exp")
        void insufficientExp() throws Exception {
            when(config.isEnableExpTrade()).thenReturn(true);
            when(config.isEnableMoneyTrade()).thenReturn(false);
            UltiTradeTestHelper.setField(service, "economy", null);

            TradeSession session = new TradeSession(player1, player2);
            session.setExp(uuid1, 10000);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            // Player1 has very little exp (level 1)
            when(player1.getLevel()).thenReturn(1);
            when(player1.getExp()).thenReturn(0.0f);
            when(player1.getExpToLevel()).thenReturn(0);

            service.completeTrade(session);

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.CANCELLED);
        }

        @Test
        @DisplayName("completeTrade should not transfer money when economy disabled")
        void noMoneyTransferWhenDisabled() throws Exception {
            when(config.isEnableMoneyTrade()).thenReturn(false);
            UltiTradeTestHelper.setField(service, "economy", null);
            when(config.isEnableExpTrade()).thenReturn(false);

            TradeSession session = new TradeSession(player1, player2);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.completeTrade(session);

            verify(economy, never()).withdrawPlayer(any(Player.class), anyDouble());
            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.COMPLETED);
        }

        @Test
        @DisplayName("completeTrade should log completed trade")
        void logCompletedTrade() throws Exception {
            when(config.isEnableExpTrade()).thenReturn(false);

            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.completeTrade(session);

            verify(logService).logCompletedTrade(eq(session), eq(player1), eq(player2), anyDouble(), anyInt());
        }

        @Test
        @DisplayName("completeTrade should cleanup session after completion")
        void cleanupAfterCompletion() throws Exception {
            when(config.isEnableExpTrade()).thenReturn(false);

            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.completeTrade(session);

            assertThat(activeSessions).doesNotContainKey(session.getSessionId());
            assertThat(playerSessionMap).doesNotContainKey(uuid1);
            assertThat(playerSessionMap).doesNotContainKey(uuid2);
        }
    }

    @Nested
    @DisplayName("Cancel Trade")
    class CancelTrade {

        @Test
        @DisplayName("cancelTrade by player should cancel session")
        void cancelTradeByPlayer() throws Exception {
            TradeSession session = new TradeSession(player1, player2);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.cancelTrade(player1);

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.CANCELLED);
        }

        @Test
        @DisplayName("cancelTrade by player should do nothing if not trading")
        void cancelNotTrading() {
            // Should not throw exception
            service.cancelTrade(player1);
        }

        @Test
        @DisplayName("cancelTrade should return items to owners")
        void returnItems() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            ItemStack diamond = new ItemStack(Material.DIAMOND, 10);
            session.setItem(uuid1, 0, diamond);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);
            when(player1.getInventory().addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

            service.cancelTrade(session, "test reason");

            // player1 should get their diamond back
            verify(player1.getInventory()).addItem(diamond);
        }

        @Test
        @DisplayName("cancelTrade should handle null players gracefully")
        void cancelWithNullPlayers() throws Exception {
            TradeSession session = new TradeSession(player1, player2);

            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            // Both players offline (null from Bukkit.getPlayer)
            service.cancelTrade(session, "test");

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.CANCELLED);
        }

        @Test
        @DisplayName("cancelTrade should include reason in message")
        void cancelWithReason() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.cancelTrade(session, "player disconnected");

            verify(player1).sendMessage(contains("player disconnected"));
        }

        @Test
        @DisplayName("cancelTrade should handle null reason")
        void cancelWithNullReason() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            when(server.getPlayer(uuid1)).thenReturn(player1);
            when(server.getPlayer(uuid2)).thenReturn(player2);

            service.cancelTrade(session, null);

            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.CANCELLED);
        }

        @Test
        @DisplayName("cancelTrade should log cancelled trade")
        void logCancelledTrade() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            service.cancelTrade(session, "test reason");

            verify(logService).logCancelledTrade(session, "test reason");
        }
    }

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("shutdown should cancel cleanup task")
        void cancelCleanupTask() throws Exception {
            org.bukkit.scheduler.BukkitTask mockTask = mock(org.bukkit.scheduler.BukkitTask.class);
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
        @DisplayName("shutdown should cancel all active sessions")
        void cancelActiveSessions() throws Exception {
            TradeSession session = new TradeSession(player1, player2);
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");
            activeSessions.put(session.getSessionId(), session);
            playerSessionMap.put(uuid1, session.getSessionId());
            playerSessionMap.put(uuid2, session.getSessionId());

            service.shutdown();

            verify(logService).logCancelledTrade(eq(session), anyString());
        }

        @Test
        @DisplayName("shutdown should clear all maps")
        void clearAllMaps() throws Exception {
            Map<UUID, TradeRequest> pendingRequests = UltiTradeTestHelper.getField(service, "pendingRequests");
            Map<UUID, TradeSession> activeSessions = UltiTradeTestHelper.getField(service, "activeSessions");
            Map<UUID, UUID> playerSessionMap = UltiTradeTestHelper.getField(service, "playerSessionMap");

            pendingRequests.put(UUID.randomUUID(), new TradeRequest(uuid1, uuid2));
            playerSessionMap.put(uuid1, UUID.randomUUID());

            service.shutdown();

            assertThat(pendingRequests).isEmpty();
            assertThat(activeSessions).isEmpty();
            assertThat(playerSessionMap).isEmpty();
        }
    }

    @Nested
    @DisplayName("Sound Effects")
    class SoundEffects {

        @Test
        @DisplayName("playSound should play when sounds enabled")
        void playSoundEnabled() {
            when(config.isEnableSounds()).thenReturn(true);

            service.playSound(player1, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING);

            verify(player1).playSound(any(org.bukkit.Location.class), eq(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING), eq(1.0f), eq(1.0f));
        }

        @Test
        @DisplayName("playSound should not play when sounds disabled")
        void playSoundDisabled() {
            when(config.isEnableSounds()).thenReturn(false);

            service.playSound(player1, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING);

            verify(player1, never()).playSound(any(org.bukkit.Location.class), any(org.bukkit.Sound.class), anyFloat(), anyFloat());
        }

        @Test
        @DisplayName("playSound should handle null player")
        void playSoundNullPlayer() {
            // Should not throw exception
            service.playSound(null, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING);
        }
    }
}
