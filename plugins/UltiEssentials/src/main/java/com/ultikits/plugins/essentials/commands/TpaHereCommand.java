package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.service.TpaService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.annotations.I18n;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Command to send a TPA-here request (request another player to teleport to you).
 * <p>
 * 发送 TPA-here 请求的命令（请求另一个玩家传送到你身边）。
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"tpahere", "tphere"}, permission = "ultiessentials.tpahere", description = "请求玩家传送到你身边")
@I18n("tpahere.description")
public class TpaHereCommand extends BaseEssentialsCommand {
    
    @Autowired
    private TpaService tpaService;
    
    @Autowired
    private EssentialsConfig config;
    
    /**
     * Send TPA-here request to a player.
     */
    @CmdMapping(format = "<player>")
    public void sendTpaHere(@CmdSender Player sender, @CmdParam("player") String playerName) {
        if (!config.isTpaEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }
        
        Player target = Bukkit.getPlayer(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(i18n("玩家不在线"));
            return;
        }
        
        TpaService.TpaResult result = tpaService.sendTpaHereRequest(sender, target);
        handleResult(sender, target, result);
    }
    
    private void handleResult(Player sender, Player target, TpaService.TpaResult result) {
        switch (result) {
            case SENT:
                sender.sendMessage(i18n("传送请求已发送给") + " " + target.getName());
                sender.sendMessage(i18n("等待对方接受..."));
                target.sendMessage(sender.getName() + " " + i18n("请求你传送到他身边"));
                target.sendMessage(i18n("使用 /tpaccept 接受，/tpdeny 拒绝"));
                break;
            case SELF_REQUEST:
                sender.sendMessage(i18n("不能向自己发送传送请求"));
                break;
            case TARGET_BUSY:
                sender.sendMessage(i18n("对方有待处理的传送请求"));
                break;
            case ON_COOLDOWN:
                int remaining = tpaService.getRemainingCooldown(sender.getUniqueId());
                sender.sendMessage(i18n("请稍后再发送请求") + " (" + remaining + "s)");
                break;
            case CROSS_WORLD_DISABLED:
                sender.sendMessage(i18n("不允许跨世界传送"));
                break;
            case DISABLED:
                sender.sendMessage(i18n("该功能已禁用"));
                break;
            default:
                break;
        }
    }
}
