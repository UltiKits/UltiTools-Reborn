package com.ultikits.plugins.chat.commands;

import com.ultikits.plugins.chat.service.AutoReplyService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Admin commands for UltiChat management.
 * UltiChat 管理命令。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(permission = "ultichat.admin", description = "UltiChat admin commands", alias = {"uchat"})
public class ChatAdminCommands extends AbstractCommandExecutor {

    private final UltiToolsPlugin plugin;
    private final AutoReplyService autoReplyService;

    public ChatAdminCommands(UltiToolsPlugin plugin, AutoReplyService autoReplyService) {
        this.plugin = plugin;
        this.autoReplyService = autoReplyService;
    }

    /**
     * Reload all UltiChat configurations.
     * 重新加载所有 UltiChat 配置。
     */
    @CmdMapping(format = "reload")
    public void onReload(@CmdSender CommandSender sender) {
        plugin.reloadSelf();
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("config_reloaded")));
    }

    /**
     * List all auto-reply rules.
     * 列出所有自动回复规则。
     */
    @CmdMapping(format = "autoreply list")
    public void onAutoReplyList(@CmdSender CommandSender sender) {
        Map<String, Map<String, Object>> rules = autoReplyService.getRules();

        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("autoreply_list_header")));

        if (rules.isEmpty()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("autoreply_list_empty")));
            return;
        }

        for (Map.Entry<String, Map<String, Object>> entry : rules.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> rule = entry.getValue();
            Object keyword = rule.get("keyword");
            Object mode = rule.get("mode");
            String keywordStr = keyword != null ? keyword.toString() : "";
            String modeStr = mode != null ? mode.toString() : "contains";

            String line = plugin.i18n("autoreply_list_entry");
            line = line.replace("{0}", name);
            line = line.replace("{1}", keywordStr);
            line = line.replace("{2}", modeStr);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    /**
     * Add a new auto-reply rule.
     * 添加新的自动回复规则。
     */
    @CmdMapping(format = "autoreply add <name> <response>")
    public void onAutoReplyAdd(@CmdSender CommandSender sender,
                               @CmdParam("name") String name,
                               @CmdParam("response") String response) {
        autoReplyService.addRule(name, name, response);
        String msg = plugin.i18n("autoreply_added").replace("{0}", name);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    /**
     * Remove an auto-reply rule.
     * 移除自动回复规则。
     */
    @CmdMapping(format = "autoreply remove <name>")
    public void onAutoReplyRemove(@CmdSender CommandSender sender,
                                  @CmdParam("name") String name) {
        Map<String, Map<String, Object>> rules = autoReplyService.getRules();
        if (!rules.containsKey(name)) {
            String msg = plugin.i18n("autoreply_not_found").replace("{0}", name);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return;
        }

        autoReplyService.removeRule(name);
        String msg = plugin.i18n("autoreply_removed").replace("{0}", name);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== UltiChat Admin Commands ===");
        sender.sendMessage(ChatColor.AQUA + "/uchat reload" + ChatColor.WHITE + " - Reload configs");
        sender.sendMessage(ChatColor.AQUA + "/uchat autoreply list" + ChatColor.WHITE + " - List auto-reply rules");
        sender.sendMessage(ChatColor.AQUA + "/uchat autoreply add <name> <response>" + ChatColor.WHITE + " - Add rule");
        sender.sendMessage(ChatColor.AQUA + "/uchat autoreply remove <name>" + ChatColor.WHITE + " - Remove rule");
    }
}
