package com.ultikits.plugins.social.listener;

import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.plugins.social.gui.FriendListGUI;
import com.ultikits.plugins.social.service.FriendService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

/**
 * Listener for social events.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class SocialListener implements Listener {
    
    @Autowired
    private FriendService friendService;
    
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
                online.sendMessage(friendService.getConfig().getFriendOnlineMessage()
                    .replace("{PLAYER}", player.getName())
                    .replace("&", "§"));
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
                online.sendMessage(friendService.getConfig().getFriendOfflineMessage()
                    .replace("{PLAYER}", player.getName())
                    .replace("&", "§"));
            }
        }
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FriendListGUI)) {
            return;
        }
        
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
            
            if (event.isLeftClick()) {
                if (event.isShiftClick()) {
                    // Toggle favorite
                    friendService.toggleFavorite(player.getUniqueId(), friend.getFriendName());
                    gui.refresh();
                    player.sendMessage(ChatColor.GREEN + "已更新好友收藏状态！");
                } else {
                    // Teleport to friend
                    if (friendService.getConfig().isTpToFriendEnabled()) {
                        Player target = Bukkit.getPlayer(UUID.fromString(friend.getFriendUuid()));
                        if (target != null) {
                            if (!friendService.canTeleport(player.getUniqueId())) {
                                int remaining = friendService.getRemainingCooldown(player.getUniqueId());
                                player.sendMessage(ChatColor.RED + "传送冷却中！请等待 " + remaining + " 秒");
                            } else {
                                player.closeInventory();
                                player.teleport(target.getLocation());
                                friendService.setTpCooldown(player.getUniqueId());
                                player.sendMessage(ChatColor.GREEN + "已传送到 " + friend.getFriendName() + " 身边！");
                            }
                        } else {
                            player.sendMessage(ChatColor.RED + friend.getFriendName() + " 不在线！");
                        }
                    }
                }
            } else if (event.isRightClick()) {
                // Remove friend
                player.closeInventory();
                friendService.removeFriend(player, friend.getFriendName());
            }
        }
    }
}
