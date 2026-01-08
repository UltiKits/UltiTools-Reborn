package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Base class for all UltiEssentials commands.
 * Provides convenient i18n access, feature check, and teleport result handling.
 * <p>
 * Uses the new BaseCommandExecutor introduced in UltiTools-API 6.2.0.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class BaseEssentialsCommand extends BaseCommandExecutor {

    /**
     * Gets the localized string from the plugin's language file.
     *
     * @param key the translation key
     * @return the localized string
     */
    protected String i18n(String key) {
        return UltiEssentials.getInstance().i18n(key);
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
        }
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
