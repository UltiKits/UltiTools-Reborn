package com.ultikits.plugins.listeners;

import com.ultikits.plugins.services.BanPlayerService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class BanListener implements Listener {
    private BanPlayerService banPlayerService = new BanPlayerService();

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (banPlayerService.isBaned(event.getPlayer())) {
            event.getPlayer().kickPlayer("你已被封禁");
        }
    }
}
