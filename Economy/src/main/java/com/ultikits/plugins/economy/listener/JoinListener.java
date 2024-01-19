package com.ultikits.plugins.economy.listener;

import com.ultikits.ultitools.annotations.EventListener;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.springframework.beans.factory.annotation.Autowired;

@EventListener
public class JoinListener implements Listener {
    @Autowired
    private Economy economy;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        boolean hasAccount = economy.hasAccount(event.getPlayer());
        if (!hasAccount) {
            economy.createPlayerAccount(event.getPlayer());
        }
    }
}
