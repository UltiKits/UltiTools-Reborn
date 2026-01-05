package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to restore player health.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"heal"}, permission = "ultiessentials.heal.self", description = "恢复生命值")
public class HealCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public HealCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "")
    public void healSelf(@CmdSender Player player) {
        if (!config.isHealEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        player.setHealth(maxHealth);
        player.sendMessage(i18n("生命值已恢复"));
    }

    @CmdMapping(format = "<player>", permission = "ultiessentials.heal.other")
    public void healOther(
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

        double maxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        target.setHealth(maxHealth);
        sender.sendMessage(String.format(i18n("已恢复 %s 的生命值"), target.getName()));
        target.sendMessage(i18n("你的生命值已被恢复"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /heal 恢复生命值"));
    }
}
