package com.ultikits.ultitools;

import cn.hutool.core.comparator.VersionComparator;
import com.ultikits.ultitools.commands.PluginInstallCommands;
import com.ultikits.ultitools.commands.UltiToolsCommands;
import com.ultikits.ultitools.context.ContextConfig;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.listeners.PlayerJoinListener;
import com.ultikits.ultitools.manager.*;
import com.ultikits.ultitools.tasks.DataStoreWaitingTask;
import com.ultikits.ultitools.utils.CommonUtils;
import com.ultikits.ultitools.utils.Metrics;
import lombok.Getter;
import lombok.Setter;
import mc.obliviate.inventory.InventoryAPI;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.ultikits.ultitools.utils.DependencyUtils.downloadRequiredDependencies;
import static com.ultikits.ultitools.utils.PluginInitiationUtils.*;
import static com.ultikits.ultitools.utils.VersionUtils.getUltiToolsNewestVersion;

/**
 * UltiTools plugin main class.
 * <p>
 * UltiTools插件主类。
 *
 * @author wisdommen, qianmo
 * @version 6.0.7
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
    private BukkitAudiences adventure;

    /**
     * Returns the instance of the UltiTools.
     * <p>
     * 获取UltiTools的实例。
     *
     * @return the instance of the UltiTools <br> UltiTools的实例
     */
    public static UltiTools getInstance() {
        return ultiTools;
    }

    /**
     * Gets the version of UltiTools.
     * <p>
     * 获取UltiTools的版本。
     *
     * @return the version of the UltiTools <br> UltiTools的版本
     */
    public static int getPluginVersion() {
        String versionString = getEnv().getString("version");
        if (versionString == null) {
            throw new RuntimeException("Version not found in env.yml!");
        }
        return Integer.parseInt(versionString.replace(".", ""));
    }

    /**
     * Retrieves the YAML configuration object containing environment variables.
     * <p>
     * 获取包含环境变量的YAML配置对象。
     *
     * @return the YAML configuration object <br> YAML配置对象
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

    @Override
    public void onLoad() {
        saveDefaultConfig();
        ultiTools = this;
        downloadRequiredDependencies();
    }

    @Override
    public void onEnable() {
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
        String result = new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining(""));
        this.language = new Language(result);

        // Plugin classloader initialization
        pluginClassLoader = getClassLoader();
        URL serverJar = CommonUtils.getServerJar();
        try {
            if (serverJar != null) {
                String name = new File(serverJar.toURI()).getName().split("\\.jar")[0];
                Bukkit.getLogger().info("[UltiTools-API] Spigot API detected: " + name);
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        URLClassLoader classLoader = new URLClassLoader(new URL[]{serverJar}, pluginClassLoader);

        // Spring context initialization
        pluginClassLoader = getClassLoader();
        context = new AnnotationConfigApplicationContext();
        context.setClassLoader(classLoader);
        context.register(ContextConfig.class);
        context.refresh();
        context.registerShutdownHook();

        // initialize plugin modules
        pluginManager = new PluginManager();
        File file = new File(getDataFolder() + File.separator + "plugins");
        if (!file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
        }
        try {
            pluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Metrics metrics = new Metrics(this, 8652);

        Bukkit.getServicesManager().register(
                PluginManager.class,
                this.pluginManager,
                this,
                ServicePriority.Normal
        );

        getCommandManager().register(new UltiToolsCommands());
        getCommandManager().register(new PluginInstallCommands());

        Bukkit.getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);

        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                String ultiToolsNewestVersion = getUltiToolsNewestVersion();
                String currentVersion = getEnv().getString("version");
                Bukkit.getLogger().log(Level.INFO, String.format(i18n("[UltiTools-API] UltiTools-API已启动，当前版本：%s"), getEnv().getString("version")));
                Bukkit.getLogger().log(Level.INFO, i18n("[UltiTools-API] 正在检查版本更新..."));
                if (new VersionComparator().compare(currentVersion, ultiToolsNewestVersion) < 0) {
                    Bukkit.getLogger().log(Level.INFO, String.format(i18n("[UltiTools-API] UltiTools-API有新版本 %s 可用，请及时更新！"), ultiToolsNewestVersion));
                    Bukkit.getLogger().log(Level.INFO, String.format(i18n("[UltiTools-API] 下载地址：§a%s"), "https://github.com/UltiKits/UltiTools-Reborn/releases/latest"));
                    return;
                }
                Bukkit.getLogger().log(Level.INFO, i18n("[UltiTools-API] UltiTools-API已是最新版本！"));
            }
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }
        stopEmbedWebServer();
        pluginManager.close();
        context.close();
        getCommandManager().close();
        DataStoreManager.close();
        getConfigManager().saveAll();
        Bukkit.getServicesManager().unregisterAll(this);
    }

    /**
     * Reloads the UltiTools plugins by calling the reload method in the PluginManager.
     * <p>
     * 通过调用PluginManager中的reload方法重新加载UltiTools插件。
     *
     * @throws IOException if an I/O error occurs during the reloading process
     */
    public void reloadPlugins() throws IOException {
        pluginManager.reload();
    }

    /**
     * Returns the supported language codes.
     * <p>
     * 返回支持的语言代码。
     *
     * @return a list of supported language codes <br> 支持的语言代码列表
     */
    @Override
    public List<String> supported() {
        return Arrays.asList("en", "zh");
    }

    /**
     * Internationalization method that translates the given string based on the current language.
     * If the string is not found in the dictionary, the original string is returned.
     * <p>
     * 根据当前语言翻译给定的字符串的国际化方法。
     * 如果在字典中找不到字符串，则返回原始字符串。
     *
     * @param str the string to be translated <br> 要翻译的字符串
     * @return the translated string or the original string if not found in the dictionary <br> 翻译后的字符串，如果在字典中找不到，则为原始字符串
     */
    public String i18n(String str) {
        return this.language.getLocalizedText(str);
    }

    /**
     * Retrieves the input stream for the specified file resource.
     * <p>
     * 获取指定文件资源的输入流。
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
     * <p>
     * 返回用于Adventure消息的BukkitAudiences实例。
     *
     * @return the BukkitAudiences instance <br> BukkitAudiences实例
     * @throws IllegalStateException if the plugin is disabled and Adventure is accessed <br> 如果在插件已经关闭时访问Adventure
     */
    public BukkitAudiences adventure() {
        if (this.adventure == null) {
            throw new IllegalStateException("[UltiTools-API] Tried to access Adventure when the plugin was disabled!");
        }
        return this.adventure;
    }

    /**
     * Get the economy provider
     * <p>
     * 获取经济服务提供者
     *
     * @return the instance of the Economy provider <br> 经济服务提供者实例
     */
    public Economy getEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            throw new RuntimeException("Vault not found!");
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            throw new RuntimeException("Economy service not found!");
        }
        return registration.getProvider();
    }
}
