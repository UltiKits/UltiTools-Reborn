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

public class ListenerManager {
    private final Map<UltiToolsPlugin, List<Listener>> listenerListMap = new HashMap<>();

    public void register(UltiToolsPlugin plugin, Listener listener) {
        listenerListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        Bukkit.getServer().getPluginManager().registerEvents(listener, UltiTools.getInstance());
        List<Listener> listeners = listenerListMap.get(plugin);
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

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

    public void registerAll(UltiToolsPlugin plugin) {
        for (String listenerBean : plugin.getContext().getBeanNamesForType(Listener.class)) {
            Listener listener = plugin.getContext().getBean(listenerBean, Listener.class);
            register(plugin, listener);
        }
    }

    public void unregister(Listener listener) {
        HandlerList.unregisterAll(listener);
    }

    public void unregisterAll(UltiToolsPlugin plugin) {
        List<Listener> listeners = listenerListMap.get(plugin);
        if (listeners == null) return;
        for (Listener listener : listeners) {
            unregister(listener);
        }
    }
}
