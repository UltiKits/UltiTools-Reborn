package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.service.KitService;
import com.ultikits.ultitools.annotations.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Command for creating kits from player inventory.
 * <p>
 * Usage: /createkit <name> [cooldown] [permission]
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"createkit", "setkit", "addkit"},
    permission = "ultiessentials.kit.create",
    description = "创建礼包"
)
public class CreateKitCommand extends BaseEssentialsCommand {
    
    @Autowired
    private KitService kitService;
    
    @CmdMapping(format = "<name>")
    public void createKit(@CmdSender Player player, @CmdParam("name") String name) {
        createKitWithCooldown(player, name, "0");
    }
    
    @CmdMapping(format = "<name> <cooldown>")
    public void createKitWithCooldown(
        @CmdSender Player player,
        @CmdParam("name") String name,
        @CmdParam("cooldown") String cooldown
    ) {
        createKitWithCooldownAndPermission(player, name, cooldown, null);
    }
    
    @CmdMapping(format = "<name> <cooldown> <permission>")
    public void createKitWithCooldownAndPermission(
        @CmdSender Player player,
        @CmdParam("name") String name,
        @CmdParam("cooldown") String cooldown,
        @CmdParam("permission") String permission
    ) {
        long cooldownSeconds;
        try {
            if (cooldown.equalsIgnoreCase("onetime") || cooldown.equals("-1")) {
                cooldownSeconds = -1;
            } else {
                cooldownSeconds = Long.parseLong(cooldown);
            }
        } catch (NumberFormatException e) {
            player.sendMessage(i18n("§c无效的冷却时间，请输入秒数或 'onetime'"));
            return;
        }
        
        // Get items from player's inventory
        ItemStack[] items = player.getInventory().getContents();
        
        KitService.KitResult result = kitService.createKit(
            name,
            items,
            cooldownSeconds,
            permission,
            player.getUniqueId()
        );
        
        switch (result) {
            case SUCCESS:
                player.sendMessage(i18n("§a成功创建礼包: ") + name);
                if (cooldownSeconds == -1) {
                    player.sendMessage(i18n("§7类型: 一次性礼包"));
                } else if (cooldownSeconds > 0) {
                    player.sendMessage(i18n("§7冷却时间: ") + cooldownSeconds + "秒");
                } else {
                    player.sendMessage(i18n("§7冷却时间: 无冷却"));
                }
                if (permission != null) {
                    player.sendMessage(i18n("§7所需权限: ") + permission);
                }
                break;
            case ALREADY_EXISTS:
                player.sendMessage(i18n("§c礼包已存在: ") + name);
                break;
            case INVALID_NAME:
                player.sendMessage(i18n("§c无效的礼包名称（长度需在1-32字符之间）"));
                break;
            case EMPTY_KIT:
                player.sendMessage(i18n("§c背包中没有物品，无法创建礼包"));
                break;
            case SERIALIZATION_ERROR:
                player.sendMessage(i18n("§c保存礼包物品时发生错误"));
                break;
            case DISABLED:
                player.sendMessage(i18n("§c礼包功能已禁用"));
                break;
        }
    }
    
    @Override
    protected void handleHelp(Player player) {
        player.sendMessage(UltiEssentials.getInstance().i18n("用法: /createkit <名称> [冷却秒数] [权限]"));
        player.sendMessage(UltiEssentials.getInstance().i18n("从你的背包物品创建一个礼包"));
        player.sendMessage(UltiEssentials.getInstance().i18n("§7冷却: 数字(秒)，'onetime' 或 '-1' 表示一次性礼包"));
        player.sendMessage(UltiEssentials.getInstance().i18n("§7示例: /createkit starter 3600 ultiessentials.kit.starter"));
    }
}
