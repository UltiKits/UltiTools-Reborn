package com.ultikits.ultitools;

import com.ultikits.ultitools.context.ContextConfig;
import com.ultikits.ultitools.commands.PluginInstallCommands;
import com.ultikits.ultitools.commands.UltiToolsCommands;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.manager.*;
import com.ultikits.ultitools.tasks.DataStoreWaitingTask;
import com.ultikits.ultitools.utils.HttpDownloadUtils;
import lombok.Getter;
import mc.obliviate.inventory.InventoryAPI;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
    @Getter
    private ClassLoader pluginClassLoader;
    private boolean restartRequired;
    private BukkitAudiences adventure;
    private AnnotationConfigApplicationContext context;

    public static UltiTools getInstance() {
        return ultiTools;
    }
    public static int getPluginVersion() {
        return 600;
    }
    public void setDataStore(DataStore dataStore) {
        this.dataStore = dataStore;
    }
    public AnnotationConfigApplicationContext getContext() {
        return context;
    }

    @Override
    public void onLoad() {
        saveDefaultConfig();
        restartRequired = downloadRequiredDependencies();
        if (restartRequired) {
//          Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API]插件依赖下载完毕，请重启服务器或者重载本插件！");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "reload");
        }
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        ultiTools = this;
        this.adventure = BukkitAudiences.create(this);
        new InventoryAPI(this).init();
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

        String storeType = getConfig().getString("datasource.type");
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null) {
            new DataStoreWaitingTask().runTaskTimerAsynchronously(this, 0L, 20L);
            dataStore = DataStoreManager.getDatastore("json");
        }

        String lanPath = "lang/" + getConfig().getString("language") + ".json";
        InputStream in = getFileResource(lanPath);
        String result  = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining(""));
        this.language  = new Language(result);

        pluginClassLoader = getClassLoader();
        pluginManager     = new PluginManager();
        context           = new AnnotationConfigApplicationContext();

        context.setClassLoader(pluginClassLoader);
        context.register(ContextConfig.class);
        context.refresh();
        context.registerShutdownHook();

        File file = new File(getDataFolder() + File.separator + "plugins");
        if (!file.exists()) {
            file.mkdirs();
        }
        try {
            pluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        getCommandManager().register(new UltiToolsCommands());
        getCommandManager().register(new PluginInstallCommands());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }
        if (restartRequired) {
            return;
        }
        stopEmbedWebServer();
        pluginManager.close();
        context.close();
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

    public BukkitAudiences adventure() {
        if (this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure when the plugin was disabled!");
        }
        return this.adventure;
    }

    private boolean downloadRequiredDependencies() {
        List<String> dependencies = Arrays.asList(
                "advancedslot-4.1.13.jar",
                "adventure-api-4.13.0.jar",
                "adventure-key-4.13.0.jar",
                "adventure-nbt-4.13.0.jar",
                "adventure-platform-api-4.3.0.jar",
                "adventure-platform-bukkit-4.3.0.jar",
                "adventure-platform-facet-4.3.0.jar",
                "adventure-platform-viaversion-4.3.0.jar",
                "adventure-text-serializer-bungeecord-4.3.0.jar",
                "adventure-text-serializer-gson-4.13.0.jar",
                "adventure-text-serializer-gson-legacy-impl-4.13.0.jar",
                "adventure-text-serializer-legacy-4.13.0.jar",
                "annotations-24.0.1.jar",
                "bukkit-1.13.1-R0.1-SNAPSHOT.jar",
                "bungeecord-chat-1.16-R0.4.jar",
                "checker-qual-3.12.0.jar",
                "commons-lang-2.6.jar",
                "configurablegui-4.1.13.jar",
                "core-4.1.13.jar",
                "error_prone_annotations-2.11.0.jar",
                "examination-api-1.3.0.jar",
                "examination-string-1.3.0.jar",
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
                "obliviate-invs-4.1.13.jar",
                "obliviate-utils-2.0.5.jar",
                "pagination-4.1.13.jar",
                "placeholder-2.0.5.jar",
                "protobuf-java-3.21.9.jar",
                "slf4j-api-1.7.25.jar",
                "snakeyaml-1.33.jar",
                "spark-core-2.9.4.jar",
                "spigot-api-1.19.3-R0.1-SNAPSHOT.jar",
                "string-2.0.5.jar",
                "VaultAPI-1.7.jar",
                "version-detection-2.0.5.jar",
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
                    Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API]Missing required libraries，trying to download...");
                    Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API]If have problems in downloading，you can download full version.");
                }
                restartRequired = true;
                String url = "https://ultitools.oss-cn-shanghai.aliyuncs.com/lib/" + name;
                Bukkit.getLogger().log(Level.INFO, "[UltiTools]Downloading: " + url);
                HttpDownloadUtils.download(url, name, getDataFolder() + "/lib");
            }
        }
        return restartRequired;
    }

}
