package com.ultikits.plugins.sidebar.commands;

import com.ultikits.plugins.sidebar.service.SideBarService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Sidebar toggle command.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"sidebar", "sb"},
    permission = "ultisidebar.toggle",
    description = "切换侧边栏显示"
)
public class SideBarCommand extends AbstractCommendExecutor {
    
    private final SideBarService sideBarService;
    
    public SideBarCommand(SideBarService sideBarService) {
        this.sideBarService = sideBarService;
    }
    
    @CmdMapping(format = "toggle")
    public void toggle(@CmdSender Player player) {
        boolean enabled = sideBarService.toggleSidebar(player);
        if (enabled) {
            player.sendMessage(ChatColor.GREEN + "侧边栏已开启！");
        } else {
            player.sendMessage(ChatColor.YELLOW + "侧边栏已关闭！");
        }
    }
    
    @CmdMapping(format = "on")
    public void on(@CmdSender Player player) {
        sideBarService.enableSidebar(player);
        player.sendMessage(ChatColor.GREEN + "侧边栏已开启！");
    }
    
    @CmdMapping(format = "off")
    public void off(@CmdSender Player player) {
        sideBarService.disableSidebar(player);
        player.sendMessage(ChatColor.YELLOW + "侧边栏已关闭！");
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== UltiSideBar 帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/sidebar toggle" + ChatColor.WHITE + " - 切换侧边栏");
        player.sendMessage(ChatColor.YELLOW + "/sidebar on" + ChatColor.WHITE + " - 开启侧边栏");
        player.sendMessage(ChatColor.YELLOW + "/sidebar off" + ChatColor.WHITE + " - 关闭侧边栏");
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            help((Player) sender);
        }
    }
}
