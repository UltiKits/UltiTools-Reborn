package com.ultikits.ultitools.widgets;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

public class Toast {

    private final NamespacedKey key;
    private final String icon;
    private final String message;
    private final Style style;

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

    public static void displayTo(Player player, String icon, String message, Style style) {
        new Toast(icon, message, style).start(player);
    }

    public static enum Style {
        GOAL,
        TASK,
        CHALLENGE
    }
}
