package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.utils.EconomyUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Listener for death punishment.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class DeathPunishListener implements Listener {
    
    @Autowired
    private EssentialsConfig config;
    
    private final Random random = new Random();
    
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!config.isDeathPunishEnabled()) {
            return;
        }
        
        Player player = event.getEntity();
        
        // Check if player is in whitelisted world
        if (config.getDeathPunishWorldWhitelist().contains(player.getWorld().getName())) {
            return;
        }
        
        // Check bypass permission
        if (player.hasPermission("ultiessentials.deathpunish.bypass")) {
            return;
        }
        
        StringBuilder message = new StringBuilder();
        message.append(ChatColor.RED).append("死亡惩罚: ");
        
        // Money loss
        if (config.isDeathPunishMoneyEnabled() && EconomyUtils.isAvailable()) {
            double balance = EconomyUtils.getBalance(player);
            double lossPercent = config.getDeathPunishMoneyPercent();
            double maxLoss = config.getDeathPunishMoneyMax();
            
            double loss = balance * (lossPercent / 100.0);
            if (maxLoss > 0 && loss > maxLoss) {
                loss = maxLoss;
            }
            
            if (loss > 0) {
                EconomyUtils.withdraw(player, loss);
                message.append(ChatColor.GOLD).append(String.format("-%.2f金币 ", loss));
            }
        }
        
        // Item drop
        if (config.isDeathPunishItemDropEnabled()) {
            int dropCount = processItemDrop(event);
            if (dropCount > 0) {
                message.append(ChatColor.YELLOW).append(dropCount).append("件物品掉落 ");
            }
        }
        
        // Experience loss
        if (config.isDeathPunishExpEnabled()) {
            int expLoss = (int) (player.getTotalExperience() * (config.getDeathPunishExpPercent() / 100.0));
            if (expLoss > 0) {
                event.setDroppedExp(Math.max(0, event.getDroppedExp() - expLoss));
                message.append(ChatColor.GREEN).append("-").append(expLoss).append("经验 ");
            }
        }
        
        // Execute command punishment
        if (config.isDeathPunishCommandEnabled()) {
            executeCommands(player);
        }
        
        // Send message
        if (message.length() > 10) {
            player.sendMessage(message.toString());
        }
    }
    
    /**
     * Processes item drop punishment.
     * @return number of items dropped
     */
    private int processItemDrop(PlayerDeathEvent event) {
        double dropChance = config.getDeathPunishItemDropChance();
        List<String> whitelist = config.getDeathPunishItemWhitelist();
        
        int dropCount = 0;
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> toDrop = new ArrayList<>();
        
        for (ItemStack item : drops) {
            if (item == null) continue;
            
            // Check whitelist
            if (whitelist.contains(item.getType().name())) {
                continue;
            }
            
            // Random chance to keep
            if (random.nextDouble() * 100 < dropChance) {
                toDrop.add(item);
                dropCount++;
            }
        }
        
        // Keep only dropped items (remove others)
        if (config.isDeathPunishKeepOtherItems()) {
            drops.retainAll(toDrop);
        }
        
        return dropCount;
    }
    
    /**
     * Executes punishment commands.
     */
    private void executeCommands(Player player) {
        List<String> commands = config.getDeathPunishCommands();
        
        for (String command : commands) {
            String parsed = command.replace("{PLAYER}", player.getName())
                                   .replace("%player%", player.getName());
            
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
    }
}
