package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.service.ScoreboardService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command for toggling scoreboard display.
 * <p>
 * Usage: /scoreboard (toggle)
 *        /sb on/off
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"scoreboard", "sb"},
    permission = "ultiessentials.scoreboard",
    description = "切换计分板显示"
)
public class ScoreboardCommand extends BaseEssentialsCommand {
    
    @Autowired
    private ScoreboardService scoreboardService;
    
    @CmdMapping(format = "")
    public void toggle(@CmdSender Player player) {
        boolean enabled = scoreboardService.toggleScoreboard(player);
        
        if (enabled) {
            player.sendMessage(i18n("§a计分板已启用"));
        } else {
            player.sendMessage(i18n("§c计分板已禁用"));
        }
    }
    
    @CmdMapping(format = "on")
    public void enable(@CmdSender Player player) {
        if (scoreboardService.isEnabled(player)) {
            player.sendMessage(i18n("§e计分板已经启用"));
            return;
        }
        
        scoreboardService.enableScoreboard(player);
        player.sendMessage(i18n("§a计分板已启用"));
    }
    
    @CmdMapping(format = "off")
    public void disable(@CmdSender Player player) {
        if (!scoreboardService.isEnabled(player)) {
            player.sendMessage(i18n("§e计分板已经禁用"));
            return;
        }
        
        scoreboardService.disableScoreboard(player);
        player.sendMessage(i18n("§c计分板已禁用"));
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /scoreboard [on/off]"));
        sender.sendMessage(i18n("切换或设置计分板显示状态"));
    }
}
