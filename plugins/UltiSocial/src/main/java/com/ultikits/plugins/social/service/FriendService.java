package com.ultikits.plugins.social.service;

import com.ultikits.plugins.social.UltiSocial;
import com.ultikits.plugins.social.config.SocialConfig;
import com.ultikits.plugins.social.entity.FriendRequest;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
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
    private SocialConfig config;
    
    private DataOperator<FriendshipData> dataOperator;
    
    // Pending friend requests - Map<ReceiverUUID, List<FriendRequest>>
    private final Map<UUID, List<FriendRequest>> pendingRequests = new ConcurrentHashMap<>();
    
    // Cache for friends - Map<PlayerUUID, List<FriendshipData>>
    private final Map<UUID, List<FriendshipData>> friendCache = new ConcurrentHashMap<>();
    
    // Teleport cooldowns - Map<PlayerUUID, LastTeleportTime>
    private final Map<UUID, Long> tpCooldowns = new ConcurrentHashMap<>();
    
    /**
     * Initialize the service.
     */
    public void init() {
        this.dataOperator = UltiSocial.getInstance().getDataOperator(FriendshipData.class);
        
        // Start cleanup task for expired requests
        Bukkit.getScheduler().runTaskTimerAsynchronously(
            UltiTools.getInstance(),
            this::cleanupExpiredRequests,
            20 * 60L,  // Every minute
            20 * 60L
        );
    }
    
    /**
     * Send a friend request.
     */
    public boolean sendRequest(Player sender, Player receiver) {
        UUID senderUuid = sender.getUniqueId();
        UUID receiverUuid = receiver.getUniqueId();
        
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
        dataOperator.delete(toRemove);
        
        // Remove reverse friendship
        List<FriendshipData> reverseFriends = dataOperator.getAll(
            WhereCondition.builder().column("player_uuid").value(toRemove.getFriendUuid()).build(),
            WhereCondition.builder().column("friend_uuid").value(playerUuid.toString()).build()
        );
        for (FriendshipData reverse : reverseFriends) {
            dataOperator.delete(reverse);
        }
        
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
        
        List<FriendshipData> friends = dataOperator.getAll(
            WhereCondition.builder()
                .column("player_uuid")
                .value(playerUuid.toString())
                .build()
        );
        
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
                dataOperator.update(friend);
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
                dataOperator.update(friend);
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
     * Cleanup expired requests.
     */
    private void cleanupExpiredRequests() {
        for (List<FriendRequest> requests : pendingRequests.values()) {
            requests.removeIf(req -> req.isExpired(config.getRequestTimeout()));
        }
    }
    
    /**
     * Clear cache for player.
     */
    public void clearCache(UUID playerUuid) {
        friendCache.remove(playerUuid);
    }
    
    public SocialConfig getConfig() {
        return config;
    }
}
