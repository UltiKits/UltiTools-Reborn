package com.ultikits.ultitools.manager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<BukkitTask> created = scanAndSchedule(bean);
        if (!created.isEmpty()) {
            pluginTasks.computeIfAbsent(plugin, k -> new ArrayList<>()).addAll(created);
        }
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
        coreTasks.addAll(scanAndSchedule(bean));
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
     * Scan one bean for {@link Scheduled} methods and schedule each valid one.
     * <p>
     * The single implementation behind all three registration entry points, which differ only in
     * which bucket they file the resulting tasks under. Three copies of this body would be three
     * places to keep a signature check, a scheduling rule or a log line in step.
     *
     * @param bean the instance to scan
     * @return the tasks created, in declaration order; empty if the bean has no valid
     *         {@link Scheduled} method
     * @since 6.3.0
     */
    private List<BukkitTask> scanAndSchedule(Object bean) {
        Class<?> targetClass = getTargetClass(bean.getClass());
        List<BukkitTask> created = new ArrayList<>();

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

            created.add(task);

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
        return created;
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
        List<BukkitTask> created = scanAndSchedule(bean);
        if (!created.isEmpty()) {
            externalTasks.computeIfAbsent(pluginName, k -> new ArrayList<>()).addAll(created);
        }
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
