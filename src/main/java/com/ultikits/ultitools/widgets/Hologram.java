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
 * <p>
 * 使用不可见盔甲架创建的多行全息文字组件。
 * 通过生成带有自定义名称的不可见、无敌盔甲架来创建浮动文字显示。
 * 每行垂直间距 0.25 格。
 * </p>
 *
 * <p><strong>Example Usage / 使用示例:</strong></p>
 * <pre>{@code
 * Hologram hologram = new Hologram("Line 1", "Line 2", "Line 3");
 * ArmorStand[] stands = hologram.spawn(location);
 * 
 * // To remove the hologram later / 稍后移除全息图
 * for (ArmorStand stand : stands) {
 *     stand.remove();
 * }
 * }</pre>
 *
 * <p><strong>ArmorStand Properties / 盔甲架属性:</strong></p>
 * <ul>
 *   <li>Invisible (不可见)</li>
 *   <li>No gravity (无重力)</li>
 *   <li>Invulnerable (无敌)</li>
 *   <li>Custom name visible (显示自定义名称)</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class Hologram {
    /** The text lines to display, from top to bottom / 要显示的文本行，从上到下 */
    private final String[] lines;

    /**
     * Creates a new hologram with the specified text lines.
     * <br>
     * 使用指定的文本行创建新的全息图。
     *
     * @param lines the text lines to display, from top to bottom
     *              要显示的文本行，从上到下
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
