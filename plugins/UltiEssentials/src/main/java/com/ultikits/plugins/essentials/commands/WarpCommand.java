package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.plugins.essentials.service.WarpService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for teleporting to warps.
 * <p>
 * Usage: /warp <name>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"warp", "w"},
    permission = "ultiessentials.warp.use",
    description = "传送到地标点"
)
public class WarpCommand extends BaseEssentialsCommand {
    
    @Autowired
    private WarpService warpService;
    
    @CmdMapping(format = "<name>")
    public void warp(@CmdSender Player player, @CmdParam("name") String name) {
        TeleportResult result = warpService.teleportToWarp(player, name);
        
        switch (result) {
            case SUCCESS:
                player.sendMessage(i18n("已传送到地标点: ") + name);
                break;
            case WARMUP_STARTED:
                player.sendMessage(i18n("正在传送，请不要移动..."));
                break;
            case NOT_FOUND:
                player.sendMessage(i18n("地标点不存在: ") + name);
                break;
            case WORLD_NOT_FOUND:
                player.sendMessage(i18n("地标点所在世界不存在"));
                break;
            case NO_PERMISSION:
                player.sendMessage(i18n("你没有权限使用该地标点"));
                break;
            case ALREADY_TELEPORTING:
                player.sendMessage(i18n("你正在传送中，请稍候"));
                break;
            case DISABLED:
                player.sendMessage(i18n("地标功能已禁用"));
                break;
            default:
                // Handle any unexpected result types
                break;
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /warp <名称>"));
        sender.sendMessage(i18n("传送到指定的地标点"));
    }
    
    @Override
    protected List<String> suggest(Player player, Command command, String[] args) {
        if (args.length == 1) {
            return warpService.getAccessibleWarps(player).stream()
                .map(w -> w.getName())
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return super.suggest(player, command, args);
    }
}
