package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.service.TpaService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.annotations.I18n;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Command to deny a TPA request.
 * <p>
 * 拒绝 TPA 请求的命令。
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"tpdeny", "tpno", "tpcancel"}, permission = "ultiessentials.tpdeny", description = "拒绝传送请求")
@I18n("tpdeny.description")
public class TpDenyCommand extends BaseEssentialsCommand {
    
    @Autowired
    private TpaService tpaService;
    
    @Autowired
    private EssentialsConfig config;
    
    /**
     * Deny a pending TPA request.
     */
    @CmdMapping(format = "")
    public void denyTpa(@CmdSender Player player) {
        if (!config.isTpaEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }
        
        TpaService.TpaRequest request = tpaService.getRequest(player.getUniqueId());
        if (request == null) {
            player.sendMessage(i18n("没有待处理的传送请求"));
            return;
        }
        
        TpaService.TpaResult result = tpaService.denyRequest(player);
        
        if (result == TpaService.TpaResult.DENIED) {
            player.sendMessage(i18n("已拒绝传送请求"));
        }
    }
}
