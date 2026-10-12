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
import com.ultikits.ultitools.utils.VersionLookup;
import com.ultikits.ultitools.utils.VersionUtils;

import lombok.Getter;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;

/**
 * Manages version checking and update notifications for UltiTools-API and modules.
 * Checks once at startup, stores results for later querying by commands and listeners.
 * <p>
 * As of 6.3.0 (Phase 19, D19-032):
 * <ul>
 *   <li><b>The check stops when UltiTools disables.</b> {@link #stop()} is the first step of
 *   {@code UltiTools#onDisable}: it cancels the task if it has not started, and the running check
 *   looks at the stop flag before each lookup and before logging its result, so a server stopped
 *   while the check is in flight makes no further lookup and logs nothing at INFO or above.
 *   Anything thrown after the flag is set -- typically the closed plugin JAR, {@code zip file
 *   closed}, while a class is loaded -- is logged at FINE. The disable does not wait for a lookup
 *   in progress: an HTTP call cannot be interrupted, and waiting could hold the shutdown for up to
 *   the 30-second read timeout. This follows the panel-message shutdown fix (#621).</li>
 *   <li><b>"Up to date" means every lookup confirmed it.</b> A lookup that failed (unreachable
 *   catalogue, error status, unreadable body) is recorded in {@link #getUnconfirmed()}, and the
 *   check then reports "Update check incomplete" naming what could not be confirmed, instead of
 *   "All plugins are up to date!".</li>
 * </ul>
 *
 * @since 6.2.0
 */
@ApiStatus.Internal
public class UpdateManager {

    /** The name the framework's own lookup is reported under, in logs and in {@link #getUnconfirmed()}. */
    public static final String FRAMEWORK_NAME = "UltiTools-API";

    private static final String LOG_PREFIX = "[UltiTools-API] ";

    private final Logger logger;

    /** Set by {@link #stop()}; read before each lookup and before the result is logged. */
    private volatile boolean stopped;

    /** The scheduled check, cancelled by {@link #stop()}; {@code null} until {@link #start(Plugin)}. */
    private volatile BukkitTask task;

    /**
     * What the last completed check could not confirm, in lookup order: {@link #FRAMEWORK_NAME}
     * and module names. Replaced as a whole when a check completes.
     */
    private volatile List<String> unconfirmed = Collections.emptyList();

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
     * Schedules the check on an asynchronous task, unless {@link #stop()} was already called.
     * Called once per {@code onEnable}, from the framework's start-up task.
     *
     * @param plugin the plugin that owns the task (UltiTools)
     * @since 6.3.0
     */
    public void start(Plugin plugin) {
        if (stopped) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::checkUpdatesSync);
    }

    /**
     * Stops the check: called as the first step of {@code UltiTools#onDisable}. Cancels the task if
     * it has not started; a check already running makes no further lookup, logs nothing at INFO or
     * above from here on, and reports anything thrown at FINE. Does not wait for a lookup in
     * progress. Safe to call more than once, and before {@link #start(Plugin)}.
     *
     * @since 6.3.0
     */
    public void stop() {
        stopped = true;
        BukkitTask scheduled = task;
        if (scheduled != null) {
            scheduled.cancel();
        }
    }

    /**
     * @return {@code true} once {@link #stop()} has been called
     * @since 6.3.0
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * What the last completed check could not confirm: {@link #FRAMEWORK_NAME} when the framework
     * lookup failed, and the name of each module whose lookup failed. Empty before a check
     * completes and when every lookup established its answer.
     *
     * @return an unmodifiable list, never {@code null}
     * @since 6.3.0
     */
    public List<String> getUnconfirmed() {
        return unconfirmed;
    }

    /**
     * Run update checks synchronously. Called from async context (the task {@link #start(Plugin)}
     * schedules). Returns at once, logging nothing, when {@link #stop()} has been called.
     */
    public void checkUpdatesSync() {
        if (stopped) {
            return;
        }
        try {
            logger.log(Level.INFO, LOG_PREFIX + UltiTools.getInstance().i18n("正在检查版本更新..."));

            List<String> failed = new ArrayList<>();
            checkFrameworkUpdate(failed);
            checkModuleUpdates(failed);
            if (stopped) {
                logger.log(Level.FINE, LOG_PREFIX + "Update check ended: UltiTools is disabling");
                return;
            }
            unconfirmed = Collections.unmodifiableList(failed);
            logResults(failed);

            checkComplete = true;
        } catch (RuntimeException | LinkageError e) {
            if (!stopped) {
                throw e;
            }
            logger.log(Level.FINE, LOG_PREFIX + "Update check ended while UltiTools was disabling: " + e);
        }
    }

