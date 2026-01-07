package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.HomeData;
import com.ultikits.plugins.essentials.service.HomeService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.annotations.I18n;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Command to list all homes.
 * <p>
 * 列出所有家的命令。
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"homes", "homelist"}, permission = "ultiessentials.homes", description = "列出所有家")
@I18n("homes.description")
public class HomesCommand extends BaseEssentialsCommand {
    
    @Autowired
    private HomeService homeService;
    
    @Autowired
    private EssentialsConfig config;
    
    /**
     * List all homes.
     */
    @CmdMapping(format = "")
    public void listHomes(@CmdSender Player player) {
        if (!config.isHomeEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }
        
        List<HomeData> homes = homeService.getHomes(player.getUniqueId());
        int maxHomes = homeService.getMaxHomes(player);
        
        player.sendMessage("§6========== " + i18n("你的家") + " §7(" + homes.size() + "/" + maxHomes + ") §6==========");
        
        if (homes.isEmpty()) {
            player.sendMessage("§7" + i18n("你还没有设置任何家"));
            player.sendMessage("§7" + i18n("使用 /sethome <名称> 设置一个家"));
        } else {
            for (HomeData home : homes) {
                String worldName = home.getWorld();
                int x = (int) home.getX();
                int y = (int) home.getY();
                int z = (int) home.getZ();
                
                player.sendMessage(String.format(
                    "§e%s §7- §f%s §7(§f%d§7, §f%d§7, §f%d§7)",
                    home.getName(),
                    worldName,
                    x, y, z
                ));
            }
        }
        
        player.sendMessage("§6" + "=".repeat(40));
    }
}
