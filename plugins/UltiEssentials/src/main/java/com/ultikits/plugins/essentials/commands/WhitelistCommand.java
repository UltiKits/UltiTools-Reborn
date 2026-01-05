package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Command to manage server whitelist.
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"wl"}, permission = "ultiessentials.whitelist.manage", description = "白名单管理")
public class WhitelistCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public WhitelistCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "add <player>")
    public void add(@CmdSender CommandSender sender, @CmdParam("player") OfflinePlayer target) {
        if (!config.isWhitelistEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (target == null) {
            sender.sendMessage(i18n("玩家不存在"));
            return;
        }

        target.setWhitelisted(true);
        sender.sendMessage(String.format(i18n("已将 %s 添加到白名单"), target.getName()));
    }

    @CmdMapping(format = "remove <player>")
    public void remove(@CmdSender CommandSender sender, @CmdParam("player") OfflinePlayer target) {
        if (!config.isWhitelistEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (target == null) {
            sender.sendMessage(i18n("玩家不存在"));
            return;
        }

        target.setWhitelisted(false);
        sender.sendMessage(String.format(i18n("已将 %s 从白名单移除"), target.getName()));
    }

    @CmdMapping(format = "list")
    public void list(@CmdSender CommandSender sender) {
        if (!config.isWhitelistEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        Set<OfflinePlayer> whitelisted = Bukkit.getWhitelistedPlayers();
        if (whitelisted.isEmpty()) {
            sender.sendMessage(i18n("白名单为空"));
            return;
        }

        String names = whitelisted.stream()
                .map(OfflinePlayer::getName)
                .collect(Collectors.joining(", "));

        sender.sendMessage(String.format(i18n("白名单 (%d): %s"), whitelisted.size(), names));
    }

    @CmdMapping(format = "on")
    public void enable(@CmdSender CommandSender sender) {
        if (!config.isWhitelistEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        Bukkit.setWhitelist(true);
        sender.sendMessage(i18n("白名单已启用"));
    }

    @CmdMapping(format = "off")
    public void disable(@CmdSender CommandSender sender) {
        if (!config.isWhitelistEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        Bukkit.setWhitelist(false);
        sender.sendMessage(i18n("白名单已禁用"));
    }

    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        if (!config.isWhitelistEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        boolean enabled = Bukkit.hasWhitelist();
        int count = Bukkit.getWhitelistedPlayers().size();

        sender.sendMessage(String.format(i18n("白名单状态: %s, 人数: %d"),
                enabled ? i18n("已启用") : i18n("已禁用"), count));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("白名单命令帮助:"));
        sender.sendMessage("/wl add <玩家> - 添加玩家到白名单");
        sender.sendMessage("/wl remove <玩家> - 从白名单移除玩家");
        sender.sendMessage("/wl list - 查看白名单列表");
        sender.sendMessage("/wl on/off - 启用/禁用白名单");
        sender.sendMessage("/wl status - 查看白名单状态");
    }
}
