package com.ultikits.ultitools.abstracts.command;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.jetbrains.annotations.ApiStatus;

/**
 * Framework-internal access to the config-bound {@code @CmdCD} state an executor carries (#531):
 * the resolved seconds of its bound cooldowns and their load-time value sources, both keyed by
 * {@code CooldownValidator.bindingKey}. The state lives on the {@link BaseCommandExecutor} itself,
 * the exact owner of a binding, so it lives and dies with the executor; a {@code CooldownValidator}
 * shared by several executors holds none of it. This class exists so that the state needs no
 * public method on {@link BaseCommandExecutor}, the class module authors extend.
 * <p>
 * Module code has no reason to use it.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConfigBoundCooldownState {

    private ConfigBoundCooldownState() {
        // Static access only.
    }

    /**
     * @param executor an executor
     * @return its resolved bound-cooldown seconds; never {@code null}
     */
    public static Map<String, Integer> seconds(BaseCommandExecutor executor) {
        return executor.configBoundCooldownSeconds;
    }

    /**
     * Replaces {@code executor}'s resolved bound-cooldown seconds. A cooldown that is already running
     * keeps the end time it was stamped with.
     *
     * @param executor an executor
     * @param seconds  resolved seconds keyed by binding key
     */
    public static void setSeconds(BaseCommandExecutor executor, Map<String, Integer> seconds) {
        executor.configBoundCooldownSeconds = Collections.unmodifiableMap(new HashMap<>(seconds));
    }

    /**
     * @param executor an executor
     * @return its bound-cooldown value sources; never {@code null}
     */
    public static Map<String, Supplier<Long>> sources(BaseCommandExecutor executor) {
        return executor.configBoundCooldownSources;
    }

    /**
     * Replaces {@code executor}'s bound-cooldown value sources.
     *
     * @param executor an executor
     * @param sources  value sources keyed by binding key
     */
    public static void setSources(BaseCommandExecutor executor, Map<String, Supplier<Long>> sources) {
        executor.configBoundCooldownSources = Collections.unmodifiableMap(new HashMap<>(sources));
    }
}
