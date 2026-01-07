package com.ultikits.plugins.remotebag;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

import java.util.Arrays;
import java.util.List;

/**
 * UltiRemoteBag - Virtual cloud storage module.
 * Provides remote bag (virtual chest) functionality for players.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(
    scanBasePackages = {"com.ultikits.plugins.remotebag"}
)
public class UltiRemoteBag extends UltiToolsPlugin {
    
    private static UltiRemoteBag instance;
    
    @Override
    public boolean registerSelf() {
        instance = this;
        getLogger().info("UltiRemoteBag has been enabled!");
        return true;
    }
    
    @Override
    public void unregisterSelf() {
        getLogger().info("UltiRemoteBag has been disabled!");
    }
    
    @Override
    public void reloadSelf() {
        getLogger().info("UltiRemoteBag configuration reloaded!");
    }
    
    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
    
    public static UltiRemoteBag getInstance() {
        return instance;
    }
}
