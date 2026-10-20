package com.ultikits.ultitools.widgets;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;

/**
 * A Minecraft advancement toast notification widget.
 * <p>
 * Creates temporary advancement toast notifications that appear in the top-right
 * corner of the player's screen. Uses dynamic advancement creation and auto-cleanup.
 * </p>
 *
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * // Display a goal-style toast
 * Toast.displayTo(player, "diamond", "You found a diamond!", Toast.Style.GOAL);
 *
 * // Display a task-style toast
 * Toast.displayTo(player, "emerald", "Trade completed!", Toast.Style.TASK);
 *
 * // Multi-line message using | separator
 * Toast.displayTo(player, "gold_ingot", "Welcome!|Enjoy your stay!", Toast.Style.CHALLENGE);
 * }</pre>
 *
 * <p><strong>Toast Styles:</strong></p>
 * <ul>
 *   <li>{@link Style#GOAL} - Green background, for completed goals</li>
 *   <li>{@link Style#TASK} - Yellow background, for tasks</li>
 *   <li>{@link Style#CHALLENGE} - Purple background, for challenges</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class Toast {

    /** Unique key for this advancement, auto-generated UUID */
    private final NamespacedKey key;
    /** The Minecraft item ID for the icon (without "minecraft:" prefix) */
    private final String icon;
    /** The toast message, use "|" for line breaks */
    private final String message;
    /** The visual style of the toast */
    private final Style style;

    /**
     * Private constructor - use {@link #displayTo(Player, String, String, Style)} instead.
     *
     * @param icon    the Minecraft item ID for the icon
     * @param message the toast message
     * @param style   the visual style
     */
    private Toast(String icon, String message, Style style) {
        this.key = new NamespacedKey("ultitools", UUID.randomUUID().toString());
        this.icon = icon;
        this.message = message;
        this.style = style;
    }

    private void start(Player player) {
        createAdvancement();
        grantAdvancement(player);

        Bukkit.getScheduler().runTaskLater(UltiTools.getInstance(), () -> {
            revokeAdvancement(player);
        }, 10);
    }

    private void createAdvancement() {
        //noinspection deprecation
        Bukkit.getUnsafe().loadAdvancement(key, "{\n" +
                "    \"criteria\": {\n" +
                "        \"trigger\": {\n" +
                "            \"trigger\": \"minecraft:impossible\"\n" +
                "        }\n" +
                "    },\n" +
                "    \"display\": {\n" +
                "        \"icon\": {\n" +
                "            \"item\": \"minecraft:" + icon + "\"\n" +
                "        },\n" +
                "        \"title\": {\n" +
                "            \"text\": \"" + message.replace("|", "\n") + "\"\n" +
                "        },\n" +
                "        \"description\": {\n" +
                "            \"text\": \"\"\n" +
                "        },\n" +
                "        \"background\": \"minecraft:textures/gui/advancements/backgrounds/adventure.png\",\n" +
                "        \"frame\": \"" + style.toString().toLowerCase() + "\",\n" +
                "        \"announce_to_chat\": false,\n" +
                "        \"show_toast\": true,\n" +
                "        \"hidden\": true\n" +
                "    },\n" +
                "    \"requirements\": [\n" +
                "        [\n" +
                "            \"trigger\"\n" +
                "        ]\n" +
                "    ]\n" +
                "}");
    }

    private void grantAdvancement(Player player) {
        player.getAdvancementProgress(Objects.requireNonNull(Bukkit.getAdvancement(key))).awardCriteria("trigger");
    }

    private void revokeAdvancement(Player player) {
        player.getAdvancementProgress(Objects.requireNonNull(Bukkit.getAdvancement(key))).revokeCriteria("trigger");
    }

    /**
     * Displays a toast notification to the specified player.
     * <p>
     * The toast will automatically be revoked after 10 ticks (0.5 seconds)
     * to clean up the temporary advancement.
     * </p>
     *
     * @param player  the target player
     * @param icon    the Minecraft item ID for the icon (e.g., "diamond", "emerald")
     * @param message the toast message, use "|" for line breaks
     * @param style   the visual style of the toast
     */
    public static void displayTo(Player player, String icon, String message, Style style) {
        new Toast(icon, message, style).start(player);
    }

    /**
     * Toast display styles based on Minecraft advancement frame types.
     */
    public enum Style {
        /** Goal style - green square frame */
        GOAL,
        /** Task style - yellow square frame (default) */
        TASK,
        /** Challenge style - purple spiked frame */
        CHALLENGE
    }
}
