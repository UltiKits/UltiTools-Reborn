package com.ultikits.plugins.trade.listener;

import com.ultikits.plugins.trade.UltiTradeTestHelper;
import com.ultikits.plugins.trade.config.TradeConfig;
import com.ultikits.plugins.trade.entity.TradeSession;
import com.ultikits.plugins.trade.gui.TradeConfirmPage;
import com.ultikits.plugins.trade.gui.TradeGUI;
import com.ultikits.plugins.trade.service.TradeService;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("TradeListener Tests")
class TradeListenerTest {

    private TradeListener listener;
    private TradeService tradeService;
    private TradeConfig config;
    private Player player1;
    private Player player2;
    private UUID uuid1;
    private UUID uuid2;

    @BeforeEach
    void setUp() throws Exception {
        UltiTradeTestHelper.setUp();

        tradeService = mock(TradeService.class);
        config = UltiTradeTestHelper.createDefaultConfig();

        listener = new TradeListener();
        UltiTradeTestHelper.setField(listener, "tradeService", tradeService);
        UltiTradeTestHelper.setField(listener, "config", config);

        uuid1 = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
        player1 = UltiTradeTestHelper.createMockPlayer("Player1", uuid1);
        player2 = UltiTradeTestHelper.createMockPlayer("Player2", uuid2);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiTradeTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Shift+Right-Click Trading")
    class ShiftRightClickTrading {

        @Test
        @DisplayName("Should send trade request on shift+right-click")
        void shiftRightClickSendRequest() {
            when(config.isEnableShiftClick()).thenReturn(true);
            when(player1.isSneaking()).thenReturn(true);
            when(player1.hasPermission("ultitrade.use")).thenReturn(true);

            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player1);
            when(event.getRightClicked()).thenReturn(player2);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            verify(event).setCancelled(true);
            verify(tradeService).sendRequest(player1, player2);
        }

        @Test
        @DisplayName("Should not trigger if shift-click disabled")
        void shiftClickDisabled() {
            when(config.isEnableShiftClick()).thenReturn(false);

            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player1);

            listener.onPlayerInteractEntity(event);

            verify(tradeService, never()).sendRequest(any(), any());
        }

