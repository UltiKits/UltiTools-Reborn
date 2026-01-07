package com.ultikits.plugins.worlds.listener;

import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.plugins.worlds.gui.WorldListGUI;
import com.ultikits.plugins.worlds.service.WorldService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

/**
 * Listener for world events.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class WorldListener implements Listener {
    
    @Autowired
    private WorldService worldService;
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WorldListGUI)) {
            return;
        }
        
        event.setCancelled(true);
        
        WorldListGUI gui = (WorldListGUI) event.getInventory().getHolder();
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        
        // Navigation
        if (slot == 45) {
            gui.previousPage();
            return;
        }
        if (slot == 53) {
            gui.nextPage();
            return;
        }
        
        // World item click
        if (slot >= 0 && slot < 45) {
            World world = gui.getWorldAtSlot(slot);
            if (world != null) {
                player.closeInventory();
                worldService.teleportToWorld(player, world.getName());
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        if (world == null) return;
        
        WorldSettings settings = worldService.getOrCreateSettings(world.getName());
        
        switch (event.getEntityType()) {
            case ZOMBIE:
            case SKELETON:
            case SPIDER:
            case CREEPER:
            case ENDERMAN:
            case WITCH:
            case SLIME:
            case PHANTOM:
            case DROWNED:
                if (!settings.isMonstersEnabled()) {
                    event.setCancelled(true);
                }
                break;
            case COW:
            case PIG:
            case SHEEP:
            case CHICKEN:
            case HORSE:
            case RABBIT:
            case WOLF:
            case CAT:
                if (!settings.isAnimalsEnabled()) {
                    event.setCancelled(true);
                }
                break;
            default:
                break;
        }
    }
    
    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        World world = event.getWorld();
        WorldSettings settings = worldService.getOrCreateSettings(world.getName());
        
        if (!settings.isWeatherEnabled() && event.toWeatherState()) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        // Apply world PVP settings
        Player player = event.getPlayer();
        World world = player.getWorld();
        WorldSettings settings = worldService.getOrCreateSettings(world.getName());
        
        world.setPVP(settings.isPvpEnabled());
    }
}
