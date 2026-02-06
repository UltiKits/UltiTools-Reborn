package com.ultikits.plugins.social.listener;

import com.ultikits.plugins.social.UltiSocialTestHelper;
import com.ultikits.plugins.social.config.SocialConfig;
import com.ultikits.plugins.social.entity.BlacklistData;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.plugins.social.gui.BlockListGUI;
import com.ultikits.plugins.social.gui.FriendListGUI;
import com.ultikits.plugins.social.service.FriendService;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.services.TeleportService;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SocialListener.
 */
@DisplayName("SocialListener Tests")
class SocialListenerTest {

    private SocialListener listener;
    private FriendService friendService;
    private NotificationService notificationService;
    private TeleportService teleportService;
    private SocialConfig config;

    private Player player;
    private Player friend;
    private UUID playerUuid;
    private UUID friendUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiSocialTestHelper.setUp();

        friendService = mock(FriendService.class);
        notificationService = mock(NotificationService.class);
        teleportService = mock(TeleportService.class);
        config = UltiSocialTestHelper.createDefaultConfig();

        listener = new SocialListener();
        UltiSocialTestHelper.setField(listener, "friendService", friendService);
        UltiSocialTestHelper.setField(listener, "notificationService", notificationService);
        UltiSocialTestHelper.setField(listener, "teleportService", teleportService);

        when(friendService.getConfig()).thenReturn(config);

        playerUuid = UUID.randomUUID();
        friendUuid = UUID.randomUUID();
        player = UltiSocialTestHelper.createMockPlayer("TestPlayer", playerUuid);
        friend = UltiSocialTestHelper.createMockPlayer("Friend", friendUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiSocialTestHelper.tearDown();
    }

    // ==================== onPlayerJoin ====================

    @Nested
    @DisplayName("onPlayerJoin")
    class OnPlayerJoin {

        @Test
        @DisplayName("Should notify friends when player joins")
        void notifyFriendsOnJoin() {
            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(notificationService).sendMessageNotification(eq(friend), anyString());
            }
        }

        @Test
        @DisplayName("Should not notify when notifications disabled")
        void noNotifyWhenDisabled() {
            when(config.isNotifyFriendOnline()).thenReturn(false);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
            listener.onPlayerJoin(event);

            verify(notificationService, never()).sendMessageNotification(any(), anyString());
        }

