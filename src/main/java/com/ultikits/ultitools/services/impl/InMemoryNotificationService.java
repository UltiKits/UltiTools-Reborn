package com.ultikits.ultitools.services.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.utils.XVersionUtils;
import com.ultikits.ultitools.widgets.Toast;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryNotificationService implements NotificationService {
    @PlayerCache
    private static final Map<UUID, BossBar> atedPlayer = new ConcurrentHashMap<>();

    /**
     * True once ANY instance of this class has registered {@link #atedPlayer} -- a static field
     * shared by every instance -- with the live {@link PlayerCacheManager} for quit-based
     * sweeping (GEN-08, D-03). Deliberately a STATIC flag, not per-instance: registering the
     * same static field once per constructed instance would track it once per instance too,
     * sweeping it redundantly on every quit (harmless, since {@link PlayerCacheManager
     * #registerBean} removes the same map reference each time -- but silently relying on that
     * idempotency rather than registering exactly once is worse than being explicit, per this
     * migration's own plan). Set lazily from {@link #sendBossBarNotification(Player, String,
     * int, BossBar, Sound)} -- the write path -- rather than the constructor, mirroring {@code
     * CooldownValidator}'s identical lazy-first-use rationale.
     */
    private static volatile boolean playerCacheRegistered = false;

    /**
     * Attempts lazy first-use registration of THIS instance -- as the reference-identity handle
     * for the shared static {@link #atedPlayer} field -- with the live {@link
     * PlayerCacheManager} singleton. Guarded by the STATIC {@link #playerCacheRegistered} flag
     * so only the first instance to reach {@link #sendBossBarNotification(Player, String, int,
     * BossBar, Sound)} registers; every later instance's call is a cheap no-op. Safe to call
     * unconditionally on every invocation: also a cheap, safely-no-op-on-failure retry when the
     * core plugin is not yet live (see {@link #playerCacheRegistered}'s javadoc).
     */
    private void ensurePlayerCacheRegistered() {
        if (playerCacheRegistered) {
            return;
        }
        UltiTools instance = UltiTools.getInstance();
        // Checking getPluginManager() too, not just getInstance(), matters especially here:
        // playerCacheRegistered is a STATIC flag shared by every instance, so an earlier test
        // (or an earlier caller) reaching this method while getInstance() is stubbed but
        // getPluginManager() is not would otherwise latch it true forever, permanently skipping
        // the retry a later, genuinely-live chain would have needed.
        if (instance == null || instance.getPluginManager() == null) {
            return;
        }
        PlayerCacheManager.tryRegister(this);
        playerCacheRegistered = true;
    }

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
        ensurePlayerCacheRegistered();
        try {
            BossBar activeBossBar;
            if (atedPlayer.containsKey(player.getUniqueId())) {
                activeBossBar = atedPlayer.get(player.getUniqueId());
                activeBossBar.setProgress(1.0);
            } else {
                if (bossBar == null) {
                    activeBossBar = Bukkit.createBossBar(message, BarColor.GREEN, BarStyle.SOLID);
                } else {
                    activeBossBar = bossBar;
                }
                atedPlayer.put(player.getUniqueId(), activeBossBar);
            }
            activeBossBar.addPlayer(player);
            if (sound != null) {
                player.playSound(player.getLocation(), sound, 10, 1);
            }

            BossBar finalBossBar = activeBossBar;
            new BukkitRunnable() {
                @Override
                public void run() {
                    BossBarTickResult result = processBossBarTick(finalBossBar, player, seconds);
                    if (result.shouldCancel) {
                        this.cancel();
                    }
                }
            }.runTaskTimerAsynchronously(UltiTools.getInstance(), 0L, 5L);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Result object for BossBar tick processing.
     */
    static class BossBarTickResult {
        final boolean shouldCancel;
        final double newProgress;

        BossBarTickResult(boolean shouldCancel, double newProgress) {
            this.shouldCancel = shouldCancel;
            this.newProgress = newProgress;
        }
    }

    /**
     * Process a single tick of the BossBar progress animation.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param bossBar the BossBar to update
     * @param player  the player viewing the BossBar
     * @param seconds the total duration in seconds
     * @return the result containing whether to cancel and the new progress
     */
    static BossBarTickResult processBossBarTick(BossBar bossBar, Player player, int seconds) {
        double progress = bossBar.getProgress();
        double decrement = 1.0 / (seconds * 4);
        progress -= decrement;
        if (progress <= 0) {
            bossBar.removePlayer(player);
            return new BossBarTickResult(true, 0);
        } else {
            bossBar.setProgress(progress);
            return new BossBarTickResult(false, progress);
        }
    }

    /**
     * Get the cached BossBar for a player. Used for testing.
     */
    static BossBar getCachedBossBar(UUID playerUUID) {
        return atedPlayer.get(playerUUID);
    }

    /**
     * Put a BossBar into cache. Used for testing.
     */
    static void cacheBossBar(UUID playerUUID, BossBar bossBar) {
        atedPlayer.put(playerUUID, bossBar);
    }

    /**
     * Clear all cached BossBars. Used for testing.
     */
    static void clearBossBarCache() {
        atedPlayer.clear();
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
        XVersionUtils.sendActionBar(player, message);
        return true;
    }

    @Override
    public boolean sendToastNotification(Player player, String icon, String message, Toast.Style style) {
        Toast.displayTo(player, icon, message, style);
        return true;
    }
}
