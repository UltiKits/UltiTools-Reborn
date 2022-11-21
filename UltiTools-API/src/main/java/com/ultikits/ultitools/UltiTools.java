package com.ultikits.ultitools;

import cn.hutool.core.io.FileUtil;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.ultikits.ultitools.manager.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class UltiTools extends JavaPlugin {
    private static UltiTools ultiTools;
    private DataStore dataStore;

    public static UltiTools getInstance() {
        return ultiTools;
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        ultiTools = this;
        saveDefaultConfig();
        String storeType = getConfig().getString("datasource.type");
        dataStore = DataStoreManager.getDatastore(storeType);
        File file = new File(getDataFolder() + File.separator + "plugins");
        FileUtil.mkdir(file);
        try {
            PluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        PluginManager.close();
        DataStoreManager.close();
    }

    public void reloadPlugins() throws IOException {
        PluginManager.init();
    }

    public DataStore getDataStore() {
        return dataStore;
    }
}
