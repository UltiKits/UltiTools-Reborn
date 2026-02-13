package com.ultikits.plugins.worlds.gui;

import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.plugins.worlds.service.WorldService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BasePaginationPage;

import mc.obliviate.inventory.Icon;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * World list GUI page using new BasePaginationPage framework.
 *
 * @author wisdomme
 * @version 2.0.0
 */
public class WorldListPage extends BasePaginationPage {
    
    private final WorldService worldService;
    private final UltiToolsPlugin plugin;

    public WorldListPage(Player player, WorldService worldService, UltiToolsPlugin plugin) {
        super(player, "world-list", plugin.i18n("gui.title"), 6);
        this.worldService = worldService;
        this.plugin = plugin;
    }
    
    @Override
    protected List<Icon> provideItems() {
        List<Icon> icons = new ArrayList<>();
        List<World> worlds = worldService.getVisibleWorlds();
        
        for (World world : worlds) {
            icons.add(createWorldIcon(world));
        }
        
        return icons;
    }
    
    /**
     * Create an icon representing a world.
     */
    private Icon createWorldIcon(World world) {
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
            
            meta.setDisplayName("§a" + displayName);
            
            List<String> lore = new ArrayList<>();
            
            if (settings.getDescription() != null && !settings.getDescription().isEmpty()) {
                lore.add("§7" + settings.getDescription());
                lore.add("");
            }
            
            lore.add(i18n("gui.environment") + " §f" + getEnvironmentName(world.getEnvironment()));
            lore.add(i18n("gui.players") + " §f" + world.getPlayers().size());
            lore.add(i18n("gui.time") + " §f" + getTimeOfDay(world));
            lore.add(i18n("gui.weather") + " §f" + getWeather(world));
            lore.add("");
            lore.add(i18n("gui.pvp") + (settings.isPvpEnabled() ? " §a" + i18n("common.enabled") : " §c" + i18n("common.disabled")));
            lore.add(i18n("gui.monsters") + (settings.isMonstersEnabled() ? " §a" + i18n("common.enabled") : " §c" + i18n("common.disabled")));
            
            if (settings.hasProtection()) {
                lore.add("");
                lore.add("§6⚠ " + i18n("gui.protected"));
            }
            
            if (settings.isLocked()) {
                lore.add("");
                lore.add("§c⚠ " + i18n("gui.locked"));
            }
            
            if (settings.isBlocked()) {
                lore.add("");
                lore.add("§4⛔ " + i18n("gui.blocked"));
            }
            
            lore.add("");
            lore.add("§e" + i18n("gui.click_to_tp"));
            
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        Icon worldIcon = new Icon(item);
        worldIcon.onClick(e -> {
            player.closeInventory();
            worldService.teleportToWorld(player, world.getName());
        });
        
        return worldIcon;
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
                return i18n("environment.normal");
            case NETHER:
                return i18n("environment.nether");
            case THE_END:
                return i18n("environment.the_end");
            default:
                return environment.name();
        }
    }
    
    /**
     * Get time of day.
     */
    private String getTimeOfDay(World world) {
        long time = world.getTime();
        if (time < 6000) return i18n("time.morning");
        if (time < 12000) return i18n("time.noon");
        if (time < 18000) return i18n("time.evening");
        return i18n("time.night");
    }
    
    /**
     * Get weather.
     */
    private String getWeather(World world) {
        if (world.isThundering()) return i18n("weather.thunder");
        if (world.hasStorm()) return i18n("weather.rain");
        return i18n("weather.clear");
    }
    
    /**
     * Get i18n message from plugin.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
