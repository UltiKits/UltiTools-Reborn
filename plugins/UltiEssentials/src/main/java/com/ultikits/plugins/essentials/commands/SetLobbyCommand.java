package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.LobbyConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;

/**
 * Command to set the server lobby/hub at player's current location.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"setlobby", "sethub"}, permission = "ultiessentials.lobby.set", description = "设置主城")
public class SetLobbyCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;
    private final LobbyConfig lobbyConfig;

    public SetLobbyCommand(EssentialsConfig config, LobbyConfig lobbyConfig) {
        this.config = config;
        this.lobbyConfig = lobbyConfig;
    }

    @CmdMapping(format = "")
    public void setLobby(@CmdSender Player player) {
        if (!config.isLobbyEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        lobbyConfig.setLobbyLocation(player.getLocation());
        try {
            lobbyConfig.save();
            player.sendMessage(i18n("主城已设置"));
        } catch (IOException e) {
            player.sendMessage(i18n("保存主城失败"));
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /setlobby 设置主城"));
    }
}
