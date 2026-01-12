package com.ultikits.plugins.sidebar.commands;

import com.ultikits.plugins.sidebar.UltiSideBar;
import com.ultikits.plugins.sidebar.service.SideBarService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Sidebar toggle command.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"sidebar", "sb"},
    permission = "ultisidebar.toggle",
    description = "sidebar_command_description"
)
public class SideBarCommand extends AbstractCommendExecutor {
    
    @Autowired
    private SideBarService sideBarService;
    
    @CmdMapping(format = "toggle")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void toggle(@CmdSender Player player) {
        boolean enabled = sideBarService.toggleSidebar(player);
        if (enabled) {
            player.sendMessage(UltiSideBar.getInstance().i18n("sidebar_toggle_on"));
        } else {
            player.sendMessage(UltiSideBar.getInstance().i18n("sidebar_toggle_off"));
        }
    }
    
    @CmdMapping(format = "on")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void on(@CmdSender Player player) {
        sideBarService.enableSidebar(player);
        player.sendMessage(UltiSideBar.getInstance().i18n("sidebar_toggle_on"));
    }
    
    @CmdMapping(format = "off")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void off(@CmdSender Player player) {
        sideBarService.disableSidebar(player);
        player.sendMessage(UltiSideBar.getInstance().i18n("sidebar_toggle_off"));
    }
    
    @CmdMapping(format = "reload", permission = "ultisidebar.admin")
    public void reload(@CmdSender CommandSender sender) {
        UltiSideBar.getInstance().reloadSelf();
        sender.sendMessage(UltiSideBar.getInstance().i18n("sidebar_reloaded"));
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender CommandSender sender) {
        sender.sendMessage(UltiSideBar.getInstance().i18n("sidebar_help_title"));
        sender.sendMessage(UltiSideBar.getInstance().i18n("sidebar_help_toggle"));
        sender.sendMessage(UltiSideBar.getInstance().i18n("sidebar_help_on"));
        sender.sendMessage(UltiSideBar.getInstance().i18n("sidebar_help_off"));
        if (sender.hasPermission("ultisidebar.admin")) {
            sender.sendMessage(UltiSideBar.getInstance().i18n("sidebar_help_reload"));
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        help(sender);
    }
    
    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = Arrays.asList("toggle", "on", "off");
            if (sender.hasPermission("ultisidebar.admin")) {
                suggestions = Arrays.asList("toggle", "on", "off", "reload");
            }
            String input = args[0].toLowerCase();
            return suggestions.stream()
                .filter(s -> s.startsWith(input))
                .collect(java.util.stream.Collectors.toList());
        }
        return super.suggest(sender, args);
    }
}
