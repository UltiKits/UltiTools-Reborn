package com.ultikits.ultitools.interfaces;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;


public interface IPlugin {

    /**
     * UltiTools主体实例化插件时会调用此方法，类比{@link JavaPlugin#onEnable()}
     * <p></p>
     * @return 插件是否启动成功
     * @throws IOException 可能返回的IO操作错误
     */
    boolean registerSelf() throws IOException;

    /**
     * 卸载此插件模块时调用的方法，类比{@link JavaPlugin#onDisable()}
     */
    void unregisterSelf();

    /**
     * 重载此插件模块时调用的方法，此重载并非重启模块，不会调用{@link #registerSelf()}和{@link #unregisterSelf()}。
     */
    default void reloadSelf() {
    }

    /**
     * @return 插件名称
     */
    String pluginName();
}
