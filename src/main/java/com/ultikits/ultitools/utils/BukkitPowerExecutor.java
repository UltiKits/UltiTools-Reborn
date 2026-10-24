package com.ultikits.ultitools.utils;

import java.io.File;
import java.util.function.Supplier;
import com.ultikits.ultitools.UltiTools;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Turns the handler's stop or restart into one main-thread scheduler task, and answers whether a
 * restart would actually restart this server.
 * <p>
 * The action is scheduled {@value #DELAY_TICKS} ticks after the result frame was handed to the
 * socket, so the panel always receives its answer first. Scheduling goes through
 * {@link PluginInitiationUtils#scheduleUnlessDisabling(String, java.util.function.Supplier)}, the
 * single hand-off check every scheduler call reachable from the socket thread uses: while the
 * framework is disabling the work is dropped with one FINE line instead of throwing.
 * <p>
 * Restart is supported only when the file the server would run — {@code settings.restart-script}
 * in {@code spigot.yml}, {@value #DEFAULT_RESTART_SCRIPT} when unset — exists as a regular file,
 * resolved against the server's working directory when the configured value is relative. Spigot's
 * own restart stops the server when that file is missing; this check keeps a requested restart
 * from silently becoming a stop.
 * <p>
 * This is the only Bukkit-facing part of the power path; the handler stays unit-testable without a
 * live server. Package-private framework plumbing; not for module use.
 *
 * @since 6.3.1
 */
final class BukkitPowerExecutor implements PowerActionHandler.PowerExecutor {

    /** The delay between the result frame and the stop or restart, matching the contract's "1 s". */
    static final long DELAY_TICKS = 20L;

    /** The value Spigot uses for {@code settings.restart-script} when the key is absent. */
    static final String DEFAULT_RESTART_SCRIPT = "./start.sh";

    private final Plugin plugin;
    private final File workingDirectory;
    private final Supplier<String> restartScriptPath;
    private final Runnable stopAction;
    private final Runnable restartAction;

    /**
     * Creates the executor over explicit collaborators.
     *
     * @param plugin the plugin whose scheduler task owns the stop or restart
     * @param workingDirectory the server's working directory, against which a relative
     *                         {@code restart-script} path is resolved
     * @param restartScriptPath supplies {@code settings.restart-script} as configured, or null
     * @param stopAction what the scheduled task does on a stop; production passes
     *                   {@code Bukkit::shutdown}
     * @param restartAction what the scheduled task does on a restart; production passes
     *                      {@code Bukkit.spigot()::restart}
     */
    BukkitPowerExecutor(Plugin plugin, File workingDirectory, Supplier<String> restartScriptPath,
                        Runnable stopAction, Runnable restartAction) {
        this.plugin = plugin;
        this.workingDirectory = workingDirectory;
        this.restartScriptPath = restartScriptPath;
        this.stopAction = stopAction;
        this.restartAction = restartAction;
    }

    /**
     * Builds the production executor: this plugin's scheduler, the process working directory, the
     * live {@code spigot.yml} value, and the platform's own stop and restart calls.
     *
     * @return the executor the wiring uses
     */
    static BukkitPowerExecutor create() {
        return new BukkitPowerExecutor(
                UltiTools.getInstance(),
                new File("."),
                () -> Bukkit.spigot().getConfig().getString("settings.restart-script", DEFAULT_RESTART_SCRIPT),
                Bukkit::shutdown,
                () -> Bukkit.spigot().restart());
    }

    /**
     * Whether the configured restart script exists as a regular file. A blank value falls back to
     * the Spigot default; a directory of that name is not a restart script.
     *
     * @return true when a restart would restart rather than stop
     */
    @Override
    public boolean restartSupported() {
        String configured = restartScriptPath.get();
        String path = configured == null ? DEFAULT_RESTART_SCRIPT : configured.trim();
        if (path.isEmpty()) {
            path = DEFAULT_RESTART_SCRIPT;
        }
        File script = new File(path);
        if (!script.isAbsolute()) {
            script = new File(workingDirectory, path);
        }
        return script.isFile();
    }

    /** Schedules the stop on the main thread, {@value #DELAY_TICKS} ticks from now. */
    @Override
    public void scheduleStop() {
        schedule("power stop", stopAction);
    }

    /** Schedules the restart on the main thread, {@value #DELAY_TICKS} ticks from now. */
    @Override
    public void scheduleRestart() {
        schedule("power restart", restartAction);
    }

    private void schedule(String what, Runnable action) {
        PluginInitiationUtils.scheduleUnlessDisabling(what,
                () -> Bukkit.getScheduler().runTaskLater(plugin, action, DELAY_TICKS));
    }
}
