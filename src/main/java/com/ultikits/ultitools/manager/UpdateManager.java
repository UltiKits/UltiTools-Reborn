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

/**
 * Manages version checking and update notifications for UltiTools-API and modules.
 * Checks once at startup, stores results for later querying by commands and listeners.
 * <br>
 * 管理UltiTools-API和模块的版本检查和更新通知。
 * 在启动时检查一次，存储结果供命令和监听器查询。
 *
 * @since 6.2.0
 */
public class UpdateManager {

    private final Logger logger;

    @Getter
    private volatile UpdateInfo frameworkUpdate;

    @Getter
    private final Map<String, UpdateInfo> moduleUpdates = new ConcurrentHashMap<>();

    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();

    @Getter
    private volatile boolean checkComplete;

    public UpdateManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Run update checks synchronously. Called from async context (BukkitRunnable).
     * <br>
     * 同步运行更新检查。从异步上下文（BukkitRunnable）调用。
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
     * <br>
     * 检查是否有任何更新（框架或模块）可用。
     *
     * @return true if any updates are available <br> 如果有任何更新可用则返回true
     */
    public boolean hasAnyUpdates() {
        return frameworkUpdate != null || !moduleUpdates.isEmpty();
    }

    /**
     * Check if a player has already been notified about available updates.
     * <br>
     * 检查玩家是否已被通知有可用更新。
     *
     * @param uuid the player's UUID <br> 玩家的UUID
     * @return true if the player has been notified <br> 如果玩家已被通知则返回true
     */
    public boolean isPlayerNotified(UUID uuid) {
        return notifiedPlayers.contains(uuid);
    }

    /**
     * Mark a player as having been notified about available updates.
     * <br>
     * 标记玩家已被通知有可用更新。
     *
     * @param uuid the player's UUID <br> 玩家的UUID
     */
    public void markPlayerNotified(UUID uuid) {
        notifiedPlayers.add(uuid);
    }
}
