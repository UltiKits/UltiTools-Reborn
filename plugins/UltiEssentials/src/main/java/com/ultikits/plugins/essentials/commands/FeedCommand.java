package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to restore player hunger.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"feed"}, permission = "ultiessentials.heal.self", description = "恢复饱食度")
public class FeedCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public FeedCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "")
    public void feedSelf(@CmdSender Player player) {
        if (!config.isHealEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.sendMessage(i18n("饱食度已恢复"));
    }

    @CmdMapping(format = "<player>", permission = "ultiessentials.heal.other")
    public void feedOther(
            @CmdSender Player sender,
            @CmdParam("player") Player target) {

        if (!config.isHealEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (target == null) {
            sender.sendMessage(i18n("玩家不存在或不在线"));
            return;
        }

        target.setFoodLevel(20);
        target.setSaturation(20.0f);
        sender.sendMessage(String.format(i18n("已恢复 %s 的饱食度"), target.getName()));
        target.sendMessage(i18n("你的饱食度已被恢复"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /feed 恢复饱食度"));
    }
}
