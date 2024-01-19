package com.ultikits.plugins.basic.listeners;

import com.ultikits.plugins.basic.services.BanPlayerService;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.springframework.beans.factory.annotation.Autowired;

@EventListener(manualRegister = true)
public class BanListener implements Listener {
    @Autowired
    private BanPlayerService banPlayerService;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (banPlayerService.isBaned(event.getPlayer())) {
            event.getPlayer().kickPlayer("你已被封禁");
        }
    }
}
