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
 * <p>
 * Minecraft 成就提示通知组件。
 * 创建临时的成就提示通知，显示在玩家屏幕右上角。
 * 使用动态成就创建和自动清理机制。
 * </p>
 *
 * <p><strong>Example Usage / 使用示例:</strong></p>
 * <pre>{@code
 * // Display a goal-style toast / 显示目标样式提示
 * Toast.displayTo(player, "diamond", "You found a diamond!", Toast.Style.GOAL);
 * 
 * // Display a task-style toast / 显示任务样式提示
 * Toast.displayTo(player, "emerald", "Trade completed!", Toast.Style.TASK);
 * 
 * // Multi-line message using | separator / 使用 | 分隔符显示多行消息
 * Toast.displayTo(player, "gold_ingot", "Welcome!|Enjoy your stay!", Toast.Style.CHALLENGE);
 * }</pre>
 *
 * <p><strong>Toast Styles / 提示样式:</strong></p>
 * <ul>
 *   <li>{@link Style#GOAL} - Green background, for completed goals / 绿色背景，用于完成目标</li>
 *   <li>{@link Style#TASK} - Yellow background, for tasks / 黄色背景，用于任务</li>
 *   <li>{@link Style#CHALLENGE} - Purple background, for challenges / 紫色背景，用于挑战</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class Toast {

    /** Unique key for this advancement, auto-generated UUID / 此成就的唯一键，自动生成 UUID */
    private final NamespacedKey key;
    /** The Minecraft item ID for the icon (without "minecraft:" prefix) / 图标的 Minecraft 物品 ID（不含 "minecraft:" 前缀） */
    private final String icon;
    /** The toast message, use "|" for line breaks / 提示消息，使用 "|" 换行 */
    private final String message;
    /** The visual style of the toast / 提示的视觉样式 */
    private final Style style;

    /**
     * Private constructor - use {@link #displayTo(Player, String, String, Style)} instead.
     * <br>
     * 私有构造函数 - 请使用 {@link #displayTo(Player, String, String, Style)} 代替。
     *
     * @param icon    the Minecraft item ID for the icon / 图标的 Minecraft 物品 ID
     * @param message the toast message / 提示消息
     * @param style   the visual style / 视觉样式
     */
    private Toast(String icon, String message, Style style) {
        this.key = new NamespacedKey(UltiTools.getInstance(), UUID.randomUUID().toString());
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
     * <p>
     * 向指定玩家显示提示通知。
     * 提示将在 10 tick（0.5 秒）后自动撤销，以清理临时成就。
     * </p>
     *
     * @param player  the target player / 目标玩家
     * @param icon    the Minecraft item ID for the icon (e.g., "diamond", "emerald")
     *                图标的 Minecraft 物品 ID（如 "diamond"、"emerald"）
     * @param message the toast message, use "|" for line breaks
     *                提示消息，使用 "|" 换行
     * @param style   the visual style of the toast / 提示的视觉样式
     */
    public static void displayTo(Player player, String icon, String message, Style style) {
        new Toast(icon, message, style).start(player);
    }

    /**
     * Toast display styles based on Minecraft advancement frame types.
     * <br>
     * 基于 Minecraft 成就框架类型的提示显示样式。
     */
    public enum Style {
        /** Goal style - green square frame / 目标样式 - 绿色方形边框 */
        GOAL,
        /** Task style - yellow square frame (default) / 任务样式 - 黄色方形边框（默认） */
        TASK,
        /** Challenge style - purple spiked frame / 挑战样式 - 紫色尖刺边框 */
        CHALLENGE
    }
}