    private void checkFrameworkUpdate(List<String> failed) {
        if (stopped) {
            return;
        }
        try {
            String currentVersion = UltiTools.getEnv().getString("version");
            VersionLookup lookup = VersionUtils.lookUpUltiToolsNewestVersion();
            if (lookup.isFailed()) {
                recordFailedLookup(failed, FRAMEWORK_NAME, lookup);
                return;
            }
            String newestVersion = lookup.getVersion();
            if (newestVersion != null && currentVersion != null
                    && VersionComparatorUtil.compare(currentVersion, newestVersion) < 0) {
                UpdateInfo info = new UpdateInfo();
                info.setPluginName(FRAMEWORK_NAME);
                info.setCurrentVersion(currentVersion);
                info.setLatestVersion(newestVersion);
                frameworkUpdate = info;
            }
        } catch (Exception e) {
            failed.add(FRAMEWORK_NAME);
            logThrown(LOG_PREFIX + "Failed to check framework update: " + e.getMessage());
        }
    }

    private void checkModuleUpdates(List<String> failed) {
        if (stopped) {
            return;
        }
        List<UltiToolsPlugin> plugins = UltiTools.getInstance().getPluginManager().getPluginList();
        for (UltiToolsPlugin plugin : plugins) {
            if (stopped) {
                return;
            }
            String idString = plugin.getIdentifyString();
            if (idString == null || idString.isEmpty()) {
                continue;
            }
            try {
                VersionLookup lookup = PluginInstallUtils.lookUpPluginLatestVersion(idString, this::isStopped);
                if (lookup.isFailed()) {
                    recordFailedLookup(failed, plugin.getPluginName(), lookup);
                    continue;
                }
                String latestVersion = lookup.getVersion();
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
                failed.add(plugin.getPluginName());
                logThrown(LOG_PREFIX + "Failed to check update for " + plugin.getPluginName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * A lookup that answered but established nothing: the name joins the incomplete line, and the
     * cause goes to FINE only, so a catalogue that cannot be reached produces one INFO line per
     * start rather than one WARNING per module.
     */
    private void recordFailedLookup(List<String> failed, String name, VersionLookup lookup) {
        failed.add(name);
        logger.log(Level.FINE, LOG_PREFIX + "Update lookup for " + name + " established nothing: "
                + lookup.getFailure());
    }

    /**
     * An exception from a lookup: a WARNING while UltiTools runs, as before 6.3.0; FINE once
     * {@link #stop()} has been called, because then it is the shutdown's doing (the closed plugin
     * JAR), not a fault worth an operator's attention.
     */
    private void logThrown(String message) {
        logger.log(stopped ? Level.FINE : Level.WARNING, message);
    }

    /**
     * One INFO line of the result, unless {@link #stop()} has been called: a disable that arrives
     * while the result is being written silences the lines still to come.
     */
    private void info(String message) {
        if (!stopped) {
            logger.log(Level.INFO, message);
        }
    }

    private void logResults(List<String> failed) {
        if (frameworkUpdate != null) {
            info(String.format("[UltiTools-API] "
                    + UltiTools.getInstance().i18n("UltiTools-API有新版本 %s 可用（当前：%s）"),
                frameworkUpdate.getLatestVersion(), frameworkUpdate.getCurrentVersion()));
            info(String.format("[UltiTools-API] "
                    + UltiTools.getInstance().i18n("下载地址：%s"),
                "https://github.com/UltiKits/UltiTools-Reborn/releases/latest"));
        }
        if (!moduleUpdates.isEmpty()) {
            info(String.format("[UltiTools-API] "
                    + UltiTools.getInstance().i18n("模块更新可用（%d个）："), moduleUpdates.size()));
            for (UpdateInfo info : moduleUpdates.values()) {
                info(String.format("[UltiTools-API]   %s %s -> %s",
                    info.getPluginName(), info.getCurrentVersion(), info.getLatestVersion()));
            }
        }
        if (!failed.isEmpty()) {
            info(LOG_PREFIX + String.format(
                    UltiTools.getInstance().i18n("更新检查未完成：无法确认 %s 的最新版本。"), String.join(", ", failed)));
        } else if (frameworkUpdate == null && moduleUpdates.isEmpty()) {
            info(LOG_PREFIX
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
