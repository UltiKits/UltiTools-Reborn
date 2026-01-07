package com.ultikits.plugins.worlds;

import java.util.Arrays;
import java.util.List;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiWorlds - Multi-world management module.
 * Provides world creation, teleportation, and world-specific settings.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(
    scanBasePackages = {"com.ultikits.plugins.worlds"}
)
public class UltiWorlds extends UltiToolsPlugin {
    
    private static UltiWorlds instance;
    
    @Override
    public boolean registerSelf() {
        instance = this;
        getLogger().info("UltiWorlds has been enabled!");
        return true;
    }
    
    @Override
    public void unregisterSelf() {
        getLogger().info("UltiWorlds has been disabled!");
    }
    
    @Override
    public void reloadSelf() {
        getLogger().info("UltiWorlds configuration reloaded!");
    }
    
    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
    
    public static UltiWorlds getInstance() {
        return instance;
    }
}
