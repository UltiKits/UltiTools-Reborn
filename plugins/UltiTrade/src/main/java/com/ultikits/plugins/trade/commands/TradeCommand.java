package com.ultikits.plugins.trade.commands;

import com.ultikits.plugins.trade.service.TradeService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Trade command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"trade", "t"},
    permission = "ultitrade.use",
    description = "玩家交易系统"
)
public class TradeCommand extends AbstractCommendExecutor {
    
    private final TradeService tradeService;
    
    public TradeCommand(TradeService tradeService) {
        this.tradeService = tradeService;
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
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== UltiTrade 帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/trade <玩家>" + ChatColor.WHITE + " - 发起交易请求");
        player.sendMessage(ChatColor.YELLOW + "/trade accept" + ChatColor.WHITE + " - 接受交易请求");
        player.sendMessage(ChatColor.YELLOW + "/trade deny" + ChatColor.WHITE + " - 拒绝交易请求");
        player.sendMessage(ChatColor.YELLOW + "/trade cancel" + ChatColor.WHITE + " - 取消当前交易");
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            help((Player) sender);
        }
    }
}
