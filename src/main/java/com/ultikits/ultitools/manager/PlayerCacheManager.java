package com.ultikits.ultitools.manager;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.jetbrains.annotations.ApiStatus;

/**
 * Manages automatic cleanup of @PlayerCache-annotated fields when players quit.
 * <p>
 * Three field shapes are recognised: a {@code Map} keyed by {@link UUID} (the entire entry --
 * including a nested value, e.g. {@code Map<UUID, Map<String, Long>>} -- is removed on quit), a
 * {@code Set<UUID>} (the UUID itself is removed), and a {@code Map} whose VALUE is a
 * {@link UUID} (every entry whose value equals the quitting player's UUID is removed, other
 * players' entries are left intact). A field whose generic shape cannot be resolved to one of
 * these three is refused at registration time -- an annotation that cannot take effect fails
 * loudly rather than being silently skipped.
 * <p>
 * A non-bean instance -- one that is never resolved from any {@code SimpleContainer}, e.g. a
 * command validator {@code new}-ed directly in a constructor -- can register itself via {@link
 * #tryRegister(Object)} even before the core plugin is enabled; see that method's javadoc.
 * <p>
 * 管理带有 @PlayerCache 注解的字段，在玩家退出时自动清理。
 *
 * @since 6.2.0
 */
@ApiStatus.Internal
public class PlayerCacheManager {

    private static final Logger LOGGER = Logger.getLogger(PlayerCacheManager.class.getName());

    /**
     * Period, in ticks, between two consecutive expiry sweeps ({@link #sweepExpiredEntries()}).
     * <p>
     * Chosen conservatively: this task runs for the entire lifetime of a long-running server, and
     * the per-player state it exists to bound (command cooldowns, usage locks) is expressed in
     * seconds-to-minutes, not milliseconds -- there is no correctness requirement for the sweep to
     * run more often than once every few minutes. 6000 ticks is 5 minutes at the server's nominal
     * 20 ticks/second, which keeps the sweep's own overhead negligible (an empty or small registry
     * costs microseconds per pass) while bounding the worst-case staleness of any expired entry to
     * 5 minutes.
     */
    static final long EXPIRY_SWEEP_PERIOD_TICKS = 6000L;

    private final List<TrackedBean> trackedBeans = new ArrayList<>();

    /**
     * Attempts lazy first-use registration of a non-bean instance with the live {@link
     * PlayerCacheManager} singleton.
     * <p>
     * Accepts any object -- it does not need to be a container-managed bean; this is the entry
     * point objects that are {@code new}-ed directly (e.g. a command validator constructed inside
     * {@code BaseCommandExecutor}'s constructor) use to reach the sweep mechanism, since {@code
     * PluginManager}'s bean-iteration registration loops only ever see objects resolved from a
     * plugin's {@code SimpleContainer}.
     * <p>
     * <b>Safe to call before the core plugin is enabled.</b> If {@link UltiTools#getInstance()}
     * (or its {@code PluginManager} / {@code PlayerCacheManager}) is not yet available -- for
     * example, a bare {@code new MyCommand()} constructed in a unit test with no running server --
     * this method returns without throwing and without registering anything. It is intended to be
     * retried on a later first-use once the core plugin is available; degrading gracefully to
     * "never registered, never swept" in a pure-unit-test context is deliberate (D-03).
     * <p>
     * <b>Idempotent per instance.</b> Calling this method more than once with the same instance
     * (by reference identity, not {@code equals}) results in exactly one tracked entry and one
     * sweep per player quit -- see {@link #registerBean(Object)}.
     * <p>
     * <b>The caller owns unregistration.</b> This method has no matching automatic teardown; a
     * caller that registers itself is responsible for calling {@link #tryUnregister(Object)} on
     * its own teardown, so the manager does not keep tracking an instance whose owner has gone
     * away.
     *
     * @param instance the object to register; scanned for {@link PlayerCache}-annotated fields
     * @since 6.3.0
     */
    public static void tryRegister(Object instance) {
        PlayerCacheManager manager = resolveLive();
        if (manager != null) {
            manager.registerBean(instance);
        }
    }

    /**
     * The static counterpart to {@link #tryRegister(Object)} -- unregisters a previously
     * registered non-bean instance from the live {@link PlayerCacheManager} singleton.
     * <p>
     * Safe to call before the core plugin is enabled or after it has been disabled: if the live
     * manager cannot be resolved, this is a no-op. Also safe to call on an instance that was
     * never registered (e.g. because an earlier {@link #tryRegister(Object)} attempt found the
     * core plugin unavailable) -- {@link #unregisterBean(Object)} is itself a no-op in that case.
     *
     * @param instance the object to stop tracking
     * @since 6.3.0
     */
    public static void tryUnregister(Object instance) {
        PlayerCacheManager manager = resolveLive();
        if (manager != null) {
            manager.unregisterBean(instance);
        }
    }

