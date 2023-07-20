package com.ultikits.plugins.economy.listener;

import com.ultikits.plugins.economy.UltiEconomy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event){
        boolean hasAccount = UltiEconomy.getVault().hasAccount(event.getPlayer());
        if (!hasAccount){
            UltiEconomy.getVault().createPlayerAccount(event.getPlayer());
        }
    }
}
