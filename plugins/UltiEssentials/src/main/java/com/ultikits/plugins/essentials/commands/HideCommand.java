package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Command to toggle vanish/invisible mode.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"hide", "vanish"}, permission = "ultiessentials.hide", description = "切换隐身模式")
public class HideCommand extends BaseEssentialsCommand {

    /**
     * Stores currently hidden player UUIDs.
     */
    private static final Set<UUID> HIDDEN_PLAYERS = new HashSet<>();

    private final EssentialsConfig config;

    public HideCommand(EssentialsConfig config) {
        this.config = config;
    }

    private Plugin getBukkitPlugin() {
        return Bukkit.getPluginManager().getPlugin("UltiTools");
    }

    @CmdMapping(format = "")
    public void toggleHide(@CmdSender Player player) {
        if (!config.isHideEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (HIDDEN_PLAYERS.contains(player.getUniqueId())) {
            // Disable vanish
            HIDDEN_PLAYERS.remove(player.getUniqueId());
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.showPlayer(getBukkitPlugin(), player);
            }
            player.sendMessage(i18n("隐身模式已关闭"));
        } else {
            // Enable vanish
            HIDDEN_PLAYERS.add(player.getUniqueId());
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.hasPermission("ultiessentials.hide.see")) {
                    online.hidePlayer(getBukkitPlugin(), player);
                }
            }
            player.sendMessage(i18n("隐身模式已开启"));
        }
    }

    /**
     * Checks if a player is currently hidden.
     *
     * @param player the player to check
     * @return true if hidden, false otherwise
     */
    public static boolean isHidden(Player player) {
        return HIDDEN_PLAYERS.contains(player.getUniqueId());
    }

    /**
     * Removes a player from the hidden set.
     * Should be called when a player disconnects.
     *
     * @param uuid the player's UUID
     */
    public static void removePlayer(UUID uuid) {
        HIDDEN_PLAYERS.remove(uuid);
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /hide 切换隐身模式"));
    }
}
