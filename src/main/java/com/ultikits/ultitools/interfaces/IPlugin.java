package com.ultikits.ultitools.interfaces;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;


/**
 * Plugin interface.
 */
public interface IPlugin {

    /**
     * UltiTools will call this method when instantiating the main body of the plugin, similar to {@link JavaPlugin#onEnable()}
     *
     * @return Whether the registration is successful
     * @throws IOException IOException
     */
    boolean registerSelf() throws IOException;

    /**
     * UltiTools will call this method when unregistering this plugin module, similar to {@link JavaPlugin#onDisable()}
     */
    void unregisterSelf();

    /**
     * UltiTools will call this method when reloading this plugin module, similar to {@link JavaPlugin#onDisable()} and {@link JavaPlugin#onEnable()}.
     * This reload is not a module restart: it does not call {@link #registerSelf()} or {@link #unregisterSelf()}.
     */
    void reloadSelf();
}
