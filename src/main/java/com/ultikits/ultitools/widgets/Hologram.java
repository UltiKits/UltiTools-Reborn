package com.ultikits.ultitools.widgets;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Hologram {
    private final String[] lines;

    public Hologram(String... lines) {
        this.lines = lines;
    }

    /**
     * Spawn the hologram.
     *
     * @param originLocation The location you want to create the hologram.
     * @return The spawned armor stands.You can use them to remove the hologram.
     */
    public ArmorStand[] spawn(Location originLocation) {
        List<ArmorStand> stands = new ArrayList<>();
        for (String line : lines) {
            ArmorStand stand = Objects.requireNonNull(originLocation.getWorld()).spawn(originLocation, ArmorStand.class);

            stand.setVisible(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);

            stand.setCustomNameVisible(true);
            stand.setCustomName(line);

            originLocation.subtract(0, 0.25, 0);
            stands.add(stand);
        }
        return stands.toArray(new ArmorStand[0]);
    }
}
