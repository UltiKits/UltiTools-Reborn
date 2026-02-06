package com.ultikits.plugins.social.service;

import com.ultikits.plugins.social.UltiSocialTestHelper;
import com.ultikits.plugins.social.config.SocialConfig;
import com.ultikits.plugins.social.entity.BlacklistData;
import com.ultikits.plugins.social.entity.FriendRequest;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FriendService.
 */
@DisplayName("FriendService Tests")
class FriendServiceTest {

    private FriendService service;
    private SocialConfig config;
    @SuppressWarnings("unchecked")
    private DataOperator<FriendshipData> friendDataOperator = mock(DataOperator.class);
    @SuppressWarnings("unchecked")
    private DataOperator<BlacklistData> blacklistDataOperator = mock(DataOperator.class);

    private Player player;
    private Player friend;
    private UUID playerUuid;
    private UUID friendUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiSocialTestHelper.setUp();

        config = UltiSocialTestHelper.createDefaultConfig();

        service = new FriendService();

        // Inject dependencies via reflection
        UltiSocialTestHelper.setField(service, "config", config);
        UltiSocialTestHelper.setField(service, "dataOperator", friendDataOperator);
        UltiSocialTestHelper.setField(service, "blacklistDataOperator", blacklistDataOperator);

        playerUuid = UUID.randomUUID();
        friendUuid = UUID.randomUUID();
        player = UltiSocialTestHelper.createMockPlayer("TestPlayer", playerUuid);
        friend = UltiSocialTestHelper.createMockPlayer("TestFriend", friendUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiSocialTestHelper.tearDown();
    }

    // ==================== sendRequest ====================

    @Nested
    @DisplayName("sendRequest")
    class SendRequest {

        @Test
        @DisplayName("Should send friend request successfully")
        void sendRequestSuccess() {
            when(friendDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            boolean result = service.sendRequest(player, friend);

            assertThat(result).isTrue();
            List<FriendRequest> requests = service.getPendingRequests(friendUuid);
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).getSender()).isEqualTo(playerUuid);
            assertThat(requests.get(0).getSenderName()).isEqualTo("TestPlayer");
        }

        @Test
        @DisplayName("Should reject request if already friends")
        void rejectIfAlreadyFriends() {
            FriendshipData existingFriendship = FriendshipData.builder()
                    .playerUuid(playerUuid.toString())
                    .friendUuid(friendUuid.toString())
                    .friendName("TestFriend")
                    .createdTime(System.currentTimeMillis())
                    .favorite(false)
                    .build();
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(existingFriendship));
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            boolean result = service.sendRequest(player, friend);

