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
     * <p>
     * Beans are enumerated by name, so a listener bound under several names (a
     * {@code @Bean(name = {primary, alias})} product) is reached once per name; it is registered
     * with Bukkit once, on the first name (C50).
     *
     * @param plugin UltiTools plugin instance
     */
    public void registerAll(UltiToolsPlugin plugin) {
        Set<Listener> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String listenerBean : plugin.getContext().getBeanNamesForType(Listener.class)) {
            Listener listener = plugin.getContext().getBean(listenerBean, Listener.class);
            if (listener == null) continue;
            if (!handled.add(listener)) continue; // same instance under another name (C50)
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
     * Unregisters from Bukkit every listener this framework registered for {@code plugin}, then stops
     * tracking {@code plugin}: once its listeners are released, this manager no longer references the
     * module or its listener objects, so an unloaded module is not kept reachable by it. A later
     * registration for the same instance starts a new list.
     *
     * @param plugin UltiTools plugin instance
     */
    public void unregisterAll(UltiToolsPlugin plugin) {
        List<Listener> listeners = listenerListMap.get(plugin);
        if (listeners == null) return;
        for (Listener listener : listeners) {
            unregister(listener);
        }
        // Dropped only after every listener was released: if one release throws, the entry stays and a
        // later call can still release the rest.
        listenerListMap.remove(plugin);
    }

    /**
     * Register all @EventListener listeners from an external plugin's IoC container.
     * A listener bound under several names is registered once (C50), as in
     * {@link #registerAll(UltiToolsPlugin)}.
     *
     * @param adapter the external plugin adapter
     * @since 6.2.2
     */
    public void registerAllExternal(ExternalPluginAdapter adapter) {
        if (adapter.getContext() == null) return;
        Set<Listener> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String listenerBean : adapter.getContext().getBeanNamesForType(Listener.class)) {
            Listener listener = adapter.getContext().getBean(listenerBean, Listener.class);
            if (listener == null) continue;
            if (!handled.add(listener)) continue; // same instance under another name (C50)
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
