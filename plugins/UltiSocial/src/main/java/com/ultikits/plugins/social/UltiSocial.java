package com.ultikits.plugins.social;

import java.util.Arrays;
import java.util.List;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiSocial - Friend system module.
 * Provides friend management, online status, and social features.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(
    scanBasePackages = {"com.ultikits.plugins.social"}
)
public class UltiSocial extends UltiToolsPlugin {
    
    private static UltiSocial instance;
    
    @Override
    public boolean registerSelf() {
        instance = this;
        getLogger().info("UltiSocial has been enabled!");
        return true;
    }
    
    @Override
    public void unregisterSelf() {
        getLogger().info("UltiSocial has been disabled!");
    }
    
    @Override
    public void reloadSelf() {
        getLogger().info("UltiSocial configuration reloaded!");
    }
    
    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
    
    public static UltiSocial getInstance() {
        return instance;
    }
}
