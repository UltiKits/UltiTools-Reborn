package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