    private static PlayerCacheManager resolveLive() {
        UltiTools ultiTools = UltiTools.getInstance();
        if (ultiTools == null) {
            return null;
        }
        PluginManager pluginManager = ultiTools.getPluginManager();
        if (pluginManager == null) {
            return null;
        }
        return pluginManager.getPlayerCacheManager();
    }

    /**
     * Scans a bean for @PlayerCache fields and tracks them.
     * <p>
     * Idempotent by reference identity: registering the same instance more than once is a no-op
     * on the second and subsequent calls, so an instance never accumulates duplicate tracked
     * entries or is swept more than once per player quit.
     *
     * @throws PluginModuleException if a {@link PlayerCache}-annotated field's shape is none of
     *                                the three this manager can sweep (a {@code Map} keyed by
     *                                {@link UUID}, a {@code Set<UUID>}, or a {@code Map} whose
     *                                value is a {@link UUID}) -- an annotation that cannot take
     *                                effect must fail loudly rather than be silently skipped.
     */
    public void registerBean(Object bean) {
        if (isTracked(bean)) {
            return;
        }
        List<TrackedField> fields = new ArrayList<>();
        // Walk the class hierarchy: getDeclaredFields() skips inherited fields, so a
        // @PlayerCache field on a superclass was never tracked. This also covers beans that
        // the container hands out as AOP proxies, whose declared fields are the generated
        // subclass's own. See issue #190.
        for (Field field : ReflectionUtil.getFields(bean.getClass())) {
            PlayerCache annotation = field.getAnnotation(PlayerCache.class);
            if (annotation == null) {
                continue;
            }
            FieldShape shape = classifyField(field);
            if (shape == null) {
                throw new PluginModuleException(ErrorCode.INVALID_ARGUMENT,
                        "@PlayerCache field '" + field.getName() + "' on "
                                + field.getDeclaringClass().getName() + " has an unsupported "
                                + "shape; PlayerCacheManager can only sweep Map<UUID, ?> "
                                + "(key-side), Map<?, UUID> (value-side), and Set<UUID>");
            }
            field.setAccessible(true); // NOPMD
            fields.add(new TrackedField(field, annotation.saveBeforeRemove(), shape));
        }
        if (!fields.isEmpty()) {
            trackedBeans.add(new TrackedBean(bean, fields));
        }
    }

