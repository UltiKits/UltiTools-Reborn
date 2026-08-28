package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.context.ConditionalRegistrationEvaluator;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.utils.PackageScanUtils;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * Listener manager.
 * <p>
 * 监听器管理器。
 */
public class ListenerManager {
    private final Map<UltiToolsPlugin, List<Listener>> listenerListMap = new HashMap<>();
    private final Map<String, List<Listener>> externalListenerMap = new HashMap<>();

    /**
     * Register listener.
     * <br>
     * 注册监听器。
     *
     * @param plugin        UltiTools plugin instance <br> UltiTools模块实例
     * @param listenerClass Listener class <br> 监听器类
     */
    public void register(UltiToolsPlugin plugin, Class<? extends Listener> listenerClass) {
        Listener listener = plugin.getContext().getBean(listenerClass);
        register(plugin, listener);
    }

    /**
     * Register listener. No auto injection. Please use {@link #register(UltiToolsPlugin, Class)} instead.
     * <br>
     * 注册监听器。无自动注入。请使用 {@link #register(UltiToolsPlugin, Class)} 代替。
     *
     * @param plugin   UltiTools plugin instance <br> UltiTools模块实例
     * @param listener Listener <br> 监听器
     * @deprecated Use {@link #register(UltiToolsPlugin, Class)} instead; this overload takes an
     *             already-constructed instance and therefore performs no dependency injection.
     *             <p>
     *             请改用 {@link #register(UltiToolsPlugin, Class)}；此重载接收已经构造好的实例，
     *             因此不会执行任何依赖注入。
     */
    @Deprecated(since = "6.0.5", forRemoval = true)
    public void register(UltiToolsPlugin plugin, Listener listener) {
        List<Listener> listeners = listenerListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        Bukkit.getServer().getPluginManager().registerEvents(listener, UltiTools.getInstance());
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Register all listeners in the package.
     * <br>
     * 注册包中的所有监听器。
     *
     * @param plugin      UltiTools plugin instance <br> UltiTools模块实例
     * @param packageName Package name <br> 包名
     */
    public void registerAll(UltiToolsPlugin plugin, String packageName) {
        Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                EventListener.class,
                packageName,
                Objects.requireNonNull(plugin.getClass().getClassLoader())
        );
        for (Class<?> clazz : classes) {
            if (!ConditionalRegistrationEvaluator.shouldRegister(clazz, plugin.getContext())) {
                continue;
            }
            try {
                Listener listener = (Listener) clazz.getDeclaredConstructor().newInstance();
                plugin.getContext().getAutowireCapableBeanFactory().autowireBean(listener);
                register(plugin, listener);
            } catch (InstantiationException |
                     InvocationTargetException |
                     IllegalAccessException |
                     NoSuchMethodException ignored) {
            }
        }
    }

    /**
     * Register all listeners in the UltiTools plugin class base package.
     * <br>
     * 注册模块实例类包中的所有监听器。
     *
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     */
    public void registerAll(UltiToolsPlugin plugin) {
        for (String listenerBean : plugin.getContext().getBeanNamesForType(Listener.class)) {
            Listener listener = plugin.getContext().getBean(listenerBean, Listener.class);
            if (listener == null) continue;
            EventListener annotation = MergedAnnotationResolver.find(listener.getClass(), EventListener.class);
            if (annotation == null || annotation.manualRegister()) continue;
            register(plugin, listener);
        }
    }

    /**
     * Unregister listener.
     * <br>
     * 注销监听器。
     *
     * @param listener Listener <br> 监听器
     */
    public void unregister(Listener listener) {
        HandlerList.unregisterAll(listener);
    }

    /**
     * Unregister all listeners in the UltiTools plugin class base package.
     * <br>
     * 注销模块实例类包中的所有监听器。
     *
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     */
    public void unregisterAll(UltiToolsPlugin plugin) {
        List<Listener> listeners = listenerListMap.get(plugin);
        if (listeners == null) return;
        for (Listener listener : listeners) {
            unregister(listener);
        }
    }

    /**
     * Register all @EventListener listeners from an external plugin's IoC container.
     * <p>
     * 注册外部插件 IoC 容器中所有 @EventListener 监听器。
     *
     * @param adapter the external plugin adapter
     * @since 6.2.2
     */
    public void registerAllExternal(ExternalPluginAdapter adapter) {
        if (adapter.getContext() == null) return;
        for (String listenerBean : adapter.getContext().getBeanNamesForType(Listener.class)) {
            Listener listener = adapter.getContext().getBean(listenerBean, Listener.class);
            if (listener == null) continue;
            EventListener annotation = MergedAnnotationResolver.find(listener.getClass(), EventListener.class);
            if (annotation == null || annotation.manualRegister()) continue;
            Bukkit.getServer().getPluginManager().registerEvents(listener, UltiTools.getInstance());
            externalListenerMap.computeIfAbsent(adapter.getPluginName(), k -> new ArrayList<>()).add(listener);
        }
    }

    /**
     * Unregister all listeners for an external plugin.
     * <p>
     * 注销外部插件的所有监听器。
     *
     * @param pluginName the external plugin name
     * @since 6.2.2
     */
    public void unregisterAllExternal(String pluginName) {
        List<Listener> listeners = externalListenerMap.remove(pluginName);
        if (listeners == null) return;
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
    }
}
