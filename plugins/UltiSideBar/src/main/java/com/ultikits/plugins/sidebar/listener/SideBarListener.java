package com.ultikits.plugins.sidebar.listener;

import com.ultikits.plugins.sidebar.service.SideBarService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener for sidebar events.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class SideBarListener implements Listener {
    
    @Autowired
    private SideBarService sideBarService;
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sideBarService.onPlayerJoin(event.getPlayer());
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sideBarService.onPlayerQuit(event.getPlayer());
    }
    
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        sideBarService.onWorldChange(event.getPlayer());
    }
}
