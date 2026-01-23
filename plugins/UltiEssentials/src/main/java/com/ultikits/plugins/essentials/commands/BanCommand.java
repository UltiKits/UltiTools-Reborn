package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.service.BanService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Command for banning players.
 * <p>
 * Usage: /ban <player> [reason]
 *        /tempban <player> <duration> [reason]
 *        /banip <player|ip> [reason]
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"ban", "eban"},
    permission = "ultiessentials.ban",
    description = "封禁玩家"
)
public class BanCommand extends BaseEssentialsCommand {
    
    @Autowired
    private BanService banService;
    
    @CmdMapping(format = "<player>")
    public void ban(@CmdSender CommandSender sender, @CmdParam("player") String playerName) {
        banWithReason(sender, playerName, "无理由");
    }
    
    @CmdMapping(format = "<player> <reason>")
    public void banWithReason(
        @CmdSender CommandSender sender,
        @CmdParam("player") String playerName,
        @CmdParam("reason") String reason
    ) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target.getUniqueId() == null && !target.hasPlayedBefore()) {
            sender.sendMessage(i18n("§c玩家不存在: ") + playerName);
            return;
        }
        
        UUID operatorUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        String operatorName = sender instanceof Player ? sender.getName() : "Console";
        
        BanService.BanResult result = banService.banPlayer(
            target.getUniqueId(),
            target.getName() != null ? target.getName() : playerName,
            reason,
            operatorUuid,
            operatorName
        );
        
        switch (result) {
            case SUCCESS:
                Bukkit.broadcastMessage(i18n("§c[封禁] §f") +
                    target.getName() + " §7被 " + operatorName + " 永久封禁");
                Bukkit.broadcastMessage(i18n("§7原因: §f") + reason);
                break;
            case ALREADY_BANNED:
                sender.sendMessage(i18n("§c该玩家已被封禁"));
                break;
            case DISABLED:
                sender.sendMessage(i18n("§c封禁功能已禁用"));
                break;
            default:
                // Handle unexpected result types
                break;
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /ban <玩家> [原因]"));
        sender.sendMessage(i18n("永久封禁一个玩家"));
    }
    
    @Override
    protected List<String> suggest(Player player, Command command, String[] args) {
        if (args.length == 1) {
            return suggestOnlinePlayers(args[0]);
        }
        return super.suggest(player, command, args);
    }
}
