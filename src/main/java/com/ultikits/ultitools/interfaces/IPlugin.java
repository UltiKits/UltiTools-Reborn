package com.ultikits.ultitools.interfaces;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;


/**
 * Plugin interface.
 * <p>
 * 插件接口
 */
public interface IPlugin {

    /**
     * UltiTools will call this method when instantiating the main body of the plugin, similar to {@link JavaPlugin#onEnable()}
     * <br>
     * UltiTools主体实例化插件时会调用此方法，类比{@link JavaPlugin#onEnable()}
     * <br>
     *
     * @return Whether the registration is successful <br> 是否注册成功
     * @throws IOException IOException <br> IO异常
     */
    boolean registerSelf() throws IOException;

    /**
     * UltiTools will call this method when unregistering this plugin module, similar to {@link JavaPlugin#onDisable()}
     * <br>
     * 卸载此插件模块时调用的方法，类比{@link JavaPlugin#onDisable()}
     */
    void unregisterSelf();

    /**
     * UltiTools will call this method when reloading this plugin module, similar to {@link JavaPlugin#onDisable()} and {@link JavaPlugin#onEnable()}
     * <br>
     * 重载此插件模块时调用的方法，此重载并非重启模块，不会调用{@link #registerSelf()}和{@link #unregisterSelf()}。
     */
    void reloadSelf();
}
