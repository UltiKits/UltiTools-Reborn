package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for remote bag operations.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class RemoteBagService {
    
    @Autowired
    private RemoteBagConfig config;
    
    private DataOperator<RemoteBagData> dataOperator;
    
    // Cache for player bags - Map<PlayerUUID, Map<PageNumber, ItemStack[]>>
    private final Map<UUID, Map<Integer, ItemStack[]>> bagCache = new ConcurrentHashMap<>();
    
    // Track currently open bags - Map<PlayerUUID, CurrentPage>
    private final Map<UUID, Integer> openBags = new ConcurrentHashMap<>();
    
    /**
     * Initialize the service.
     */
    public void init() {
        this.dataOperator = UltiRemoteBag.getInstance().getDataOperator(RemoteBagData.class);
        
        // Start auto-save task
        if (config.getAutoSaveInterval() > 0) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(
                UltiTools.getInstance(),
                this::saveAllBags,
                config.getAutoSaveInterval() * 20L,
                config.getAutoSaveInterval() * 20L
            );
        }
    }
    
    /**
     * Get number of pages a player has access to.
     */
    public int getPlayerMaxPages(Player player) {
        if (!config.isPermissionBasedPages()) {
            return config.getMaxPages();
        }
        
        for (int i = config.getMaxPages(); i >= 1; i--) {
            if (player.hasPermission(config.getPermissionPrefix() + i)) {
                return i;
            }
        }
        
        return config.getDefaultPages();
    }
    
    /**
     * Open remote bag for a player.
     */
    public void openBag(Player player, int page) {
        int maxPages = getPlayerMaxPages(player);
        
        if (page < 1) page = 1;
        if (page > maxPages) {
            player.sendMessage(config.getPageLockedMessage()
                .replace("{PAGE}", String.valueOf(page))
                .replace("&", "§"));
            return;
        }
        
        // Load bag if not cached
        loadBagIfNeeded(player.getUniqueId());
        
        // Create inventory
        int size = config.getRowsPerPage() * 9;
        String title = config.getGuiTitle()
            .replace("{PAGE}", String.valueOf(page))
            .replace("{MAX}", String.valueOf(maxPages))
            .replace("&", "§");
        
        Inventory inv = Bukkit.createInventory(null, size + 9, title); // +9 for navigation row
        
        // Fill contents
        ItemStack[] contents = getBagPage(player.getUniqueId(), page);
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, size); i++) {
                if (contents[i] != null) {
                    inv.setItem(i, contents[i]);
                }
            }
        }
        
        // Navigation row
        addNavigationRow(inv, size, page, maxPages);
        
        // Track open bag
        openBags.put(player.getUniqueId(), page);
        
        player.openInventory(inv);
    }
    
    /**
     * Add navigation row to inventory.
     */
    private void addNavigationRow(Inventory inv, int startSlot, int currentPage, int maxPages) {
        // Previous page button
        if (currentPage > 1) {
            ItemStack prev = createNavigationItem(Material.ARROW, "§a上一页", "§7点击翻到第 " + (currentPage - 1) + " 页");
            inv.setItem(startSlot, prev);
        } else {
            ItemStack disabled = createNavigationItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
            inv.setItem(startSlot, disabled);
        }
        
        // Page indicator
        ItemStack indicator = createNavigationItem(Material.BOOK, 
            "§e第 " + currentPage + " / " + maxPages + " 页",
            "§7左键: 上一页", "§7右键: 下一页");
        inv.setItem(startSlot + 4, indicator);
        
        // Next page button
        if (currentPage < maxPages) {
            ItemStack next = createNavigationItem(Material.ARROW, "§a下一页", "§7点击翻到第 " + (currentPage + 1) + " 页");
            inv.setItem(startSlot + 8, next);
        } else {
            ItemStack disabled = createNavigationItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
            inv.setItem(startSlot + 8, disabled);
        }
        
        // Fill rest with glass
        for (int i = 1; i < 4; i++) {
            inv.setItem(startSlot + i, createNavigationItem(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        }
        for (int i = 5; i < 8; i++) {
            inv.setItem(startSlot + i, createNavigationItem(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        }
    }
    
    /**
     * Create a navigation item.
     */
    private ItemStack createNavigationItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && lore.length > 0 && lore[0] != null) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * Load bag from database if not in cache.
     */
    private void loadBagIfNeeded(UUID playerUuid) {
        if (bagCache.containsKey(playerUuid)) {
            return;
        }
        
        Map<Integer, ItemStack[]> pages = new HashMap<>();
        
        List<RemoteBagData> data = dataOperator.getAll(
            com.ultikits.ultitools.entities.WhereCondition.builder()
                .column("player_uuid")
                .value(playerUuid.toString())
                .build()
        );
        
        for (RemoteBagData bagData : data) {
            ItemStack[] items = deserializeItems(bagData.getContents());
            pages.put(bagData.getPageNumber(), items);
        }
        
        bagCache.put(playerUuid, pages);
    }
    
    /**
     * Get a specific bag page.
     */
    public ItemStack[] getBagPage(UUID playerUuid, int page) {
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null) {
            return null;
        }
        return pages.get(page);
    }
    
    /**
     * Set contents of a bag page.
     */
    public void setBagPage(UUID playerUuid, int page, ItemStack[] contents) {
        bagCache.computeIfAbsent(playerUuid, k -> new HashMap<>()).put(page, contents);
    }
    
    /**
     * Save bag to database.
     */
    public void saveBag(UUID playerUuid) {
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null) {
            return;
        }
        
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            String contents = serializeItems(entry.getValue());
            
            // Check if exists
            List<RemoteBagData> existing = dataOperator.getAll(
                com.ultikits.ultitools.entities.WhereCondition.builder()
                    .column("player_uuid")
                    .value(playerUuid.toString())
                    .build(),
                com.ultikits.ultitools.entities.WhereCondition.builder()
                    .column("page_number")
                    .value(entry.getKey())
                    .build()
            );
            
            if (existing.isEmpty()) {
                dataOperator.insert(RemoteBagData.create(playerUuid, entry.getKey(), contents));
            } else {
                RemoteBagData data = existing.get(0);
                data.setContents(contents);
                data.setLastUpdated(System.currentTimeMillis());
                try {
                    dataOperator.update(data);
                } catch (IllegalAccessException e) {
                    UltiRemoteBag.getInstance().getLogger().error("Failed to update bag data", e);
                }
            }
        }
    }
    
    /**
     * Save all bags in cache.
     */
    public void saveAllBags() {
        for (UUID playerUuid : bagCache.keySet()) {
            saveBag(playerUuid);
        }
    }
    
    /**
     * Handle bag close.
     */
    public void closeBag(Player player, Inventory inv) {
        UUID playerUuid = player.getUniqueId();
        Integer page = openBags.remove(playerUuid);
        
        if (page != null && config.isSaveOnClose()) {
            // Save contents (excluding navigation row)
            int size = config.getRowsPerPage() * 9;
            ItemStack[] contents = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                contents[i] = inv.getItem(i);
            }
            setBagPage(playerUuid, page, contents);
            saveBag(playerUuid);
        }
    }
    
    /**
     * Get current page of open bag.
     */
    public Integer getCurrentPage(UUID playerUuid) {
        return openBags.get(playerUuid);
    }
    
    /**
     * Check if player has bag open.
     */
    public boolean hasBagOpen(UUID playerUuid) {
        return openBags.containsKey(playerUuid);
    }
    
    /**
     * Serialize items to YAML string.
     */
    private String serializeItems(ItemStack[] items) {
        if (items == null) return "";
        
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                yaml.set("items." + i, items[i]);
            }
        }
        return yaml.saveToString();
    }
    
    /**
     * Deserialize items from YAML string.
     */
    private ItemStack[] deserializeItems(String data) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[config.getRowsPerPage() * 9];
        }
        
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);
            
            ItemStack[] items = new ItemStack[config.getRowsPerPage() * 9];
            if (yaml.isConfigurationSection("items")) {
                for (String key : yaml.getConfigurationSection("items").getKeys(false)) {
                    int slot = Integer.parseInt(key);
                    items[slot] = yaml.getItemStack("items." + key);
                }
            }
            return items;
        } catch (Exception e) {
            e.printStackTrace();
            return new ItemStack[config.getRowsPerPage() * 9];
        }
    }
    
    /**
     * Clear cache for a player.
     */
    public void clearCache(UUID playerUuid) {
        bagCache.remove(playerUuid);
        openBags.remove(playerUuid);
    }
    
    public RemoteBagConfig getConfig() {
        return config;
    }
}