        @Test
        @DisplayName("Should not trigger if not sneaking")
        void notSneaking() {
            when(config.isEnableShiftClick()).thenReturn(true);
            when(player1.isSneaking()).thenReturn(false);

            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player1);
            when(event.getRightClicked()).thenReturn(player2);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            verify(tradeService, never()).sendRequest(any(), any());
        }

        @Test
        @DisplayName("Should not trigger if not main hand")
        void notMainHand() {
            when(config.isEnableShiftClick()).thenReturn(true);

            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);

            listener.onPlayerInteractEntity(event);

            verify(tradeService, never()).sendRequest(any(), any());
        }

        @Test
        @DisplayName("Should not trigger if no permission")
        void noPermission() {
            when(config.isEnableShiftClick()).thenReturn(true);
            when(player1.isSneaking()).thenReturn(true);
            when(player1.hasPermission("ultitrade.use")).thenReturn(false);

            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player1);
            when(event.getRightClicked()).thenReturn(player2);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            verify(tradeService, never()).sendRequest(any(), any());
        }

        @Test
        @DisplayName("Should not trigger for self-trade")
        void selfTrade() {
            when(config.isEnableShiftClick()).thenReturn(true);
            when(player1.isSneaking()).thenReturn(true);

            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player1);
            when(event.getRightClicked()).thenReturn(player1);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            verify(tradeService, never()).sendRequest(any(), any());
        }

        @Test
        @DisplayName("Should not trigger if right-clicked entity is not a Player")
        void nonPlayerEntity() {
            when(config.isEnableShiftClick()).thenReturn(true);
            when(player1.isSneaking()).thenReturn(true);

            Entity entity = mock(Entity.class); // Not a Player
            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(player1);
            when(event.getRightClicked()).thenReturn(entity);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);

            listener.onPlayerInteractEntity(event);

            verify(tradeService, never()).sendRequest(any(), any());
        }
    }

    @Nested
    @DisplayName("Player Quit Handling")
    class PlayerQuitHandling {

        @Test
        @DisplayName("Should cancel trade on quit")
        void cancelTradeOnQuit() {
            when(tradeService.isTrading(uuid1)).thenReturn(true);

            PlayerQuitEvent event = new PlayerQuitEvent(player1, "Quit message");

            listener.onPlayerQuit(event);

            verify(tradeService).cancelTrade(player1);
        }

        @Test
        @DisplayName("Should not cancel if not trading")
        void noTradeOnQuit() {
            when(tradeService.isTrading(uuid1)).thenReturn(false);

            PlayerQuitEvent event = new PlayerQuitEvent(player1, "Quit message");

            listener.onPlayerQuit(event);

            verify(tradeService, never()).cancelTrade(any());
        }

        @Test
        @DisplayName("Should remove from waiting for input on quit")
        void removeFromWaitingOnQuit() throws Exception {
            Map<UUID, ?> waitingForInput = UltiTradeTestHelper.getField(listener, "waitingForInput");
            // Add player to waiting list - we need to use the enum via reflection
            Class<?> inputTypeClass = Class.forName("com.ultikits.plugins.trade.listener.TradeListener$InputType");
            Object moneyType = inputTypeClass.getEnumConstants()[0]; // MONEY
            @SuppressWarnings("unchecked")
            Map<UUID, Object> typedMap = (Map<UUID, Object>) waitingForInput;
            typedMap.put(uuid1, moneyType);

            when(tradeService.isTrading(uuid1)).thenReturn(false);

            PlayerQuitEvent event = new PlayerQuitEvent(player1, "Quit message");
            listener.onPlayerQuit(event);

            assertThat(waitingForInput).doesNotContainKey(uuid1);
        }
    }

    @Nested
    @DisplayName("Inventory Click Handling")
    class InventoryClickHandling {

        @Test
        @DisplayName("Should handle confirm button click")
        void confirmButtonClick() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);
            when(gui.getInventory()).thenReturn(mock(Inventory.class));

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.CONFIRM_SLOT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(tradeService).confirmTrade(player1);
        }

        @Test
        @DisplayName("Should handle cancel button click")
        void cancelButtonClick() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.CANCEL_SLOT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(tradeService).cancelTrade(player1);
        }

        @Test
        @DisplayName("Should cancel unconfirm on confirm button click")
        void unconfirmButtonClick() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            session.setConfirmed(uuid1, true);
            when(gui.getSession()).thenReturn(session);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.CONFIRM_SLOT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(tradeService).cancelConfirmation(player1);
        }

        @Test
        @DisplayName("Should handle click outside inventory")
        void clickOutside() {
            TradeGUI gui = mock(TradeGUI.class);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getRawSlot()).thenReturn(100); // Outside inventory

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should ignore non-TradeGUI inventory clicks")
        void nonTradeGuiClick() {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(null);

            listener.onInventoryClick(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should block clicks on their slots")
        void blockTheirSlots() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.THEIR_SLOTS[0]); // First "their" slot

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should block clicks on separator slots")
        void blockSeparatorSlots() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.SEPARATOR_SLOTS[0]); // First separator slot

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should block clicks on status slots")
        void blockStatusSlots() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);
            when(gui.isMoneySlot(anyInt())).thenReturn(false);
            when(gui.isExpSlot(anyInt())).thenReturn(false);
            when(gui.isYourSlot(anyInt())).thenReturn(false);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.YOUR_STATUS_SLOT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should handle TradeConfirmPage clicks")
        void confirmPageClick() {
            TradeConfirmPage confirmPage = mock(TradeConfirmPage.class);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(confirmPage);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(confirmPage).handleClick(event);
        }

        @Test
        @DisplayName("Should handle money slot click with economy")
        void moneySlotClick() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);
            when(gui.isMoneySlot(TradeGUI.YOUR_MONEY_SLOT)).thenReturn(true);
            when(tradeService.hasEconomy()).thenReturn(true);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.YOUR_MONEY_SLOT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(player1).closeInventory();
            verify(player1).sendMessage(contains("\u91D1\u5E01\u6570\u91CF")); // "金币数量"
        }

        @Test
        @DisplayName("Should handle experience slot click")
        void expSlotClick() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);
            when(gui.isMoneySlot(anyInt())).thenReturn(false);
            when(gui.isExpSlot(TradeGUI.YOUR_EXP_SLOT)).thenReturn(true);
            when(config.isEnableExpTrade()).thenReturn(true);
            when(tradeService.getTotalExperience(player1)).thenReturn(500);

            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getWhoClicked()).thenReturn(player1);
            when(event.getRawSlot()).thenReturn(TradeGUI.YOUR_EXP_SLOT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(player1).closeInventory();
            verify(player1).sendMessage(contains("\u7ECF\u9A8C\u503C")); // "经验值"
        }
    }

    @Nested
    @DisplayName("Inventory Drag Handling")
    class InventoryDragHandling {

        @Test
        @DisplayName("Should cancel drag on TradeGUI")
        void cancelDragOnTradeGUI() {
            TradeGUI gui = mock(TradeGUI.class);

            InventoryDragEvent event = mock(InventoryDragEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);

            listener.onInventoryDrag(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should cancel drag on TradeConfirmPage")
        void cancelDragOnConfirmPage() {
            TradeConfirmPage confirmPage = mock(TradeConfirmPage.class);

            InventoryDragEvent event = mock(InventoryDragEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(confirmPage);

            listener.onInventoryDrag(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should not cancel drag on non-trade inventory")
        void noCancelDragOnOtherInventory() {
            InventoryDragEvent event = mock(InventoryDragEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(null);

            listener.onInventoryDrag(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Nested
    @DisplayName("Inventory Close Handling")
    class InventoryCloseHandling {

        @Test
        @DisplayName("Should cancel trade on close")
        void cancelTradeOnClose() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);
            when(tradeService.getSession(uuid1)).thenReturn(session);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getPlayer()).thenReturn(player1);

            listener.onInventoryClose(event);

            // Verify scheduled task (implementation specific)
            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.TRADING);
        }

        @Test
        @DisplayName("Should not cancel trade on TradeConfirmPage close")
        void dontCancelOnConfirmPageClose() {
            TradeConfirmPage confirmPage = mock(TradeConfirmPage.class);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(confirmPage);
            when(event.getPlayer()).thenReturn(player1);

            listener.onInventoryClose(event);

            // Should return early without calling cancelTrade
            verify(tradeService, never()).cancelTrade(any(Player.class));
        }

        @Test
        @DisplayName("Should not cancel if not a TradeGUI holder")
        void notTradeGUIHolder() {
            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(null);

            listener.onInventoryClose(event);

            verify(tradeService, never()).getSession(any());
        }

        @Test
        @DisplayName("Should not cancel trade when waiting for input")
        void dontCancelWhenWaitingForInput() throws Exception {
            Map<UUID, ?> waitingForInput = UltiTradeTestHelper.getField(listener, "waitingForInput");
            Class<?> inputTypeClass = Class.forName("com.ultikits.plugins.trade.listener.TradeListener$InputType");
            Object moneyType = inputTypeClass.getEnumConstants()[0];
            @SuppressWarnings("unchecked")
            Map<UUID, Object> typedMap = (Map<UUID, Object>) waitingForInput;
            typedMap.put(uuid1, moneyType);

            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            when(gui.getSession()).thenReturn(session);
            when(tradeService.getSession(uuid1)).thenReturn(session);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getPlayer()).thenReturn(player1);

            listener.onInventoryClose(event);

            // Should not schedule cancel because player is waiting for input
            verify(tradeService, never()).cancelTrade(any(Player.class));
        }

        @Test
        @DisplayName("Should not cancel if session is not in TRADING state")
        void notInTradingState() {
            TradeGUI gui = mock(TradeGUI.class);
            TradeSession session = new TradeSession(player1, player2);
            session.setState(TradeSession.TradeState.COMPLETED);
            when(gui.getSession()).thenReturn(session);
            when(tradeService.getSession(uuid1)).thenReturn(session);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getPlayer()).thenReturn(player1);

            listener.onInventoryClose(event);

            // Session is completed, so no cancel should be scheduled
            verify(tradeService, never()).cancelTrade(any(Player.class));
        }

        @Test
        @DisplayName("Should not cancel if no session found for player")
        void noSessionFound() {
            TradeGUI gui = mock(TradeGUI.class);

            when(tradeService.getSession(uuid1)).thenReturn(null);

            InventoryCloseEvent event = mock(InventoryCloseEvent.class);
            when(event.getInventory()).thenReturn(mock(Inventory.class));
            when(event.getInventory().getHolder()).thenReturn(gui);
            when(event.getPlayer()).thenReturn(player1);

            listener.onInventoryClose(event);

            verify(tradeService, never()).cancelTrade(any(Player.class));
        }
    }

    @Nested
    @DisplayName("Chat Input Handling")
    class ChatInputHandling {

        @Test
        @DisplayName("Should ignore chat if not waiting for input")
        void ignoreChatNotWaiting() {
            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "hello", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should handle cancel input")
        void handleCancelInput() throws Exception {
            addToWaitingForInput(uuid1, 0); // MONEY

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "cancel", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player1).sendMessage(contains("\u53D6\u6D88\u8F93\u5165")); // "取消输入"
        }

        @Test
        @DisplayName("Should handle negative value input")
        void handleNegativeValue() throws Exception {
            addToWaitingForInput(uuid1, 0); // MONEY

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "-100", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player1).sendMessage(contains("\u4E0D\u80FD\u4E3A\u8D1F\u6570")); // "不能为负数"
        }

        @Test
        @DisplayName("Should handle invalid number input")
        void handleInvalidNumber() throws Exception {
            addToWaitingForInput(uuid1, 0); // MONEY

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "not_a_number", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player1).sendMessage(contains("\u65E0\u6548\u7684\u6570\u503C")); // "无效的数值"
        }

        @Test
        @DisplayName("Should handle valid money input")
        void handleValidMoneyInput() throws Exception {
            addToWaitingForInput(uuid1, 0); // MONEY

            TradeSession session = new TradeSession(player1, player2);
            when(tradeService.getSession(uuid1)).thenReturn(session);
            when(tradeService.hasEconomy()).thenReturn(true);
            net.milkbowl.vault.economy.Economy mockEconomy = UltiTradeTestHelper.createMockEconomy();
            when(tradeService.getEconomy()).thenReturn(mockEconomy);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "500", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            assertThat(session.getPlayerMoney(uuid1)).isEqualTo(500.0);
            verify(player1).sendMessage(contains("\u91D1\u5E01")); // "金币"
        }

        @Test
        @DisplayName("Should handle valid experience input")
        void handleValidExpInput() throws Exception {
            addToWaitingForInput(uuid1, 1); // EXPERIENCE

            TradeSession session = new TradeSession(player1, player2);
            when(tradeService.getSession(uuid1)).thenReturn(session);
            when(tradeService.getTotalExperience(player1)).thenReturn(1000);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "500", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            assertThat(session.getPlayerExp(uuid1)).isEqualTo(500);
            verify(player1).sendMessage(contains("\u7ECF\u9A8C")); // "经验"
        }

        @Test
        @DisplayName("Should reject money input exceeding balance")
        void rejectInsufficientBalance() throws Exception {
            addToWaitingForInput(uuid1, 0); // MONEY

            TradeSession session = new TradeSession(player1, player2);
            when(tradeService.getSession(uuid1)).thenReturn(session);
            when(tradeService.hasEconomy()).thenReturn(true);
            net.milkbowl.vault.economy.Economy mockEconomy = mock(net.milkbowl.vault.economy.Economy.class);
            when(mockEconomy.getBalance(player1)).thenReturn(100.0);
            when(tradeService.getEconomy()).thenReturn(mockEconomy);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "500", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player1).sendMessage(contains("\u4F59\u989D\u4E0D\u8DB3")); // "余额不足"
        }

        @Test
        @DisplayName("Should reject exp input exceeding available exp")
        void rejectInsufficientExp() throws Exception {
            addToWaitingForInput(uuid1, 1); // EXPERIENCE

            TradeSession session = new TradeSession(player1, player2);
            when(tradeService.getSession(uuid1)).thenReturn(session);
            when(tradeService.getTotalExperience(player1)).thenReturn(100);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "500", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player1).sendMessage(contains("\u7ECF\u9A8C\u4E0D\u8DB3")); // "经验不足"
        }

        @Test
        @DisplayName("Should handle trade ended during input")
        void tradeEndedDuringInput() throws Exception {
            addToWaitingForInput(uuid1, 0); // MONEY

            when(tradeService.getSession(uuid1)).thenReturn(null);

            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player1, "500", new HashSet<>());

            listener.onPlayerChat(event);

            assertThat(event.isCancelled()).isTrue();
            verify(player1).sendMessage(contains("\u4EA4\u6613\u5DF2\u7ED3\u675F")); // "交易已结束"
        }

        /**
         * Helper to add a player to the waiting for input map.
         */
        private void addToWaitingForInput(UUID uuid, int typeOrdinal) throws Exception {
            Map<UUID, ?> waitingForInput = UltiTradeTestHelper.getField(listener, "waitingForInput");
            Class<?> inputTypeClass = Class.forName("com.ultikits.plugins.trade.listener.TradeListener$InputType");
            Object inputType = inputTypeClass.getEnumConstants()[typeOrdinal];
            @SuppressWarnings("unchecked")
            Map<UUID, Object> typedMap = (Map<UUID, Object>) waitingForInput;
            typedMap.put(uuid, inputType);
        }
    }
}
