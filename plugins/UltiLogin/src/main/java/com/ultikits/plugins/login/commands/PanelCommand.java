package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.UltiLogin;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Panel command — opens the UltiCloud web panel with magic link authentication.
 * Players can use /panel to get a clickable link that opens the web management panel.
 * 面板命令 - 通过魔法链接认证打开UltiCloud网页面板。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"panel"},
    description = "Open UltiCloud web panel"
)
public class PanelCommand extends AbstractCommendExecutor {

    private final LoginService loginService;

    public PanelCommand(LoginService loginService) {
        this.loginService = loginService;
    }

    @CmdMapping(format = "")
    public void openPanel(@CmdSender Player player) {
        if (!loginService.isPanelEnabled()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                UltiLogin.getInstance().i18n("panel_not_enabled")));
            return;
        }

        // Send generating message
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            UltiLogin.getInstance().i18n("panel_generating")));

        // Run async to avoid blocking the main thread (HTTP call)
        Bukkit.getScheduler().runTaskAsynchronously(UltiTools.getInstance(), () -> {
            LoginService.PanelLinkResult result = loginService.requestPanelLink(player);

            // Send result back on main thread
            Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
                if (!player.isOnline()) {
                    return;
                }

                if (result.isSuccess()) {
                    String url = result.getUrl();
                    // Send clickable link using Bungee chat API
                    TextComponent message = new TextComponent(
                        ChatColor.translateAlternateColorCodes('&',
                            UltiLogin.getInstance().i18n("panel_link_sent")
                                .replace("{URL}", url)));
                    message.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                    message.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(ChatColor.GRAY + "Click to open panel").create()));
                    player.spigot().sendMessage(message);
                } else {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        UltiLogin.getInstance().i18n("panel_error")));
                }
            });
        });
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /panel - Open UltiCloud web panel");
    }
}
