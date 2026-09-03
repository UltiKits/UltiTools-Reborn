package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Conditionally registers a component based on a YAML config value.
 * <p>
 * This is the framework-supported replacement for an in-code {@code if (config.isEnabled())}
 * block: when placed on a {@code @Service}, {@code @CmdExecutor}, {@code @EventListener},
 * or any component class, the framework checks the specified config file and key
 * during component scanning. If the config value is {@code false} (or missing),
 * the component is skipped entirely.
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
