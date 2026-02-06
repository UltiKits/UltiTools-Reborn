package com.ultikits.plugins.social.commands;

import com.ultikits.plugins.social.UltiSocialTestHelper;
import com.ultikits.plugins.social.config.SocialConfig;
import com.ultikits.plugins.social.entity.BlacklistData;
import com.ultikits.plugins.social.entity.FriendRequest;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.plugins.social.service.FriendService;
import com.ultikits.ultitools.services.TeleportService;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FriendCommand.
 */
@DisplayName("FriendCommand Tests")
class FriendCommandTest {

    private FriendCommand command;
    private FriendService friendService;
    private TeleportService teleportService;
    private SocialConfig config;

    private Player player;
    private Player target;
    private UUID playerUuid;
    private UUID targetUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiSocialTestHelper.setUp();

        friendService = mock(FriendService.class);
        teleportService = mock(TeleportService.class);
        config = UltiSocialTestHelper.createDefaultConfig();

        // Mock config getter for all tests
        lenient().when(friendService.getConfig()).thenReturn(config);

        command = new FriendCommand(friendService, teleportService);

        playerUuid = UUID.randomUUID();
        targetUuid = UUID.randomUUID();
        player = UltiSocialTestHelper.createMockPlayer("TestPlayer", playerUuid);
        target = UltiSocialTestHelper.createMockPlayer("TargetPlayer", targetUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiSocialTestHelper.tearDown();
    }

    // ==================== listFriends ====================

    @Nested
    @DisplayName("listFriends")
    class ListFriends {

        @Test
        @DisplayName("Should list friends when player has friends")
        void listFriendsWithFriends() {
            UUID friend1Uuid = UUID.randomUUID();
            UUID friend2Uuid = UUID.randomUUID();

            FriendshipData friend1 = FriendshipData.builder()
                    .friendUuid(friend1Uuid.toString())
                    .friendName("Friend1")
                    .favorite(false)
                    .createdTime(System.currentTimeMillis())
                    .build();
            FriendshipData friend2 = FriendshipData.builder()
                    .friendUuid(friend2Uuid.toString())
                    .friendName("Friend2")
                    .favorite(true)
                    .createdTime(System.currentTimeMillis())
                    .build();

            when(friendService.getFriends(playerUuid))
                    .thenReturn(Arrays.asList(friend1, friend2));

            // Mock Bukkit.getPlayer() to return null (offline friends)
            when(UltiSocialTestHelper.getMockServer().getPlayer(friend1Uuid)).thenReturn(null);
            when(UltiSocialTestHelper.getMockServer().getPlayer(friend2Uuid)).thenReturn(null);

            command.listFriends(player);

            verify(player, atLeastOnce()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show message when no friends")
        void listFriendsNoFriends() {
            when(friendService.getFriends(playerUuid))
                    .thenReturn(new ArrayList<>());

            command.listFriends(player);

            verify(player).sendMessage(contains("还没有好友"));
        }
    }

    // ==================== addFriend ====================

    @Nested
    @DisplayName("addFriend")
    class AddFriend {

        @Test
        @DisplayName("Should send friend request to online player")
        void addFriendOnline() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("TargetPlayer"))
                        .thenReturn(target);

                command.addFriend(player, "TargetPlayer");

                verify(friendService).sendRequest(player, target);
            }
        }

