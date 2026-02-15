package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Conditionally registers a component based on a YAML config value.
 * <p>
 * When placed on a {@code @Service}, {@code @CmdExecutor}, {@code @EventListener},
 * or any component class, the framework checks the specified config file and key
 * during component scanning. If the config value is {@code false} (or missing),
 * the component is skipped entirely.
 * <p>
 * 根据 YAML 配置值条件性地注册组件。放在 {@code @Service}、{@code @CmdExecutor}、
 * {@code @EventListener} 或任何组件类上时，框架在组件扫描期间检查指定的配置文件和键。
 * 如果配置值为 {@code false}（或缺失），则完全跳过该组件。
 *
 * <p>Usage example:
 * <pre>{@code
 * @CmdExecutor(alias = {"warp"}, permission = "ultikits.tools.command.warp")
 * @ConditionalOnConfig(value = "config/config.yml", path = "enableWarp")
 * public class WarpCommands extends AbstractCommandExecutor {
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
     * <p>
     * 相对于插件数据目录的配置文件路径。
     *
     * @return the config file path
     */
    String value();

    /**
     * Dot-separated or slash-separated YAML key path.
     * <p>
     * 点分隔或斜杠分隔的 YAML 键路径。
     *
     * @return the config key path
     */
    String path();

    /**
     * If true, register when the config value is false (inverted logic).
     * <p>
     * 如果为 true，则在配置值为 false 时注册（反转逻辑）。
     *
     * @return true to negate the condition
     */
    boolean negate() default false;
}
