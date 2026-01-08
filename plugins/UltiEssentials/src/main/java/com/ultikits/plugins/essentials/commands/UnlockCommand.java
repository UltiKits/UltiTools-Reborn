package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.entity.ChestLockData;
import com.ultikits.plugins.essentials.service.ChestLockService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Command for unlocking containers.
 * <p>
 * Usage: /unlock (while looking at a container)
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"unlock", "ul"},
    permission = "ultiessentials.lock",
    description = "解锁容器"
)
public class UnlockCommand extends BaseEssentialsCommand {
    
    @Autowired
    private ChestLockService chestLockService;
    
    @CmdMapping(format = "")
    public void unlock(@CmdSender Player player) {
        Block target = player.getTargetBlockExact(5);
        
        if (target == null) {
            player.sendMessage(i18n("§c请看向一个容器"));
            return;
        }
        
        ChestLockService.UnlockResult result = chestLockService.unlockBlock(target, player);
        
        switch (result) {
            case SUCCESS:
                player.sendMessage(i18n("§a已解锁该容器"));
                break;
            case NOT_LOCKED:
                player.sendMessage(i18n("§c该容器未被锁定"));
                break;
            case NOT_OWNER:
                player.sendMessage(i18n("§c你不是该容器的主人"));
                break;
        }
    }
    
    @CmdMapping(format = "info")
    public void info(@CmdSender Player player) {
        Block target = player.getTargetBlockExact(5);
        
        if (target == null) {
            player.sendMessage(i18n("§c请看向一个容器"));
            return;
        }
        
        ChestLockData lock = chestLockService.getLock(target.getLocation());
        
        if (lock == null) {
            player.sendMessage(i18n("§7该容器未被锁定"));
        } else {
            player.sendMessage(i18n("§6=== 容器锁定信息 ==="));
            player.sendMessage(i18n("§7主人: §f") + lock.getOwnerName());
            player.sendMessage(i18n("§7位置: §f") + 
                lock.getWorld() + " (" + lock.getX() + ", " + lock.getY() + ", " + lock.getZ() + ")");
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /unlock"));
        sender.sendMessage(i18n("看向一个容器并使用此命令来解锁它"));
        sender.sendMessage(i18n("/unlock info - 查看锁定信息"));
    }
}
