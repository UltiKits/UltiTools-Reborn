package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.service.HomeService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.annotations.I18n;
import org.bukkit.entity.Player;

/**
 * Command to delete a home.
 * <p>
 * 删除家的命令。
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"delhome", "deletehome", "rmhome"}, permission = "ultiessentials.delhome", description = "删除家")
@I18n("delhome.description")
public class DelHomeCommand extends BaseEssentialsCommand {
    
    @Autowired
    private HomeService homeService;
    
    @Autowired
    private EssentialsConfig config;
    
    /**
     * Delete a home by name.
     */
    @CmdMapping(format = "<name>")
    public void deleteHome(@CmdSender Player player, @CmdParam("name") String name) {
        if (!config.isHomeEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }
        
        boolean deleted = homeService.deleteHome(player.getUniqueId(), name);
        
        if (deleted) {
            player.sendMessage(i18n("家已删除！") + " (" + name.toLowerCase() + ")");
        } else {
            player.sendMessage(i18n("找不到该家"));
        }
    }
}
