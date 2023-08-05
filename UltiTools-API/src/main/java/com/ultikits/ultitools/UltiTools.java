package com.ultikits.ultitools;


import com.ultikits.ultitools.commands.PluginInstallCommands;
import com.ultikits.ultitools.commands.UltiToolsCommands;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.listeners.InventoryListener;
import com.ultikits.ultitools.manager.*;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.services.impl.InMemeryTeleportService;
import com.ultikits.ultitools.services.registers.TeleportServiceRegister;
import com.ultikits.ultitools.tasks.DataStoreWaitingTask;
import com.ultikits.ultitools.utils.HttpDownloadUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.ultikits.ultitools.utils.PluginInitiationUtils.*;

public final class UltiTools extends JavaPlugin implements Localized {
    private static UltiTools ultiTools;
    private final ListenerManager listenerManager = new ListenerManager();
    private final CommandManager commandManager = new CommandManager();
    private DataStore dataStore;
    private VersionWrapper versionWrapper;
    private Language language;
    private PluginManager pluginManager;
    private ConfigManager configManager;
    private ViewManager viewManager;
    private boolean restartRequired;

    public static UltiTools getInstance() {
        return ultiTools;
    }

    public static int getPluginVersion() {
        return 600;
    }

    public DataStore getDataStore() {
        return dataStore;
    }

    public void setDataStore(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    public VersionWrapper getVersionWrapper() {
        return versionWrapper;
    }

    public Language getLanguage() {
        return language;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public void onLoad() {
        saveDefaultConfig();
        restartRequired = downloadRequiredDependencies();
        if (restartRequired) {
//            Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API]插件依赖下载完毕，请重启服务器或者重载本插件！");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "reload");
        }
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        ultiTools = this;
        this.versionWrapper = new SpigotVersionManager().match();
        if (this.versionWrapper == null) {
            Bukkit.getLogger().log(Level.SEVERE, "Your server version isn't supported in UltiTools-API!");
            return;
        }
        initEmbedWebServer();
        try {
            loginAccount();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        configManager = new ConfigManager();
        viewManager = new ViewManager();

        String storeType = getConfig().getString("datasource.type");
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null) {
            new DataStoreWaitingTask().runTaskTimerAsynchronously(this, 0L, 20L);
            dataStore = DataStoreManager.getDatastore("json");
        }

        File file = new File(getDataFolder() + File.separator + "plugins");
        if (!file.exists()) {
            file.mkdirs();
        }
        try {
            pluginManager = new PluginManager();
            pluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String lanPath = "lang/" + getConfig().getString("language") + ".json";
        InputStream in = getFileResource(lanPath);
        String result = new BufferedReader(new InputStreamReader(in))
                .lines().collect(Collectors.joining(""));
        this.language = new Language(result);

        getCommandManager().register(new UltiToolsCommands(), "", "UltiTools Commands", "ul", "ultitools", "ulti");
        getCommandManager().register(new PluginInstallCommands(), "", "UltiTools Plugin Management Commands", "upm");
        Bukkit.getServer().getPluginManager().registerEvents(new InventoryListener(), this);

        new TeleportServiceRegister(TeleportService.class, new InMemeryTeleportService());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (restartRequired) {
            return;
        }
        stopEmbedWebServer();
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

    private InputStream getFileResource(String filename) {
        try {
            return this.getClass().getClassLoader().getResource(filename).openStream();
        } catch (IOException ex) {
            return null;
        }
    }

    public ListenerManager getListenerManager() {
        return listenerManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    private boolean downloadRequiredDependencies() {
        List<String> dependencies = Arrays.asList(
                "bukkit-1.13.1-R0.1-SNAPSHOT.jar",
                "bungeecord-chat-1.16-R0.4.jar",
                "checker-qual-2.5.8.jar",
                "commons-lang-2.6.jar",
                "error_prone_annotations-2.11.0.jar",
                "failureaccess-1.0.1.jar",
                "fastjson-1.2.83.jar",
                "gson-2.10.jar",
                "guava-31.1-jre.jar",
                "hamcrest-core-1.1.jar",
                "hutool-all-5.8.20.jar",
                "j2objc-annotations-1.3.jar",
                "javax.servlet-api-3.1.0.jar",
                "jetty-client-9.4.48.v20220622.jar",
                "jetty-http-9.4.48.v20220622.jar",
                "jetty-io-9.4.48.v20220622.jar",
                "jetty-security-9.4.48.v20220622.jar",
                "jetty-server-9.4.48.v20220622.jar",
                "jetty-servlet-9.4.48.v20220622.jar",
                "jetty-util-9.4.48.v20220622.jar",
                "jetty-util-ajax-9.4.48.v20220622.jar",
                "jetty-webapp-9.4.48.v20220622.jar",
                "jetty-xml-9.4.48.v20220622.jar",
                "json-simple-1.1.1.jar",
                "jsr305-3.0.2.jar",
                "junit-4.10.jar",
                "listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar",
                "lombok-1.18.24.jar",
                "mysql-connector-j-8.0.33.jar",
                "protobuf-java-3.21.9.jar",
                "slf4j-api-1.7.25.jar",
                "snakeyaml-1.33.jar",
                "spark-core-2.9.4.jar",
                "spigot-api-1.19.3-R0.1-SNAPSHOT.jar",
                "text-adapter-bukkit-3.0.6.jar",
                "text-api-3.0.4.jar",
                "text-serializer-gson-3.0.4.jar",
                "text-serializer-legacy-3.0.4.jar",
                "VaultAPI-1.7.jar",
                "websocket-api-9.4.48.v20220622.jar",
                "websocket-client-9.4.48.v20220622.jar",
                "websocket-common-9.4.48.v20220622.jar",
                "websocket-server-9.4.48.v20220622.jar",
                "websocket-servlet-9.4.48.v20220622.jar"
        );
        boolean restartRequired = false;
        for (String name : dependencies) {
            File file = new File(getDataFolder() + "/lib", name);
            if (!file.exists()) {
                if (!restartRequired) {
                    Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API]插件必要库缺失，正在尝试在线下载...");
                    Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API]若下载失败或多次重启均无法启动，请尝试下载包含依赖的版本。");
                }
                restartRequired = true;
                String url = "https://ultitools.oss-cn-shanghai.aliyuncs.com/lib/" + name;
                Bukkit.getLogger().log(Level.INFO, "[UltiTools]正在下载" + url);
                HttpDownloadUtils.download(url, name, getDataFolder() + "/lib");
            }
        }
        return restartRequired;
    }

    public ViewManager getViewManager() {
        return viewManager;
    }
}