        @Test
        @DisplayName("Should not notify non-friends")
        void noNotifyNonFriends() {
            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(friendService.areFriends(any(), any())).thenReturn(false);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(notificationService, never()).sendMessageNotification(any(), anyString());
            }
        }

        @Test
        @DisplayName("Should not notify self")
        void noNotifySelf() {
            when(config.isNotifyFriendOnline()).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Collections.singletonList(player);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(notificationService, never()).sendMessageNotification(any(), anyString());
            }
        }

        @Test
        @DisplayName("Should replace {PLAYER} placeholder and color codes in join message")
        void replacePlayerPlaceholderAndColorCodes() {
            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(config.getFriendOnlineMessage()).thenReturn("&a{PLAYER} is online!");
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
                verify(notificationService).sendMessageNotification(eq(friend), messageCaptor.capture());

                String sentMessage = messageCaptor.getValue();
                assertThat(sentMessage).contains("TestPlayer");
                assertThat(sentMessage).contains("\u00a7a"); // §a color code
                assertThat(sentMessage).doesNotContain("&a");
                assertThat(sentMessage).doesNotContain("{PLAYER}");
            }
        }

        @Test
        @DisplayName("Should not notify areFriends when only one player online")
        void noFriendsCheckWhenOnlyPlayerOnline() {
            when(config.isNotifyFriendOnline()).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Collections.singletonList(player);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(friendService, never()).areFriends(any(), any());
            }
        }

        @Test
        @DisplayName("Should handle empty online players list")
        void handleEmptyOnlinePlayers() {
            when(config.isNotifyFriendOnline()).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Collections.emptyList();
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(notificationService, never()).sendMessageNotification(any(), anyString());
            }
        }

        @Test
        @DisplayName("Should only notify friends, skip non-friends among multiple players")
        void notifyOnlyFriendsAmongMultiple() {
            Player nonFriend = UltiSocialTestHelper.createMockPlayer("NonFriend", UUID.randomUUID());

            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);
            when(friendService.areFriends(nonFriend.getUniqueId(), playerUuid)).thenReturn(false);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend, nonFriend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(notificationService, times(1)).sendMessageNotification(eq(friend), anyString());
                verify(notificationService, never()).sendMessageNotification(eq(nonFriend), anyString());
            }
        }
    }

    // ==================== onPlayerQuit ====================

    @Nested
    @DisplayName("onPlayerQuit")
    class OnPlayerQuit {

        @Test
        @DisplayName("Should clear cache when player quits")
        void clearCacheOnQuit() {
            // Mock Bukkit.getOnlinePlayers() so listener doesn't fail
            when(UltiSocialTestHelper.getMockServer().getOnlinePlayers())
                    .thenReturn(Collections.emptyList());

            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
            listener.onPlayerQuit(event);

            verify(friendService).clearCache(playerUuid);
        }

        @Test
        @DisplayName("Should notify friends when player quits")
        void notifyFriendsOnQuit() {
            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                verify(notificationService).sendMessageNotification(eq(friend), anyString());
            }
        }

        @Test
        @DisplayName("Should not notify when notifications disabled")
        void noNotifyWhenDisabled() {
            when(config.isNotifyFriendOffline()).thenReturn(false);

            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
            listener.onPlayerQuit(event);

            verify(notificationService, never()).sendMessageNotification(any(), anyString());
        }

        @Test
        @DisplayName("Should not notify non-friends on quit")
        void noNotifyNonFriends() {
            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(friendService.areFriends(any(), any())).thenReturn(false);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                verify(notificationService, never()).sendMessageNotification(any(), anyString());
            }
        }

        @Test
        @DisplayName("Should not notify self on quit")
        void noNotifySelfOnQuit() {
            when(config.isNotifyFriendOffline()).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Collections.singletonList(player);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                verify(notificationService, never()).sendMessageNotification(any(), anyString());
            }
        }

        @Test
        @DisplayName("Should always clear cache even when notifications disabled")
        void alwaysClearCacheOnQuit() {
            when(config.isNotifyFriendOffline()).thenReturn(false);

            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
            listener.onPlayerQuit(event);

            verify(friendService).clearCache(playerUuid);
        }

        @Test
        @DisplayName("Should replace {PLAYER} placeholder and color codes in quit message")
        void replacePlayerPlaceholderAndColorCodesOnQuit() {
            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(config.getFriendOfflineMessage()).thenReturn("&7{PLAYER} went offline");
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
                verify(notificationService).sendMessageNotification(eq(friend), messageCaptor.capture());

                String sentMessage = messageCaptor.getValue();
                assertThat(sentMessage).contains("TestPlayer");
                assertThat(sentMessage).contains("\u00a77"); // §7 color code
                assertThat(sentMessage).doesNotContain("&7");
                assertThat(sentMessage).doesNotContain("{PLAYER}");
            }
        }

        @Test
        @DisplayName("Should only notify friends, skip non-friends among multiple players on quit")
        void notifyOnlyFriendsAmongMultipleOnQuit() {
            Player nonFriend = UltiSocialTestHelper.createMockPlayer("NonFriend", UUID.randomUUID());

            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);
            when(friendService.areFriends(nonFriend.getUniqueId(), playerUuid)).thenReturn(false);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend, nonFriend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                verify(notificationService, times(1)).sendMessageNotification(eq(friend), anyString());
                verify(notificationService, never()).sendMessageNotification(eq(nonFriend), anyString());
            }
        }
    }

    // ==================== Notification Fallback ====================

    @Nested
    @DisplayName("Notification Fallback")
    class NotificationFallback {

        @Test
        @DisplayName("Should use player.sendMessage when NotificationService unavailable on join")
        void fallbackToPlayerSendMessageOnJoin() throws Exception {
            // Remove notification service
            UltiSocialTestHelper.setField(listener, "notificationService", null);

            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(friend).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("Should use player.sendMessage when NotificationService unavailable on quit")
        void fallbackToPlayerSendMessageOnQuit() throws Exception {
            // Remove notification service
            UltiSocialTestHelper.setField(listener, "notificationService", null);

            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                verify(friend).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("Should replace placeholders in fallback message on join")
        void fallbackReplaceOnJoin() throws Exception {
            UltiSocialTestHelper.setField(listener, "notificationService", null);

            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(config.getFriendOnlineMessage()).thenReturn("&e{PLAYER} joined!");
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
                verify(friend).sendMessage(captor.capture());

                assertThat(captor.getValue()).contains("TestPlayer");
                assertThat(captor.getValue()).contains("\u00a7e"); // §e
                assertThat(captor.getValue()).doesNotContain("{PLAYER}");
            }
        }

        @Test
        @DisplayName("Should replace placeholders in fallback message on quit")
        void fallbackReplaceOnQuit() throws Exception {
            UltiSocialTestHelper.setField(listener, "notificationService", null);

            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(config.getFriendOfflineMessage()).thenReturn("&c{PLAYER} left!");
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
                verify(friend).sendMessage(captor.capture());

                assertThat(captor.getValue()).contains("TestPlayer");
                assertThat(captor.getValue()).contains("\u00a7c"); // §c
                assertThat(captor.getValue()).doesNotContain("{PLAYER}");
            }
        }
    }

    // ==================== Message Content ====================

    @Nested
    @DisplayName("Message Content")
    class MessageContent {

        @Test
        @DisplayName("Should use configured online message")
        void useConfiguredOnlineMessage() {
            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(config.getFriendOnlineMessage()).thenReturn("&a{PLAYER} is online!");
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(notificationService).sendMessageNotification(
                        eq(friend),
                        contains("TestPlayer")
                );
            }
        }

        @Test
        @DisplayName("Should use configured offline message")
        void useConfiguredOfflineMessage() {
            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(config.getFriendOfflineMessage()).thenReturn("&7{PLAYER} went offline");
            when(friendService.areFriends(friendUuid, playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                verify(notificationService).sendMessageNotification(
                        eq(friend),
                        contains("TestPlayer")
                );
            }
        }
    }

    // ==================== Multiple Friends ====================

    @Nested
    @DisplayName("Multiple Friends")
    class MultipleFriends {

        @Test
        @DisplayName("Should notify all online friends on join")
        void notifyAllFriendsOnJoin() {
            Player friend2 = UltiSocialTestHelper.createMockPlayer("Friend2", UUID.randomUUID());
            Player friend3 = UltiSocialTestHelper.createMockPlayer("Friend3", UUID.randomUUID());

            when(config.isNotifyFriendOnline()).thenReturn(true);
            when(friendService.areFriends(any(), eq(playerUuid))).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend, friend2, friend3);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerJoinEvent event = new PlayerJoinEvent(player, "join message");
                listener.onPlayerJoin(event);

                verify(notificationService, times(3)).sendMessageNotification(any(Player.class), anyString());
            }
        }

        @Test
        @DisplayName("Should notify all online friends on quit")
        void notifyAllFriendsOnQuit() {
            Player friend2 = UltiSocialTestHelper.createMockPlayer("Friend2", UUID.randomUUID());
            Player friend3 = UltiSocialTestHelper.createMockPlayer("Friend3", UUID.randomUUID());

            when(config.isNotifyFriendOffline()).thenReturn(true);
            when(friendService.areFriends(any(), eq(playerUuid))).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                Collection<Player> onlinePlayers = Arrays.asList(player, friend, friend2, friend3);
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);

                PlayerQuitEvent event = new PlayerQuitEvent(player, "quit message");
                listener.onPlayerQuit(event);

                verify(notificationService, times(3)).sendMessageNotification(any(Player.class), anyString());
            }
        }
    }

    // ==================== onInventoryClick ====================

    @Nested
    @DisplayName("onInventoryClick")
    class OnInventoryClick {

        @Test
        @DisplayName("Should ignore click when holder is not FriendListGUI or BlockListGUI")
        void ignoreNonGuiClick() {
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(null);

            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(inventory);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, null, 0,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            // Event should not be cancelled (no GUI handler matched)
            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should route to FriendListGUI handler when holder is FriendListGUI")
        void routeToFriendListGUI() {
            FriendListGUI gui = mock(FriendListGUI.class);
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(gui);

            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(inventory);

            // Click on navigation slot 45 (previous page)
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 45,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(gui).previousPage();
        }

        @Test
        @DisplayName("Should route to BlockListGUI handler when holder is BlockListGUI")
        void routeToBlockListGUI() {
            BlockListGUI gui = mock(BlockListGUI.class);
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(gui);

            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(inventory);

            // Click on navigation slot 45 (previous page)
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 45,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
            verify(gui).previousPage();
        }
    }

    // ==================== FriendListGUI Click Handling ====================

    @Nested
    @DisplayName("handleFriendListClick")
    class HandleFriendListClick {

        private FriendListGUI gui;
        private Inventory inventory;
        private InventoryView view;

        @BeforeEach
        void setUpGui() {
            gui = mock(FriendListGUI.class);
            inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(gui);

            view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(inventory);
        }

        @Test
        @DisplayName("Should cancel event for all FriendListGUI clicks")
        void cancelEvent() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 0,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should navigate to previous page on slot 45")
        void previousPage() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 45,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(gui).previousPage();
        }

        @Test
        @DisplayName("Should navigate to next page on slot 53")
        void nextPage() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 53,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(gui).nextPage();
        }

        @Test
        @DisplayName("Should open pending requests on slot 47")
        void pendingRequests() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 47,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(player).closeInventory();
            verify(player).performCommand("friend requests");
        }

        @Test
        @DisplayName("Should do nothing when clicking empty friend slot")
        void emptySlot() {
            when(gui.getFriendAtSlot(10)).thenReturn(null);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 10,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(friendService, never()).toggleFavorite(any(), anyString());
            verify(player, never()).teleport(any(Location.class));
        }

        @Test
        @DisplayName("Should toggle favorite on shift+left click")
        void toggleFavoriteOnShiftLeftClick() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.SHIFT_LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(friend);

                listener.onInventoryClick(event);
            }

            verify(friendService).toggleFavorite(playerUuid, "Friend");
            verify(gui).refresh();
            verify(player).sendMessage(contains("\u00a7a")); // Green message
        }

        @Test
        @DisplayName("Should teleport to online friend on left click when tp enabled and no cooldown")
        void teleportToOnlineFriendLeftClick() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);
            when(config.isTpToFriendEnabled()).thenReturn(true);
            when(friendService.canTeleport(playerUuid)).thenReturn(true);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(friend);

                listener.onInventoryClick(event);
            }

            verify(player).closeInventory();
            verify(teleportService).teleport(eq(player), any(Location.class));
            verify(friendService).setTpCooldown(playerUuid);
            verify(player).sendMessage(contains("Friend"));
        }

        @Test
        @DisplayName("Should show cooldown message when tp on cooldown")
        void showCooldownMessage() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);
            when(config.isTpToFriendEnabled()).thenReturn(true);
            when(friendService.canTeleport(playerUuid)).thenReturn(false);
            when(friendService.getRemainingCooldown(playerUuid)).thenReturn(15);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(friend);

                listener.onInventoryClick(event);
            }

            verify(player).sendMessage(contains("15"));
            verify(player, never()).closeInventory();
        }

        @Test
        @DisplayName("Should show offline message on left click when friend is offline")
        void showOfflineMessageLeftClick() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);

                listener.onInventoryClick(event);
            }

            verify(player).sendMessage(contains("Friend"));
            verify(player).sendMessage(contains("\u00a7c")); // Red message
        }

        @Test
        @DisplayName("Should use direct teleport when TeleportService unavailable")
        void directTeleportWhenNoService() throws Exception {
            UltiSocialTestHelper.setField(listener, "teleportService", null);

            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);
            when(config.isTpToFriendEnabled()).thenReturn(true);
            when(friendService.canTeleport(playerUuid)).thenReturn(true);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(friend);

                listener.onInventoryClick(event);
            }

            verify(player).teleport(any(Location.class));
            verify(friendService).setTpCooldown(playerUuid);
        }

        @Test
        @DisplayName("Should show private message hint on right click when friend is online")
        void privateMessageHintRightClick() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.RIGHT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(friend);

                listener.onInventoryClick(event);
            }

            verify(player).closeInventory();
            verify(player).sendMessage(contains("/friend msg Friend"));
        }

        @Test
        @DisplayName("Should remove friend on right click when friend is offline")
        void removeFriendRightClickOffline() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.RIGHT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);

                listener.onInventoryClick(event);
            }

            verify(player).closeInventory();
            verify(friendService).removeFriend(player, "Friend");
        }

        @Test
        @DisplayName("Should remove friend on shift+right click")
        void removeFriendShiftRightClick() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.SHIFT_RIGHT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(friend);

                listener.onInventoryClick(event);
            }

            verify(player).closeInventory();
            verify(friendService).removeFriend(player, "Friend");
        }

        @Test
        @DisplayName("Should ignore clicks outside friend item range (slot >= 45, not nav)")
        void ignoreOutOfRangeSlots() {
            // Slot 46 is not navigation and not in friend range
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 46,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui, never()).getFriendAtSlot(anyInt());
            verify(gui, never()).previousPage();
            verify(gui, never()).nextPage();
        }

        @Test
        @DisplayName("Should handle slot 0 as valid friend slot")
        void handleSlotZero() {
            when(gui.getFriendAtSlot(0)).thenReturn(null);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 0,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui).getFriendAtSlot(0);
        }

        @Test
        @DisplayName("Should handle slot 44 as last valid friend slot")
        void handleLastFriendSlot() {
            when(gui.getFriendAtSlot(44)).thenReturn(null);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 44,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui).getFriendAtSlot(44);
        }

        @Test
        @DisplayName("Should not teleport when tp is disabled even if friend is online")
        void noTeleportWhenDisabled() {
            FriendshipData friendData = FriendshipData.builder()
                    .friendUuid(friendUuid.toString())
                    .friendName("Friend")
                    .build();
            when(gui.getFriendAtSlot(5)).thenReturn(friendData);
            when(config.isTpToFriendEnabled()).thenReturn(false);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(friend);

                listener.onInventoryClick(event);
            }

            verify(player, never()).closeInventory();
            verify(player, never()).teleport(any(Location.class));
            verify(teleportService, never()).teleport(any(), any(Location.class));
        }

        @Test
        @DisplayName("Should handle negative slot gracefully")
        void handleNegativeSlot() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, -1,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui, never()).getFriendAtSlot(anyInt());
        }
    }

    // ==================== BlockListGUI Click Handling ====================

    @Nested
    @DisplayName("handleBlockListClick")
    class HandleBlockListClick {

        private BlockListGUI gui;
        private Inventory inventory;
        private InventoryView view;

        @BeforeEach
        void setUpGui() {
            gui = mock(BlockListGUI.class);
            inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(gui);

            view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(inventory);
        }

        @Test
        @DisplayName("Should cancel event for all BlockListGUI clicks")
        void cancelEvent() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 0,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should navigate to previous page on slot 45")
        void previousPage() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 45,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(gui).previousPage();
        }

        @Test
        @DisplayName("Should navigate to next page on slot 53")
        void nextPage() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 53,
                    ClickType.LEFT);

            listener.onInventoryClick(event);

            verify(gui).nextPage();
        }

        @Test
        @DisplayName("Should go back to friend list on slot 47")
        void backToFriendList() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 47,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            // Note: this creates a new FriendListGUI which will fail in test, but we verify closeInventory
            try {
                listener.onInventoryClick(event);
            } catch (Exception e) {
                // Expected - FriendListGUI constructor may fail in test environment
            }

            verify(player).closeInventory();
        }

        @Test
        @DisplayName("Should do nothing when clicking empty blocked user slot")
        void emptySlot() {
            when(gui.getBlockedUserAtSlot(10)).thenReturn(null);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 10,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(friendService, never()).removeFromBlacklist(any(Player.class), anyString());
        }

        @Test
        @DisplayName("Should unblock user on left click")
        void unblockOnLeftClick() {
            BlacklistData blocked = BlacklistData.builder()
                    .blockedName("BlockedUser")
                    .blockedUuid(friendUuid.toString())
                    .build();
            when(gui.getBlockedUserAtSlot(5)).thenReturn(blocked);
            when(friendService.removeFromBlacklist(player, "BlockedUser")).thenReturn(true);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(friendService).removeFromBlacklist(player, "BlockedUser");
            verify(player).sendMessage(contains("BlockedUser"));
            verify(gui).refresh();
        }

        @Test
        @DisplayName("Should show error when unblock fails")
        void showErrorOnUnblockFail() {
            BlacklistData blocked = BlacklistData.builder()
                    .blockedName("BlockedUser")
                    .blockedUuid(friendUuid.toString())
                    .build();
            when(gui.getBlockedUserAtSlot(5)).thenReturn(blocked);
            when(friendService.removeFromBlacklist(player, "BlockedUser")).thenReturn(false);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 5,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(player).sendMessage(contains("\u00a7c")); // Red error message
            verify(gui, never()).refresh();
        }

        @Test
        @DisplayName("Should handle slot 0 as valid blocked user slot")
        void handleSlotZero() {
            when(gui.getBlockedUserAtSlot(0)).thenReturn(null);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 0,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui).getBlockedUserAtSlot(0);
        }

        @Test
        @DisplayName("Should handle slot 44 as last valid blocked user slot")
        void handleLastSlot() {
            when(gui.getBlockedUserAtSlot(44)).thenReturn(null);

            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 44,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui).getBlockedUserAtSlot(44);
        }

        @Test
        @DisplayName("Should ignore clicks outside blocked user range (slot >= 45, not nav)")
        void ignoreOutOfRangeSlots() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, 50,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui, never()).getBlockedUserAtSlot(anyInt());
        }

        @Test
        @DisplayName("Should handle negative slot gracefully")
        void handleNegativeSlot() {
            InventoryClickEvent event = createInventoryClickEvent(view, inventory, gui, -1,
                    ClickType.LEFT);
            when(event.getWhoClicked()).thenReturn(player);

            listener.onInventoryClick(event);

            verify(gui, never()).getBlockedUserAtSlot(anyInt());
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Create a mock InventoryClickEvent.
     * Uses Mockito mocking since InventoryClickEvent requires complex Bukkit internals.
     */
    private InventoryClickEvent createInventoryClickEvent(
            InventoryView view, Inventory inventory,
            Object holder, int rawSlot, ClickType clickType) {

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        lenient().when(event.getInventory()).thenReturn(inventory);
        lenient().when(event.getView()).thenReturn(view);
        lenient().when(event.getRawSlot()).thenReturn(rawSlot);
        lenient().when(event.getWhoClicked()).thenReturn(player);

        // Set click type behaviors
        lenient().when(event.getClick()).thenReturn(clickType);
        lenient().when(event.isLeftClick()).thenReturn(
                clickType == ClickType.LEFT || clickType == ClickType.SHIFT_LEFT);
        lenient().when(event.isRightClick()).thenReturn(
                clickType == ClickType.RIGHT || clickType == ClickType.SHIFT_RIGHT);
        lenient().when(event.isShiftClick()).thenReturn(
                clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT);

        return event;
    }
}
