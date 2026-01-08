package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.entity.KitData;
import com.ultikits.plugins.essentials.service.KitService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for deleting kits.
 * <p>
 * Usage: /deletekit <name>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"deletekit", "delkit", "rmkit", "removekit"},
    permission = "ultiessentials.kit.delete",
    description = "删除礼包"
)
public class DeleteKitCommand extends BaseEssentialsCommand {
    
    @Autowired
    private KitService kitService;
    
    @CmdMapping(format = "<name>")
    public void deleteKit(@CmdSender Player player, @CmdParam("name") String name) {
        boolean deleted = kitService.deleteKit(name);
        
        if (deleted) {
            player.sendMessage(i18n("§a已删除礼包: ") + name);
        } else {
            player.sendMessage(i18n("§c礼包不存在: ") + name);
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /deletekit <名称>"));
        sender.sendMessage(i18n("删除指定的礼包"));
    }
    
    @Override
    protected List<String> suggest(Player player, Command command, String[] args) {
        if (args.length == 1) {
            return kitService.getAllKits().stream()
                .map(KitData::getName)
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return super.suggest(player, command, args);
    }
}
