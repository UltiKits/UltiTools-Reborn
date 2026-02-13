package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Base class for all UltiEssentials commands.
 * Provides convenient i18n access, feature check, teleport result handling,
 * and common tab completion helpers.
 * <p>
 * Uses the new BaseCommandExecutor introduced in UltiTools-API 6.2.0.
 *
 * @author wisdomme
 * @version 1.1.0
 */
public abstract class BaseEssentialsCommand extends BaseCommandExecutor {

    @Autowired
    protected UltiToolsPlugin plugin;

    /**
     * Gets the localized string from the plugin's language file.
     *
     * @param key the translation key
     * @return the localized string
     */
    protected String i18n(String key) {
        return plugin.i18n(key);
    }

    /**
     * Checks if a feature is enabled and sends a message if not.
     *
     * @param enabled whether the feature is enabled
     * @param sender  the command sender
     * @return true if feature is enabled, false otherwise
     */
    protected boolean checkFeatureEnabled(boolean enabled, CommandSender sender) {
        if (!enabled) {
            sender.sendMessage(i18n("feature_disabled"));
            return false;
        }
        return true;
    }

    /**
     * Sends appropriate message based on teleport result.
     *
     * @param player the player to send message to
     * @param result the teleport result
     */
    protected void sendTeleportResultMessage(Player player, TeleportResult result) {
        switch (result) {
            case SUCCESS:
                player.sendMessage(i18n("teleport_success"));
                break;
            case WARMUP_STARTED:
                player.sendMessage(i18n("teleport_warmup_started"));
                break;
            case NOT_FOUND:
                player.sendMessage(i18n("teleport_target_not_found"));
                break;
            case WORLD_NOT_FOUND:
                player.sendMessage(i18n("teleport_world_not_found"));
                break;
            case NO_PERMISSION:
                player.sendMessage(i18n("teleport_no_permission"));
                break;
            case ALREADY_TELEPORTING:
                player.sendMessage(i18n("teleport_already_in_progress"));
                break;
            case DISABLED:
                player.sendMessage(i18n("feature_disabled"));
                break;
            case CANCELLED:
                player.sendMessage(i18n("teleport_cancelled"));
                break;
            default:
                // Handle any unexpected result types
                break;
        }
    }

    /**
     * Suggests online player names for tab completion.
     * Filters players whose names start with the given prefix (case-insensitive).
     *
     * @param prefix the prefix to filter by (usually args[argIndex])
     * @return list of matching online player names
     */
    protected List<String> suggestOnlinePlayers(String prefix) {
        String lowerPrefix = prefix != null ? prefix.toLowerCase() : "";
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lowerPrefix))
                .collect(Collectors.toList());
    }

    /**
     * Suggests offline player names for tab completion.
     * Returns players who have played before but may not be online.
     * Filters players whose names start with the given prefix (case-insensitive).
     *
     * @param prefix the prefix to filter by
     * @return list of matching offline player names
     */
    protected List<String> suggestOfflinePlayers(String prefix) {
        String lowerPrefix = prefix != null ? prefix.toLowerCase() : "";
        return Arrays.stream(Bukkit.getOfflinePlayers())
                .map(OfflinePlayer::getName)
                .filter(name -> name != null && name.toLowerCase().startsWith(lowerPrefix))
                .distinct()
                .limit(50) // Limit to prevent performance issues
                .collect(Collectors.toList());
    }

    /**
     * Suggests both online and offline player names for tab completion.
     * Online players are prioritized in the result.
     *
     * @param prefix the prefix to filter by
     * @return list of matching player names (online first, then offline)
     */
    protected List<String> suggestAllPlayers(String prefix) {
        List<String> online = suggestOnlinePlayers(prefix);
        List<String> offline = suggestOfflinePlayers(prefix);
        
        // Remove duplicates (online players already in the list)
        offline.removeAll(online);
        
        // Combine: online first, then offline
        online.addAll(offline);
        return online;
    }

    /**
     * Default implementation for help command.
     * Subclasses can override this to provide custom help messages.
     *
     * @param sender the command sender
     */
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /help 获取帮助"));
    }
}
