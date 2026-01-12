package com.ultikits.plugins.social.listener;

import com.ultikits.plugins.social.entity.BlacklistData;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.plugins.social.gui.BlockListGUI;
import com.ultikits.plugins.social.gui.FriendListGUI;
import com.ultikits.plugins.social.service.FriendService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.services.TeleportService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Listener for social events.
 * Handles player join/quit notifications and GUI interactions.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@EventListener
public class SocialListener implements Listener {
    
    @Autowired
    private FriendService friendService;
    
    @Autowired(required = false)
    private NotificationService notificationService;
    
    @Autowired(required = false)
    private TeleportService teleportService;
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        if (!friendService.getConfig().isNotifyFriendOnline()) {
            return;
        }
        
        // Notify friends that player is online
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            
            if (friendService.areFriends(online.getUniqueId(), player.getUniqueId())) {
                String message = friendService.getConfig().getFriendOnlineMessage()
                    .replace("{PLAYER}", player.getName())
                    .replace("&", "§");
                
                // Use NotificationService if available
                if (notificationService != null) {
                    notificationService.sendMessageNotification(online, message);
                } else {
                    online.sendMessage(message);
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Clear cache
        friendService.clearCache(player.getUniqueId());
        
        if (!friendService.getConfig().isNotifyFriendOffline()) {
            return;
        }
        
        // Notify friends that player is offline
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            
            if (friendService.areFriends(online.getUniqueId(), player.getUniqueId())) {
                String message = friendService.getConfig().getFriendOfflineMessage()
                    .replace("{PLAYER}", player.getName())
                    .replace("&", "§");
                
                // Use NotificationService if available
                if (notificationService != null) {
                    notificationService.sendMessageNotification(online, message);
                } else {
                    online.sendMessage(message);
                }
            }
        }
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Handle FriendListGUI
        if (event.getInventory().getHolder() instanceof FriendListGUI) {
            handleFriendListClick(event);
            return;
        }
        
        // Handle BlockListGUI
        if (event.getInventory().getHolder() instanceof BlockListGUI) {
            handleBlockListClick(event);
            return;
        }
    }
    
    /**
     * Handle clicks in FriendListGUI.
     */
    private void handleFriendListClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        FriendListGUI gui = (FriendListGUI) event.getInventory().getHolder();
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        
        // Navigation buttons
        if (slot == 45) { // Previous page
            gui.previousPage();
            return;
        }
        if (slot == 53) { // Next page
            gui.nextPage();
            return;
        }
        if (slot == 47) { // Pending requests
            player.closeInventory();
            player.performCommand("friend requests");
            return;
        }
        
        // Friend item clicks
        if (slot >= 0 && slot < 45) {
            FriendshipData friend = gui.getFriendAtSlot(slot);
            if (friend == null) return;
            
            Player target = Bukkit.getPlayer(UUID.fromString(friend.getFriendUuid()));
            boolean online = target != null;
            
            if (event.isLeftClick()) {
                if (event.isShiftClick()) {
                    // Shift+Left: Toggle favorite
                    friendService.toggleFavorite(player.getUniqueId(), friend.getFriendName());
                    gui.refresh();
                    player.sendMessage(ChatColor.GREEN + "已更新好友收藏状态！");
                } else {
                    // Left: Teleport to friend (if online)
                    if (online && friendService.getConfig().isTpToFriendEnabled()) {
                        if (!friendService.canTeleport(player.getUniqueId())) {
                            int remaining = friendService.getRemainingCooldown(player.getUniqueId());
                            player.sendMessage(ChatColor.RED + "传送冷却中！请等待 " + remaining + " 秒");
                        } else {
                            player.closeInventory();
                            // Use TeleportService if available
                            if (teleportService != null) {
                                teleportService.teleport(player, target.getLocation());
                            } else {
                                player.teleport(target.getLocation());
                            }
                            friendService.setTpCooldown(player.getUniqueId());
                            player.sendMessage(ChatColor.GREEN + "已传送到 " + friend.getFriendName() + " 身边！");
                        }
                    } else if (!online) {
                        player.sendMessage(ChatColor.RED + friend.getFriendName() + " 不在线！");
                    }
                }
            } else if (event.isRightClick()) {
                if (event.isShiftClick()) {
                    // Shift+Right: Delete friend
                    player.closeInventory();
                    friendService.removeFriend(player, friend.getFriendName());
                } else {
                    // Right: Send message (if online) or delete (if offline)
                    if (online) {
                        player.closeInventory();
                        player.sendMessage(ChatColor.YELLOW + "请使用命令发送私聊: " + 
                            ChatColor.WHITE + "/friend msg " + friend.getFriendName() + " <消息>");
                    } else {
                        // Offline - delete friend
                        player.closeInventory();
                        friendService.removeFriend(player, friend.getFriendName());
                    }
                }
            }
        }
    }
    
    /**
     * Handle clicks in BlockListGUI.
     */
    private void handleBlockListClick(InventoryClickEvent event) {
        event.setCancelled(true);
        
        BlockListGUI gui = (BlockListGUI) event.getInventory().getHolder();
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        
        // Navigation buttons
        if (slot == 45) { // Previous page
            gui.previousPage();
            return;
        }
        if (slot == 53) { // Next page
            gui.nextPage();
            return;
        }
        if (slot == 47) { // Back to friend list
            player.closeInventory();
            FriendListGUI friendGui = new FriendListGUI(friendService, player);
            player.openInventory(friendGui.getInventory());
            return;
        }
        
        // Blocked user clicks
        if (slot >= 0 && slot < 45) {
            BlacklistData blocked = gui.getBlockedUserAtSlot(slot);
            if (blocked == null) return;
            
            if (event.isLeftClick()) {
                // Unblock
                if (friendService.removeFromBlacklist(player, blocked.getBlockedName())) {
                    player.sendMessage(ChatColor.GREEN + "已将 " + blocked.getBlockedName() + " 从黑名单移除");
                    gui.refresh();
                } else {
                    player.sendMessage(ChatColor.RED + "解除拉黑失败！");
                }
            }
        }
    }
}
