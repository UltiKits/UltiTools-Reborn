package com.ultikits.plugins.tpa.listeners;

import com.ultikits.plugins.tpa.services.TpaService;
import com.ultikits.ultitools.manager.PluginManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;

public class TpaListener implements Listener {
    private static final TpaService tpaService;

    static {
        Optional<TpaService> service = PluginManager.getService(TpaService.class);
        if (!service.isPresent()){
            throw new RuntimeException("TPA Service Not Found!");
        }
        tpaService = service.get();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        tpaService.removePlayer(event.getPlayer());
    }
}
