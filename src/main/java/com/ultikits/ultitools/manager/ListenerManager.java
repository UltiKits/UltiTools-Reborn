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
 */
public class ListenerManager {
    private final Map<UltiToolsPlugin, List<Listener>> listenerListMap = new HashMap<>();
    private final Map<String, List<Listener>> externalListenerMap = new HashMap<>();

    /**
     * Register listener.
     *
     * @param plugin        UltiTools plugin instance
     * @param listenerClass Listener class
     */
    public void register(UltiToolsPlugin plugin, Class<? extends Listener> listenerClass) {
        Listener listener = plugin.getContext().getBean(listenerClass);
        registerListener(plugin, listener);
    }

    /**
     * Register all listeners in the UltiTools plugin class base package.
     *
     * @param plugin UltiTools plugin instance
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
     *
     * @param plugin   UltiTools plugin instance
     * @param listener Listener
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
     *
     * @param listener Listener
     */
    public void unregister(Listener listener) {
        HandlerList.unregisterAll(listener);
    }

    /**
     * Unregister all listeners in the UltiTools plugin class base package.
     *
     * @param plugin UltiTools plugin instance
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
