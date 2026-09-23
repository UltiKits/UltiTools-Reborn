package com.ultikits.ultitools.manager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import org.jetbrains.annotations.ApiStatus;

/**
 * Manages scheduled tasks for plugin modules.
 * <p>
 * Scans beans for {@link Scheduled} annotated methods and registers them
 * as Bukkit tasks. Automatically cancels all tasks when a plugin is unloaded.
 * <p>
 * <b>Thread-confinement assumption (IN-02, gate-1 review, 16-REVIEW-residue.md):</b>
 * {@link #pluginTasks}/{@link #externalTasks}/{@link #coreTasks} are plain, non-concurrent
 * collections ({@code HashMap}/{@code ArrayList}). {@link #scanAndSchedule(UltiToolsPlugin, Object, Consumer)}
 * mutates them synchronously, once per successfully-scheduled task, inside its own scan loop
 * (#410) - every current call site ({@code PluginManager.onPluginRegistered},
 * {@link #registerScheduledMethodsCore(Object)}, {@link #registerScheduledMethodsExternal(String,
 * Object)}) runs during plugin loading on the main thread, so this is safe today, but nothing
 * enforces it. A future caller invoking any {@code registerScheduledMethods*} entry point from
 * an async context would race these collections with no compile-time or runtime signal - the
 * same main-thread-only contract {@link com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiScheduler}'s
 * own class javadoc documents explicitly for its collections.
 * <p>
 * <b>Config-bound tasks (#531):</b> a {@link Scheduled} method whose period or delay is bound to
 * a module config key is scheduled from the key's value (seconds, times 20) and kept as a
 * {@link BoundTask} handle in {@link #boundTasks}, so {@link #rescheduleBound(UltiToolsPlugin)}
 * can find that one method's task again after {@code /ul reload}. A method with no binding takes
 * exactly the pre-#531 path -- same scheduler call, same bucket, same log line.
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
    /**
     * Config-bound tasks, per module, as handles that remember their method, their binding, their
     * current timing and their last run -- the per-method record the three buckets above do not
     * keep. Only modules can bind, so there is no external or core counterpart.
     *
     * @since 6.3.0
     */
    private final Map<UltiToolsPlugin, List<BoundTask>> boundTasks = new HashMap<>();
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
        scanAndSchedule(plugin, bean, task ->
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
        scanAndSchedule(null, bean, coreTasks::add);
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
     * <b>#531:</b> a config-bound method is handed to {@link #scheduleBound} instead and recorded
     * in {@link #boundTasks} as soon as it is scheduled, by the same #410 rule. Only a module has
     * a config registry to bind against, so a bound method reached with no {@code module} (an
     * external plugin's or a framework-owned bean) is refused.
     *
     * @param module   the owning module, or {@code null} for an external plugin or the framework
     * @param bean     the instance to scan
     * @param recorder invoked once per successfully scheduled task, in declaration order, with
     *                 that task -- files it into this bean's owning bucket immediately
     * @throws PluginModuleException for a config-bound method with no {@code module}, or for a
     *                               binding that cannot be resolved
     * @since 6.3.0
     */
    private void scanAndSchedule(UltiToolsPlugin module, Object bean, Consumer<BukkitTask> recorder) {
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

            if (ConfigBindings.isBound(scheduled)) {
                if (module == null) {
                    throw bindingOutsideModule(targetClass, method);
                }
                scheduleBound(module, bean, targetClass, method, scheduled);
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
        List<BoundTask> bound = boundTasks.remove(plugin);
        if (bound != null) {
            for (BoundTask handle : bound) {
                try {
                    handle.task.cancel();
                } catch (Exception e) {
                    // Task may already be cancelled
                    Bukkit.getLogger().log(Level.FINE,
                            "[UltiTools-API] Task already cancelled: " + handle.task.getTaskId());
                }
            }
        }
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
     * Schedules one config-bound {@link Scheduled} method from its resolved binding and records
     * its handle in {@link #boundTasks} immediately (#410).
     *
     * @throws PluginModuleException           if the binding cannot be resolved
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a bound value is out of range
     */
    private void scheduleBound(UltiToolsPlugin module, Object bean, Class<?> targetClass, Method method,
                               Scheduled scheduled) {
        BoundTask handle = BoundTask.resolveAtLoad(module, bean, targetClass, method, scheduled);
        method.setAccessible(true);
        handle.armTick = Bukkit.getCurrentTick();
        handle.task = arm(handle, handle.delayTicks);
        boundTasks.computeIfAbsent(module, k -> new ArrayList<>()).add(handle);

        StringBuilder keys = new StringBuilder();
        if (handle.periodSource != null) {
            keys.append(", periodKey=").append(handle.periodSource.key).append('=')
                    .append(handle.periodTicks / ConfigBindings.TICKS_PER_SECOND).append('s');
        }
        if (handle.delaySource != null) {
            keys.append(", delayKey=").append(handle.delaySource.key).append('=')
                    .append(handle.delayTicks / ConfigBindings.TICKS_PER_SECOND).append('s');
        }
        Bukkit.getLogger().log(Level.INFO,
                String.format("[UltiTools-API] Registered config-bound @Scheduled task: %s (delay=%d, period=%d, "
                                + "async=%s, config=%s%s)",
                        handle.owner, handle.delayTicks, handle.periodTicks, scheduled.async(),
                        scheduled.config().getSimpleName(), keys));
    }

    /**
     * Schedules {@code handle}'s method with its current timing, first running after
     * {@code firstDelay} ticks. Each call builds a new runnable -- a {@link BukkitRunnable} can be
     * scheduled only once. Always on the main thread: a bound task cannot be {@code async}
     * ({@link ConfigBindings#checkShape(String, Scheduled)}), so its run is observed exactly where
     * the reload step also runs.
     */
    private BukkitTask arm(BoundTask handle, long firstDelay) {
        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                handle.lastFireTick = Bukkit.getCurrentTick();
                try {
                    handle.method.invoke(handle.bean);
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.WARNING,
                            String.format("[UltiTools-API] Error executing @Scheduled method '%s'", handle.owner), e);
                }
            }
        };
        if (handle.periodTicks <= 0) {
            return runnable.runTaskLater(hostPlugin, firstDelay);
        }
        return runnable.runTaskTimer(hostPlugin, firstDelay, handle.periodTicks);
    }

    /**
     * Applies {@code plugin}'s reloaded configuration to its config-bound {@link Scheduled}
     * tasks. Called by {@code UltiToolsPlugin.reloadSelf()}, through
     * {@code PluginManager.applyReloadedConfigBindings}, only after the configuration reload
     * succeeded.
     * <p>
     * For each bound task whose bound value changed:
     * <ul>
     *   <li>it has run before: the next run is <b>last run + new period</b>, or the next tick if
     *       that moment has already passed;</li>
     *   <li>it has not run yet: the first run is <b>arm tick + new delay</b>, or the next tick if
     *       that moment has already passed.</li>
     * </ul>
     * So a reload never runs a task early and never postpones it by restarting its clock -- a
     * 30-minute interest payment is neither paid on reload nor pushed back by repeated reloads. A
     * task whose bound values did not change is left alone. A bound value that is now out of range
     * ({@code < 1} second, {@code null}, or too large) is not applied: the running value is kept
     * and one WARNING names the module, the key, the rejected value and the value kept.
     * <p>
     * <b>Where "the last run" comes from.</b> A bound task is always sync, so it runs on the main
     * thread, and its last run is observed at the moment it starts -- the same thread this step
     * runs on, so the two cannot race. An {@code async} binding is refused at load instead of
     * predicting when the server dispatches async work: that prediction was wrong for tasks armed
     * at boot (#531 gate-1 round 2, WR-01).
     * <p>
     * Must run on the main thread, where {@link TaskManager}'s collections live; from any other
     * thread it throws before reading them ({@code PluginManager} checks first and logs instead).
     *
     * @param plugin the module whose configuration was just reloaded
     * @since 6.3.0
     */
    public void rescheduleBound(UltiToolsPlugin plugin) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("TaskManager.rescheduleBound must run on the main thread, where the "
                    + "task registry lives; it was called from '" + Thread.currentThread().getName() + "'");
        }
        List<BoundTask> handles = boundTasks.get(plugin);
        if (handles == null || handles.isEmpty()) {
            return;
        }
        for (BoundTask handle : handles) {
            rescheduleOne(plugin, handle);
        }
    }

    private void rescheduleOne(UltiToolsPlugin plugin, BoundTask handle) {
        Set<String> warnedKeys = new TreeSet<>();
        long newPeriodTicks = reloadedTicks(plugin, handle, handle.periodSource, handle.periodTicks, warnedKeys);
        long newDelayTicks = reloadedTicks(plugin, handle, handle.delaySource, handle.delayTicks, warnedKeys);
        if (newPeriodTicks == handle.periodTicks && newDelayTicks == handle.delayTicks) {
            return;
        }
        handle.periodTicks = newPeriodTicks;
        handle.delayTicks = newDelayTicks;

        int lastRun = handle.lastFireTick;
        boolean hasRun = lastRun != BoundTask.NOT_RUN;
        if (hasRun && newPeriodTicks <= 0) {
            Bukkit.getLogger().log(Level.INFO, String.format(
                    "[UltiTools-API] %s: config-bound @Scheduled task %s already ran once; its new delay=%d "
                            + "has nothing to reschedule", plugin.getPluginName(), handle.owner, newDelayTicks));
            return;
        }
        int now = Bukkit.getCurrentTick();
        long due = hasRun ? (long) lastRun + newPeriodTicks : (long) handle.armTick + newDelayTicks;
        long firstDelay = Math.max(1L, due - now);

        handle.task.cancel();
        handle.task = arm(handle, firstDelay);
        Bukkit.getLogger().log(Level.INFO, String.format(
                "[UltiTools-API] %s: rescheduled config-bound @Scheduled task %s after reload "
                        + "(delay=%d, period=%d, async=%s); next run in %d ticks",
                plugin.getPluginName(), handle.owner, newDelayTicks, newPeriodTicks,
                handle.scheduled.async(), firstDelay));
    }

    /**
     * @return the reloaded value of {@code source} in ticks, or {@code currentTicks} if it is
     *         unbound or its new value is out of range (then warned once per key)
     */
    private static long reloadedTicks(UltiToolsPlugin plugin, BoundTask handle, ConfigBindings.Source source,
                                      long currentTicks, Set<String> warnedKeys) {
        if (source == null) {
            return currentTicks;
        }
        Long seconds = source.readSeconds();
        if (ConfigBindings.isValidTimerSeconds(seconds)) {
            return seconds * ConfigBindings.TICKS_PER_SECOND;
        }
        if (warnedKeys.add(source.key)) {
            Bukkit.getLogger().log(Level.WARNING, String.format(
                    "[UltiTools-API] %s: %s is bound to %s key '%s', which has value %s after the reload; %s; "
                            + "keeping %ds",
                    plugin.getPluginName(), handle.owner, source.configName(), source.key, seconds,
                    ConfigBindings.TIMER_RULE, currentTicks / ConfigBindings.TICKS_PER_SECOND));
        }
        return currentTicks;
    }

    /**
     * Load-time check of every config-bound {@link Scheduled} method on {@code bean}, over exactly
     * the methods {@link #scanAndSchedule} would schedule. Used by {@code PluginManager} before any
     * Bukkit side effect, so an invalid binding refuses the one module (#531).
     *
     * @param module the module being assembled
     * @param bean   one of its container singletons
     * @throws PluginModuleException           if a binding cannot be resolved
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a bound value is out of range
     * @since 6.3.0
     */
    static void validateConfigBindings(UltiToolsPlugin module, Object bean) {
        Class<?> targetClass = ProxyFactory.unwrap(bean.getClass());
        for (Method method : targetClass.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled != null && isSchedulableSignature(method) && ConfigBindings.isBound(scheduled)) {
                BoundTask.resolveAtLoad(module, bean, targetClass, method, scheduled);
            }
        }
    }

    /**
     * @param bean a container singleton
     * @return {@code Class.method} of the first config-bound {@link Scheduled} method that
     *         {@link #scanAndSchedule} would schedule, or {@code null}
     * @since 6.3.0
     */
    static String firstBoundMethod(Object bean) {
        Class<?> targetClass = ProxyFactory.unwrap(bean.getClass());
        for (Method method : targetClass.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled != null && isSchedulableSignature(method) && ConfigBindings.isBound(scheduled)) {
                return targetClass.getSimpleName() + "." + method.getName();
            }
        }
        return null;
    }

    /**
     * Refuses any config-bound {@link Scheduled} method on {@code bean} -- for a container with no
     * module config registry (an external plugin's).
     *
     * @param bean one of the container's singletons
     * @throws PluginModuleException naming the first bound method found
     * @since 6.3.0
     */
    static void refuseConfigBindings(Object bean) {
        Class<?> targetClass = ProxyFactory.unwrap(bean.getClass());
        for (Method method : targetClass.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled != null && isSchedulableSignature(method) && ConfigBindings.isBound(scheduled)) {
                throw bindingOutsideModule(targetClass, method);
            }
        }
    }

    /** The two signature rules {@link #scanAndSchedule} skips a method for, without its logging. */
    private static boolean isSchedulableSignature(Method method) {
        return method.getParameterCount() == 0
                && (method.getReturnType() == void.class || method.getReturnType() == Void.class);
    }

    private static PluginModuleException bindingOutsideModule(Class<?> targetClass, Method method) {
        return new PluginModuleException(ErrorCode.CONFIG_ERROR, String.format(
                "Invalid config binding on %s.%s: a config-bound @Scheduled is supported only in an UltiTools "
                        + "module; external plugins and framework-owned objects have no module config registry",
                targetClass.getSimpleName(), method.getName()));
    }

    /**
     * Per-method record of one config-bound {@link Scheduled} task: what to invoke, where its
     * bound values come from, its current timing in ticks, when it was armed and last ran, and
     * its current Bukkit task. Mutated only on the main thread: a bound task is never async.
     */
    static final class BoundTask {
        static final int NOT_RUN = Integer.MIN_VALUE;

        final Object bean;
        final Method method;
        final Scheduled scheduled;
        final String owner;
        final ConfigBindings.Source periodSource;
        final ConfigBindings.Source delaySource;
        long periodTicks;
        long delayTicks;
        /** Tick the task was first armed at load; the anchor for "arm tick + new delay". */
        int armTick;
        /** Observed start of the last run, stamped on the main thread, or {@link #NOT_RUN}. */
        int lastFireTick = NOT_RUN;
        BukkitTask task;

        private BoundTask(Object bean, Method method, Scheduled scheduled, String owner,
                          ConfigBindings.Source periodSource, ConfigBindings.Source delaySource,
                          long periodTicks, long delayTicks) {
            this.bean = bean;
            this.method = method;
            this.scheduled = scheduled;
            this.owner = owner;
            this.periodSource = periodSource;
            this.delaySource = delaySource;
            this.periodTicks = periodTicks;
            this.delayTicks = delayTicks;
        }

        /**
         * Resolves and range-checks a bound method at load. An unbound element keeps its literal.
         */
        static BoundTask resolveAtLoad(UltiToolsPlugin module, Object bean, Class<?> targetClass, Method method,
                                       Scheduled scheduled) {
            String owner = targetClass.getSimpleName() + "." + method.getName();
            ConfigBindings.checkShape(owner, scheduled);
            ConfigBindings.Source period = scheduled.periodKey().isEmpty() ? null
                    : ConfigBindings.resolve(module, owner, scheduled.config(), scheduled.periodKey());
            ConfigBindings.Source delay = scheduled.delayKey().isEmpty() ? null
                    : ConfigBindings.resolve(module, owner, scheduled.config(), scheduled.delayKey());
            long periodTicks = period == null ? scheduled.period() : ticksAtLoad(module, owner, period);
            long delayTicks = delay == null ? scheduled.delay() : ticksAtLoad(module, owner, delay);
            return new BoundTask(bean, method, scheduled, owner, period, delay, periodTicks, delayTicks);
        }

        private static long ticksAtLoad(UltiToolsPlugin module, String owner, ConfigBindings.Source source) {
            Long seconds = source.readSeconds();
            if (!ConfigBindings.isValidTimerSeconds(seconds)) {
                throw ConfigBindings.invalidValueAtLoad(module, owner, source, seconds, ConfigBindings.TIMER_RULE);
            }
            return seconds * ConfigBindings.TICKS_PER_SECOND;
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
        scanAndSchedule(null, bean, task ->
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
        List<BoundTask> bound = boundTasks.get(plugin);
        return (tasks == null ? 0 : tasks.size()) + (bound == null ? 0 : bound.size());
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
