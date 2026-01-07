package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.service.HomeService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.annotations.I18n;
import org.bukkit.entity.Player;

/**
 * Command to set a home location.
 * <p>
 * 设置家位置的命令。
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"sethome", "sh"}, permission = "ultiessentials.sethome", description = "设置家")
@I18n("sethome.description")
public class SetHomeCommand extends BaseEssentialsCommand {
    
    @Autowired
    private HomeService homeService;
    
    @Autowired
    private EssentialsConfig config;
    
    /**
     * Set default home (named "home").
     */
    @CmdMapping(format = "")
    public void setDefaultHome(@CmdSender Player player) {
        setHome(player, "home");
    }
    
    /**
     * Set a named home.
     */
    @CmdMapping(format = "<name>")
    public void setHome(@CmdSender Player player, @CmdParam("name") String name) {
        if (!config.isHomeEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }
        
        HomeService.SetHomeResult result = homeService.setHome(player, name);
        
        switch (result) {
            case CREATED:
                player.sendMessage(i18n("家已设置！") + " (" + name.toLowerCase() + ")");
                int current = homeService.getHomeCount(player.getUniqueId());
                int max = homeService.getMaxHomes(player);
                player.sendMessage(i18n("当前家数量") + ": " + current + "/" + max);
                break;
            case UPDATED:
                player.sendMessage(i18n("家位置已更新！") + " (" + name.toLowerCase() + ")");
                break;
            case LIMIT_REACHED:
                player.sendMessage(i18n("你已达到最大家数量限制"));
                player.sendMessage(i18n("使用 /delhome <名称> 删除一个家"));
                break;
            case INVALID_NAME:
                player.sendMessage(i18n("无效的家名称"));
                break;
            case DISABLED:
                player.sendMessage(i18n("该功能已禁用"));
                break;
        }
    }
}
