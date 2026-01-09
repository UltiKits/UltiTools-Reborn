package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.service.BanService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Command for temporarily banning players.
 * <p>
 * Usage: /tempban <player> <duration> [reason]
 * Duration examples: 1d, 2h, 30m, 1w, 1d12h30m
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"tempban", "tban"},
    permission = "ultiessentials.ban.temp",
    description = "临时封禁玩家"
)
public class TempBanCommand extends BaseEssentialsCommand {
    
    @Autowired
    private BanService banService;
    
    @CmdMapping(format = "<player> <duration>")
    public void tempban(
        @CmdSender CommandSender sender,
        @CmdParam("player") String playerName,
        @CmdParam("duration") String duration
    ) {
        tempbanWithReason(sender, playerName, duration, "无理由");
    }
    
    @CmdMapping(format = "<player> <duration> <reason>")
    public void tempbanWithReason(
        @CmdSender CommandSender sender,
        @CmdParam("player") String playerName,
        @CmdParam("duration") String duration,
        @CmdParam("reason") String reason
    ) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target.getUniqueId() == null && !target.hasPlayedBefore()) {
            sender.sendMessage(i18n("§c玩家不存在: ") + playerName);
            return;
        }
        
        long durationMillis = BanService.parseDuration(duration);
        if (durationMillis <= 0) {
            sender.sendMessage(i18n("§c无效的时长格式"));
            sender.sendMessage(i18n("§7示例: 1d, 2h, 30m, 1w, 1d12h30m"));
            return;
        }
        
        UUID operatorUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        String operatorName = sender instanceof Player ? sender.getName() : "Console";
        
        BanService.BanResult result = banService.banPlayer(
            target.getUniqueId(),
            target.getName() != null ? target.getName() : playerName,
            reason,
            operatorUuid,
            operatorName,
            durationMillis,
            null
        );
        
        switch (result) {
            case SUCCESS:
                String durationStr = BanService.formatDuration(durationMillis);
                Bukkit.broadcastMessage(i18n("§c[临时封禁] §f") + 
                    target.getName() + " §7被 " + operatorName + " 封禁 " + durationStr);
                Bukkit.broadcastMessage(i18n("§7原因: §f") + reason);
                break;
            case ALREADY_BANNED:
                sender.sendMessage(i18n("§c该玩家已被封禁"));
                break;
            case DISABLED:
                sender.sendMessage(i18n("§c封禁功能已禁用"));
                break;
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /tempban <玩家> <时长> [原因]"));
        sender.sendMessage(i18n("临时封禁一个玩家"));
        sender.sendMessage(i18n("§7时长格式: 1d(天), 2h(小时), 30m(分钟), 1w(周)"));
        sender.sendMessage(i18n("§7可组合: 1d12h30m = 1天1小时30分钟"));
    }
    
    @Override
    protected List<String> suggest(Player player, Command command, String[] args) {
        if (args.length == 1) {
            return suggestOnlinePlayers(args[0]);
        }
        if (args.length == 2) {
            return java.util.Arrays.asList("1h", "6h", "12h", "1d", "3d", "7d", "30d", "1w");
        }
        return super.suggest(player, command, args);
    }
}
