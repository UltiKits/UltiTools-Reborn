package com.ultikits.plugins.social.service;

import com.ultikits.plugins.social.config.SocialConfig;
import com.ultikits.plugins.social.entity.BlacklistData;
import com.ultikits.plugins.social.entity.FriendRequest;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for friend system operations.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class FriendService {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private SocialConfig config;

    private DataOperator<FriendshipData> dataOperator;
    private DataOperator<BlacklistData> blacklistDataOperator;
    
    // Pending friend requests - Map<ReceiverUUID, List<FriendRequest>>
    private final Map<UUID, List<FriendRequest>> pendingRequests = new ConcurrentHashMap<>();
    
    // Cache for friends - Map<PlayerUUID, List<FriendshipData>>
    private final Map<UUID, List<FriendshipData>> friendCache = new ConcurrentHashMap<>();
    
    // Cache for blacklist - Map<PlayerUUID, List<BlacklistData>>
    private final Map<UUID, List<BlacklistData>> blacklistCache = new ConcurrentHashMap<>();
    
    // Teleport cooldowns - Map<PlayerUUID, LastTeleportTime>
    private final Map<UUID, Long> tpCooldowns = new ConcurrentHashMap<>();
    
    /**
     * Initialize the service.
     */
    @PostConstruct
    public void init() {
        this.dataOperator = plugin.getDataOperator(FriendshipData.class);
        this.blacklistDataOperator = plugin.getDataOperator(BlacklistData.class);
    }

    /**
     * Scheduled cleanup task for expired friend requests.
     * 定时清理过期好友请求任务
     */
    @Scheduled(period = 1200, async = true)  // Every minute (60 seconds * 20 ticks)
    public void cleanupExpiredRequests() {
        for (List<FriendRequest> requests : pendingRequests.values()) {
            requests.removeIf(req -> req.isExpired(config.getRequestTimeout()));
        }
    }
    
    /**
     * Send a friend request.
     */
    public boolean sendRequest(Player sender, Player receiver) {
        UUID senderUuid = sender.getUniqueId();
        UUID receiverUuid = receiver.getUniqueId();
        
        // Check blacklist (bidirectional)
        if (isBlocked(senderUuid, receiverUuid)) {
            sender.sendMessage(config.getBlockedMessage()
                .replace("{PLAYER}", receiver.getName())
                .replace("&", "§"));
            return false;
        }
        
        // Check if already friends
        if (areFriends(senderUuid, receiverUuid)) {
            sender.sendMessage(config.getAlreadyFriendsMessage()
                .replace("{PLAYER}", receiver.getName())
                .replace("&", "§"));
            return false;
        }
        
        // Check max friends limit
        if (getFriendCount(senderUuid) >= config.getMaxFriends()) {
            sender.sendMessage(config.getMaxFriendsMessage().replace("&", "§"));
            return false;
        }
        
        // Check if request already pending
        List<FriendRequest> requests = pendingRequests.computeIfAbsent(receiverUuid, k -> new ArrayList<>());
        for (FriendRequest req : requests) {
            if (req.getSender().equals(senderUuid)) {
                sender.sendMessage("§c你已经向 " + receiver.getName() + " 发送过好友请求了！");
                return false;
            }
        }
        
        // Check if receiver has sent request to sender (auto-accept)
        List<FriendRequest> senderRequests = pendingRequests.get(senderUuid);
        if (senderRequests != null) {
            for (FriendRequest req : senderRequests) {
                if (req.getSender().equals(receiverUuid)) {
                    // Auto accept - both want to be friends
                    acceptRequest(sender, receiver.getName());
                    return true;
                }
            }
        }
        
        // Add request
        requests.add(FriendRequest.create(senderUuid, sender.getName(), receiverUuid));
        
        sender.sendMessage(config.getRequestSentMessage()
            .replace("{PLAYER}", receiver.getName())
            .replace("&", "§"));
        
        receiver.sendMessage(config.getRequestReceivedMessage()
            .replace("{PLAYER}", sender.getName())
            .replace("&", "§"));
        
        return true;
    }
    
    /**
     * Accept a friend request.
     */
    public boolean acceptRequest(Player receiver, String senderName) {
        UUID receiverUuid = receiver.getUniqueId();
        List<FriendRequest> requests = pendingRequests.get(receiverUuid);
        
        if (requests == null || requests.isEmpty()) {
            receiver.sendMessage("§c没有来自 " + senderName + " 的好友请求！");
            return false;
        }
        
        FriendRequest request = null;
        for (FriendRequest req : requests) {
            if (req.getSenderName().equalsIgnoreCase(senderName)) {
                request = req;
                break;
            }
        }
        
        if (request == null || request.isExpired(config.getRequestTimeout())) {
            receiver.sendMessage("§c好友请求已过期或不存在！");
            return false;
        }
        
        // Check max friends
        if (getFriendCount(receiverUuid) >= config.getMaxFriends()) {
            receiver.sendMessage(config.getMaxFriendsMessage().replace("&", "§"));
            return false;
        }
        
        // Create friendship (bidirectional)
        addFriend(receiverUuid, request.getSender(), request.getSenderName());
        addFriend(request.getSender(), receiverUuid, receiver.getName());
        
        // Remove request
        requests.remove(request);
        
        // Notify both players
        receiver.sendMessage(config.getFriendAddedMessage()
            .replace("{PLAYER}", senderName)
            .replace("&", "§"));
        
        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender != null) {
            sender.sendMessage(config.getFriendAddedMessage()
                .replace("{PLAYER}", receiver.getName())
                .replace("&", "§"));
        }
        
        // Clear cache
        friendCache.remove(receiverUuid);
        friendCache.remove(request.getSender());
        
        return true;
    }
    
    /**
     * Deny a friend request.
     */
    public boolean denyRequest(Player receiver, String senderName) {
        UUID receiverUuid = receiver.getUniqueId();
        List<FriendRequest> requests = pendingRequests.get(receiverUuid);
        
        if (requests == null || requests.isEmpty()) {
            receiver.sendMessage("§c没有来自 " + senderName + " 的好友请求！");
            return false;
        }
        
        FriendRequest request = null;
        for (FriendRequest req : requests) {
            if (req.getSenderName().equalsIgnoreCase(senderName)) {
                request = req;
                break;
            }
        }
        
        if (request == null) {
            receiver.sendMessage("§c好友请求不存在！");
            return false;
        }
        
        requests.remove(request);
        
        receiver.sendMessage(config.getRequestDeniedMessage()
            .replace("{PLAYER}", senderName)
            .replace("&", "§"));
        
        return true;
    }
    
    /**
     * Add a friend to database.
     */
    private void addFriend(UUID playerUuid, UUID friendUuid, String friendName) {
        FriendshipData friendship = FriendshipData.create(playerUuid, friendUuid, friendName);
        dataOperator.insert(friendship);
    }
    
    /**
     * Remove a friend.
     */
    public boolean removeFriend(Player player, String friendName) {
        UUID playerUuid = player.getUniqueId();
        List<FriendshipData> friends = getFriends(playerUuid);
        
        FriendshipData toRemove = null;
        for (FriendshipData friend : friends) {
            if (friend.getFriendName().equalsIgnoreCase(friendName)) {
                toRemove = friend;
                break;
            }
        }
        
        if (toRemove == null) {
            player.sendMessage("§c" + friendName + " 不是你的好友！");
            return false;
        }
        
        // Remove bidirectional
        dataOperator.delById(toRemove.getId());
        
        // Remove reverse friendship
        dataOperator.query()
            .where("player_uuid").eq(toRemove.getFriendUuid())
            .where("friend_uuid").eq(playerUuid.toString())
            .delete();
        
        // Clear cache
        friendCache.remove(playerUuid);
        friendCache.remove(UUID.fromString(toRemove.getFriendUuid()));
        
        player.sendMessage(config.getFriendRemovedMessage()
            .replace("{PLAYER}", friendName)
            .replace("&", "§"));
        
        return true;
    }
    
    /**
     * Get all friends for a player.
     */
    public List<FriendshipData> getFriends(UUID playerUuid) {
        if (friendCache.containsKey(playerUuid)) {
            return friendCache.get(playerUuid);
        }

        List<FriendshipData> friends = dataOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();

        // Sort by favorite, then by name
        friends.sort((a, b) -> {
            if (a.isFavorite() != b.isFavorite()) {
                return b.isFavorite() ? 1 : -1;
            }
            return a.getFriendName().compareToIgnoreCase(b.getFriendName());
        });

        friendCache.put(playerUuid, friends);
        return friends;
    }
    
    /**
     * Get friend count.
     */
    public int getFriendCount(UUID playerUuid) {
        return getFriends(playerUuid).size();
    }
    
    /**
     * Check if two players are friends.
     */
    public boolean areFriends(UUID player1, UUID player2) {
        List<FriendshipData> friends = getFriends(player1);
        for (FriendshipData friend : friends) {
            if (friend.getFriendUuid().equals(player2.toString())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get pending requests for a player.
     */
    public List<FriendRequest> getPendingRequests(UUID playerUuid) {
        List<FriendRequest> requests = pendingRequests.get(playerUuid);
        if (requests == null) {
            return Collections.emptyList();
        }
        
        // Filter out expired
        requests.removeIf(req -> req.isExpired(config.getRequestTimeout()));
        return requests;
    }
    
    /**
     * Toggle favorite status.
     */
    public void toggleFavorite(UUID playerUuid, String friendName) {
        List<FriendshipData> friends = getFriends(playerUuid);
        for (FriendshipData friend : friends) {
            if (friend.getFriendName().equalsIgnoreCase(friendName)) {
                friend.setFavorite(!friend.isFavorite());
                try {
                    dataOperator.update(friend);
                } catch (IllegalAccessException e) {
                    plugin.getLogger().error("Failed to update friend data", e);
                }
                friendCache.remove(playerUuid);
                break;
            }
        }
    }
    
    /**
     * Set nickname for a friend.
     */
    public void setNickname(UUID playerUuid, String friendName, String nickname) {
        List<FriendshipData> friends = getFriends(playerUuid);
        for (FriendshipData friend : friends) {
            if (friend.getFriendName().equalsIgnoreCase(friendName)) {
                friend.setNickname(nickname);
                try {
                    dataOperator.update(friend);
                } catch (IllegalAccessException e) {
                    plugin.getLogger().error("Failed to update friend data", e);
                }
                friendCache.remove(playerUuid);
                break;
            }
        }
    }
    
    /**
     * Check teleport cooldown.
     */
    public boolean canTeleport(UUID playerUuid) {
        Long lastTp = tpCooldowns.get(playerUuid);
        if (lastTp == null) {
            return true;
        }
        return System.currentTimeMillis() - lastTp > config.getTpCooldown() * 1000L;
    }
    
    /**
     * Set teleport cooldown.
     */
    public void setTpCooldown(UUID playerUuid) {
        tpCooldowns.put(playerUuid, System.currentTimeMillis());
    }
    
    /**
     * Get remaining cooldown in seconds.
     */
    public int getRemainingCooldown(UUID playerUuid) {
        Long lastTp = tpCooldowns.get(playerUuid);
        if (lastTp == null) {
            return 0;
        }
        long remaining = (config.getTpCooldown() * 1000L) - (System.currentTimeMillis() - lastTp);
        return Math.max(0, (int) (remaining / 1000));
    }
    
    /**
     * Clear cache for player.
     */
    public void clearCache(UUID playerUuid) {
        friendCache.remove(playerUuid);
        blacklistCache.remove(playerUuid);
    }
    
    // ==================== Blacklist Methods ====================
    
    /**
     * Add a player to blacklist.
     * This will also remove any existing friendship between the two players.
     *
     * @param blocker The player who is blocking
     * @param blocked The player being blocked
     * @param reason Optional reason for blocking
     * @return true if successfully blocked
     */
    public boolean addToBlacklist(Player blocker, Player blocked, String reason) {
        return addToBlacklist(blocker.getUniqueId(), blocked.getUniqueId(), blocked.getName(), reason);
    }
    
    /**
     * Add a player to blacklist by UUID.
     *
     * @param blockerUuid UUID of the player who is blocking
     * @param blockedUuid UUID of the player being blocked
     * @param blockedName Name of the player being blocked
     * @param reason Optional reason for blocking
     * @return true if successfully blocked
     */
    public boolean addToBlacklist(UUID blockerUuid, UUID blockedUuid, String blockedName, String reason) {
        // Check if already blocked
        if (isBlockedBy(blockerUuid, blockedUuid)) {
            return false;
        }
        
        // Remove friendship if exists (bidirectional)
        removeFriendByUuid(blockerUuid, blockedUuid);
        
        // Create blacklist entry
        BlacklistData blacklist = BlacklistData.create(blockerUuid, blockedUuid, blockedName, reason);
        blacklistDataOperator.insert(blacklist);
        
        // Clear cache
        blacklistCache.remove(blockerUuid);
        
        return true;
    }
    
    /**
     * Remove a player from blacklist.
     *
     * @param blocker The player who blocked
     * @param blockedName Name of the blocked player
     * @return true if successfully unblocked
     */
    public boolean removeFromBlacklist(Player blocker, String blockedName) {
        UUID blockerUuid = blocker.getUniqueId();
        List<BlacklistData> blacklist = getBlacklist(blockerUuid);
        
        BlacklistData toRemove = null;
        for (BlacklistData entry : blacklist) {
            if (entry.getBlockedName().equalsIgnoreCase(blockedName)) {
                toRemove = entry;
                break;
            }
        }
        
        if (toRemove == null) {
            return false;
        }
        
        blacklistDataOperator.delById(toRemove.getId());
        blacklistCache.remove(blockerUuid);
        
        return true;
    }
    
    /**
     * Remove a player from blacklist by UUID.
     *
     * @param blockerUuid UUID of the player who blocked
     * @param blockedUuid UUID of the blocked player
     * @return true if successfully unblocked
     */
    public boolean removeFromBlacklist(UUID blockerUuid, UUID blockedUuid) {
        boolean exists = blacklistDataOperator.query()
            .where("player_uuid").eq(blockerUuid.toString())
            .where("blocked_uuid").eq(blockedUuid.toString())
            .exists();

        if (!exists) {
            return false;
        }

        blacklistDataOperator.query()
            .where("player_uuid").eq(blockerUuid.toString())
            .where("blocked_uuid").eq(blockedUuid.toString())
            .delete();

        blacklistCache.remove(blockerUuid);
        return true;
    }
    
    /**
     * Check if player1 has blocked player2 (single direction).
     *
     * @param blockerUuid UUID of potential blocker
     * @param blockedUuid UUID of potentially blocked player
     * @return true if player1 has blocked player2
     */
    public boolean isBlockedBy(UUID blockerUuid, UUID blockedUuid) {
        List<BlacklistData> blacklist = getBlacklist(blockerUuid);
        for (BlacklistData entry : blacklist) {
            if (entry.getBlockedUuid().equals(blockedUuid.toString())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if there is any block between two players (bidirectional).
     * Returns true if either player has blocked the other.
     *
     * @param uuid1 First player UUID
     * @param uuid2 Second player UUID
     * @return true if either player has blocked the other
     */
    public boolean isBlocked(UUID uuid1, UUID uuid2) {
        return isBlockedBy(uuid1, uuid2) || isBlockedBy(uuid2, uuid1);
    }
    
    /**
     * Get blacklist for a player.
     *
     * @param playerUuid UUID of the player
     * @return List of blocked players
     */
    public List<BlacklistData> getBlacklist(UUID playerUuid) {
        if (blacklistCache.containsKey(playerUuid)) {
            return blacklistCache.get(playerUuid);
        }

        List<BlacklistData> blacklist = blacklistDataOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();

        // Sort by time descending
        blacklist.sort((a, b) -> Long.compare(b.getCreatedTime(), a.getCreatedTime()));

        blacklistCache.put(playerUuid, blacklist);
        return blacklist;
    }
    
    /**
     * Get blacklist count for a player.
     *
     * @param playerUuid UUID of the player
     * @return Number of blocked players
     */
    public int getBlacklistCount(UUID playerUuid) {
        return getBlacklist(playerUuid).size();
    }
    
    /**
     * Remove friendship by UUID (internal helper for blacklist).
     */
    private void removeFriendByUuid(UUID playerUuid, UUID friendUuid) {
        // Remove from player's list
        dataOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .where("friend_uuid").eq(friendUuid.toString())
            .delete();

        // Remove from friend's list (reverse)
        dataOperator.query()
            .where("player_uuid").eq(friendUuid.toString())
            .where("friend_uuid").eq(playerUuid.toString())
            .delete();

        // Clear caches
        friendCache.remove(playerUuid);
        friendCache.remove(friendUuid);
    }
    
    public SocialConfig getConfig() {
        return config;
    }
}
