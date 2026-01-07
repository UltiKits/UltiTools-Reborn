package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.entity.KitData;
import com.ultikits.plugins.essentials.service.KitService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for claiming kits.
 * <p>
 * Usage: /kit <name>
 *        /kit (to list all kits)
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"kit", "kits"},
    permission = "ultiessentials.kit.use",
    description = "领取礼包"
)
public class KitCommand extends AbstractCommandExecutor {
    
    @Autowired
    private KitService kitService;
    
    @CmdMapping(format = "")
    public void listKits(@CmdSender Player player) {
        List<KitData> availableKits = kitService.getAvailableKits(player);
        
        if (availableKits.isEmpty()) {
            player.sendMessage(UltiEssentials.getInstance().i18n("§c没有可用的礼包"));
            return;
        }
        
        player.sendMessage(UltiEssentials.getInstance().i18n("§6=== 可用礼包 ==="));
        
        for (KitData kit : availableKits) {
            long remaining = kitService.getRemainingCooldown(player.getUniqueId(), kit);
            StringBuilder info = new StringBuilder();
            
            info.append("§e").append(kit.getDisplayName() != null ? kit.getDisplayName() : kit.getName());
            
            if (kit.isOneTime()) {
                if (remaining == -1) {
                    info.append(" §c[已领取]");
                } else {
                    info.append(" §a[一次性]");
                }
            } else if (remaining > 0) {
                info.append(" §7[冷却: ").append(KitService.formatCooldown(remaining)).append("]");
            } else {
                info.append(" §a[可用]");
            }
            
            player.sendMessage(info.toString());
            
            if (kit.getDescription() != null && !kit.getDescription().isEmpty()) {
                player.sendMessage("  §7" + kit.getDescription());
            }
        }
        
        player.sendMessage(UltiEssentials.getInstance().i18n("§7使用 /kit <名称> 领取礼包"));
    }
    
    @CmdMapping(format = "<name>")
    public void claimKit(@CmdSender Player player, @CmdParam("name") String name) {
        KitService.ClaimResult result = kitService.claimKit(player, name);
        
        switch (result) {
            case SUCCESS:
                player.sendMessage(UltiEssentials.getInstance().i18n("§a成功领取礼包: ") + name);
                break;
            case NOT_FOUND:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c礼包不存在: ") + name);
                break;
            case NO_PERMISSION:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c你没有权限使用该礼包"));
                break;
            case ALREADY_CLAIMED:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c你已经领取过该礼包（一次性礼包）"));
                break;
            case ON_COOLDOWN:
                KitData kit = kitService.getKit(name);
                if (kit != null) {
                    long remaining = kitService.getRemainingCooldown(player.getUniqueId(), kit);
                    player.sendMessage(UltiEssentials.getInstance().i18n("§c礼包冷却中，剩余: ") + 
                        KitService.formatCooldown(remaining));
                }
                break;
            case INVENTORY_FULL:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c背包空间不足"));
                break;
            case ERROR:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c领取礼包时发生错误"));
                break;
            case DISABLED:
                player.sendMessage(UltiEssentials.getInstance().i18n("§c礼包功能已禁用"));
                break;
        }
    }
    
    @Override
    protected void handleHelp(Player player) {
        player.sendMessage(UltiEssentials.getInstance().i18n("用法: /kit [名称]"));
        player.sendMessage(UltiEssentials.getInstance().i18n("不带名称显示所有可用礼包"));
        player.sendMessage(UltiEssentials.getInstance().i18n("带名称领取指定礼包"));
    }
    
    @Override
    protected List<String> suggest(Player player, String[] args) {
        if (args.length == 1) {
            return kitService.getAvailableKits(player).stream()
                .map(KitData::getName)
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return super.suggest(player, args);
    }
}
