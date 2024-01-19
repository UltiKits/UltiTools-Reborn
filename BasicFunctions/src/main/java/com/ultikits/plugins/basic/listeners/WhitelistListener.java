package com.ultikits.plugins.basic.listeners;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.services.WhiteListService;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.springframework.beans.factory.annotation.Autowired;

@EventListener(manualRegister = true)
public class WhitelistListener implements Listener {
    @Autowired
    private WhiteListService whiteListService;

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (!whiteListService.isWhiteList(String.valueOf(player.getUniqueId()))) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, ChatColor.AQUA + BasicFunctions.getInstance().i18n("你不在白名单上哦！"));
        }
    }
}
