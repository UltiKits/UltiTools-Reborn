package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to change player game mode.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"gm"}, permission = "ultiessentials.gamemode.self", description = "切换游戏模式")
public class GameModeCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public GameModeCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "<mode>")
    public void setGameMode(@CmdSender Player player, @CmdParam("mode") String mode) {
        if (!config.isGamemodeEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        GameMode gameMode = parseGameMode(mode);
        if (gameMode == null) {
            player.sendMessage(i18n("无效的游戏模式，可用: 0/s/survival, 1/c/creative, 2/a/adventure, 3/sp/spectator"));
            return;
        }

        player.setGameMode(gameMode);
        player.sendMessage(String.format(i18n("游戏模式已切换为 %s"), gameMode.name()));
    }

    @CmdMapping(format = "<mode> <player>", permission = "ultiessentials.gamemode.other")
    public void setGameModeOther(
            @CmdSender Player sender,
            @CmdParam("mode") String mode,
            @CmdParam("player") Player target) {

        if (!config.isGamemodeEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (target == null) {
            sender.sendMessage(i18n("玩家不存在或不在线"));
            return;
        }

        GameMode gameMode = parseGameMode(mode);
        if (gameMode == null) {
            sender.sendMessage(i18n("无效的游戏模式"));
            return;
        }

        target.setGameMode(gameMode);
        sender.sendMessage(String.format(i18n("已将 %s 的游戏模式切换为 %s"), target.getName(), gameMode.name()));
        target.sendMessage(String.format(i18n("你的游戏模式已被切换为 %s"), gameMode.name()));
    }

    /**
     * Parses a game mode string into a GameMode enum.
     *
     * @param mode the mode string
     * @return the GameMode or null if invalid
     */
    private GameMode parseGameMode(String mode) {
        switch (mode.toLowerCase()) {
            case "0":
            case "s":
            case "survival":
                return GameMode.SURVIVAL;
            case "1":
            case "c":
            case "creative":
                return GameMode.CREATIVE;
            case "2":
            case "a":
            case "adventure":
                return GameMode.ADVENTURE;
            case "3":
            case "sp":
            case "spectator":
                return GameMode.SPECTATOR;
            default:
                return null;
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /gm <模式> 切换游戏模式"));
    }
}
