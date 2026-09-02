package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

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
        registerListener(plugin, listener);
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
            registerListener(plugin, listener);
        }
    }

    /**
     * Registers an already-constructed {@code listener} for {@code plugin} with Bukkit and
     * tracks it for later {@link #unregisterAll}. The shared registration step behind both
     * {@link #register(UltiToolsPlugin, Class)} (which resolves the listener as a bean first)
     * and {@link #registerAll(UltiToolsPlugin)}. Plan 07-14 (GEN-04) removed the public
     * {@code register(UltiToolsPlugin, Listener)} overload this logic used to live in -- that
     * overload took an already-constructed instance and therefore performed no dependency
     * injection of its own, so its actual registration behaviour is unchanged here, only its
     * public, unvalidated entry point is gone.
     * <br>
     * 为 {@code plugin} 向 Bukkit 注册一个已经构造好的 {@code listener}，并记录下来供之后
     * {@link #unregisterAll} 使用。是 {@link #register(UltiToolsPlugin, Class)}（先把监听器解析
     * 为 bean）与 {@link #registerAll(UltiToolsPlugin)} 共用的注册步骤。计划 07-14（GEN-04）移除了
     * 这段逻辑原先所在的公开 {@code register(UltiToolsPlugin, Listener)} 重载——那个重载接收已经
     * 构造好的实例，本就不做依赖注入，因此这里的实际注册行为不变，变的只是它未经校验的公开入口。
     *
     * @param plugin   UltiTools plugin instance <br> UltiTools模块实例
     * @param listener Listener <br> 监听器
     */
    private void registerListener(UltiToolsPlugin plugin, Listener listener) {
        List<Listener> listeners = listenerListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        Bukkit.getServer().getPluginManager().registerEvents(listener, UltiTools.getInstance());
        if (!listeners.contains(listener)) {
            listeners.add(listener);
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
