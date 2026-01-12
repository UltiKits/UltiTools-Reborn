package com.ultikits.plugins.social.gui;

import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.plugins.social.service.FriendService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Friend list GUI.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class FriendListGUI implements InventoryHolder {
    
    private final FriendService friendService;
    private final Player viewer;
    private final Inventory inventory;
    private final List<FriendshipData> friends;
    private int currentPage = 0;
    
    private static final int ITEMS_PER_PAGE = 45;
    
    public FriendListGUI(FriendService friendService, Player viewer) {
        this.friendService = friendService;
        this.viewer = viewer;
        this.friends = friendService.getFriends(viewer.getUniqueId());
        
        String title = friendService.getConfig().getGuiTitle()
            .replace("{COUNT}", String.valueOf(friends.size()))
            .replace("{MAX}", String.valueOf(friendService.getConfig().getMaxFriends()))
            .replace("&", "§");
        
        this.inventory = Bukkit.createInventory(this, 54, title);
        updateInventory();
    }
    
    /**
     * Update inventory contents.
     */
    public void updateInventory() {
        inventory.clear();
        
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, friends.size());
        
        for (int i = start; i < end; i++) {
            FriendshipData friend = friends.get(i);
            inventory.setItem(i - start, createFriendItem(friend));
        }
        
        // Navigation row
        addNavigationRow();
    }
    
    /**
     * Create an item representing a friend.
     */
    private ItemStack createFriendItem(FriendshipData friend) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        
        if (meta != null) {
            // Set skull owner
            Player onlineFriend = Bukkit.getPlayer(UUID.fromString(friend.getFriendUuid()));
            boolean online = onlineFriend != null;
            
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(friend.getFriendUuid())));
            
            // Display name
            String displayName = friend.getNickname() != null ? 
                friend.getNickname() + " §7(" + friend.getFriendName() + ")" :
                friend.getFriendName();
            
            if (friend.isFavorite()) {
                displayName = "§e★ " + displayName;
            }
            
            meta.setDisplayName((online ? ChatColor.GREEN : ChatColor.GRAY) + displayName);
            
            // Lore
            List<String> lore = new ArrayList<>();
            lore.add(online ? ChatColor.GREEN + "● 在线" : ChatColor.GRAY + "○ 离线");
            
            if (online && onlineFriend != null) {
                lore.add(ChatColor.GRAY + "世界: " + ChatColor.WHITE + onlineFriend.getWorld().getName());
                // Show game mode
                String gameMode = onlineFriend.getGameMode().name();
                String gameModeDisplay = formatGameMode(gameMode);
                lore.add(ChatColor.GRAY + "模式: " + ChatColor.WHITE + gameModeDisplay);
            }
            
            lore.add(ChatColor.GRAY + "添加时间: " + ChatColor.WHITE + formatTime(friend.getCreatedTime()));
            lore.add("");
            
            if (online && friendService.getConfig().isTpToFriendEnabled()) {
                lore.add(ChatColor.GREEN + "左键点击: 传送到好友");
            }
            if (online) {
                lore.add(ChatColor.AQUA + "右键点击: 发送私聊");
            } else {
                lore.add(ChatColor.RED + "右键点击: 删除好友");
            }
            lore.add(ChatColor.YELLOW + "Shift+左键: " + (friend.isFavorite() ? "取消收藏" : "收藏好友"));
            lore.add(ChatColor.RED + "Shift+右键: 删除好友");
            
            meta.setLore(lore);
            skull.setItemMeta(meta);
        }
        
        return skull;
    }
    
    /**
     * Format game mode for display.
     */
    private String formatGameMode(String gameMode) {
        switch (gameMode.toUpperCase()) {
            case "SURVIVAL": return "生存模式";
            case "CREATIVE": return "创造模式";
            case "ADVENTURE": return "冒险模式";
            case "SPECTATOR": return "旁观模式";
            default: return gameMode;
        }
    }
    
    /**
     * Add navigation row.
     */
    private void addNavigationRow() {
        int totalPages = (int) Math.ceil((double) friends.size() / ITEMS_PER_PAGE);
        if (totalPages == 0) totalPages = 1;
        
        // Fill bottom row with glass
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
        
        // Previous page
        if (currentPage > 0) {
            inventory.setItem(45, createItem(Material.ARROW, ChatColor.GREEN + "上一页"));
        }
        
        // Pending requests button
        int requestCount = friendService.getPendingRequests(viewer.getUniqueId()).size();
        if (requestCount > 0) {
            inventory.setItem(47, createItem(Material.WRITABLE_BOOK, 
                ChatColor.YELLOW + "待处理的好友请求 (" + requestCount + ")",
                ChatColor.GRAY + "点击查看"));
        }
        
        // Page indicator
        inventory.setItem(49, createItem(Material.BOOK, 
            ChatColor.YELLOW + "第 " + (currentPage + 1) + " / " + totalPages + " 页"));
        
        // Next page
        if (currentPage < totalPages - 1) {
            inventory.setItem(53, createItem(Material.ARROW, ChatColor.GREEN + "下一页"));
        }
    }
    
    /**
     * Create an item with name and lore.
     */
    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                List<String> loreList = new ArrayList<>();
                for (String line : lore) {
                    loreList.add(line);
                }
                meta.setLore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * Format timestamp.
     */
    private String formatTime(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new java.util.Date(timestamp));
    }
    
    /**
     * Get friend at slot.
     */
    public FriendshipData getFriendAtSlot(int slot) {
        if (slot < 0 || slot >= ITEMS_PER_PAGE) return null;
        
        int index = currentPage * ITEMS_PER_PAGE + slot;
        if (index >= friends.size()) return null;
        
        return friends.get(index);
    }
    
    /**
     * Go to next page.
     */
    public void nextPage() {
        int totalPages = (int) Math.ceil((double) friends.size() / ITEMS_PER_PAGE);
        if (currentPage < totalPages - 1) {
            currentPage++;
            updateInventory();
        }
    }
    
    /**
     * Go to previous page.
     */
    public void previousPage() {
        if (currentPage > 0) {
            currentPage--;
            updateInventory();
        }
    }
    
    /**
     * Refresh friends list.
     */
    public void refresh() {
        friendService.clearCache(viewer.getUniqueId());
        friends.clear();
        friends.addAll(friendService.getFriends(viewer.getUniqueId()));
        updateInventory();
    }
    
    public Player getViewer() {
        return viewer;
    }
    
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
