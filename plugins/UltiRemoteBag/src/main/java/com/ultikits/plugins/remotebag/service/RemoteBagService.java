package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.utils.EconomyUtils;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for remote bag operations.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class RemoteBagService {

    private final UltiToolsPlugin plugin;
    private final RemoteBagConfig config;

    private DataOperator<RemoteBagData> dataOperator;

    // Cache for player bags - Map<PlayerUUID, Map<PageNumber, ItemStack[]>>
    private final Map<UUID, Map<Integer, ItemStack[]>> bagCache = new ConcurrentHashMap<>();

    public RemoteBagService(UltiToolsPlugin plugin, RemoteBagConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Initialize the service.
     */
    public void init() {
        this.dataOperator = plugin.getDataOperator(RemoteBagData.class);
    }

    /**
     * Auto-save all bags task.
     * Runs every 300 seconds (6000 ticks) if auto-save is enabled in config.
     * Note: Period is fixed at 300 seconds. Adjust config.auto_save_interval to 300 or disable (0).
     */
    @Scheduled(period = 6000) // 300 seconds * 20 ticks = 6000 ticks
    public void autoSaveTask() {
        saveAllBags();
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
     * Load bag from database if not in cache.
     *
     * @param playerUuid 玩家 UUID
     */
    public void loadBagIfNeeded(UUID playerUuid) {
        if (bagCache.containsKey(playerUuid)) {
            return;
        }

        Map<Integer, ItemStack[]> pages = new HashMap<>();

        List<RemoteBagData> data = dataOperator.query()
                .where("player_uuid").eq(playerUuid.toString())
                .list();

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
            List<RemoteBagData> existing = dataOperator.query()
                    .where("player_uuid").eq(playerUuid.toString())
                    .where("page_number").eq(entry.getKey())
                    .list();

            if (existing.isEmpty()) {
                dataOperator.insert(RemoteBagData.create(playerUuid, entry.getKey(), contents));
            } else {
                RemoteBagData data = existing.get(0);
                data.setContents(contents);
                data.setLastUpdated(System.currentTimeMillis());
                try {
                    dataOperator.update(data);
                } catch (IllegalAccessException e) {
                    plugin.getLogger().error("Failed to update bag data", e);
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
     * 
     * @param playerUuid 玩家 UUID
     */
    public void clearCache(UUID playerUuid) {
        bagCache.remove(playerUuid);
    }
    
    public RemoteBagConfig getConfig() {
        return config;
    }
    
    // ==================== GUI 支持方法 ====================
    
    /**
     * 获取玩家拥有的所有背包页码列表
     *
     * @param playerUuid 玩家 UUID
     * @return 背包页码列表（已排序）
     */
    public List<Integer> getPlayerBagPages(UUID playerUuid) {
        loadBagIfNeeded(playerUuid);
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || pages.isEmpty()) {
            // 如果没有任何背包，返回默认的第一页
            return Collections.singletonList(1);
        }
        return pages.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
    }
    
    /**
     * 获取指定背包页的物品总数量
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 物品总数量（所有堆叠物品的数量总和）
     */
    public int getItemCount(UUID playerUuid, int page) {
        ItemStack[] contents = getBagPage(playerUuid, page);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                count += item.getAmount();
            }
        }
        return count;
    }
    
    /**
     * 获取指定背包页占用的槽位数量
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 占用槽位数量
     */
    public int getStackCount(UUID playerUuid, int page) {
        ItemStack[] contents = getBagPage(playerUuid, page);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 计算购买第 N 个背包的价格
     * <p>
     * 如果启用价格递增，公式为：basePrice * (1 + priceIncreaseRate)^(n-1)
     *
     * @param bagNumber 背包编号（第几个背包）
     * @return 购买价格
     */
    public int calculatePrice(int bagNumber) {
        int basePrice = config.getBasePrice();
        if (!config.isPriceIncreaseEnabled() || bagNumber <= 1) {
            return basePrice;
        }
        double rate = config.getPriceIncreaseRate();
        double multiplier = Math.pow(1 + rate, bagNumber - 1);
        return (int) Math.ceil(basePrice * multiplier);
    }
    
    /**
     * 玩家购买新背包
     *
     * @param player 玩家
     * @return 购买是否成功
     */
    public boolean purchaseBag(Player player) {
        if (!config.isEconomyEnabled() || !EconomyUtils.isAvailable()) {
            // 经济系统未启用，直接创建背包
            return createNewBagPage(player);
        }
        
        List<Integer> existingPages = getPlayerBagPages(player.getUniqueId());
        int nextBagNum = existingPages.size() + 1;
        
        // 检查是否超过上限
        int maxPages = getPlayerMaxPages(player);
        if (nextBagNum > maxPages) {
            return false;
        }
        
        int price = calculatePrice(nextBagNum);
        
        // 扣款
        if (!EconomyUtils.withdraw(player, price)) {
            return false;
        }
        
        // 创建新背包页
        return createNewBagPage(player);
    }
    
    /**
     * 为玩家创建新的背包页
     *
     * @param player 玩家
     * @return 是否成功
     */
    private boolean createNewBagPage(Player player) {
        UUID playerUuid = player.getUniqueId();
        loadBagIfNeeded(playerUuid);
        
        List<Integer> existingPages = getPlayerBagPages(playerUuid);
        int nextPage = existingPages.isEmpty() ? 1 : Collections.max(existingPages) + 1;
        
        // 检查是否超过上限
        int maxPages = getPlayerMaxPages(player);
        if (nextPage > maxPages) {
            return false;
        }
        
        // 创建空的背包页
        ItemStack[] emptyContents = new ItemStack[config.getRowsPerPage() * 9];
        setBagPage(playerUuid, nextPage, emptyContents);
        
        // 保存到数据库
        saveBag(playerUuid);
        
        return true;
    }
    
    // ==================== 管理员命令支持方法 ====================
    
    /**
     * 为指定玩家创建新的背包页（管理员操作）
     *
     * @param playerUuid 玩家 UUID
     * @return 新创建的背包页码，失败返回 -1
     */
    public int createBagPage(UUID playerUuid) {
        loadBagIfNeeded(playerUuid);
        
        List<Integer> existingPages = getPlayerBagPages(playerUuid);
        int nextPage = existingPages.isEmpty() || (existingPages.size() == 1 && existingPages.get(0) == 1) 
                ? (existingPages.isEmpty() ? 1 : Collections.max(existingPages) + 1)
                : Collections.max(existingPages) + 1;
        
        // 创建空的背包页
        ItemStack[] emptyContents = new ItemStack[config.getRowsPerPage() * 9];
        setBagPage(playerUuid, nextPage, emptyContents);
        
        // 保存到数据库
        saveBag(playerUuid);
        
        return nextPage;
    }
    
    /**
     * 删除指定玩家的背包页（管理员操作）
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 是否成功
     */
    public boolean deleteBagPage(UUID playerUuid, int page) {
        loadBagIfNeeded(playerUuid);
        
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || !pages.containsKey(page)) {
            return false;
        }
        
        // 从缓存中移除
        pages.remove(page);

        // 从数据库中删除
        List<RemoteBagData> existing = dataOperator.query()
                .where("player_uuid").eq(playerUuid.toString())
                .where("page_number").eq(page)
                .list();

        for (RemoteBagData data : existing) {
            dataOperator.delById(data.getId());
        }

        return true;
    }
    
    /**
     * 清空指定玩家的背包页内容（管理员操作）
     *
     * @param playerUuid 玩家 UUID
     * @param page       背包页码
     * @return 是否成功
     */
    public boolean clearBagPage(UUID playerUuid, int page) {
        loadBagIfNeeded(playerUuid);
        
        Map<Integer, ItemStack[]> pages = bagCache.get(playerUuid);
        if (pages == null || !pages.containsKey(page)) {
            return false;
        }
        
        // 创建空的内容
        ItemStack[] emptyContents = new ItemStack[config.getRowsPerPage() * 9];
        setBagPage(playerUuid, page, emptyContents);
        
        // 保存到数据库
        saveBag(playerUuid);
        
        return true;
    }
}
