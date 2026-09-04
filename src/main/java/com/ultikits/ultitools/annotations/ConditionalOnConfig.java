package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Conditionally registers a component based on a YAML config value.
 * <p>
 * When placed on a {@code @Service}, {@code @CmdExecutor}, {@code @EventListener}, or any
 * component class, the framework checks the specified config file and key <b>once</b>, during
 * component scanning at plugin startup. If the config value is {@code false} (or missing), the
 * component is skipped entirely for the lifetime of that startup.
 *
 * <p>Usage example:
 * <pre>{@code
 * @CmdExecutor(alias = {"warp"}, permission = "ultikits.tools.command.warp")
 * @ConditionalOnConfig(value = "config/config.yml", path = "enableWarp")
 * public class WarpCommands extends BaseCommandExecutor {
 *     // Only registered if enableWarp: true in config.yml
 * }
 * }</pre>
 *
 * <p><b>A restart is required for a change to the named key to take effect, in both
 * directions.</b> {@code ul reload} re-reads the config file, but only <i>reports</i> the drift
 * as a {@code Level.WARNING} naming the class, the file, the key, and the new direction -- it
 * does not register or unregister anything (issue #392, D-01). See {@code
 * com.ultikits.ultitools.context.ConditionalRegistrationEvaluator} for the reporting mechanism
 * and why re-registering on reload was rejected.
 *
 * @apiNote This is <b>not</b> equivalent to an in-code {@code if (config.isEnabled())} block,
 *         which re-reads the config value on every call and therefore does follow a reload. A
 *         module author migrating off a hand-written {@code if} block onto this annotation is
 *         trading that per-call freshness for load-time simplicity -- know that before switching.
 * @since 6.2.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConditionalOnConfig {
    /**
     * Config file path relative to the plugin data folder.
     *
     * @return the config file path
     */
    String value();

    /**
     * Dot-separated or slash-separated YAML key path.
     *
     * @return the config key path
     */
    String path();

    /**
     * If true, register when the config value is false (inverted logic).
     *
     * @return true to negate the condition
     */
    boolean negate() default false;
}
