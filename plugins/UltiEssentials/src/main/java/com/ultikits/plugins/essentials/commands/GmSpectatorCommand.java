package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shortcut command to switch to spectator mode.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"gmsp"}, permission = "ultiessentials.gamemode.self", description = "切换到旁观模式")
public class GmSpectatorCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public GmSpectatorCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "")
    public void spectator(@CmdSender Player player) {
        if (!config.isGamemodeEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }
        player.setGameMode(GameMode.SPECTATOR);
        player.sendMessage(i18n("游戏模式已切换为 SPECTATOR"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /gmsp 切换到旁观模式"));
    }
}
