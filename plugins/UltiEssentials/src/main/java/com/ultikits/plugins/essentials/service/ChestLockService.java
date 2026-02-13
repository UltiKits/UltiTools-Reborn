package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.ChestLockData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

import javax.annotation.Nullable;
import com.ultikits.ultitools.annotations.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing chest locks.
 * <p>
 * 管理箱子锁的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class ChestLockService {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private EssentialsConfig config;

    private DataOperator<ChestLockData> lockOperator;

    // Cache for quick lookups
    private final Map<String, ChestLockData> lockCache = new ConcurrentHashMap<>();
    
    // Lockable block types
    private static final Set<Material> LOCKABLE_BLOCKS = new HashSet<>(Arrays.asList(
        Material.CHEST,
        Material.TRAPPED_CHEST,
        Material.BARREL,
        Material.SHULKER_BOX,
        Material.WHITE_SHULKER_BOX,
        Material.ORANGE_SHULKER_BOX,
        Material.MAGENTA_SHULKER_BOX,
        Material.LIGHT_BLUE_SHULKER_BOX,
        Material.YELLOW_SHULKER_BOX,
        Material.LIME_SHULKER_BOX,
        Material.PINK_SHULKER_BOX,
        Material.GRAY_SHULKER_BOX,
        Material.LIGHT_GRAY_SHULKER_BOX,
        Material.CYAN_SHULKER_BOX,
        Material.PURPLE_SHULKER_BOX,
        Material.BLUE_SHULKER_BOX,
        Material.BROWN_SHULKER_BOX,
        Material.GREEN_SHULKER_BOX,
        Material.RED_SHULKER_BOX,
        Material.BLACK_SHULKER_BOX,
        Material.FURNACE,
        Material.BLAST_FURNACE,
        Material.SMOKER,
        Material.HOPPER,
        Material.DROPPER,
        Material.DISPENSER,
        Material.BREWING_STAND
    ));
    
    /**
     * Initializes the service.
     * Automatically called by the IoC container after construction.
     */
    @PostConstruct
    public void init() {
        this.lockOperator = plugin.getDataOperator(ChestLockData.class);
        loadCache();
    }
    
    /**
     * Loads all locks into cache.
     */
    private void loadCache() {
        lockCache.clear();
        List<ChestLockData> allLocks = lockOperator.getAll();
        for (ChestLockData lock : allLocks) {
            lockCache.put(lock.getLocationKey(), lock);
        }
        log.info("Loaded {} chest locks into cache", lockCache.size());
    }
    
    /**
     * Checks if a block type is lockable.
     */
    public boolean isLockable(Material material) {
        return LOCKABLE_BLOCKS.contains(material);
    }
    
    /**
     * Locks a block.
     */
    public LockResult lockBlock(Block block, Player player) {
        if (!config.isChestLockEnabled()) {
            return LockResult.DISABLED;
        }
        
        if (!isLockable(block.getType())) {
            return LockResult.NOT_LOCKABLE;
        }
        
        // Check if already locked
        ChestLockData existing = getLock(block.getLocation());
        if (existing != null) {
            if (existing.getOwnerUuid().equals(player.getUniqueId().toString())) {
                return LockResult.ALREADY_LOCKED_BY_YOU;
            } else {
                return LockResult.ALREADY_LOCKED;
            }
        }
        
        // Create lock
        ChestLockData lock = ChestLockData.builder()
            .uuid(UUID.randomUUID())
            .world(block.getWorld().getName())
            .x(block.getX())
            .y(block.getY())
            .z(block.getZ())
            .ownerUuid(player.getUniqueId().toString())
            .ownerName(player.getName())
            .createdAt(System.currentTimeMillis())
            .build();
        
        lockOperator.insert(lock);
        lockCache.put(lock.getLocationKey(), lock);
        
        // If it's a double chest, lock the other half too
        lockDoubleChestOther(block, player);
        
        return LockResult.SUCCESS;
    }
    
    /**
     * Locks the other half of a double chest.
     */
    private void lockDoubleChestOther(Block block, Player player) {
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            return;
        }
        
        if (!(block.getState() instanceof Chest)) {
            return;
        }
        
        Chest chest = (Chest) block.getState();
        InventoryHolder holder = chest.getInventory().getHolder();
        
        if (!(holder instanceof DoubleChest)) {
            return;
        }
        
        DoubleChest doubleChest = (DoubleChest) holder;
        Location left = ((Chest) doubleChest.getLeftSide()).getLocation();
        Location right = ((Chest) doubleChest.getRightSide()).getLocation();
        
        Location other = block.getLocation().equals(left) ? right : left;
        
        if (getLock(other) == null) {
            ChestLockData otherLock = ChestLockData.builder()
                .uuid(UUID.randomUUID())
                .world(other.getWorld().getName())
                .x(other.getBlockX())
                .y(other.getBlockY())
                .z(other.getBlockZ())
                .ownerUuid(player.getUniqueId().toString())
                .ownerName(player.getName())
                .createdAt(System.currentTimeMillis())
                .build();
            
            lockOperator.insert(otherLock);
            lockCache.put(otherLock.getLocationKey(), otherLock);
        }
    }
    
    /**
     * Unlocks a block.
     */
    public UnlockResult unlockBlock(Block block, Player player) {
        ChestLockData lock = getLock(block.getLocation());
        
        if (lock == null) {
            return UnlockResult.NOT_LOCKED;
        }
        
        // Check permission
        if (!lock.getOwnerUuid().equals(player.getUniqueId().toString())
                && !player.hasPermission("ultiessentials.lock.admin")) {
            return UnlockResult.NOT_OWNER;
        }
        
        // Remove lock
        lockOperator.delById(lock.getId());
        lockCache.remove(lock.getLocationKey());
        
        // If it's a double chest, unlock the other half too
        unlockDoubleChestOther(block);
        
        return UnlockResult.SUCCESS;
    }
    
    /**
     * Unlocks the other half of a double chest.
     */
    private void unlockDoubleChestOther(Block block) {
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            return;
        }
        
        if (!(block.getState() instanceof Chest)) {
            return;
        }
        
        Chest chest = (Chest) block.getState();
        InventoryHolder holder = chest.getInventory().getHolder();
        
        if (!(holder instanceof DoubleChest)) {
            return;
        }
        
        DoubleChest doubleChest = (DoubleChest) holder;
        Location left = ((Chest) doubleChest.getLeftSide()).getLocation();
        Location right = ((Chest) doubleChest.getRightSide()).getLocation();
        
        Location other = block.getLocation().equals(left) ? right : left;
        
        ChestLockData otherLock = getLock(other);
        if (otherLock != null) {
            lockOperator.delById(otherLock.getId());
            lockCache.remove(otherLock.getLocationKey());
        }
    }
    
    /**
     * Gets the lock for a location.
     */
    @Nullable
    public ChestLockData getLock(Location location) {
        String key = ChestLockData.createLocationKey(
            location.getWorld().getName(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
        return lockCache.get(key);
    }
    
    /**
     * Checks if a player can access a locked block.
     */
    public boolean canAccess(Location location, Player player) {
        ChestLockData lock = getLock(location);
        
        if (lock == null) {
            return true;
        }
        
        // Owner can always access
        if (lock.getOwnerUuid().equals(player.getUniqueId().toString())) {
            return true;
        }
        
        // Admin can access if config allows
        return config.isChestLockAdminBypass() && player.hasPermission("ultiessentials.lock.admin");
    }
    
    /**
     * Checks if a block is locked.
     */
    public boolean isLocked(Location location) {
        return getLock(location) != null;
    }
    
    /**
     * Removes a lock when block is broken (for cleanup).
     */
    public void onBlockBreak(Location location) {
        ChestLockData lock = getLock(location);
        if (lock != null) {
            lockOperator.delById(lock.getId());
            lockCache.remove(lock.getLocationKey());
        }
    }
    
    public enum LockResult {
        SUCCESS,
        NOT_LOCKABLE,
        ALREADY_LOCKED,
        ALREADY_LOCKED_BY_YOU,
        DISABLED
    }
    
    public enum UnlockResult {
        SUCCESS,
        NOT_LOCKED,
        NOT_OWNER
    }
}
