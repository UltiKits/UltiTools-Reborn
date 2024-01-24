package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;
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

    /**
     * Register listener.
     * <br>
     * 注册监听器。
     *
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
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
     * @param plugin  UltiTools plugin instance <br> UltiTools模块实例
     * @param listener Listener <br> 监听器
     */
    @Deprecated
    public void register(UltiToolsPlugin plugin, Listener listener) {
        listenerListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        Bukkit.getServer().getPluginManager().registerEvents(listener, UltiTools.getInstance());
        List<Listener> listeners = listenerListMap.get(plugin);
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Register all listeners in the package.
     * <br>
     * 注册包中的所有监听器。
     *
     * @param plugin     UltiTools plugin instance <br> UltiTools模块实例
     * @param packageName Package name <br> 包名
     */
    public void registerAll(UltiToolsPlugin plugin, String packageName) {
        Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                EventListener.class,
                packageName,
                Objects.requireNonNull(plugin.getClass().getClassLoader())
        );
        for (Class<?> clazz : classes) {
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
            if (listener.getClass().getAnnotation(EventListener.class).manualRegister()) continue;
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
}
