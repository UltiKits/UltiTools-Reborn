package com.ultikits.ultitools;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
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
import lombok.Setter;
import mc.obliviate.inventory.InventoryAPI;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.ultikits.ultitools.utils.PluginInitiationUtils.*;

/**
 * UltiTools plugin main class.
 *
 * @author wisdommen, qianmo
 * @version 6.0.0
 */
public final class UltiTools extends JavaPlugin implements Localized {
    private static UltiTools ultiTools;
    @Getter
    private final ListenerManager listenerManager = new ListenerManager();
    @Getter
    private final CommandManager commandManager = new CommandManager();
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
    @Getter
    private AnnotationConfigApplicationContext context;
    @Getter
    @Setter
    private DataStore dataStore;
    private boolean restartRequired;
    private BukkitAudiences adventure;

    /**
     * Returns the instance of the UltiTools.
     *
     * @return the instance of the UltiTools
     */
    public static UltiTools getInstance() {
        return ultiTools;
    }

    /**
     * Gets the version of UltiTools.
     *
     * @return the version of the UltiTools
     */
    public static int getPluginVersion() {
        return 600;
    }

    /**
     * Pre-initialization of the plugin.
     * <p>
     * It will save the default config and download the required libraries.
     */
    @Override
    public void onLoad() {
        saveDefaultConfig();
        ultiTools = this;
        restartRequired = downloadRequiredDependencies();
        if (restartRequired) {
            Bukkit.getLogger().log(
                    Level.WARNING,
                    "[UltiTools-API] Libraries downloaded, please restart the server or reload UltiTools!"
            );
//          Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "reload");
        }
    }

    /**
     * Initialization of the plugin.
     * <p>
     * It will do nothing if the plugin requires restart.
     */
    @Override
    public void onEnable() {
        // check if the plugin requires restart
        if (restartRequired) {
            return;
        }

        // External bukkit libraries initialization
        this.adventure = BukkitAudiences.create(this);
        new InventoryAPI(this).init();

        // Adopt server version
        this.versionWrapper = new SpigotVersionManager().match();
        if (this.versionWrapper == null) {
            Bukkit.getLogger().log(
                    Level.SEVERE,
                    "[UltiTools-API] Your server version isn't supported in UltiTools-API!"
            );
            return;
        }

        // Embed web server initialization & Account login
        initEmbedWebServer();
        try {
            loginAccount();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Config initialization & DataStore initialization
        configManager = new ConfigManager();
        String storeType = getConfig().getString("datasource.type");
        //noinspection DataFlowIssue
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null) {
            new DataStoreWaitingTask().runTaskTimerAsynchronously(this, 0L, 20L);
            dataStore = DataStoreManager.getDatastore("json");
        }

        // Language initialization
        String lanPath = "lang/" + getConfig().getString("language") + ".json";
        InputStream in = getFileResource(lanPath);
        @SuppressWarnings("DataFlowIssue")
        String result  = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining(""));
        this.language  = new Language(result);

        // Spring context initialization
        pluginClassLoader = getClassLoader();
        context           = new AnnotationConfigApplicationContext();
        context.setClassLoader(pluginClassLoader);
        context.register(ContextConfig.class);
        context.refresh();
        context.registerShutdownHook();

        // initialize plugin modules
        pluginManager = new PluginManager();
        File file     = new File(getDataFolder() + File.separator + "plugins");
        if (!file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
        }
        try {
            pluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // bukkit plugin registration
        getCommandManager().register(new UltiToolsCommands());
        getCommandManager().register(new PluginInstallCommands());

        Bukkit.getServicesManager().register(
                PluginManager.class,
                this.pluginManager,
                this,
                ServicePriority.Normal
        );
    }

    /**
     * Shutdown of the plugin.
     * <p>
     * It will do nothing if the plugin requires restart.
     */
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

    /**
     * Reloads the UltiTools plugins by calling the reload method in the PluginManager.
     *
     * @throws IOException if an I/O error occurs during the reloading process
     */
    public void reloadPlugins() throws IOException {
        pluginManager.reload();
    }

    /**
     * Returns the supported language codes.
     *
     * @return a list of supported language codes
     */
    @Override
    public List<String> supported() {
        return Arrays.asList("en", "zh");
    }

    /**
     * Internationalization method that translates the given string based on the current language.
     * If the string is not found in the dictionary, the original string is returned.
     *
     * @param str the string to be translated
     * @return the translated string or the original string if not found in the dictionary
     */
    public String i18n(String str) {
        return this.language.getLocalizedText(str);
    }

    /**
     * Retrieves the input stream for the specified file resource.
     *
     * @param filename the name of the file resource
     * @return the input stream for the file resource, or null if an I/O error occurs
     */
    private InputStream getFileResource(String filename) {
        try {
            return Objects.requireNonNull(this.getClass().getClassLoader().getResource(filename)).openStream();
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Returns the instance of the BukkitAudiences used for Adventure messaging.
     *
     * @return the BukkitAudiences instance
     * @throws IllegalStateException if the plugin is disabled and Adventure is accessed
     */
    public BukkitAudiences adventure() {
        if (this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure when the plugin was disabled!");
        }
        return this.adventure;
    }

    /**
     * Retrieves the YAML configuration object containing environment variables.
     *
     * @return the YAML configuration object
     */
    public static YamlConfiguration getEnv() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(Objects.requireNonNull(getInstance().getTextResource("env.yml")));
        } catch (IOException | InvalidConfigurationException e) {
            throw new RuntimeException(e);
        }
        return config;
    }

    /**
     * get UltiTools UUID
     *
     * @return UUID
     * @throws IOException if an I/O error occurs
     */
    public static String getUltiToolsUUID() throws IOException {
        File dataFile = new File(UltiTools.getInstance().getDataFolder(), "data.json");
        JSON json = new cn.hutool.json.JSONObject();
        if (dataFile.exists()) {
            json = JSONUtil.readJSON(dataFile, StandardCharsets.UTF_8);
        } else {
            json.putByPath("uuid", IdUtil.simpleUUID());
            json.write(new FileWriter(dataFile));
        }
        return json.getByPath("uuid").toString();
    }
}
