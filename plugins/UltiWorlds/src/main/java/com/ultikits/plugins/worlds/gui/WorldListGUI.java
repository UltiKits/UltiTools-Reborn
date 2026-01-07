package com.ultikits.plugins.worlds.gui;

import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.plugins.worlds.service.WorldService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * World list GUI.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class WorldListGUI implements InventoryHolder {
    
    private final WorldService worldService;
    private final Player viewer;
    private final Inventory inventory;
    private final List<World> worlds;
    private int currentPage = 0;
    
    private static final int ITEMS_PER_PAGE = 45;
    
    public WorldListGUI(WorldService worldService, Player viewer) {
        this.worldService = worldService;
        this.viewer = viewer;
        this.worlds = worldService.getVisibleWorlds();
        
        String title = worldService.getConfig().getGuiTitle().replace("&", "§");
        this.inventory = Bukkit.createInventory(this, 54, title);
        updateInventory();
    }
    
    /**
     * Update inventory contents.
     */
    public void updateInventory() {
        inventory.clear();
        
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, worlds.size());
        
        for (int i = start; i < end; i++) {
            World world = worlds.get(i);
            inventory.setItem(i - start, createWorldItem(world));
        }
        
        addNavigationRow();
    }
    
    /**
     * Create an item representing a world.
     */
    private ItemStack createWorldItem(World world) {
        WorldSettings settings = worldService.getOrCreateSettings(world.getName());
        
        Material icon;
        try {
            icon = Material.valueOf(settings.getIcon());
        } catch (Exception e) {
            icon = getDefaultIcon(world.getEnvironment());
        }
        
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            String displayName = settings.getDisplayName() != null ? 
                settings.getDisplayName() : world.getName();
            
            meta.setDisplayName(ChatColor.GREEN + displayName);
            
            List<String> lore = new ArrayList<>();
            
            if (settings.getDescription() != null && !settings.getDescription().isEmpty()) {
                lore.add(ChatColor.GRAY + settings.getDescription());
                lore.add("");
            }
            
            lore.add(ChatColor.GRAY + "环境: " + ChatColor.WHITE + getEnvironmentName(world.getEnvironment()));
            lore.add(ChatColor.GRAY + "玩家: " + ChatColor.WHITE + world.getPlayers().size());
            lore.add(ChatColor.GRAY + "时间: " + ChatColor.WHITE + getTimeOfDay(world));
            lore.add(ChatColor.GRAY + "天气: " + ChatColor.WHITE + getWeather(world));
            lore.add("");
            lore.add(ChatColor.GRAY + "PVP: " + (settings.isPvpEnabled() ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"));
            lore.add(ChatColor.GRAY + "怪物: " + (settings.isMonstersEnabled() ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"));
            
            if (settings.isLocked()) {
                lore.add("");
                lore.add(ChatColor.RED + "⚠ 此世界已锁定");
            }
            
            lore.add("");
            lore.add(ChatColor.YELLOW + "点击传送到此世界");
            
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return item;
    }
    
    /**
     * Get default icon for environment.
     */
    private Material getDefaultIcon(World.Environment environment) {
        switch (environment) {
            case NETHER:
                return Material.NETHERRACK;
            case THE_END:
                return Material.END_STONE;
            default:
                return Material.GRASS_BLOCK;
        }
    }
    
    /**
     * Get environment display name.
     */
    private String getEnvironmentName(World.Environment environment) {
        switch (environment) {
            case NORMAL:
                return "主世界";
            case NETHER:
                return "下界";
            case THE_END:
                return "末地";
            default:
                return environment.name();
        }
    }
    
    /**
     * Get time of day.
     */
    private String getTimeOfDay(World world) {
        long time = world.getTime();
        if (time < 6000) return "早晨";
        if (time < 12000) return "中午";
        if (time < 18000) return "傍晚";
        return "夜晚";
    }
    
    /**
     * Get weather.
     */
    private String getWeather(World world) {
        if (world.isThundering()) return "雷暴";
        if (world.hasStorm()) return "下雨";
        return "晴朗";
    }
    
    /**
     * Add navigation row.
     */
    private void addNavigationRow() {
        int totalPages = (int) Math.ceil((double) worlds.size() / ITEMS_PER_PAGE);
        if (totalPages == 0) totalPages = 1;
        
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
        
        if (currentPage > 0) {
            inventory.setItem(45, createItem(Material.ARROW, ChatColor.GREEN + "上一页"));
        }
        
        inventory.setItem(49, createItem(Material.BOOK, 
            ChatColor.YELLOW + "第 " + (currentPage + 1) + " / " + totalPages + " 页"));
        
        if (currentPage < totalPages - 1) {
            inventory.setItem(53, createItem(Material.ARROW, ChatColor.GREEN + "下一页"));
        }
    }
    
    /**
     * Create an item.
     */
    private ItemStack createItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * Get world at slot.
     */
    public World getWorldAtSlot(int slot) {
        if (slot < 0 || slot >= ITEMS_PER_PAGE) return null;
        
        int index = currentPage * ITEMS_PER_PAGE + slot;
        if (index >= worlds.size()) return null;
        
        return worlds.get(index);
    }
    
    public void nextPage() {
        int totalPages = (int) Math.ceil((double) worlds.size() / ITEMS_PER_PAGE);
        if (currentPage < totalPages - 1) {
            currentPage++;
            updateInventory();
        }
    }
    
    public void previousPage() {
        if (currentPage > 0) {
            currentPage--;
            updateInventory();
        }
    }
    
    public Player getViewer() {
        return viewer;
    }
    
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
