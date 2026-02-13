package com.ultikits.plugins.worlds.gui;

import com.ultikits.plugins.worlds.service.WorldService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Confirmation page for world deletion.
 *
 * @author wisdomme
 * @version 2.0.0
 */
public class WorldDeleteConfirmPage extends BaseConfirmationPage {
    
    private final WorldService worldService;
    private final UltiToolsPlugin plugin;
    private final String worldName;

    public WorldDeleteConfirmPage(Player player, WorldService worldService, String worldName, UltiToolsPlugin plugin) {
        super(player, "delete-" + worldName, plugin.i18n("gui.delete.title").replace("%world%", worldName), 3);
        this.worldService = worldService;
        this.plugin = plugin;
        this.worldName = worldName;
    }
    
    @Override
    protected void onConfirm(InventoryClickEvent event) {
        boolean success = worldService.deleteWorld(worldName);
        
        if (success) {
            player.sendMessage(i18n("command.delete.success").replace("%world%", worldName));
        } else {
            player.sendMessage(i18n("command.delete.failed").replace("%world%", worldName));
        }
    }
    
    @Override
    protected void onCancel(InventoryClickEvent event) {
        player.sendMessage(i18n("command.delete.cancelled"));
    }
    
    /**
     * Get i18n message from plugin.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
