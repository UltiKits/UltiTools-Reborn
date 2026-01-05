package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shortcut command to switch to survival mode.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"gms"}, permission = "ultiessentials.gamemode.self", description = "切换到生存模式")
public class GmSurvivalCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public GmSurvivalCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "")
    public void survival(@CmdSender Player player) {
        if (!config.isGamemodeEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }
        player.setGameMode(GameMode.SURVIVAL);
        player.sendMessage(i18n("游戏模式已切换为 SURVIVAL"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /gms 切换到生存模式"));
    }
}
