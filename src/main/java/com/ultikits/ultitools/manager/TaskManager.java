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

/**
 * Manages scheduled tasks for plugin modules.
 * <p>
 * Scans beans for {@link Scheduled} annotated methods and registers them
 * as Bukkit tasks. Automatically cancels all tasks when a plugin is unloaded.
 * <p>
 * 管理插件模块的定时任务。扫描 Bean 中标注 {@link Scheduled} 的方法并注册为 Bukkit 任务。
 * 插件卸载时自动取消所有任务。
 *
 * @since 6.2.0
 */
public class TaskManager {

    private final Map<UltiToolsPlugin, List<BukkitTask>> pluginTasks = new HashMap<>();
    private final JavaPlugin hostPlugin;

    public TaskManager(JavaPlugin hostPlugin) {
        this.hostPlugin = hostPlugin;
    }

    /**
     * Scan a bean for {@link Scheduled} methods and register them as Bukkit tasks.
     * <p>
     * 扫描 Bean 中的 {@link Scheduled} 方法并注册为 Bukkit 任务。
     *
     * @param plugin the owning plugin module
     * @param bean   the bean instance to scan
     */
    public void registerScheduledMethods(UltiToolsPlugin plugin, Object bean) {
        // Walk the class hierarchy to find @Scheduled methods (handles CGLIB proxies)
        Class<?> targetClass = getTargetClass(bean.getClass());

        for (Method method : targetClass.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled == null) {
                continue;
            }

            // Validate method signature
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
            // Use a final reference to the method for the lambda
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

            pluginTasks.computeIfAbsent(plugin, k -> new ArrayList<>()).add(task);

            Bukkit.getLogger().log(Level.FINE,
                    String.format("[UltiTools-API] Registered @Scheduled task: %s.%s (delay=%d, period=%d, async=%s)",
                            targetClass.getSimpleName(), method.getName(),
                            scheduled.delay(), scheduled.period(), scheduled.async()));
        }
    }

    /**
     * Cancel all scheduled tasks for a plugin.
     * <p>
     * 取消插件的所有定时任务。
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
     * Get the original class, unwrapping CGLIB proxies.
     * CGLIB proxy classes contain "$$" in their name.
     */
    private Class<?> getTargetClass(Class<?> clazz) {
        Class<?> current = clazz;
        while (current.getName().contains("$$")) {
            current = current.getSuperclass();
            if (current == null || current == Object.class) {
                return clazz; // Fallback to original
            }
        }
        return current;
    }

    /**
     * Get the number of registered tasks for a plugin (for testing).
     */
    int getTaskCount(UltiToolsPlugin plugin) {
        List<BukkitTask> tasks = pluginTasks.get(plugin);
        return tasks == null ? 0 : tasks.size();
    }
}
