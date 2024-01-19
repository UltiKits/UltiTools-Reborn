package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.services.BanPlayerService;
import com.ultikits.plugins.basic.suggests.CommonSuggest;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.springframework.beans.factory.annotation.Autowired;


@CmdSuggest({CommonSuggest.class})
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(permission = "ultikits.ban.command.all", description = "封禁功能", alias = {"uban"}, manualRegister = true)
public class BanCommands extends AbstractCommendExecutor {
    @Autowired
    private BanPlayerService banPlayerService;

    @CmdMapping(format = "ban <player> <reason>")
    public void banPlayer(@CmdSender CommandSender sender,
                          @CmdParam(value = "player", suggest = "suggestPlayer") String player,
                          @CmdParam(value = "reason", suggest = "[原因]") String reason) {
        OfflinePlayer kickedPlayer = Bukkit.getOfflinePlayer(player);
        banPlayerService.banPlayer(kickedPlayer, sender.getName(), reason);
        if (kickedPlayer.getPlayer() != null) {
            kickedPlayer.getPlayer().kickPlayer(String.format(BasicFunctions.getInstance().i18n("你已被封禁! 原因: %s"), reason));
        }
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a封禁成功"));
    }

    @CmdMapping(format = "unban <player>")
    public void unBanPlayer(@CmdSender CommandSender sender,
                            @CmdParam(value = "player", suggest = "suggestPlayer") String player) {
        banPlayerService.unBanPlayer(Bukkit.getOfflinePlayer(player));
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a解封成功"));
    }


    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(BasicFunctions.getInstance().i18n("§c/uban ban <player> <reason> §7封禁玩家"));
        sender.sendMessage(BasicFunctions.getInstance().i18n("§c/uban unban <player> §7解封玩家"));
    }
}
