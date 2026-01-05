package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Command to view another player's armor and offhand.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"armorsee"}, permission = "ultiessentials.armorsee", description = "查看玩家装备")
public class ArmorseeCommand extends BaseEssentialsCommand {

    private final EssentialsConfig config;

    public ArmorseeCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "<player>")
    public void armorsee(@CmdSender Player sender, @CmdParam("player") Player target) {
        if (!config.isInvseeEnabled()) {
            sender.sendMessage(i18n("该功能已禁用"));
            return;
        }

        if (target == null) {
            sender.sendMessage(i18n("玩家不存在或不在线"));
            return;
        }

        // Create a temporary display inventory
        Inventory armorInventory = Bukkit.createInventory(null, 9,
                String.format(i18n("%s 的装备"), target.getName()));

        ItemStack[] armor = target.getInventory().getArmorContents();
        // Place in reverse order: helmet, chestplate, leggings, boots
        for (int i = 0; i < armor.length; i++) {
            armorInventory.setItem(i, armor[armor.length - 1 - i]);
        }

        // Offhand item
        armorInventory.setItem(5, target.getInventory().getItemInOffHand());

        sender.openInventory(armorInventory);
        sender.sendMessage(String.format(i18n("正在查看 %s 的装备"), target.getName()));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /armorsee <玩家> 查看玩家装备"));
    }
}
