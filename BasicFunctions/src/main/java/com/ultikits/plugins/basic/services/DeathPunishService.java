package com.ultikits.plugins.basic.services;

import com.ultikits.ultitools.UltiTools;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author qianmo, wisdomme
 */
@Service
public class DeathPunishService {
    public void takeMoney(Player player, int money) {
        Economy economy = UltiTools.getInstance().getEconomy();
        economy.withdrawPlayer(player, Math.min(economy.getBalance(player), money));
    }

    public void execCommand(List<String> cmd, String playerName) {
        for (String s : cmd) {
            Bukkit.getServer().dispatchCommand(
                    Bukkit.getServer().getConsoleSender(), s.replace("{PLAYER}", playerName)
            );
        }
    }

    public void takeItem(Player player, int drop, List<String> whitelist) {
        Inventory inventory = player.getInventory();
        List<Integer> inventorySlot = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !whitelist.contains(item.getType().toString())) {
                inventorySlot.add(i);
            }
        }
        Collections.shuffle(inventorySlot);
        List<Integer> ints = new ArrayList<>();
        for (int i = 0; i < drop; i++) {
            if (i >= inventorySlot.size()) break;
            ints.add(inventorySlot.get(i));
        }
        for (Integer slot : ints) {
            ItemStack itemStack = inventory.getItem(slot);
            if (itemStack == null) continue;
            itemStack.setAmount(itemStack.getAmount() - 1);
            inventory.setItem(slot, itemStack);
        }
    }
}
