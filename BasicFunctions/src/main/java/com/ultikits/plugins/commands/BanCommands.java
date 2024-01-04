package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.services.BanPlayerService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.springframework.beans.factory.annotation.Autowired;


@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(permission = "ultikits.ban.command.all", description = "Ban功能", alias = {"uban"})
public class BanCommands extends AbstractCommendExecutor {
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    private BanPlayerService banPlayerService = new BanPlayerService();

    @CmdMapping(format = "ban <player> <reason>")
    public void banPlayer(@CmdSender CommandSender sender, @CmdParam("player") String player, @CmdParam("reason") String reason) {
        OfflinePlayer kickedPlayer = Bukkit.getOfflinePlayer(player);
        banPlayerService.banPlayer(kickedPlayer, sender.getName(), reason);
        if (kickedPlayer.isOnline()) {
            kickedPlayer.getPlayer().kickPlayer(String.format(BasicFunctions.getInstance().i18n("你已被封禁! 原因: %s"), reason));
        }
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a封禁成功"));
    }

    @CmdMapping(format = "unban <player>")
    public void unBanPlayer(@CmdSender CommandSender sender, @CmdParam("player") String player) {
        banPlayerService.unBanPlayer(Bukkit.getOfflinePlayer(player));
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a解封成功"));
    }


    @Override
    protected void handleHelp(CommandSender sender) {

    }
}
