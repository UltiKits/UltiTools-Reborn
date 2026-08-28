package com.ultikits.ultitools.context;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

/**
 * The single shared {@code @ConditionalOnConfig} registration decision (D-17).
 * <p>
 * Both {@link ComponentScanner} (the IoC-scan path) and {@code ListenerManager}'s
 * reflective package-scan overload consult this evaluator, so the annotation is honoured
 * identically wherever it is read instead of each caller carrying its own copy of the
 * decision that can drift out of sync with the other.
 * <br>
 * 唯一共享的 {@code @ConditionalOnConfig} 注册判定逻辑（D-17）。{@link ComponentScanner}
 * （IoC 扫描路径）与 {@code ListenerManager} 的反射式包扫描重载都调用此判定器，从而在所有读取该
 * 注解的地方保持一致的行为，而不是让每个调用方各自维护一份可能逐渐产生差异的逻辑副本。
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConditionalRegistrationEvaluator {

    private static final Logger LOGGER =
            Logger.getLogger(ConditionalRegistrationEvaluator.class.getName());

    private ConditionalRegistrationEvaluator() {
    }

    /**
     * Check if a class should be registered based on {@code @ConditionalOnConfig}.
     * <p>
     * The two "the decision cannot be determined" branches stay fail-open -- a component
     * that should have been enabled but was not is harder to diagnose than the reverse --
     * but each now emits a {@code Level.WARNING} record naming the evaluated class and the
     * reason, rather than silently registering by default (D-20). The config-file-missing
     * branch is unaffected: it returns {@code condition.negate()}, i.e. missing means
     * disabled, which already matches the annotation's own javadoc.
     * <br>
     * 根据 {@code @ConditionalOnConfig} 检查类是否应被注册。
     * <p>
     * 两条"无法判定"分支仍然保持放行（fail-open）——一个本应启用却被静默禁用的组件，比反过来更
     * 难排查——但现在各自会发出一条命名了被判定类与原因的 {@code Level.WARNING} 记录，而不是
     * 悄悄按默认值注册（D-20）。配置文件缺失分支不受影响：仍然返回 {@code condition.negate()}，
     * 即"缺失即禁用"，这与该注解自身的 javadoc 已经一致。
     *
     * @param clazz     the class to check <br> 待检查的类
     * @param container the container used to resolve the owning {@link UltiToolsPlugin} <br>
     *                  用于解析所属插件的容器
     * @return true if the class should be registered <br> 若应注册该类则返回 true
     */
    public static boolean shouldRegister(Class<?> clazz, SimpleContainer container) {
        ConditionalOnConfig condition = clazz.getAnnotation(ConditionalOnConfig.class);
        if (condition == null) {
            return true;
        }

        // Retrieve the plugin from the container
        UltiToolsPlugin plugin = null;
        try {
            plugin = container.getBean(UltiToolsPlugin.class);
        } catch (Exception e) {
            // No plugin in container — skip conditional (register by default), but say so:
            // a component that should have been enabled and was silently skipped is harder to
            // diagnose than the reverse (D-20).
            LOGGER.log(Level.WARNING, "@ConditionalOnConfig on " + clazz.getName()
                    + " could not be evaluated: no UltiToolsPlugin could be resolved from the "
                    + "container (" + e.getMessage() + "). Registering by default (fail-open).");
            return true;
        }

        if (plugin == null || plugin.getResourceFolderPath() == null) {
            LOGGER.log(Level.WARNING, "@ConditionalOnConfig on " + clazz.getName()
                    + " could not be evaluated: " + (plugin == null
                    ? "no UltiToolsPlugin instance was resolved from the container"
                    : "the plugin's resource folder path is null")
                    + ". Registering by default (fail-open).");
            return true;
        }

        // Load the referenced config file from the plugin's config folder
        File configFile = new File(plugin.getResourceFolderPath(), condition.value());
        if (!configFile.exists()) {
            // Config file doesn't exist yet — feature disabled
            return condition.negate();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        boolean value = yaml.getBoolean(condition.path(), false);
        return condition.negate() ? !value : value;
    }
}
