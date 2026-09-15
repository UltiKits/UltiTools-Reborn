package com.ultikits.ultitools.manager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import com.ultikits.ultitools.utils.VersionComparatorUtil;
import com.ultikits.ultitools.utils.VersionUtils;

import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;

/**
 * Manages version checking and update notifications for UltiTools-API and modules.
 * Checks once at startup, stores results for later querying by commands and listeners.
 *
 * @since 6.2.0
 */
@ApiStatus.Internal
public class UpdateManager {

    private final Logger logger;

    @Getter
    private volatile UpdateInfo frameworkUpdate;

    @Getter
    private final Map<String, UpdateInfo> moduleUpdates = new ConcurrentHashMap<>();

    /**
     * Players already notified about an available update, for the lifetime of this manager
     * instance -- which is one server run: {@code UltiTools.scheduleStartupMessages()} builds a
     * fresh {@code UpdateManager} exactly once per {@code onEnable()}, so a restart always starts
     * from an empty set. Deliberately NOT {@code @PlayerCache} (#431): GEN-08/D-03 (plan 05-04)
     * registered this field with {@link PlayerCacheManager} for quit-based sweeping to bound its
     * size, but that made a quitting-and-rejoining player look never-notified within the SAME
     * server run, breaking {@link com.ultikits.ultitools.listeners.UpdateJoinListener}'s own
     * documented promise of one notification per player per server session. The set's natural
     * size bound is the number of distinct OP UUIDs that join this run, which does not grow on a
     * repeat quit/rejoin of the same player -- there was no real unbounded-growth hazard here to
     * trade the promise away for. Scoped to this field alone: the three other fields GEN-08/D-03
     * migrated onto {@code PlayerCacheManager} (in {@code InMemoryNotificationService} and
     * {@code InMemeryTeleportService}) represent genuinely connection-scoped state and are
     * untouched.
     */
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();

    @Getter
    private volatile boolean checkComplete;

    public UpdateManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Run update checks synchronously. Called from async context (BukkitRunnable).
     */
    public void checkUpdatesSync() {
        logger.log(Level.INFO, "[UltiTools-API] " + UltiTools.getInstance().i18n("正在检查版本更新..."));

        checkFrameworkUpdate();
        checkModuleUpdates();
        logResults();

        checkComplete = true;
    }

    private void checkFrameworkUpdate() {
        try {
            String currentVersion = UltiTools.getEnv().getString("version");
            String newestVersion = VersionUtils.getUltiToolsNewestVersion();
            if (newestVersion != null && currentVersion != null
                    && VersionComparatorUtil.compare(currentVersion, newestVersion) < 0) {
                UpdateInfo info = new UpdateInfo();
                info.setPluginName("UltiTools-API");
                info.setCurrentVersion(currentVersion);
                info.setLatestVersion(newestVersion);
                frameworkUpdate = info;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[UltiTools-API] Failed to check framework update: " + e.getMessage());
        }
    }

    private void checkModuleUpdates() {
        List<UltiToolsPlugin> plugins = UltiTools.getInstance().getPluginManager().getPluginList();
        for (UltiToolsPlugin plugin : plugins) {
            String idString = plugin.getIdentifyString();
            if (idString == null || idString.isEmpty()) {
                continue;
            }
            try {
                String latestVersion = PluginInstallUtils.getPluginLatestVersion(idString);
                if (latestVersion != null
                        && VersionComparatorUtil.compare(plugin.getVersion(), latestVersion) < 0) {
                    UpdateInfo info = new UpdateInfo();
                    info.setPluginName(plugin.getPluginName());
                    info.setIdentifyString(idString);
                    info.setCurrentVersion(plugin.getVersion());
                    info.setLatestVersion(latestVersion);
                    moduleUpdates.put(plugin.getPluginName(), info);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING,
                    "[UltiTools-API] Failed to check update for " + plugin.getPluginName() + ": " + e.getMessage());
            }
        }
    }

    private void logResults() {
        if (frameworkUpdate != null) {
            logger.log(Level.INFO, String.format("[UltiTools-API] "
                    + UltiTools.getInstance().i18n("UltiTools-API有新版本 %s 可用（当前：%s）"),
                frameworkUpdate.getLatestVersion(), frameworkUpdate.getCurrentVersion()));
            logger.log(Level.INFO, String.format("[UltiTools-API] "
                    + UltiTools.getInstance().i18n("下载地址：%s"),
                "https://github.com/UltiKits/UltiTools-Reborn/releases/latest"));
        }
        if (!moduleUpdates.isEmpty()) {
            logger.log(Level.INFO, String.format("[UltiTools-API] "
                    + UltiTools.getInstance().i18n("模块更新可用（%d个）："), moduleUpdates.size()));
            for (UpdateInfo info : moduleUpdates.values()) {
                logger.log(Level.INFO, String.format("[UltiTools-API]   %s %s -> %s",
                    info.getPluginName(), info.getCurrentVersion(), info.getLatestVersion()));
            }
        }
        if (frameworkUpdate == null && moduleUpdates.isEmpty()) {
            logger.log(Level.INFO, "[UltiTools-API] "
                    + UltiTools.getInstance().i18n("所有插件已是最新版本！"));
        }
    }

    /**
     * Check if any updates (framework or modules) are available.
     *
     * @return true if any updates are available
     */
    public boolean hasAnyUpdates() {
        return frameworkUpdate != null || !moduleUpdates.isEmpty();
    }

    /**
     * Check if a player has already been notified about available updates.
     *
     * @param uuid the player's UUID
     * @return true if the player has been notified
     */
    public boolean isPlayerNotified(UUID uuid) {
        return notifiedPlayers.contains(uuid);
    }

    /**
     * Mark a player as having been notified about available updates. The mark lives for this
     * manager instance's lifetime (one server run) and is not cleared when the player quits
     * (#431).
     *
     * @param uuid the player's UUID
     */
    public void markPlayerNotified(UUID uuid) {
        notifiedPlayers.add(uuid);
    }
}
