package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.ChestLockData;
import com.ultikits.plugins.essentials.service.ChestLockService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Listener for chest lock protection.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class ChestLockListener implements Listener {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private EssentialsConfig config;

    @Autowired
    private ChestLockService chestLockService;
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!config.isChestLockEnabled()) {
            return;
        }
        
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        
        if (!chestLockService.isLockable(block.getType())) {
            return;
        }
        
        Player player = event.getPlayer();
        
        if (!chestLockService.canAccess(block.getLocation(), player)) {
            event.setCancelled(true);
            
            ChestLockData lock = chestLockService.getLock(block.getLocation());
            if (lock != null) {
                player.sendMessage(plugin.i18n("§c该容器被 §f") + 
                    lock.getOwnerName() + plugin.i18n(" §c锁定"));
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.isChestLockEnabled()) {
            return;
        }
        
        Block block = event.getBlock();
        
        if (!chestLockService.isLocked(block.getLocation())) {
            return;
        }
        
        Player player = event.getPlayer();
        ChestLockData lock = chestLockService.getLock(block.getLocation());
        
        if (lock == null) {
            return;
        }
        
        // Check if player is owner or admin
        boolean isOwner = lock.getOwnerUuid().equals(player.getUniqueId().toString());
        boolean isAdmin = player.hasPermission("ultiessentials.lock.admin");
        
        if (!isOwner && !isAdmin) {
            event.setCancelled(true);
            player.sendMessage(plugin.i18n("§c该容器被 §f") + 
                lock.getOwnerName() + plugin.i18n(" §c锁定，无法破坏"));
            return;
        }
        
        // Remove lock when broken
        chestLockService.onBlockBreak(block.getLocation());
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!config.isChestLockEnabled()) {
            return;
        }
        
        // Remove locked blocks from explosion
        event.blockList().removeIf(block -> chestLockService.isLocked(block.getLocation()));
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!config.isChestLockEnabled()) {
            return;
        }
        
        // Remove locked blocks from explosion
        event.blockList().removeIf(block -> chestLockService.isLocked(block.getLocation()));
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!config.isChestLockEnabled()) {
            return;
        }
        
        for (Block block : event.getBlocks()) {
            if (chestLockService.isLocked(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!config.isChestLockEnabled()) {
            return;
        }
        
        for (Block block : event.getBlocks()) {
            if (chestLockService.isLocked(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!config.isChestLockEnabled()) {
            return;
        }
        
        // Prevent hopper from moving items from locked containers
        InventoryHolder source = event.getSource().getHolder();
        if (source instanceof org.bukkit.block.Container) {
            org.bukkit.block.Container container = (org.bukkit.block.Container) source;
            if (chestLockService.isLocked(container.getLocation())) {
                event.setCancelled(true);
            }
        }
    }
}