    private boolean isTracked(Object bean) {
        for (TrackedBean tracked : trackedBeans) {
            if (tracked.bean == bean) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classifies a {@link PlayerCache}-annotated field's generic shape.
     * <p>
     * Java 8 erasure means the key-side and value-side {@code Map} cases cannot be told apart
     * from {@link Field#getType()} alone -- this reads {@link Field#getGenericType()} and its
     * actual type arguments instead. Where the generic signature is absent or unresolvable (a raw
     * type, or neither side is {@link UUID}), this returns {@code null} rather than guessing, and
     * the caller refuses the field.
     *
     * @param field the field to classify
     * @return the recognised shape, or {@code null} if the field's shape is unsupported
     */
    private static FieldShape classifyField(Field field) {
        Class<?> rawType = field.getType();
        Type genericType = field.getGenericType();
        if (Map.class.isAssignableFrom(rawType)) {
            if (!(genericType instanceof ParameterizedType)) {
                return null;
            }
            Type[] typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
            if (typeArguments.length != 2) {
                return null;
            }
            if (isUuid(typeArguments[0])) {
                return FieldShape.KEY_MAP;
            }
            if (isUuid(typeArguments[1])) {
                return FieldShape.VALUE_MAP;
            }
            return null;
        }
        if (Set.class.isAssignableFrom(rawType)) {
            if (!(genericType instanceof ParameterizedType)) {
                return null;
            }
            Type[] typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
            if (typeArguments.length == 1 && isUuid(typeArguments[0])) {
                return FieldShape.UUID_SET;
            }
            return null;
        }
        return null;
    }

    private static boolean isUuid(Type type) {
        return UUID.class.equals(type);
    }

    /**
     * Removes a bean from tracking.
     */
    public void unregisterBean(Object bean) {
        trackedBeans.removeIf(tb -> tb.bean == bean);
    }

    /**
     * Called when a player quits. Cleans up all tracked fields across all three supported shapes.
     */
    public void onPlayerQuit(UUID playerId) {
        for (TrackedBean tracked : trackedBeans) {
            for (TrackedField tf : tracked.fields) {
                try {
                    if (tf.saveBeforeRemove && tracked.bean instanceof PlayerCacheSaver) {
                        ((PlayerCacheSaver) tracked.bean).savePlayerData(playerId);
                    }
                    Object value = tf.field.get(tracked.bean);
                    if (value == null) {
                        continue;
                    }
                    sweepField(value, tf.shape, playerId);
                } catch (IllegalAccessException e) {
                    LOGGER.log(Level.WARNING, "Failed to clean player cache field: "
                            + tf.field.getName() + " on " + tracked.bean.getClass().getName(), e);
                }
            }
        }
    }

    private static void sweepField(Object value, FieldShape shape, UUID playerId) {
        switch (shape) {
            case KEY_MAP:
                ((Map<?, ?>) value).remove(playerId);
                break;
            case VALUE_MAP:
                // Mirrors UsageLockValidator.clearPlayerLocks's already-correct predicate:
                // remove every entry whose VALUE is the quitting player's UUID, leaving entries
                // belonging to other players intact.
                ((Map<?, ?>) value).entrySet().removeIf(entry -> playerId.equals(entry.getValue()));
                break;
            case UUID_SET:
                ((Set<?>) value).remove(playerId);
                break;
            default:
                throw new IllegalStateException("Unreachable: unknown field shape " + shape);
        }
    }

    /**
     * Returns the number of beans being tracked.
     */
    public int getTrackedBeanCount() {
        return trackedBeans.size();
    }

    /**
     * Periodic, time-based expiry sweep -- driven by the framework's own {@link Scheduled} (i.e.
     * {@code @Scheduled}) mechanism rather than a hand-rolled {@code BukkitRunnable}, because this
     * state is stale on a clock, not on a player-quit event: it must be pruned whether or not any
     * player is online (D-03 -- mirrors {@code CooldownValidator.cleanupExpired}'s shape).
     * <p>
     * Every tracked instance that implements {@link ExpiringPlayerCache} has {@link
     * ExpiringPlayerCache#sweepExpired()} invoked once per pass. One participant's hook throwing
     * is caught, logged, and does not prevent the remaining participants' hooks from running in
     * the same pass. An instance that does not implement {@link ExpiringPlayerCache} is not
     * visited at all -- opt-in by type, not by reflection guessing at method names. Tolerates an
     * empty registry and being invoked mid-shutdown: it touches only this manager's own tracked
     * list, never the Bukkit API directly.
     * <p>
     * Sweep period: see {@link #EXPIRY_SWEEP_PERIOD_TICKS} (5 minutes -- see that constant's
     * javadoc for the rationale).
     *
     * @since 6.3.0
     */
    @Scheduled(delay = EXPIRY_SWEEP_PERIOD_TICKS, period = EXPIRY_SWEEP_PERIOD_TICKS)
    public void sweepExpiredEntries() {
        // Snapshot before iterating: a participant's hook (or a concurrent unregisterBean call
        // triggered from within it) must not raise a ConcurrentModificationException against the
        // live list.
        for (TrackedBean tracked : new ArrayList<>(trackedBeans)) {
            if (tracked.bean instanceof ExpiringPlayerCache) {
                try {
                    ((ExpiringPlayerCache) tracked.bean).sweepExpired();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error running expiry sweep for "
                            + tracked.bean.getClass().getName(), e);
                }
            }
        }
    }

    /**
     * Opt-in contract for a tracked instance that also carries time-based (as opposed to
     * player-quit-based) state to prune. Implemented by anything registered with this manager
     * that wants {@link #sweepExpiredEntries()}'s periodic pass to invoke it -- narrow and
     * type-checked rather than reflection guessing at a method name, so an instance that does not
     * implement this is definitively not visited.
     *
     * @since 6.3.0
     */
    public interface ExpiringPlayerCache {
        /**
         * Invoked once per {@link #sweepExpiredEntries()} pass. Implementations should be cheap
         * and must not rely on being called on the main thread. An exception thrown here is
         * caught and logged by the caller; it aborts only this instance's own sweep for the
         * current pass, not the pass as a whole.
         */
        void sweepExpired();
    }

    /**
     * The three field shapes {@link #registerBean(Object)} can sweep.
     */
    private enum FieldShape {
        /** {@code Map<UUID, ?>} -- the whole entry (key + value) is removed on quit. */
        KEY_MAP,
        /** {@code Map<?, UUID>} -- every entry whose VALUE equals the quitting player is removed. */
        VALUE_MAP,
        /** {@code Set<UUID>} -- the UUID itself is removed. */
        UUID_SET
    }

    private static class TrackedBean {
        final Object bean;
        final List<TrackedField> fields;

        TrackedBean(Object bean, List<TrackedField> fields) {
            this.bean = bean;
            this.fields = fields;
        }
    }

    private static class TrackedField {
        final Field field;
        final boolean saveBeforeRemove;
        final FieldShape shape;

        TrackedField(Field field, boolean saveBeforeRemove, FieldShape shape) {
            this.field = field;
            this.saveBeforeRemove = saveBeforeRemove;
            this.shape = shape;
        }
    }
}
