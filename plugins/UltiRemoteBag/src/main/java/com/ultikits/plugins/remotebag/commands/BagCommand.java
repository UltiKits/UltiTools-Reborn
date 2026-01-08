package com.ultikits.plugins.remotebag.commands;

import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Remote bag command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"bag", "remotebag", "rb", "yunbag"},
    permission = "ultibag.use",
    description = "远程背包系统"
)
public class BagCommand extends AbstractCommendExecutor {
    
    private final RemoteBagService bagService;
    
    public BagCommand(RemoteBagService bagService) {
        this.bagService = bagService;
    }
    
    @CmdMapping(format = "")
    public void openBag(@CmdSender Player player) {
        bagService.openBag(player, 1);
    }
    
    @CmdMapping(format = "<page>")
    public void openPage(@CmdSender Player sender, @CmdParam("page") int page) {
        bagService.openBag(sender, page);
    }
    
    @CmdMapping(format = "save")
    public void saveBag(@CmdSender Player player) {
        bagService.saveBag(player.getUniqueId());
        player.sendMessage(bagService.getConfig().getBagSavedMessage().replace("&", "§"));
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            player.sendMessage(ChatColor.GOLD + "=== UltiRemoteBag 帮助 ===");
            player.sendMessage(ChatColor.YELLOW + "/bag" + ChatColor.WHITE + " - 打开远程背包");
            player.sendMessage(ChatColor.YELLOW + "/bag <页码>" + ChatColor.WHITE + " - 打开指定页");
            player.sendMessage(ChatColor.YELLOW + "/bag save" + ChatColor.WHITE + " - 保存背包");
        }
    }
}
