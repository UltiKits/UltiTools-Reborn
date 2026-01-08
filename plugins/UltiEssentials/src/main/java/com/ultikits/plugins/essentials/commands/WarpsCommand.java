package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.entity.WarpData;
import com.ultikits.plugins.essentials.service.WarpService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Command for listing all warps.
 * <p>
 * Usage: /warps
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"warps", "warplist", "listwarp"},
    permission = "ultiessentials.warp.list",
    description = "列出所有地标点"
)
public class WarpsCommand extends BaseEssentialsCommand {
    
    @Autowired
    private WarpService warpService;
    
    @CmdMapping(format = "")
    public void listWarps(@CmdSender Player player) {
        List<WarpData> accessibleWarps = warpService.getAccessibleWarps(player);
        
        if (accessibleWarps.isEmpty()) {
            player.sendMessage(i18n("没有可用的地标点"));
            return;
        }
        
        player.sendMessage(i18n("§6=== 地标点列表 ==="));
        
        for (WarpData warp : accessibleWarps) {
            StringBuilder info = new StringBuilder();
            info.append("§e").append(warp.getName());
            info.append(" §7- ").append(warp.getWorld());
            info.append(" (").append(String.format("%.1f", warp.getX()));
            info.append(", ").append(String.format("%.1f", warp.getY()));
            info.append(", ").append(String.format("%.1f", warp.getZ())).append(")");
            
            if (warp.getPermission() != null && !warp.getPermission().isEmpty()) {
                info.append(" §c[需要权限]");
            }
            
            player.sendMessage(info.toString());
        }
        
        player.sendMessage(i18n("§7共 ") + accessibleWarps.size() + 
            i18n(" 个可用地标点"));
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /warps"));
        sender.sendMessage(i18n("列出所有你可以访问的地标点"));
    }
}
