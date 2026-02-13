package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for executing scheduled console commands.
 * <p>
 * 定时执行控制台命令的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class ScheduledCommandService {

    @Autowired
    private EssentialsConfig config;

    private Plugin bukkitPlugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @PostConstruct
    public void init() {
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        startTasks();
    }

    /**
     * Start all scheduled command tasks.
     */
    public void startTasks() {
        if (!config.isScheduledCommandsEnabled()) {
            return;
        }

        for (String entry : config.getScheduledCommands()) {
            int colonIndex = entry.indexOf(':');
            if (colonIndex <= 0) {
                log.warn("Invalid scheduled command entry (missing interval): {}", entry);
                continue;
            }

            int interval;
            try {
                interval = Integer.parseInt(entry.substring(0, colonIndex));
            } catch (NumberFormatException e) {
                log.warn("Invalid interval in scheduled command entry: {}", entry);
                continue;
            }

            if (interval <= 0) {
                log.warn("Interval must be positive in scheduled command entry: {}", entry);
                continue;
            }

            String command = entry.substring(colonIndex + 1).trim();
            if (command.isEmpty()) {
                log.warn("Empty command in scheduled command entry: {}", entry);
                continue;
            }

            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            }.runTaskTimer(bukkitPlugin, interval * 20L, interval * 20L);

            tasks.add(task);
            log.info("Scheduled command (every {}s): {}", interval, command);
        }
    }

    /**
     * Stop all scheduled tasks.
     */
    public void shutdown() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    /**
     * Reload: stop and restart all tasks.
     */
    public void reload() {
        shutdown();
        startTasks();
    }

    /**
     * Parse a scheduled command entry. Returns interval or null if invalid.
     * Exposed for testing.
     *
     * @param entry format "interval:command"
     * @return int array with [interval] or null if invalid
     */
    public static int[] parseEntry(String entry) {
        int colonIndex = entry.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }
        try {
            int interval = Integer.parseInt(entry.substring(0, colonIndex));
            if (interval <= 0) return null;
            return new int[]{interval};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
