package com.ultikits.plugins.listeners;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.services.WhiteListService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public class WhitelistListener implements Listener {
    private final WhiteListService whiteListService = new WhiteListService();

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (!whiteListService.isWhiteList(String.valueOf(player.getUniqueId()))) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, ChatColor.AQUA + BasicFunctions.getInstance().i18n("你不在白名单上哦！"));
        }
    }
}
