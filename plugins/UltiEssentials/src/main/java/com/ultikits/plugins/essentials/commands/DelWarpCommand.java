package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.service.WarpService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for deleting warps.
 * <p>
 * Usage: /delwarp <name>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"delwarp", "deletewarp", "rmwarp", "removewarp"},
    permission = "ultiessentials.warp.delete",
    description = "删除地标点"
)
public class DelWarpCommand extends BaseEssentialsCommand {
    
    @Autowired
    private WarpService warpService;
    
    @CmdMapping(format = "<name>")
    public void delWarp(@CmdSender Player player, @CmdParam("name") String name) {
        boolean deleted = warpService.deleteWarp(name);
        
        if (deleted) {
            player.sendMessage(i18n("已删除地标点: ") + name);
        } else {
            player.sendMessage(i18n("地标点不存在: ") + name);
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /delwarp <名称>"));
        sender.sendMessage(i18n("删除指定的地标点"));
    }
    
    @Override
    protected List<String> suggest(Player player, Command command, String[] args) {
        if (args.length == 1) {
            return warpService.getAllWarps().stream()
                .map(w -> w.getName())
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return super.suggest(player, command, args);
    }
}
