package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to view another player's ender chest.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"endersee", "echest"}, permission = "ultiessentials.endersee", description = "查看玩家末影箱")
public class EnderseeCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public EnderseeCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "<player>")
    public void endersee(@CmdSender Player sender, @CmdParam("player") Player target) {
        if (!config.isInvseeEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (target == null) {
            sender.sendMessage(i18n("玩家不存在或不在线"));
            return;
        }

        sender.openInventory(target.getEnderChest());
        sender.sendMessage(String.format(i18n("正在查看 %s 的末影箱"), target.getName()));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /endersee <玩家> 查看玩家末影箱"));
    }
}
