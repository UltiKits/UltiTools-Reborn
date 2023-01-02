package com.ultikits.ultitools;

import cn.hutool.core.io.FileUtil;
import com.ultikits.api.VersionWrapper;
import com.ultikits.ultitools.commands.ReloadPluginsCommand;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.manager.*;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.services.impl.InMemeryTeleportService;
import com.ultikits.ultitools.services.registers.TeleportServiceRegister;
import com.ultikits.ultitools.tasks.DataStoreWaitingTask;
import com.ultikits.utils.VersionAdaptor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static spark.Spark.port;
import static spark.Spark.secure;

public final class UltiTools extends JavaPlugin implements Localized {
    private static UltiTools ultiTools;
    @Getter
    private final ListenerManager listenerManager = new ListenerManager();
    @Getter
    private final CommandManager commandManager = new CommandManager();
    @Setter
    @Getter
    private DataStore dataStore;
    @Getter
    private VersionWrapper versionWrapper;
    @Getter
    private Language language;
    @Getter
    private PluginManager pluginManager;
    @Getter
    private ConfigManager configManager;

    public static UltiTools getInstance() {
        return ultiTools;
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        ultiTools = this;
        saveDefaultConfig();
        initEmbedWebServer();
        configManager = new ConfigManager();

        String storeType = getConfig().getString("datasource.type");
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null){
            new DataStoreWaitingTask().runTaskTimerAsynchronously(this, 0L, 20L);
            dataStore = DataStoreManager.getDatastore("json");
        }
        File file = new File(getDataFolder() + File.separator + "plugins");
        FileUtil.mkdir(file);
        try {
            pluginManager = new PluginManager();
            pluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.versionWrapper = new VersionAdaptor().match();
        String lanPath = "lang/" + getConfig().getString("language") + ".json";
        InputStream in = getResource(lanPath);
        String result = new BufferedReader(new InputStreamReader(in))
                .lines().collect(Collectors.joining(""));
        this.language = new Language(result);

        getCommandManager().register(new ReloadPluginsCommand(), "", "Reload Plugins", "replugins", "rps");

        new TeleportServiceRegister(TeleportService.class, new InMemeryTeleportService());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        pluginManager.close();
        DataStoreManager.close();
        getConfigManager().saveAll();
    }

    public void reloadPlugins() throws IOException {
        pluginManager.reload();
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("en", "zh");
    }

    public String i18n(String str) {
        return this.language.getLocalizedText(str);
    }

    private void initEmbedWebServer(){
        if (getConfig().getBoolean("web-editor.enable")){
            int port = getConfig().getInt("web-editor.port");
            port(port);
            if (getConfig().getBoolean("web-editor.https.enable")){
                String keystoreFilePath = getConfig().getString("web-editor.https.keystore-file-path");
                String keystorePassword = getConfig().getString("web-editor.https.keystore-password");
                String truststoreFilePath = getConfig().getString("web-editor.https.truststore-file-path");
                String truststorePassword = getConfig().getString("web-editor.https.truststore-password");
                secure(keystoreFilePath, keystorePassword, truststoreFilePath, truststorePassword);
            }
        }
    }

}
