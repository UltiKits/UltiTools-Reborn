package com.ultikits.ultitools;

import cn.hutool.core.io.FileUtil;
import com.ultikits.api.VersionWrapper;
import com.ultikits.ultitools.commands.ReloadPluginsCommand;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.services.impl.InMemeryTeleportService;
import com.ultikits.ultitools.services.registers.TeleportServiceRegister;
import com.ultikits.ultitools.tasks.DataStoreWaitingTask;
import com.ultikits.utils.VersionAdaptor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class UltiTools extends JavaPlugin implements Localized {
    private static UltiTools ultiTools;
    private final ListenerManager listenerManager = new ListenerManager();
    private final CommandManager commandManager = new CommandManager();
    private DataStore dataStore;
    private VersionWrapper versionWrapper;
    private Language language;

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
        if (dataStore == null){
            new DataStoreWaitingTask().runTaskTimerAsynchronously(this, 0L, 20L);
            dataStore = DataStoreManager.getDatastore("json");
        }
        File file = new File(getDataFolder() + File.separator + "plugins");
        FileUtil.mkdir(file);
        try {
            PluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.versionWrapper = new VersionAdaptor().match();
        String lanPath = "lang/" + getConfig().getString("language") + ".json";
        InputStream in = getResource(lanPath);
        String result = new BufferedReader(new InputStreamReader(in))
                .lines().collect(Collectors.joining(""));
        this.language = new Language(result);

        getCommandManager().registerCommand(new ReloadPluginsCommand(), "", "Reload Plugins", "replugins", "rps");

        new TeleportServiceRegister(TeleportService.class, new InMemeryTeleportService());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        PluginManager.close();
        DataStoreManager.close();
    }

    public void reloadPlugins() throws IOException {
        PluginManager.reload();
    }

    public DataStore getDataStore() {
        return dataStore;
    }

    public VersionWrapper getVersionWrapper() {
        return versionWrapper;
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("en", "zh");
    }

    public String i18n(String str) {
        return this.language.getLocalizedText(str);
    }

    public ListenerManager getListenerManager() {
        return listenerManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public void setDataStore(DataStore dataStore){
        this.dataStore = dataStore;
    }
}