            assertThat(result).isFalse();
            verify(player).sendMessage(contains("already"));
        }

        @Test
        @DisplayName("Should reject request if blocked")
        void rejectIfBlocked() {
            BlacklistData blacklist = BlacklistData.builder()
                    .playerUuid(playerUuid.toString())
                    .blockedUuid(friendUuid.toString())
                    .build();
            when(blacklistDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(blacklist));

            boolean result = service.sendRequest(player, friend);

            assertThat(result).isFalse();
            verify(player).sendMessage(contains("blacklist"));
        }

        @Test
        @DisplayName("Should reject if max friends reached")
        void rejectIfMaxFriends() {
            List<FriendshipData> friends = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                friends.add(FriendshipData.builder()
                        .friendName("Friend" + i)
                        .friendUuid(UUID.randomUUID().toString())
                        .playerUuid(playerUuid.toString())
                        .createdTime(System.currentTimeMillis())
                        .favorite(false)
                        .build());
            }
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(friends);
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            boolean result = service.sendRequest(player, friend);

            assertThat(result).isFalse();
            verify(player).sendMessage(contains("maximum"));
        }

        @Test
        @DisplayName("Should reject duplicate request")
        void rejectDuplicateRequest() {
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            service.sendRequest(player, friend);
            boolean result = service.sendRequest(player, friend);

            assertThat(result).isFalse();
            verify(player).sendMessage(contains("已经向"));
        }
    }

    // ==================== acceptRequest ====================

    @Nested
    @DisplayName("acceptRequest")
    class AcceptRequest {

        @Test
        @DisplayName("Should accept valid request")
        void acceptValidRequest() {
            // Send a request first
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            service.sendRequest(player, friend);

            // Mock Bukkit.getPlayer() to return player for notification
            when(UltiSocialTestHelper.getMockServer().getPlayer(playerUuid)).thenReturn(player);

            boolean result = service.acceptRequest(friend, "TestPlayer");

            assertThat(result).isTrue();
            verify(friendDataOperator, times(2)).insert(any(FriendshipData.class));
        }

        @Test
        @DisplayName("Should reject non-existent request")
        void rejectNonExistentRequest() {
            boolean result = service.acceptRequest(friend, "NonExistent");

            assertThat(result).isFalse();
            verify(friend).sendMessage(contains("没有来自"));
        }

        @Test
        @DisplayName("Should reject expired request")
        void rejectExpiredRequest() {
            FriendRequest expiredRequest = new FriendRequest(
                    playerUuid,
                    "TestPlayer",
                    friendUuid,
                    System.currentTimeMillis() - 120000 // 2 minutes ago
            );
            Map<UUID, List<FriendRequest>> requests = new HashMap<>();
            requests.put(friendUuid, new ArrayList<>(Collections.singletonList(expiredRequest)));
            try {
                UltiSocialTestHelper.setField(service, "pendingRequests", requests);
            } catch (Exception e) {
                fail("Failed to set pending requests");
            }

            boolean result = service.acceptRequest(friend, "TestPlayer");

            assertThat(result).isFalse();
            verify(friend).sendMessage(contains("过期"));
        }

        @Test
        @DisplayName("Should reject if max friends reached")
        void rejectIfMaxFriends() {
            // Send request
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            service.sendRequest(player, friend);

            // Now friend has max friends
            List<FriendshipData> maxFriends = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                maxFriends.add(FriendshipData.builder()
                        .friendName("Friend" + i)
                        .friendUuid(UUID.randomUUID().toString())
                        .playerUuid(friendUuid.toString())
                        .createdTime(System.currentTimeMillis())
                        .favorite(false)
                        .build());
            }
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(maxFriends);

            boolean result = service.acceptRequest(friend, "TestPlayer");

            assertThat(result).isFalse();
            verify(friend).sendMessage(contains("maximum"));
        }
    }

    // ==================== denyRequest ====================

    @Nested
    @DisplayName("denyRequest")
    class DenyRequest {

        @Test
        @DisplayName("Should deny valid request")
        void denyValidRequest() {
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            service.sendRequest(player, friend);

            boolean result = service.denyRequest(friend, "TestPlayer");

            assertThat(result).isTrue();
            List<FriendRequest> requests = service.getPendingRequests(friendUuid);
            assertThat(requests).isEmpty();
        }

        @Test
        @DisplayName("Should reject non-existent request")
        void rejectNonExistentRequest() {
            boolean result = service.denyRequest(friend, "NonExistent");

            assertThat(result).isFalse();
            verify(friend).sendMessage(contains("没有来自"));
        }
    }

    // ==================== removeFriend ====================

    @Nested
    @DisplayName("removeFriend")
    class RemoveFriend {

        @Test
        @DisplayName("Should remove friend successfully")
        void removeFriendSuccess() {
            FriendshipData friendship = FriendshipData.builder()
                    .playerUuid(playerUuid.toString())
                    .friendUuid(friendUuid.toString())
                    .friendName("TestFriend")
                    .build();
            friendship.setId("friend-id");
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(friendship));
            when(friendDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            boolean result = service.removeFriend(player, "TestFriend");

            assertThat(result).isTrue();
            verify(friendDataOperator).delById("friend-id");
        }

        @Test
        @DisplayName("Should reject non-existent friend")
        void rejectNonExistentFriend() {
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            boolean result = service.removeFriend(player, "NonExistent");

            assertThat(result).isFalse();
            verify(player).sendMessage(contains("不是你的好友"));
        }
    }

    // ==================== getFriends ====================

    @Nested
    @DisplayName("getFriends")
    class GetFriends {

        @Test
        @DisplayName("Should return sorted friends list")
        void returnSortedList() {
            FriendshipData favorite = FriendshipData.builder()
                    .friendName("Favorite")
                    .favorite(true)
                    .build();
            FriendshipData normal1 = FriendshipData.builder()
                    .friendName("BNormal")
                    .favorite(false)
                    .build();
            FriendshipData normal2 = FriendshipData.builder()
                    .friendName("ANormal")
                    .favorite(false)
                    .build();

            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Arrays.asList(normal1, favorite, normal2));

            List<FriendshipData> friends = service.getFriends(playerUuid);

            assertThat(friends).hasSize(3);
            assertThat(friends.get(0).getFriendName()).isEqualTo("Favorite");
            assertThat(friends.get(1).getFriendName()).isEqualTo("ANormal");
            assertThat(friends.get(2).getFriendName()).isEqualTo("BNormal");
        }

        @Test
        @DisplayName("Should return empty list when no friends")
        void returnEmptyList() {
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            List<FriendshipData> friends = service.getFriends(playerUuid);

            assertThat(friends).isEmpty();
        }
    }

    // ==================== areFriends ====================

    @Nested
    @DisplayName("areFriends")
    class AreFriends {

        @Test
        @DisplayName("Should return true for friends")
        void returnTrueForFriends() {
            FriendshipData friendship = FriendshipData.builder()
                    .playerUuid(playerUuid.toString())
                    .friendUuid(friendUuid.toString())
                    .build();
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(friendship));

            boolean result = service.areFriends(playerUuid, friendUuid);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false for non-friends")
        void returnFalseForNonFriends() {
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            boolean result = service.areFriends(playerUuid, friendUuid);

            assertThat(result).isFalse();
        }
    }

    // ==================== Teleport Cooldown ====================

    @Nested
    @DisplayName("Teleport Cooldown")
    class TeleportCooldown {

        @Test
        @DisplayName("Should allow teleport with no cooldown")
        void allowTeleportNoCooldown() {
            boolean canTeleport = service.canTeleport(playerUuid);

            assertThat(canTeleport).isTrue();
        }

        @Test
        @DisplayName("Should block teleport during cooldown")
        void blockTeleportDuringCooldown() {
            service.setTpCooldown(playerUuid);

            boolean canTeleport = service.canTeleport(playerUuid);

            assertThat(canTeleport).isFalse();
        }

        @Test
        @DisplayName("Should return remaining cooldown")
        void returnRemainingCooldown() {
            service.setTpCooldown(playerUuid);

            int remaining = service.getRemainingCooldown(playerUuid);

            assertThat(remaining).isGreaterThan(0).isLessThanOrEqualTo(30);
        }

        @Test
        @DisplayName("Should return 0 remaining with no cooldown")
        void returnZeroNoCooldown() {
            int remaining = service.getRemainingCooldown(playerUuid);

            assertThat(remaining).isZero();
        }
    }

    // ==================== Blacklist ====================

    @Nested
    @DisplayName("Blacklist Operations")
    class BlacklistOperations {

        @Test
        @DisplayName("Should add player to blacklist")
        void addToBlacklist() {
            when(blacklistDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            when(friendDataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());

            boolean result = service.addToBlacklist(player, friend, null);

            assertThat(result).isTrue();
            verify(blacklistDataOperator).insert(any(BlacklistData.class));
        }

        @Test
        @DisplayName("Should not add duplicate to blacklist")
        void noDuplicateBlacklist() {
            BlacklistData existing = BlacklistData.builder()
                    .playerUuid(playerUuid.toString())
                    .blockedUuid(friendUuid.toString())
                    .build();
            when(blacklistDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(existing));

            boolean result = service.addToBlacklist(player, friend, null);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should remove from blacklist")
        void removeFromBlacklist() {
            BlacklistData blacklist = BlacklistData.builder()
                    .playerUuid(playerUuid.toString())
                    .blockedUuid(friendUuid.toString())
                    .blockedName("TestFriend")
                    .build();
            blacklist.setId("blacklist-id");
            when(blacklistDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(blacklist));

            boolean result = service.removeFromBlacklist(player, "TestFriend");

            assertThat(result).isTrue();
            verify(blacklistDataOperator).delById("blacklist-id");
        }

        @Test
        @DisplayName("Should check if blocked bidirectionally")
        void checkBlockedBidirectional() {
            BlacklistData blacklist = BlacklistData.builder()
                    .playerUuid(playerUuid.toString())
                    .blockedUuid(friendUuid.toString())
                    .build();
            when(blacklistDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(blacklist));

            boolean blocked1 = service.isBlocked(playerUuid, friendUuid);
            boolean blocked2 = service.isBlocked(friendUuid, playerUuid);

            assertThat(blocked1).isTrue();
            assertThat(blocked2).isTrue();
        }

        @Test
        @DisplayName("Should return blacklist sorted by time")
        void returnSortedBlacklist() {
            BlacklistData newer = BlacklistData.builder()
                    .blockedName("Newer")
                    .createdTime(2000L)
                    .build();
            BlacklistData older = BlacklistData.builder()
                    .blockedName("Older")
                    .createdTime(1000L)
                    .build();
            when(blacklistDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Arrays.asList(older, newer));

            List<BlacklistData> blacklist = service.getBlacklist(playerUuid);

            assertThat(blacklist).hasSize(2);
            assertThat(blacklist.get(0).getBlockedName()).isEqualTo("Newer");
            assertThat(blacklist.get(1).getBlockedName()).isEqualTo("Older");
        }
    }

    // ==================== toggleFavorite ====================

    @Nested
    @DisplayName("toggleFavorite")
    class ToggleFavorite {

        @Test
        @DisplayName("Should toggle favorite status")
        void toggleFavorite() throws Exception {
            FriendshipData friendship = FriendshipData.builder()
                    .playerUuid(playerUuid.toString())
                    .friendUuid(friendUuid.toString())
                    .friendName("TestFriend")
                    .favorite(false)
                    .build();
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(friendship));

            service.toggleFavorite(playerUuid, "TestFriend");

            assertThat(friendship.isFavorite()).isTrue();
            verify(friendDataOperator).update(friendship);
        }
    }

    // ==================== setNickname ====================

    @Nested
    @DisplayName("setNickname")
    class SetNickname {

        @Test
        @DisplayName("Should set nickname for friend")
        void setNickname() throws Exception {
            FriendshipData friendship = FriendshipData.builder()
                    .playerUuid(playerUuid.toString())
                    .friendUuid(friendUuid.toString())
                    .friendName("TestFriend")
                    .build();
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(friendship));

            service.setNickname(playerUuid, "TestFriend", "BestBuddy");

            assertThat(friendship.getNickname()).isEqualTo("BestBuddy");
            verify(friendDataOperator).update(friendship);
        }
    }

    // ==================== clearCache ====================

    @Nested
    @DisplayName("clearCache")
    class ClearCache {

        @Test
        @DisplayName("Should clear cache for player")
        void clearCache() {
            // Populate cache
            when(friendDataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(new ArrayList<>());
            service.getFriends(playerUuid);

            // Clear cache
            service.clearCache(playerUuid);

            // Next call should hit database again
            service.getFriends(playerUuid);

            verify(friendDataOperator, times(2)).getAll(any(WhereCondition.class));
        }
    }

    // ==================== Config Getter ====================

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("Should return config")
        void returnConfig() {
            assertThat(service.getConfig()).isSameAs(config);
        }
    }
}
