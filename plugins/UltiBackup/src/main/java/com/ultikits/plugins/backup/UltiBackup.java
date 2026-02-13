package com.ultikits.plugins.backup;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

import java.util.Arrays;
import java.util.List;

/**
 * UltiBackup - Inventory backup and restore module.
 * Automatically backs up player inventories and allows restoration.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(
    scanBasePackages = {"com.ultikits.plugins.backup"}
)
public class UltiBackup extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        getLogger().info("UltiBackup has been enabled!");
        return true;
    }

    @Override
    public void unregisterSelf() {
        getLogger().info("UltiBackup has been disabled!");
    }

    @Override
    public void reloadSelf() {
        getLogger().info("UltiBackup configuration reloaded!");
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
