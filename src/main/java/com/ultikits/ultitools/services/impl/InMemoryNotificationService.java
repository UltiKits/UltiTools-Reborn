package com.ultikits.ultitools.services.impl;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.widgets.Toast;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class InMemoryNotificationService implements NotificationService {
    private static final Map<UUID, BossBar> atedPlayer = new HashMap<>();

    @Override
    public String getName() {
        return "InMemoryNotificationService";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    public boolean sendBossBarNotification(Player player, String message) {
        return sendBossBarNotification(player, message, 25, null, null);
    }

    @Override
    public boolean sendBossBarNotification(Player player, String message, int seconds) {
        return sendBossBarNotification(player, message, seconds, null, null);
    }

    @Override
    public boolean sendBossBarNotification(Player player, String message, int seconds, Sound sound) {
        return sendBossBarNotification(player, message, seconds, null, sound);
    }

    @Override
    public boolean sendBossBarNotification(Player player, String message, int seconds, BossBar bossBar) {
        return sendBossBarNotification(player, message, seconds, bossBar, null);
    }

    @Override
    public boolean sendBossBarNotification(Player player, String message, int seconds, BossBar bossBar, Sound sound) {
        try {
            if (atedPlayer.containsKey(player.getUniqueId())) {
                bossBar = atedPlayer.get(player.getUniqueId());
                bossBar.setProgress(1.0);
            } else {
                if (bossBar == null) {
                    bossBar = Bukkit.createBossBar(message, BarColor.GREEN, BarStyle.SOLID);
                }
                atedPlayer.put(player.getUniqueId(), bossBar);
            }
            bossBar.addPlayer(player);
            if (sound != null) {
                player.playSound(player.getLocation(), sound, 10, 1);
            }

            BossBar finalBossBar = bossBar;
            new BukkitRunnable() {
                @Override
                public void run() {
                    double progress = finalBossBar.getProgress();
                    progress -= 1.0 / (seconds * 4);
                    if (progress <= 0) {
                        finalBossBar.removePlayer(player);
                        this.cancel();
                    } else {
                        finalBossBar.setProgress(progress);
                    }
                }
            }.runTaskTimerAsynchronously(UltiTools.getInstance(), 0L, 5L);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean sendMessageNotification(Player player, String message) {
        return sendMessageNotification(player, message, null);
    }

    @Override
    public boolean sendMessageNotification(Player player, String message, Sound sound) {
        player.sendMessage(message);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 10, 1);
        }
        return false;
    }

    @Override
    public boolean sendSubTitleNotification(Player player, String subtitle) {
        return sendTitleNotification(player, "", subtitle, null);
    }

    @Override
    public boolean sendSubTitleNotification(Player player, String subtitle, Sound sound) {
        return sendTitleNotification(player, "", subtitle, sound);
    }

    @Override
    public boolean sendTitleNotification(Player player, String title, String subtitle) {
        return sendTitleNotification(player, title, subtitle, null);
    }

    @Override
    public boolean sendTitleNotification(Player player, String title, String subtitle, Sound sound) {
        return sendTitleNotification(player, title, subtitle, sound, 10, 50, 20);
    }

    @Override
    public boolean sendTitleNotification(Player player, String title, String subtitle, Sound sound, int fadeIn, int stay, int fadeOut) {
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 10, 1);
        }
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        return true;
    }

    @Override
    public boolean sendActionBarNotification(Player player, String message) {
        UltiTools.getInstance().getVersionWrapper().sendActionBar(player, message);
        return true;
    }

    @Override
    public boolean sendToastNotification(Player player, String icon, String message, Toast.Style style) {
        Toast.displayTo(player, icon, message, style);
        return true;
    }
}
