package com.ultikits.ultitools.services;

import com.ultikits.ultitools.interfaces.Registrable;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface TeleportService extends Registrable {
    void teleport(Player player, Location location);

    void delayTeleport(Player player, Location location, int delay);

    Optional<Location> getLastTeleportLocation(UUID uuid);
}
