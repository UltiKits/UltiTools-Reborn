package com.ultikits.ultitools.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;

/**
 * A multi-line hologram widget using invisible ArmorStands.
 * <p>
 * Creates floating text displays by spawning invisible, invulnerable ArmorStands
 * with custom names. Each line is spaced 0.25 blocks apart vertically.
 * </p>
 *
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * Hologram hologram = new Hologram("Line 1", "Line 2", "Line 3");
 * ArmorStand[] stands = hologram.spawn(location);
 *
 * // To remove the hologram later
 * for (ArmorStand stand : stands) {
 *     stand.remove();
 * }
 * }</pre>
 *
 * <p><strong>ArmorStand Properties:</strong></p>
 * <ul>
 *   <li>Invisible</li>
 *   <li>No gravity</li>
 *   <li>Invulnerable</li>
 *   <li>Custom name visible</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class Hologram {
    /** The text lines to display, from top to bottom */
    private final String[] lines;

    /**
     * Creates a new hologram with the specified text lines.
     *
     * @param lines the text lines to display, from top to bottom
     */
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
