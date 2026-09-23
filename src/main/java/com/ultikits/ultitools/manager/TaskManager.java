package com.ultikits.ultitools.manager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.aop.ProxyOf;
import org.jetbrains.annotations.ApiStatus;

/**
 * Manages scheduled tasks for plugin modules.
 * <p>
 * Scans beans for {@link Scheduled} annotated methods and registers them
 * as Bukkit tasks. Automatically cancels all tasks when a plugin is unloaded.
 * <p>
 * <b>Thread-confinement assumption (IN-02, gate-1 review, 16-REVIEW-residue.md):</b>
 * {@link #pluginTasks}/{@link #externalTasks}/{@link #coreTasks} are plain, non-concurrent
 * collections ({@code HashMap}/{@code ArrayList}). {@link #scanAndSchedule(Object, Consumer)}
 * mutates them synchronously, once per successfully-scheduled task, inside its own scan loop
 * (#410) - every current call site ({@code PluginManager.onPluginRegistered},
 * {@link #registerScheduledMethodsCore(Object)}, {@link #registerScheduledMethodsExternal(String,
 * Object)}) runs during plugin loading on the main thread, so this is safe today, but nothing
 * enforces it. A future caller invoking any {@code registerScheduledMethods*} entry point from
 * an async context would race these collections with no compile-time or runtime signal - the
 * same main-thread-only contract {@link com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiScheduler}'s
 * own class javadoc documents explicitly for its collections.
 *
 * @since 6.2.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Invokes @Scheduled methods -- see 08-GATE05-TRIAGE.md
@ApiStatus.Internal
public class TaskManager {

    private final Map<UltiToolsPlugin, List<BukkitTask>> pluginTasks = new HashMap<>();
    private final Map<String, List<BukkitTask>> externalTasks = new HashMap<>();
    /**
     * Tasks owned by the framework itself rather than by a plugin module or an external plugin.
     * <p>
     * A framework-owned object belongs to no {@code SimpleContainer}, so it is reached by neither
     * {@link #registerScheduledMethods(UltiToolsPlugin, Object)} nor {@link
     * #registerScheduledMethodsExternal(String, Object)} -- both of which iterate a container's
     * beans. Before 6.3.0 there was no third bucket, which is why {@code
     * PlayerCacheManager.sweepExpiredEntries()} carried a {@code @Scheduled} annotation that was
     * never registered and never ran (#384).
     *
     * @since 6.3.0
     */
    private final List<BukkitTask> coreTasks = new ArrayList<>();
    private final JavaPlugin hostPlugin;

    public TaskManager(JavaPlugin hostPlugin) {
        this.hostPlugin = hostPlugin;
    }

    /**
     * Scan a bean for {@link Scheduled} methods and register them as Bukkit tasks.
     *
     * @param plugin the owning plugin module
     * @param bean   the bean instance to scan
     */
    public void registerScheduledMethods(UltiToolsPlugin plugin, Object bean) {
        scanAndSchedule(bean, task ->
                pluginTasks.computeIfAbsent(plugin, k -> new ArrayList<>()).add(task));
    }

    /**
     * Scan a framework-owned object for {@link Scheduled} methods and register them.
     * <p>
     * The other two entry points key their tasks on a plugin module or an external plugin, and
     * both are reached by iterating a {@code SimpleContainer}'s beans. An object the framework
     * constructs directly -- {@code PlayerCacheManager}, for instance -- belongs to no container
     * and is therefore reached by neither, which is why its {@code @Scheduled} method silently
     * never ran before 6.3.0 (#384). This is the third bucket, for objects the framework owns.
     * <p>
     * Callers must not invoke this ad hoc. The set of framework-owned types whose scheduled
     * methods are registered is declared by {@code PluginManager.FRAMEWORK_SCHEDULED_OWNER_TYPES}
     * and enforced by {@code FrameworkScheduledWiringTest}; adding an object here without adding
     * its type there would reintroduce exactly the drift that guard exists to prevent.
     *
     * @param bean the framework-owned instance to scan
     * @since 6.3.0
     */
    public void registerScheduledMethodsCore(Object bean) {
        scanAndSchedule(bean, coreTasks::add);
    }

    /**
     * Cancel every framework-owned scheduled task.
     * <p>
     * Called from {@code PluginManager.close()}, which {@code UltiTools.onDisable()} invokes. A
     * repeating task that is not cancelled here survives a {@code /reload} and a second copy is
     * scheduled on the next enable.
     *
     * @since 6.3.0
     */
    public void cancelAllCore() {
        for (BukkitTask task : coreTasks) {
            try {
                task.cancel();
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.FINE,
                        "[UltiTools-API] Task already cancelled: " + task.getTaskId());
            }
        }
        coreTasks.clear();
    }

    /**
     * Scan one bean for {@link Scheduled} methods and schedule each valid one, recording each
     * task in its owning bucket as soon as it is created rather than batching them into a list
     * returned at the end.
     * <p>
     * The single implementation behind all three registration entry points, which differ only in
     * which bucket ({@code recorder}) they file the resulting tasks under. Three copies of this
     * body would be three places to keep a signature check, a scheduling rule or a log line in
     * step.
     * <p>
     * <b>#410:</b> before this, tasks were accumulated in a local list and handed to the owning
     * bucket only via {@code created.addAll(...)} in the CALLER, after this method returned. If
     * scheduling a later {@link Scheduled} method threw (e.g. {@code runTaskTimer} throwing
     * because the host plugin became disabled mid-registration), every task already scheduled
     * for earlier methods in this same scan was already a live Bukkit task, but the exception
     * meant this method never returned, so {@code created} never reached any bucket -- {@code
     * cancelAll}/{@code cancelAllExternal}/{@code cancelAllCore} had no record of them and could
     * never cancel them at teardown. Calling {@code recorder} inside the loop, immediately after
     * each task is scheduled, means an exception from a later method can no longer un-record an
     * earlier one: whatever the loop reached before throwing is already exactly where it needs
     * to be for teardown to find it. This also restores the original, pre-three-bucket-split
     * behaviour of recording each task as it was scheduled, one at a time.
     *
     * @param bean     the instance to scan
     * @param recorder invoked once per successfully scheduled task, in declaration order, with
     *                 that task -- files it into this bean's owning bucket immediately
     * @since 6.3.0
     */
    private void scanAndSchedule(Object bean, Consumer<BukkitTask> recorder) {
        Class<?> targetClass = getTargetClass(bean.getClass());

        for (Method method : targetClass.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled == null) {
                continue;
            }

            if (method.getParameterCount() != 0) {
                Bukkit.getLogger().log(Level.WARNING,
                        String.format("[UltiTools-API] @Scheduled method '%s.%s' must have no parameters. Skipping.",
                                targetClass.getSimpleName(), method.getName()));
                continue;
            }
            if (method.getReturnType() != void.class && method.getReturnType() != Void.class) {
                Bukkit.getLogger().log(Level.WARNING,
                        String.format("[UltiTools-API] @Scheduled method '%s.%s' must return void. Skipping.",
                                targetClass.getSimpleName(), method.getName()));
                continue;
            }

            method.setAccessible(true);
            final Method targetMethod = method;

            BukkitRunnable runnable = new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        targetMethod.invoke(bean);
                    } catch (Exception e) {
                        Bukkit.getLogger().log(Level.WARNING,
                                String.format("[UltiTools-API] Error executing @Scheduled method '%s.%s'",
                                        targetClass.getSimpleName(), targetMethod.getName()),
                                e);
                    }
                }
            };

            BukkitTask task;
            if (scheduled.period() <= 0) {
                // One-shot delayed task
                task = scheduled.async()
                        ? runnable.runTaskLaterAsynchronously(hostPlugin, scheduled.delay())
                        : runnable.runTaskLater(hostPlugin, scheduled.delay());
            } else {
                // Repeating task
                task = scheduled.async()
                        ? runnable.runTaskTimerAsynchronously(hostPlugin, scheduled.delay(), scheduled.period())
                        : runnable.runTaskTimer(hostPlugin, scheduled.delay(), scheduled.period());
            }

            // Record in the owning bucket NOW, before touching anything else -- an exception
            // from a LATER @Scheduled method's own scheduling call must not be able to un-record
            // this already-live task (#410).
            recorder.accept(task);

            // INFO, not FINE. Bukkit's default logger configuration does not print FINE, which
            // made "registered but not firing" indistinguishable from "never registered" from
            // outside the JVM -- diagnosing #384 and #382 both required attaching a bytecode
            // probe to establish something this line already knew. There are 16 @Scheduled
            // methods across the whole ecosystem, so this is a bounded amount of startup output.
            Bukkit.getLogger().log(Level.INFO,
                    String.format("[UltiTools-API] Registered @Scheduled task: %s.%s (delay=%d, period=%d, async=%s)",
                            targetClass.getSimpleName(), method.getName(),
                            scheduled.delay(), scheduled.period(), scheduled.async()));
        }
    }

    /**
     * Cancel all scheduled tasks for a plugin.
     *
     * @param plugin the plugin to cancel tasks for
     */
    public void cancelAll(UltiToolsPlugin plugin) {
        List<BukkitTask> tasks = pluginTasks.remove(plugin);
        if (tasks != null) {
            for (BukkitTask task : tasks) {
                try {
                    task.cancel();
                } catch (Exception e) {
                    // Task may already be cancelled
                    Bukkit.getLogger().log(Level.FINE,
                            "[UltiTools-API] Task already cancelled: " + task.getTaskId());
                }
            }
        }
    }

    /**
     * Re-reads every config-bound {@link Scheduled} task of {@code plugin} after a reload.
     *
     * @param plugin the module whose configuration was just reloaded
     * @since 6.3.0
     */
    public void rescheduleBound(UltiToolsPlugin plugin) {
        // Inert until the binding is implemented.
    }

    /**
     * Get the original class, unwrapping proxies generated by {@link ProxyFactory}.
     * <p>
     * Delegates entirely to {@link ProxyFactory#unwrap(Class)} - proxy identity is owned by that
     * class, not derived here. A proxy of a proxy already carries a {@link ProxyOf} marker naming
     * the original target, so this needs no hierarchy walk and no {@code Object.class} fallback:
     * {@code unwrap} returns a non-proxy argument unchanged, which is the same answer the removed
     * fallback was approximating for the common case.
     */
    private Class<?> getTargetClass(Class<?> clazz) {
        return ProxyFactory.unwrap(clazz);
    }

    /**
     * Scan a bean for {@link Scheduled} methods and register them for an external plugin.
     *
     * @param pluginName the external plugin name (used as key)
     * @param bean       the bean instance to scan
     * @since 6.2.2
     */
    public void registerScheduledMethodsExternal(String pluginName, Object bean) {
        scanAndSchedule(bean, task ->
                externalTasks.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(task));
    }

    /**
     * Cancel all scheduled tasks for an external plugin.
     *
     * @param pluginName the external plugin name
     * @since 6.2.2
     */
    public void cancelAllExternal(String pluginName) {
        List<BukkitTask> tasks = externalTasks.remove(pluginName);
        if (tasks != null) {
            for (BukkitTask task : tasks) {
                try {
                    task.cancel();
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.FINE,
                            "[UltiTools-API] Task already cancelled: " + task.getTaskId());
                }
            }
        }
    }

    /**
     * Get the number of registered tasks for a plugin (for testing).
     */
    int getTaskCount(UltiToolsPlugin plugin) {
        List<BukkitTask> tasks = pluginTasks.get(plugin);
        return tasks == null ? 0 : tasks.size();
    }

    /**
     * Get the number of registered framework-owned tasks (for testing).
     *
     * @since 6.3.0
     */
    int getCoreTaskCount() {
        return coreTasks.size();
    }

    /**
     * Get the number of registered tasks for an external plugin (for testing).
     */
    int getExternalTaskCount(String pluginName) {
        List<BukkitTask> tasks = externalTasks.get(pluginName);
        return tasks == null ? 0 : tasks.size();
    }
}
