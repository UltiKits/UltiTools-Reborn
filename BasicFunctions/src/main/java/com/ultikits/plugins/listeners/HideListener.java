package com.ultikits.plugins.listeners;

import com.ultikits.plugins.commands.HideCommands;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

@EventListener(manualRegister = true)
public class HideListener implements Listener {
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        HideCommands.removeHidePlayer(event.getPlayer().getUniqueId());
    }
}
