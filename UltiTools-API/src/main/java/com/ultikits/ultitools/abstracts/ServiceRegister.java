package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.interfaces.Registrable;
import com.ultikits.ultitools.manager.PluginManager;
import org.bukkit.Bukkit;

import java.util.logging.Level;

public abstract class ServiceRegister<T extends Registrable> {
    private final Class<T> api;
    private final Registrable registrable;

    public ServiceRegister(Class<T> api, Registrable service) {
        this.api = api;
        this.registrable = service;
        if (!this.load()) throw new RuntimeException(registrable.getName() + "插件加载失败!");
    }

    private boolean load() {
        return PluginManager.register(api, registrable);
    }

    public void unload() {
        PluginManager.unregister(api, registrable);
    }

    public void sendMessageToConsole(Level level, String message) {
        Bukkit.getLogger().log(level, "[" + registrable.getName() + "]" + message);
    }
}
