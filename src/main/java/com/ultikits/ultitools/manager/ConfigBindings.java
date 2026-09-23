package com.ultikits.ultitools.manager;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Resolution rules for a {@link Scheduled} period/delay or a {@link CmdCD} cooldown bound to a
 * module config key (#531). One place for the shape rules, the key lookup and the value range, so
 * the load-time check, the scheduler and the reload step cannot disagree about what a binding is.
 * <p>
 * A bound value is always a whole number of <b>seconds</b>, read from the {@code @ConfigEntry}
 * field of the module's own registered config entity -- the same instance {@code /ul reload}
 * reloads in place, resolved once at load and kept, so there is one source of truth. A reload
 * that {@code validateFields()} refused never reaches the binding step; the one gap is a reload
 * whose file write failed with an {@code IOException}, which {@code ConfigManager.reloadConfigs}
 * logs and continues past without running the field's own validation annotations (#533), so only the binding's own range rule is guaranteed. The default lives only in that
 * field's initializer; an annotation literal next to a binding is refused rather than used as a
 * fallback, because after {@code init()} a declared key always has a value and a fallback would be
 * a second, hand-synchronised copy of the default.
 * <p>
 * Ranges: a bound {@code @Scheduled} period or delay must be 1 to {@link #MAX_TICK_SECONDS}
 * seconds -- {@code 0} does not mean "off" and does not mean "run once". A bound {@code @CmdCD}
 * cooldown may be 0 to {@link Integer#MAX_VALUE} seconds, where {@code 0} means "no cooldown"
 * (maintainer ruling, 2026-09-23). Out of range is refused at load; on reload the running value is
 * kept. A module that uses any binding must declare {@code api-version: }{@value #MIN_API_VERSION}
 * or higher.
 */
final class ConfigBindings {

    /** Bukkit's nominal tick rate. A bound seconds value is multiplied by this. */
    static final long TICKS_PER_SECOND = 20L;

    /**
     * Largest bound period or delay, in seconds: the tick count then fits in an {@code int}, so the
     * reschedule arithmetic ({@code int} tick plus period, in {@code long}) cannot overflow. That is
     * about 3.4 years (#531 gate-1 IN-03).
     */
    static final long MAX_TICK_SECONDS = Integer.MAX_VALUE / TICKS_PER_SECOND;

    /** The same ceiling in ticks: every interval of a bound task, bound or literal, is at most this. */
    static final long MAX_TICKS = MAX_TICK_SECONDS * TICKS_PER_SECOND;

    /**
     * The lowest {@code plugin.yml} {@code api-version} a module using a binding may declare. An
     * older framework silently drops the binding elements, so this floor is what makes it refuse the
     * module instead (#531 gate-1 WR-03).
     */
    static final int MIN_API_VERSION = 630;

    /** The rule text for a bound period or delay. */
    static final String TIMER_RULE = "a bound @Scheduled period or delay must be a whole number of seconds "
            + "from 1 to " + MAX_TICK_SECONDS + " (0 does not mean off)";

    /** The rule text for a bound cooldown. */
    static final String COOLDOWN_RULE = "a bound @CmdCD cooldown must be a whole number of seconds "
            + "from 0 (no cooldown) to " + Integer.MAX_VALUE;

    /** The field types a binding may read. */
    private static final Set<Class<?>> INTEGRAL_FIELD_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.<Class<?>>asList(int.class, long.class, Integer.class, Long.class)));

    private ConfigBindings() {
        // Static rules only.
    }

    /**
     * @param scheduled the annotation
     * @return {@code true} if any of the three binding elements is set
     */
    static boolean isBound(Scheduled scheduled) {
        return scheduled.config() != AbstractConfigEntity.class
                || !scheduled.periodKey().isEmpty()
                || !scheduled.delayKey().isEmpty();
    }

    /**
     * @param cmdCD the annotation
     * @return {@code true} if either binding element is set
     */
    static boolean isBound(CmdCD cmdCD) {
        return cmdCD.config() != AbstractConfigEntity.class || !cmdCD.key().isEmpty();
    }

    /**
     * Refuses a {@link Scheduled} binding whose elements contradict each other: a key without a
     * config class, a config class without any key, {@code async = true} (a bound task must be
     * sync, so that its runs are observed on the main thread where a reload reschedules it -- #531
     * gate-1 round 2), or a literal set alongside the key that replaces it.
     *
     * @param owner     {@code Class.method}, for the message
     * @param scheduled the bound annotation
     * @throws PluginModuleException naming the owner and the contradicting elements
     */
    static void checkShape(String owner, Scheduled scheduled) {
        if (scheduled.config() == AbstractConfigEntity.class) {
            throw shapeError(owner, "@Scheduled names periodKey/delayKey but no config class; "
                    + "set config = <YourConfig>.class");
        }
        if (scheduled.periodKey().isEmpty() && scheduled.delayKey().isEmpty()) {
            throw shapeError(owner, "@Scheduled names config class " + scheduled.config().getSimpleName()
                    + " but neither periodKey nor delayKey");
        }
        if (scheduled.async()) {
            throw shapeError(owner, "a config-bound @Scheduled cannot be async = true; bind a sync task and hand "
                    + "the heavy work to Bukkit.getScheduler().runTaskAsynchronously(...) from its body");
        }
        if (!scheduled.periodKey().isEmpty() && scheduled.period() != -1) {
            throw shapeError(owner, "@Scheduled sets both period=" + scheduled.period() + " and periodKey '"
                    + scheduled.periodKey() + "'; the default lives only in the config field, so leave period unset");
        }
        if (!scheduled.delayKey().isEmpty() && scheduled.delay() != 0) {
            throw shapeError(owner, "@Scheduled sets both delay=" + scheduled.delay() + " and delayKey '"
                    + scheduled.delayKey() + "'; the default lives only in the config field, so leave delay unset");
        }
        // The literal half of a partially bound task is held to the same tick ceiling as the bound
        // half, so every interval of a bound task fits in Bukkit's int tick window and the
        // reschedule arithmetic stays exact across its wraparound (Codex round 10 on #536).
        if (scheduled.delayKey().isEmpty() && (scheduled.delay() < 0 || scheduled.delay() > MAX_TICKS)) {
            throw shapeError(owner, "@Scheduled binds periodKey '" + scheduled.periodKey() + "' with a literal delay="
                    + scheduled.delay() + " ticks; next to a binding a literal delay must be 0 to " + MAX_TICKS + " ticks");
        }
        if (scheduled.periodKey().isEmpty() && scheduled.period() > MAX_TICKS) {
            throw shapeError(owner, "@Scheduled binds delayKey '" + scheduled.delayKey() + "' with a literal period="
                    + scheduled.period() + " ticks; next to a binding a literal period must be at most " + MAX_TICKS
                    + " ticks (or -1 to run once)");
        }
    }

    /**
     * Refuses a {@link CmdCD} binding whose elements contradict each other.
     *
     * @param owner the executor class, or {@code Class.method}, for the message
     * @param cmdCD the bound annotation
     * @throws PluginModuleException naming the owner and the contradicting elements
     */
    static void checkShape(String owner, CmdCD cmdCD) {
        if (cmdCD.config() == AbstractConfigEntity.class) {
            throw shapeError(owner, "@CmdCD names key '" + cmdCD.key() + "' but no config class; "
                    + "set config = <YourConfig>.class");
        }
        if (cmdCD.key().isEmpty()) {
            throw shapeError(owner, "@CmdCD names config class " + cmdCD.config().getSimpleName() + " but no key");
        }
        if (cmdCD.value() != 0) {
            throw shapeError(owner, "@CmdCD sets both value=" + cmdCD.value() + " and key '" + cmdCD.key()
                    + "'; the default lives only in the config field, so leave value unset");
        }
    }

    /**
     * A resolved binding: the module's config entity and the {@code @ConfigEntry} field a key
     * names. The entity is reloaded in place, so reading the field again after a reload sees the
     * reloaded value.
     */
    static final class Source {
        final AbstractConfigEntity entity;
        final Field field;
        final String key;

        Source(AbstractConfigEntity entity, Field field, String key) {
            this.entity = entity;
            this.field = field;
            this.key = key;
        }

        /** @return the current field value as seconds, or {@code null} for a {@code null} boxed field */
        Long readSeconds() {
            Object value = ReflectionUtil.getFieldValue(entity, field);
            return value == null ? null : ((Number) value).longValue();
        }

        /** @return the entity's simple class name, for messages */
        String configName() {
            return entity.getClass().getSimpleName();
        }

        /**
         * Whether this entity's last load or reload did not complete its validation -- for example a
         * write-back {@code IOException} that {@link ConfigManager} caught (Codex round 1 on #536). Its
         * fields may then hold values their validation never checked, so the reload step keeps the
         * running value.
         *
         * @return {@code true} if the value must not be applied
         */
        boolean lastReloadFailed() {
            return entity.isLastInitIncomplete();
        }

        /**
         * A value source for the {@code @CmdCD} reload step: reads the field, or throws
         * {@link ReloadFailedException} when {@link #lastReloadFailed()}.
         *
         * @return the source
         */
        Supplier<Long> reloadSource() {
            return () -> {
                if (lastReloadFailed()) {
                    throw new ReloadFailedException(entity.getConfigFilePath());
                }
                return readSeconds();
            };
        }
    }

    /**
     * Thrown by {@link Source#reloadSource()} when the bound entity's last reload failed, so the
     * {@code @CmdCD} reload step can keep the running value and say why.
     */
    static final class ReloadFailedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ReloadFailedException(String configFilePath) {
            super("the reload of " + configFilePath + " did not complete (for example, its write-back threw an "
                    + "IOException), so its values were not validated");
        }
    }

    /**
     * Resolves {@code key} on the single config entity of {@code configClass} registered for
     * {@code plugin}.
     *
     * @param plugin      the module
     * @param owner       {@code Class.method} or the executor class, for the message
     * @param configClass the bound config class
     * @param key         the {@code @ConfigEntry} path
     * @return the resolved source
     * @throws PluginModuleException when the class is not registered exactly once, the key names
     *                               no {@code @ConfigEntry} field, or the field is not integral
     * @throws ConfigurationException when the entity's load failed with an {@code IOException} that
     *                                {@code ConfigManager} caught, so its values were never validated
     */
    static Source resolve(UltiToolsPlugin plugin, String owner,
                          Class<? extends AbstractConfigEntity> configClass, String key) {
        List<? extends AbstractConfigEntity> entities =
                UltiTools.getInstance().getConfigManager().getConfigEntities(plugin, configClass);
        if (entities == null || entities.isEmpty()) {
            throw shapeError(owner, "binds key '" + key + "' of " + configClass.getSimpleName()
                    + ", which is not registered for module '" + plugin.getPluginName() + "'");
        }
        if (entities.size() > 1) {
            throw shapeError(owner, "binds key '" + key + "' of " + configClass.getSimpleName()
                    + ", which is registered " + entities.size() + " times for module '" + plugin.getPluginName()
                    + "' (a directory @ConfigEntity?); a binding needs exactly one instance");
        }
        AbstractConfigEntity entity = entities.get(0);
        if (entity.isLastInitIncomplete()) {
            throw new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED, String.format(
                    "Module '%s' refused to load: %s binds key '%s' of %s, but loading %s did not complete "
                            + "(for example, its write-back threw an IOException), so its values were not validated. "
                            + "Fix the cause logged above (such as the file's permissions) and restart.", plugin.getPluginName(), owner, key, configClass.getSimpleName(),
                    entity.getConfigFilePath()));
        }
        Field field = findEntryField(entity.getClass(), key);
        if (field == null) {
            throw shapeError(owner, "binds key '" + key + "', which matches no @ConfigEntry path on "
                    + configClass.getSimpleName());
        }
        if (!INTEGRAL_FIELD_TYPES.contains(field.getType())) {
            throw shapeError(owner, "binds key '" + key + "' of " + configClass.getSimpleName()
                    + ", whose field '" + field.getName() + "' is " + field.getType().getSimpleName()
                    + "; a bound value must be an int, long, Integer or Long number of seconds");
        }
        return new Source(entity, field, key);
    }


    /**
     * @return the {@code @ConfigEntry} field of {@code entityClass} (declared or inherited) whose
     *         path -- or field name, when the path is empty -- equals {@code key}, or {@code null}
     */
    private static Field findEntryField(Class<?> entityClass, String key) {
        for (Field field : ReflectionUtil.getFields(entityClass)) {
            ConfigEntry entry = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
            if (entry != null && key.equals(entry.path().isEmpty() ? field.getName() : entry.path())) {
                return field;
            }
        }
        return null;
    }

    /**
     * @param seconds a bound value
     * @return {@code true} for a scheduling value in [1, {@link #MAX_TICK_SECONDS}]
     */
    static boolean isValidTimerSeconds(Long seconds) {
        return seconds != null && seconds >= 1 && seconds <= MAX_TICK_SECONDS;
    }

    /**
     * @param seconds a bound value
     * @return {@code true} for a cooldown value in [0, {@link Integer#MAX_VALUE}]; 0 means no cooldown
     */
    static boolean isValidCooldownSeconds(Long seconds) {
        return seconds != null && seconds >= 0 && seconds <= Integer.MAX_VALUE;
    }

    /**
     * Refuses a module that uses a binding while declaring an {@code api-version} below
     * {@link #MIN_API_VERSION}.
     *
     * @param plugin       the module
     * @param firstBinding the first bound declaration found, for the message
     * @throws PluginModuleException if the declared floor is too low
     */
    static void checkApiVersionFloor(UltiToolsPlugin plugin, String firstBinding) {
        int declared = plugin.getMinUltiToolsVersion();
        if (declared < MIN_API_VERSION) {
            throw new PluginModuleException(ErrorCode.CONFIG_ERROR, String.format(
                    "Module '%s' uses a config binding (%s) but declares api-version: %d in plugin.yml; a module "
                            + "using a config-bound @Scheduled or @CmdCD must declare api-version: %d or higher, "
                            + "because an older framework silently ignores the binding and runs the wrong timing",
                    plugin.getPluginName(), firstBinding, declared, MIN_API_VERSION));
        }
    }

    /**
     * The load-time refusal for an out-of-range bound value -- the same exception type and the
     * same "file was not modified" contract as a {@code @Range} violation.
     *
     * @param plugin the module refusing to load
     * @param owner  {@code Class.method} or the executor class
     * @param source the binding
     * @param value  the refused value
     * @param rule   the rule it broke
     * @return the exception to throw
     */
    static ConfigurationException invalidValueAtLoad(UltiToolsPlugin plugin, String owner, Source source,
                                                     Long value, String rule) {
        return new ConfigurationException(ErrorCode.CONFIG_VALIDATION_FAILED, String.format(
                "Module '%s' refused to load: %s is bound to %s key '%s' (%s), which has value %s; %s. "
                        + "The file was not modified - fix the value and restart.",
                plugin.getPluginName(), owner, source.configName(), source.key,
                source.entity.getConfigFilePath(), value, rule));
    }

    private static PluginModuleException shapeError(String owner, String detail) {
        return new PluginModuleException(ErrorCode.CONFIG_ERROR,
                "Invalid config binding on " + owner + ": " + detail);
    }
}
