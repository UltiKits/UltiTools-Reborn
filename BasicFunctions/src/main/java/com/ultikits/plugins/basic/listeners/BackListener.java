package com.ultikits.plugins.basic.listeners;

import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventListener(manualRegister = true)
public class BackListener implements Listener {

    private static final Map<UUID, Location> playerDeathLocation = new HashMap<>();

    public static Location getPlayerLastDeathLocation(UUID player) {
        return playerDeathLocation.get(player);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location deathLocation = player.getLocation();
        playerDeathLocation.put(player.getUniqueId(), deathLocation);
    }
}
