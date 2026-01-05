package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Base class for all UltiEssentials commands.
 * Provides convenient i18n access and default handleHelp implementation.
 * <p>
 * Uses the new BaseCommandExecutor introduced in UltiTools-API 6.2.0.
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
