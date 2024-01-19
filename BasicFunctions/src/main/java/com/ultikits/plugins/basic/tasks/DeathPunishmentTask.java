package com.ultikits.plugins.basic.tasks;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.config.DeathPunishmentConfig;
import com.ultikits.plugins.basic.services.DeathPunishService;
import com.ultikits.ultitools.UltiTools;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DeathPunishmentTask {
    protected List<UUID> punishQueue = new ArrayList<>();
    @Autowired
    private DeathPunishService deathPunishService;

    public DeathPunishmentTask() {
        DeathPunishmentConfig deathPunishmentConfig = UltiTools.getInstance().getConfigManager().getConfigEntity(BasicFunctions.getInstance(), DeathPunishmentConfig.class);
        new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<UUID> iterator = punishQueue.iterator();
                while (iterator.hasNext()) {
                    UUID uuid;
                    try {
                        uuid = iterator.next();
                    } catch (ConcurrentModificationException e) {
                        continue;
                    }
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null) {
                        continue;
                    }
                    String world = player.getWorld().getName();
                    List<String> list;
                    if (deathPunishmentConfig.isEnableItemDrop()) {
                        list = deathPunishmentConfig.getWorldEnabledItemDrop();
                        for (String s : list) {
                            if (s.equals(world)) {
                                int itemDropOnDeath = deathPunishmentConfig.getItemDropOnDeath();
                                deathPunishService.takeItem(player, itemDropOnDeath, deathPunishmentConfig.getItemDropWhiteList());
                                player.sendMessage(ChatColor.RED + String.format(BasicFunctions.getInstance().i18n("[死亡惩罚]你因为死亡随机掉落了%d个物品！"), itemDropOnDeath));
                            }
                        }
                    }

                    if (deathPunishmentConfig.isEnableMoneyDrop()) {
                        list = deathPunishmentConfig.getWorldEnabledMoneyDrop();
                        for (String s : list) {
                            if (s.equals(world)) {
                                int moneyDropOnDeath = deathPunishmentConfig.getMoneyDropOnDeath();
                                deathPunishService.takeMoney(player, moneyDropOnDeath);
                                player.sendMessage(ChatColor.RED + String.format(BasicFunctions.getInstance().i18n("[死亡惩罚]你因为死亡而扣除了%d个金币！"), moneyDropOnDeath));
                            }
                        }
                    }

                    if (deathPunishmentConfig.isEnablePunishCommands()) {
                        list = deathPunishmentConfig.getWorldEnabledPunishCommands();
                        for (String s : list) {
                            if (s.equals(world)) {
                                UltiTools.getInstance().getServer().getScheduler().callSyncMethod(UltiTools.getInstance(), () -> {
                                    deathPunishService.execCommand(deathPunishmentConfig.getPunishCommands(), player.getName());
                                    return null;
                                });
                            }
                        }
                    }
                    punishQueue.remove(uuid);
                }
            }
        }.runTaskTimerAsynchronously(UltiTools.getInstance(), 0, 20);
    }

    public void addPlayerToQueue(Player player) {
        if (punishQueue.contains(player.getUniqueId())) {
            return;
        }
        punishQueue.add(player.getUniqueId());
    }
}
