package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.service.WarpService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command for creating warps.
 * <p>
 * Usage: /setwarp <name> [permission]
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"setwarp", "swarp", "addwarp"},
    permission = "ultiessentials.warp.set",
    description = "创建地标点"
)
public class SetWarpCommand extends BaseEssentialsCommand {
    
    @Autowired
    private WarpService warpService;
    
    @CmdMapping(format = "<name>")
    public void setWarp(@CmdSender Player player, @CmdParam("name") String name) {
        setWarpWithPermission(player, name, null);
    }
    
    @CmdMapping(format = "<name> <permission>")
    public void setWarpWithPermission(
        @CmdSender Player player,
        @CmdParam("name") String name,
        @CmdParam("permission") String permission
    ) {
        WarpService.WarpResult result = warpService.createWarp(
            name,
            player.getLocation(),
            player.getUniqueId(),
            permission
        );
        
        switch (result) {
            case CREATED:
                if (permission != null && !permission.isEmpty()) {
                    player.sendMessage(i18n("已创建地标点: ") + name +
                        i18n(" (权限: ") + permission + ")");
                } else {
                    player.sendMessage(i18n("已创建地标点: ") + name);
                }
                break;
            case ALREADY_EXISTS:
                player.sendMessage(i18n("地标点已存在: ") + name);
                break;
            case INVALID_NAME:
                player.sendMessage(i18n("无效的地标名称（长度需在1-32字符之间）"));
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
        sender.sendMessage(i18n("用法: /setwarp <名称> [权限]"));
        sender.sendMessage(i18n("在当前位置创建一个地标点"));
        sender.sendMessage(i18n("可选：指定访问此地标所需的权限"));
    }
}