        @Test
        @DisplayName("Should show error when player offline")
        void addFriendOffline() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("OfflinePlayer"))
                        .thenReturn(null);

                command.addFriend(player, "OfflinePlayer");

                verify(player).sendMessage(contains("不在线"));
            }
        }

        @Test
        @DisplayName("Should show error when trying to add self")
        void addFriendSelf() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("TestPlayer"))
                        .thenReturn(player);

                command.addFriend(player, "TestPlayer");

                verify(player).sendMessage(contains("不能添加自己"));
            }
        }
    }

    // ==================== acceptRequest ====================

    @Nested
    @DisplayName("acceptRequest")
    class AcceptRequest {

        @Test
        @DisplayName("Should accept friend request")
        void acceptRequest() {
            command.acceptRequest(player, "Sender");

            verify(friendService).acceptRequest(player, "Sender");
        }
    }

    // ==================== denyRequest ====================

    @Nested
    @DisplayName("denyRequest")
    class DenyRequest {

        @Test
        @DisplayName("Should deny friend request")
        void denyRequest() {
            command.denyRequest(player, "Sender");

            verify(friendService).denyRequest(player, "Sender");
        }
    }

    // ==================== removeFriend ====================

    @Nested
    @DisplayName("removeFriend")
    class RemoveFriend {

        @Test
        @DisplayName("Should remove friend")
        void removeFriend() {
            command.removeFriend(player, "Friend");

            verify(friendService).removeFriend(player, "Friend");
        }
    }

    // ==================== viewRequests ====================

    @Nested
    @DisplayName("viewRequests")
    class ViewRequests {

        @Test
        @DisplayName("Should show pending requests")
        void showPendingRequests() {
            FriendRequest request1 = FriendRequest.create(
                    UUID.randomUUID(),
                    "Sender1",
                    playerUuid
            );
            FriendRequest request2 = FriendRequest.create(
                    UUID.randomUUID(),
                    "Sender2",
                    playerUuid
            );

            when(friendService.getPendingRequests(playerUuid))
                    .thenReturn(Arrays.asList(request1, request2));

            command.viewRequests(player);

            verify(player, atLeastOnce()).sendMessage(contains("好友请求"));
        }

        @Test
        @DisplayName("Should show message when no requests")
        void showNoRequests() {
            when(friendService.getPendingRequests(playerUuid))
                    .thenReturn(new ArrayList<>());

            command.viewRequests(player);

            verify(player).sendMessage(contains("没有待处理"));
        }
    }

    // ==================== teleportToFriend ====================

    @Nested
    @DisplayName("teleportToFriend")
    class TeleportToFriend {

        @Test
        @DisplayName("Should teleport to online friend")
        void teleportToOnlineFriend() {
            FriendshipData friendship = FriendshipData.builder()
                    .friendUuid(targetUuid.toString())
                    .friendName("TargetPlayer")
                    .build();
            when(friendService.getConfig().isTpToFriendEnabled()).thenReturn(true);
            when(friendService.getFriends(playerUuid))
                    .thenReturn(Collections.singletonList(friendship));
            when(friendService.canTeleport(playerUuid)).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid))
                        .thenReturn(target);

                command.teleportToFriend(player, "TargetPlayer");

                verify(teleportService).teleport(eq(player), any(Location.class));
                verify(friendService).setTpCooldown(playerUuid);
            }
        }

        @Test
        @DisplayName("Should show error when tp disabled")
        void teleportDisabled() {
            when(friendService.getConfig().isTpToFriendEnabled()).thenReturn(false);

            command.teleportToFriend(player, "Friend");

            verify(player).sendMessage(contains("已禁用"));
        }

        @Test
        @DisplayName("Should show error when not friends")
        void teleportNotFriends() {
            when(friendService.getConfig().isTpToFriendEnabled()).thenReturn(true);
            when(friendService.getFriends(playerUuid))
                    .thenReturn(new ArrayList<>());

            command.teleportToFriend(player, "NotFriend");

            verify(player).sendMessage(contains("不是你的好友"));
        }

        @Test
        @DisplayName("Should show error when friend offline")
        void teleportFriendOffline() {
            FriendshipData friendship = FriendshipData.builder()
                    .friendUuid(targetUuid.toString())
                    .friendName("TargetPlayer")
                    .build();
            when(friendService.getConfig().isTpToFriendEnabled()).thenReturn(true);
            when(friendService.getFriends(playerUuid))
                    .thenReturn(Collections.singletonList(friendship));

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid))
                        .thenReturn(null);

                command.teleportToFriend(player, "TargetPlayer");

                verify(player).sendMessage(contains("不在线"));
            }
        }

        @Test
        @DisplayName("Should show cooldown message when on cooldown")
        void teleportOnCooldown() {
            FriendshipData friendship = FriendshipData.builder()
                    .friendUuid(targetUuid.toString())
                    .friendName("TargetPlayer")
                    .build();
            when(friendService.getConfig().isTpToFriendEnabled()).thenReturn(true);
            when(friendService.getFriends(playerUuid))
                    .thenReturn(Collections.singletonList(friendship));
            when(friendService.canTeleport(playerUuid)).thenReturn(false);
            when(friendService.getRemainingCooldown(playerUuid)).thenReturn(15);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid))
                        .thenReturn(target);

                command.teleportToFriend(player, "TargetPlayer");

                verify(player).sendMessage(contains("冷却中"));
            }
        }
    }

    // ==================== blockPlayer ====================

    @Nested
    @DisplayName("blockPlayer")
    class BlockPlayer {

        @Test
        @DisplayName("Should block online player")
        void blockOnlinePlayer() {
            when(friendService.addToBlacklist(player, target, null))
                    .thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("TargetPlayer"))
                        .thenReturn(target);

                command.blockPlayer(player, "TargetPlayer");

                verify(friendService).addToBlacklist(player, target, null);
                verify(player).sendMessage(contains("加入黑名单"));
            }
        }

        @Test
        @DisplayName("Should show error when trying to block self")
        void blockSelf() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("TestPlayer"))
                        .thenReturn(player);

                command.blockPlayer(player, "TestPlayer");

                verify(player).sendMessage(contains("不能拉黑自己"));
            }
        }

        @Test
        @DisplayName("Should show error when already blocked")
        void blockAlreadyBlocked() {
            when(friendService.addToBlacklist(player, target, null))
                    .thenReturn(false);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("TargetPlayer"))
                        .thenReturn(target);

                command.blockPlayer(player, "TargetPlayer");

                verify(player).sendMessage(contains("已在黑名单中"));
            }
        }
    }

    // ==================== unblockPlayer ====================

    @Nested
    @DisplayName("unblockPlayer")
    class UnblockPlayer {

        @Test
        @DisplayName("Should unblock player")
        void unblockPlayer() {
            when(friendService.removeFromBlacklist(player, "BlockedPlayer"))
                    .thenReturn(true);

            command.unblockPlayer(player, "BlockedPlayer");

            verify(friendService).removeFromBlacklist(player, "BlockedPlayer");
            verify(player).sendMessage(contains("从黑名单中移除"));
        }

        @Test
        @DisplayName("Should show error when not blocked")
        void unblockNotBlocked() {
            when(friendService.removeFromBlacklist(player, "NotBlocked"))
                    .thenReturn(false);

            command.unblockPlayer(player, "NotBlocked");

            verify(player).sendMessage(contains("不在你的黑名单中"));
        }
    }

    // ==================== help ====================

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("Should show help message")
        void showHelp() {
            command.help(player);

            verify(player, atLeastOnce()).sendMessage(contains("好友系统帮助"));
        }
    }

    // ==================== Tab Completion ====================
    // NOTE: Tab completion tests are disabled because they require UltiTools.getInstance()
    // which cannot be mocked (UltiTools is final). Tab completion is tested in integration tests.

    /* Disabled: requires UltiTools mock
    @Nested
    @DisplayName("Tab Completion")
    class TabCompletion {

        @Test
        @DisplayName("Should suggest subcommands")
        void suggestSubcommands() {
            List<String> suggestions = command.onTabComplete(
                    player,
                    null,
                    "friend",
                    new String[]{"a"}
            );

            if (suggestions != null && !suggestions.isEmpty()) {
                assertThat(suggestions).contains("add", "accept");
            }
        }

        @Test
        @DisplayName("Should suggest friends for remove command")
        void suggestFriendsForRemove() {
            FriendshipData friend1 = FriendshipData.builder()
                    .friendName("Friend1")
                    .build();
            when(friendService.getFriends(playerUuid))
                    .thenReturn(Collections.singletonList(friend1));

            List<String> suggestions = command.onTabComplete(
                    player,
                    null,
                    "friend",
                    new String[]{"remove", "Fr"}
            );

            if (suggestions != null && !suggestions.isEmpty()) {
                assertThat(suggestions).contains("Friend1");
            }
        }

        @Test
        @DisplayName("Should suggest pending requests for accept command")
        void suggestPendingForAccept() {
            FriendRequest request = FriendRequest.create(
                    UUID.randomUUID(),
                    "Sender1",
                    playerUuid
            );
            when(friendService.getPendingRequests(playerUuid))
                    .thenReturn(Collections.singletonList(request));

            List<String> suggestions = command.onTabComplete(
                    player,
                    null,
                    "friend",
                    new String[]{"accept", "S"}
            );

            if (suggestions != null && !suggestions.isEmpty()) {
                assertThat(suggestions).contains("Sender1");
            }
        }

        @Test
        @DisplayName("Should suggest blocked players for unblock command")
        void suggestBlockedForUnblock() {
            BlacklistData blocked = BlacklistData.builder()
                    .blockedName("BlockedPlayer")
                    .build();
            when(friendService.getBlacklist(playerUuid))
                    .thenReturn(Collections.singletonList(blocked));

            List<String> suggestions = command.onTabComplete(
                    player,
                    null,
                    "friend",
                    new String[]{"unblock", "Bl"}
            );

            if (suggestions != null && !suggestions.isEmpty()) {
                assertThat(suggestions).contains("BlockedPlayer");
            }
        }
    }
    */
}
