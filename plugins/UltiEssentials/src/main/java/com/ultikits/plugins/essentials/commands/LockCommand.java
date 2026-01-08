package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.service.ChestLockService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command for locking containers.
 * <p>
 * Usage: /lock (while looking at a container)
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"lock", "l"},
    permission = "ultiessentials.lock",
    description = "锁定容器"
)
public class LockCommand extends BaseEssentialsCommand {
    
    @Autowired
    private ChestLockService chestLockService;
    
    @CmdMapping(format = "")
    public void lock(@CmdSender Player player) {
        Block target = player.getTargetBlockExact(5);
        
        if (target == null) {
            player.sendMessage(i18n("§c请看向一个容器"));
            return;
        }
        
        ChestLockService.LockResult result = chestLockService.lockBlock(target, player);
        
        switch (result) {
            case SUCCESS:
                player.sendMessage(i18n("§a已锁定该容器"));
                break;
            case NOT_LOCKABLE:
                player.sendMessage(i18n("§c该方块无法锁定"));
                break;
            case ALREADY_LOCKED:
                player.sendMessage(i18n("§c该容器已被其他玩家锁定"));
                break;
            case ALREADY_LOCKED_BY_YOU:
                player.sendMessage(i18n("§e你已经锁定了该容器"));
                break;
            case DISABLED:
                player.sendMessage(i18n("§c箱子锁功能已禁用"));
                break;
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /lock"));
        sender.sendMessage(i18n("看向一个容器并使用此命令来锁定它"));
    }
}
