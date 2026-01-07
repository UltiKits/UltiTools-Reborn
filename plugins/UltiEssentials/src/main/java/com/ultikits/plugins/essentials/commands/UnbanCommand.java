package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.entity.BanData;
import com.ultikits.plugins.essentials.service.BanService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for unbanning players.
 * <p>
 * Usage: /unban <player>
 *        /unbanip <ip>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"unban", "pardon"},
    permission = "ultiessentials.unban",
    description = "解除封禁"
)
public class UnbanCommand extends AbstractCommandExecutor {
    
    @Autowired
    private BanService banService;
    
    @CmdMapping(format = "<player>")
    public void unban(@CmdSender CommandSender sender, @CmdParam("player") String playerName) {
        boolean success = banService.unbanPlayerByName(playerName);
        
        if (success) {
            sender.sendMessage(UltiEssentials.getInstance().i18n("§a已解除 ") + playerName + 
                UltiEssentials.getInstance().i18n(" 的封禁"));
            Bukkit.broadcastMessage(UltiEssentials.getInstance().i18n("§a[解禁] §f") + 
                playerName + " §7的封禁已被解除");
        } else {
            sender.sendMessage(UltiEssentials.getInstance().i18n("§c该玩家未被封禁: ") + playerName);
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(UltiEssentials.getInstance().i18n("用法: /unban <玩家>"));
        sender.sendMessage(UltiEssentials.getInstance().i18n("解除玩家的封禁"));
    }
    
    @Override
    protected List<String> suggest(Player player, String[] args) {
        if (args.length == 1) {
            return banService.getActiveBans().stream()
                .map(BanData::getPlayerName)
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                .distinct()
                .collect(Collectors.toList());
        }
        return super.suggest(player, args);
    }
}
