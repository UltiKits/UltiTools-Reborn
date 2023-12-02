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
import lombok.Getter;
import mc.obliviate.inventory.InventoryAPI;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
        ultiTools = this;
        restartRequired = downloadRequiredDependencies();
        if (restartRequired) {
            Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API] Libraries downloaded, please restart the server or reload UltiTools!");
//          Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "reload");
        }
    }

    @Override
    public void onEnable() {
        if (restartRequired) {
            return;
        }
        // Plugin startup logic
        this.adventure = BukkitAudiences.create(this);
        new InventoryAPI(this).init();
        this.versionWrapper = new SpigotVersionManager().match();
        if (this.versionWrapper == null) {
            Bukkit.getLogger().log(Level.SEVERE, "[UltiTools-API] Your server version isn't supported in UltiTools-API!");
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
      
        Bukkit.getServicesManager().register(PluginManager.class, this.pluginManager, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        if (restartRequired) {
            return;
        }
        // Plugin shutdown logic
        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }
        stopEmbedWebServer();
        pluginManager.close();
        context.close();
        DataStoreManager.close();
        getConfigManager().saveAll();
        Bukkit.getServicesManager().unregisterAll(this);
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

    public static YamlConfiguration getEnv() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(Objects.requireNonNull(getInstance().getTextResource("env.yml")));
        } catch (IOException | InvalidConfigurationException e) {
            throw new RuntimeException(e);
        }
        return config;
    }
}
