package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to recall (teleport) another player to your location.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"recall"}, permission = "ultiessentials.recall", description = "召回玩家到你的位置")
public class RecallCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public RecallCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "<player>")
    public void recall(@CmdSender Player sender, @CmdParam("player") Player target) {
        if (!config.isRecallEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (target == null) {
            sender.sendMessage(i18n("玩家不存在或不在线"));
            return;
        }

        if (target.equals(sender)) {
            sender.sendMessage(i18n("不能召回自己"));
            return;
        }

        target.teleport(sender.getLocation());
        sender.sendMessage(String.format(i18n("已将 %s 召回到你的位置"), target.getName()));
        target.sendMessage(String.format(i18n("你被 %s 召回"), sender.getName()));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /recall <玩家> 召回玩家到你的位置"));
    }
}
