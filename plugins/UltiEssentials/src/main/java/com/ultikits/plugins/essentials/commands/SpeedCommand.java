package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to adjust player movement speed.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"speed"}, permission = "ultiessentials.speed", description = "调整移动速度")
public class SpeedCommand extends BaseEssentialsCommand {

    private static final float DEFAULT_WALK_SPEED = 0.2f;
    private static final float DEFAULT_FLY_SPEED = 0.1f;

    private final EssentialsConfig config;

    public SpeedCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "<speed>")
    public void setSpeed(@CmdSender Player player, @CmdParam("speed") int speed) {
        if (!config.isSpeedEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        int maxSpeed = config.getSpeedMaxSpeed();
        if (speed < 0 || speed > maxSpeed) {
            player.sendMessage(String.format(i18n("速度必须在 0-%d 之间"), maxSpeed));
            return;
        }

        float speedValue;
        if (speed == 0) {
            // Reset to default speed
            player.setWalkSpeed(DEFAULT_WALK_SPEED);
            player.setFlySpeed(DEFAULT_FLY_SPEED);
            player.sendMessage(i18n("速度已重置为默认值"));
        } else {
            // Set speed (1-10 mapped to 0.2-1.0)
            speedValue = Math.min(1.0f, DEFAULT_WALK_SPEED * speed);
            player.setWalkSpeed(speedValue);
            player.setFlySpeed(Math.min(1.0f, DEFAULT_FLY_SPEED * speed));
            player.sendMessage(String.format(i18n("速度已设置为 %d"), speed));
        }
    }

    @CmdMapping(format = "reset")
    public void resetSpeed(@CmdSender Player player) {
        if (!config.isSpeedEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        player.setWalkSpeed(DEFAULT_WALK_SPEED);
        player.setFlySpeed(DEFAULT_FLY_SPEED);
        player.sendMessage(i18n("速度已重置为默认值"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /speed <速度> 调整移动速度"));
    }
}
