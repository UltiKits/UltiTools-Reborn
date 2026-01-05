package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.LobbyConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command to teleport player to the server lobby/hub.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"lobby", "hub"}, permission = "ultiessentials.lobby.teleport", description = "传送到主城")
public class LobbyCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;
    private final LobbyConfig lobbyConfig;

    public LobbyCommand(EssentialsConfig config, LobbyConfig lobbyConfig) {
        this.config = config;
        this.lobbyConfig = lobbyConfig;
    }

    @CmdMapping(format = "")
    public void teleportToLobby(@CmdSender Player player) {
        if (!config.isLobbyEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        Location lobby = lobbyConfig.getLobbyLocation();
        if (lobby.getWorld() == null) {
            player.sendMessage(i18n("主城世界不存在"));
            return;
        }

        player.teleport(lobby);
        player.sendMessage(i18n("已传送到主城"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /lobby 传送到主城"));
    }
}
