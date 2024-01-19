package com.ultikits.plugins.basic.listeners;

import com.ultikits.plugins.basic.commands.HideCommands;
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
