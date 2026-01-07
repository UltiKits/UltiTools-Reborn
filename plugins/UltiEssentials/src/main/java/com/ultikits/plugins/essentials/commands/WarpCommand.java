package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.service.WarpService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.*;
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
public class WarpCommand extends AbstractCommandExecutor {
    
    @Autowired
    private WarpService warpService;
    
    @CmdMapping(format = "<name>")
    public void warp(@CmdSender Player player, @CmdParam("name") String name) {
        WarpService.TeleportResult result = warpService.teleportToWarp(player, name);
        
        switch (result) {
            case SUCCESS:
                player.sendMessage(UltiEssentials.getInstance().i18n("已传送到地标点: ") + name);
                break;
            case WARMUP_STARTED:
                player.sendMessage(UltiEssentials.getInstance().i18n("正在传送，请不要移动..."));
                break;
            case NOT_FOUND:
                player.sendMessage(UltiEssentials.getInstance().i18n("地标点不存在: ") + name);
                break;
            case WORLD_NOT_FOUND:
                player.sendMessage(UltiEssentials.getInstance().i18n("地标点所在世界不存在"));
                break;
            case NO_PERMISSION:
                player.sendMessage(UltiEssentials.getInstance().i18n("你没有权限使用该地标点"));
                break;
            case ALREADY_TELEPORTING:
                player.sendMessage(UltiEssentials.getInstance().i18n("你正在传送中，请稍候"));
                break;
            case DISABLED:
                player.sendMessage(UltiEssentials.getInstance().i18n("地标功能已禁用"));
                break;
        }
    }
    
    @Override
    protected void handleHelp(Player player) {
        player.sendMessage(UltiEssentials.getInstance().i18n("用法: /warp <名称>"));
        player.sendMessage(UltiEssentials.getInstance().i18n("传送到指定的地标点"));
    }
    
    @Override
    protected List<String> suggest(Player player, String[] args) {
        if (args.length == 1) {
            return warpService.getAccessibleWarps(player).stream()
                .map(w -> w.getName())
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return super.suggest(player, args);
    }
}
