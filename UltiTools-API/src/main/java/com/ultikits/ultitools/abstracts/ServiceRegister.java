package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.Registrable;
import org.bukkit.Bukkit;

import java.util.logging.Level;

/**
 * 服务注册器抽象类
 *
 * @param <T> 必须继承{@link Registrable}
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class ServiceRegister<T extends Registrable> {
    private final Class<T> api;
    private final Registrable registrable;

    public ServiceRegister(Class<T> api, Registrable service) {
        this.api = api;
        this.registrable = service;
        if (!this.load()) throw new RuntimeException(registrable.getName() + "插件加载失败!");
    }

    private boolean load() {
        return UltiTools.getInstance().getPluginManager().register(api, registrable);
    }

    public void unload() {
        UltiTools.getInstance().getPluginManager().unregister(api, registrable);
    }

    public void sendMessageToConsole(Level level, String message) {
        Bukkit.getLogger().log(level, "[" + registrable.getName() + "]" + message);
    }
}
