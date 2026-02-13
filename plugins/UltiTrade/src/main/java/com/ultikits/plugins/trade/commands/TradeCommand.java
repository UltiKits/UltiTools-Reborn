package com.ultikits.plugins.trade.commands;

import com.ultikits.plugins.trade.service.TradeLogService;
import com.ultikits.plugins.trade.service.TradeService;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Trade command executor with blacklist and toggle support.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"trade", "t"},
    permission = "ultitrade.use",
    description = "玩家交易系统"
)
public class TradeCommand extends BaseCommandExecutor {
    
    private final TradeService tradeService;
    private final TradeLogService logService;
    
    public TradeCommand(TradeService tradeService, TradeLogService logService) {
        this.tradeService = tradeService;
        this.logService = logService;
    }
    
    @CmdMapping(format = "<player>")
    public void sendRequest(@CmdSender Player sender, @CmdParam("player") String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "玩家 " + targetName + " 不在线！");
            return;
        }
        
        if (target.equals(sender)) {
            sender.sendMessage(ChatColor.RED + "不能和自己交易！");
            return;
        }
        
        tradeService.sendRequest(sender, target);
    }
    
    @CmdMapping(format = "accept")
    public void accept(@CmdSender Player player) {
        tradeService.acceptRequest(player);
    }
    
    @CmdMapping(format = "deny")
    public void deny(@CmdSender Player player) {
        tradeService.denyRequest(player);
    }
    
    @CmdMapping(format = "cancel")
    public void cancel(@CmdSender Player player) {
        if (!tradeService.isTrading(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "你当前没有在交易！");
            return;
        }
        tradeService.cancelTrade(player);
    }
    
    @CmdMapping(format = "toggle")
    public void toggle(@CmdSender Player player) {
        boolean newState = logService.toggleTrade(player);
        if (newState) {
            player.sendMessage(ChatColor.GREEN + "已开启交易功能！其他玩家现在可以向你发送交易请求。");
        } else {
            player.sendMessage(ChatColor.YELLOW + "已关闭交易功能！其他玩家将无法向你发送交易请求。");
        }
    }
    
    @CmdMapping(format = "block <player>")
    public void blockPlayer(@CmdSender Player player, @CmdParam("player") String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            // Try to block by name even if offline
            player.sendMessage(ChatColor.RED + "玩家 " + targetName + " 不在线！无法添加到黑名单。");
            return;
        }
        
        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "不能将自己添加到黑名单！");
            return;
        }
        
        if (logService.isBlocked(player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(ChatColor.RED + target.getName() + " 已经在你的交易黑名单中！");
            return;
        }
        
        logService.blockPlayer(player, target.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "已将 " + target.getName() + " 添加到交易黑名单！");
        player.sendMessage(ChatColor.GRAY + "该玩家将无法向你发送交易请求。");
    }
    
    @CmdMapping(format = "unblock <player>")
    public void unblockPlayer(@CmdSender Player player, @CmdParam("player") String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "玩家 " + targetName + " 不在线！无法从黑名单移除。");
            return;
        }
        
        if (!logService.isBlocked(player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(ChatColor.RED + target.getName() + " 不在你的交易黑名单中！");
            return;
        }
        
        logService.unblockPlayer(player, target.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "已将 " + target.getName() + " 从交易黑名单中移除！");
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== UltiTrade 帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/trade <玩家>" + ChatColor.WHITE + " - 发起交易请求");
        player.sendMessage(ChatColor.YELLOW + "/trade accept" + ChatColor.WHITE + " - 接受交易请求");
        player.sendMessage(ChatColor.YELLOW + "/trade deny" + ChatColor.WHITE + " - 拒绝交易请求");
        player.sendMessage(ChatColor.YELLOW + "/trade cancel" + ChatColor.WHITE + " - 取消当前交易");
        player.sendMessage(ChatColor.YELLOW + "/trade toggle" + ChatColor.WHITE + " - 开启/关闭交易功能");
        player.sendMessage(ChatColor.YELLOW + "/trade block <玩家>" + ChatColor.WHITE + " - 屏蔽指定玩家");
        player.sendMessage(ChatColor.YELLOW + "/trade unblock <玩家>" + ChatColor.WHITE + " - 取消屏蔽玩家");
        player.sendMessage("");
        
        // Show current status
        boolean tradeEnabled = logService.isTradeEnabled(player.getUniqueId());
        player.sendMessage(ChatColor.GRAY + "交易状态: " + 
            (tradeEnabled ? ChatColor.GREEN + "已开启" : ChatColor.RED + "已关闭"));
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            help((Player) sender);
        }
    }
}
