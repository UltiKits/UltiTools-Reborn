package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.commands.BackCommand;
import com.ultikits.plugins.essentials.commands.HideCommand;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener for cleaning up player data when they quit.
 */
@EventListener
public class PlayerQuitListener implements Listener {

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up BackCommand temporary data
        BackCommand.removePlayer(event.getPlayer().getUniqueId());

        // Clean up HideCommand vanish state
        HideCommand.removePlayer(event.getPlayer().getUniqueId());
    }
}
