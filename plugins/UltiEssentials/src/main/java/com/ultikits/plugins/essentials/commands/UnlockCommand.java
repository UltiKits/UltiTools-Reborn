package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.entity.ChestLockData;
import com.ultikits.plugins.essentials.service.ChestLockService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.*;
import org.bukkit.block.Block;
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
public class UnlockCommand extends AbstractCommandExecutor {
    
    @Autowired
    private ChestLockService chestLockService;
    
    @CmdMapping(format = "")
    public void unlock(@CmdSender Player player) {
        Block target = player.getTargetBlockExact(5);
        
        if (target == null) {
            player.sendMessage(UltiEssentials.getInstance().i18n("§c请看向一个容器"));
            return;
        }
        
        ChestLockService.UnlockResult result = chestLockService.unlockBlock(target, player);
        
        switch (result) {
            case SUCCESS:
                player.sendMessage(UltiEssentials.getInstance().i18n("§a已解锁该容器"));
                break;
            case NOT_LOCKED:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c该容器未被锁定"));
                break;
            case NOT_OWNER:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c你不是该容器的主人"));
                break;
        }
    }
    
    @CmdMapping(format = "info")
    public void info(@CmdSender Player player) {
        Block target = player.getTargetBlockExact(5);
        
        if (target == null) {
            player.sendMessage(UltiEssentials.getInstance().i18n("§c请看向一个容器"));
            return;
        }
        
        ChestLockData lock = chestLockService.getLock(target.getLocation());
        
        if (lock == null) {
            player.sendMessage(UltiEssentials.getInstance().i18n("§7该容器未被锁定"));
        } else {
            player.sendMessage(UltiEssentials.getInstance().i18n("§6=== 容器锁定信息 ==="));
            player.sendMessage(UltiEssentials.getInstance().i18n("§7主人: §f") + lock.getOwnerName());
            player.sendMessage(UltiEssentials.getInstance().i18n("§7位置: §f") + 
                lock.getWorld() + " (" + lock.getX() + ", " + lock.getY() + ", " + lock.getZ() + ")");
        }
    }
    
    @Override
    protected void handleHelp(Player player) {
        player.sendMessage(UltiEssentials.getInstance().i18n("用法: /unlock"));
        player.sendMessage(UltiEssentials.getInstance().i18n("看向一个容器并使用此命令来解锁它"));
        player.sendMessage(UltiEssentials.getInstance().i18n("/unlock info - 查看锁定信息"));
    }
}
