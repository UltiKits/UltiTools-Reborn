package com.ultikits.ultitools;

import static com.ultikits.ultitools.utils.CommonUtils.getUltiToolsUUID;
import static com.ultikits.ultitools.utils.PluginInitiationUtils.stopWebsocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.commands.CloudLoginCommand;
import com.ultikits.ultitools.commands.PluginInstallCommands;
import com.ultikits.ultitools.commands.UltiToolsCommands;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.config.document.ConfigLoadResult;
import com.ultikits.ultitools.config.document.ConfigParseException;
import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.config.document.OwnedPaths;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.handler.ConsoleMirror;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataStore;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataStore;
import com.ultikits.ultitools.listeners.PlayerJoinListener;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.EarlyLogCapture;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.PlayerEventManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.listeners.UpdateJoinListener;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.utils.Metrics;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.OfficialLanguageFiles;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.SecurityPolicy;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

import lombok.Getter;
import lombok.Setter;

/**
 * UltiTools plugin main class.
 *
 * @author wisdommen, qianmo
 * @version 6.0.7
 */
public final class UltiTools extends JavaPlugin implements Localized {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^([0-9]+\\.[0-9]+\\.[0-9]+)(?:-[0-9A-Za-z]+)*$");
    // Deliberately java.util.logging, not Bukkit.getLogger() — this backs a static, test-seam
    // method (collectModuleJarUrls) that must be callable from a plain JUnit test with no live
    // Bukkit server (see the WIRE-11 test-seam decision recorded in 04-03-PLAN.md).
    private static final Logger MODULE_SCAN_LOGGER = Logger.getLogger(UltiTools.class.getName());

    /** D-03: the ten current blocked commands, shipped as the operator-editable blocklist's default. */
    private static final List<String> DEFAULT_COMMAND_BLOCKLIST = Collections.unmodifiableList(Arrays.asList(
            "op", "deop", "stop", "restart", "reload", "ban-ip", "pardon-ip", "whitelist", "save-off", "save-all"));
    private static final List<String> COMMAND_BLOCKLIST_COMMENT = Arrays.asList(
            "Commands the panel may never execute remotely. Fully operator-editable — add or remove entries freely.",
            "面板永远不允许远程执行的命令列表。完全由操作员编辑——可自由增删。");

    /** D-15: the default editable-root set — plugin configs and historical logs. */
    private static final List<String> DEFAULT_EDITABLE_ROOTS = Collections.unmodifiableList(
            Arrays.asList("plugins", "logs"));
    private static final List<String> EDITABLE_ROOTS_COMMENT = Arrays.asList(
            "Directories the panel's file API may read/write/delete within, subject to the capability switches above.",
            "面板文件 API 可以在其中读取/写入/删除的目录，仍受上方能力开关约束。");

    private static final List<String> ACTION_LOG_SIZE_COMMENT = Arrays.asList(
            "Rotation size (bytes) for the active file, "
                    + "plugins/UltiTools/security/action.log.0, before it rolls to the next generation.",
            "action.log.0（当前活跃文件）的单文件轮转大小（字节），超过后滚动到下一代。");
    private static final List<String> ACTION_LOG_FILES_COMMENT = Arrays.asList(
            "Number of rotated action.log.<generation> files to keep (generation 0 is always active).",
            "保留的 action.log.<代数> 文件数量（代数 0 始终是当前活跃文件）。");

    // The six constants above are declared here, at the top of the class alongside the other
    // static final constants, rather than at their original position (immediately before
    // migrateCapabilitiesConfig()/migrateKeyIfAbsent(), the only methods that use them) — so that
    // they precede all methods (PMD FieldDeclarationsShouldBeAtStartOfClass). The instance-field
    // block below used to violate this rule around getVersionWrapper() (an accessor method that
    // sat between two fields); that method was removed in 6.3.0 (GEN-04) along with the
    // VersionWrapper cluster it belonged to, so the block is contiguous again as of this change.
    private static UltiTools ultiTools;
    @Getter
    private final ListenerManager listenerManager = new ListenerManager();
    @Getter
    private final CommandManager commandManager = new CommandManager();
    @Getter
    private DependenceManagers dependenceManagers;
    private URLClassLoader ultiToolsClassLoader;
    /**
     * The module update and removal transactions of this start (#505): applied before the module
     * class loader is built, decided after the modules load.
     */
    private ModuleFileTransactions moduleFileTransactions;
    /**
     * The official languages the framework ships ({@code lang/<code>.json} in its jar), the codes a custom
     * language name may be based on (#608). {@link #supported()} returns them.
     */
    private static final String[] SHIPPED_LANGUAGES = {"en", "zh"};
    @Getter
    private Language language;
    @Getter
    private PluginManager pluginManager;
    @Getter
    private ConfigManager configManager;
    @Getter
    @Setter
    private DataStore dataStore;
    @Getter
    private ServerMonitorManager serverMonitorManager;
    @Getter
    private CommandExecutionManager commandExecutionManager;
    @Getter
    private FileOperationManager fileOperationManager;
    @Getter
    private LogStreamManager logStreamManager;
    @Getter
    private PlayerEventManager playerEventManager;
    @Getter
    private ServerPropertiesManager serverPropertiesManager;
    @Getter
    private UpdateManager updateManager;
    @Getter
    private EventBus eventBus;
    @Getter
    private ErrorReportCollector errorReportCollector;
    /**
     * The single accessor {@code PluginInitiationUtils}'s capability gate and every WebSocket
     * manager use to append action-log lines — {@code getRemoteActionLog()} (generated by
     * {@link Getter} above).
     */
    // PMD.FieldDeclarationsShouldBeAtStartOfClass: deliberately grouped with its sibling manager
    // fields (language ... errorReportCollector above). This block previously violated the rule
    // because getVersionWrapper() (a method) sat between two of these fields; that method was
    // removed in 6.3.0 (GEN-04) along with the VersionWrapper cluster, so the field block is now
    // contiguous. The @SuppressWarnings below is left in place pending re-verification by the
    // Codacy gate itself rather than removed on inference — this comment no longer claims a live
    // violation, only records why the annotation exists.
    @Getter
    @SuppressWarnings("PMD.FieldDeclarationsShouldBeAtStartOfClass")
    private RemoteActionLog remoteActionLog;
    /**
     * The single-owner registry a module uses to claim a request/response responder for a panel
     * message type the framework itself does not own (WIRE-16, D-26/D-27), reached via
     * {@code getPanelResponderRegistry()} (generated by {@link Getter} above) from both
     * {@code PluginInitiationUtils#handleInboundMessage}'s dispatch and
     * {@code PluginManager}'s two module-unload sites. Constructed unconditionally in
     * {@link #initWebSocketManagers()} alongside the seven WebSocket managers — not gated by any
     * {@link Capability}, since the registry itself holds no data and starts no collection; what
     * flows through it is already gated at the dispatch table it sits behind.
     */
    // PMD.FieldDeclarationsShouldBeAtStartOfClass: same rationale as remoteActionLog above.
    @Getter
    @SuppressWarnings("PMD.FieldDeclarationsShouldBeAtStartOfClass")
    private PanelResponderRegistry panelResponderRegistry;

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
        String versionString = getEnv().getString("version");
        return parsePluginVersion(versionString);
    }

    static int parsePluginVersion(String versionString) {
        if (versionString == null || versionString.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin version is missing");
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionString.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid plugin version: " + versionString);
        }
        try {
            long parsed = Long.parseLong(matcher.group(1).replace(".", ""));
            if (parsed > Integer.MAX_VALUE) {
                throw new NumberFormatException("Version exceeds supported range");
            }
            return (int) parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid plugin version: " + versionString, e);
        }
    }

    /**
     * Retrieves the YAML configuration object containing environment variables.
     *
     * @return the YAML configuration object
     */
    public static YamlConfiguration getEnv() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            Reader envReader = getInstance().getTextResource("env.yml");
            if (envReader == null) {
                // GATE-05 group two (08-21): routed to the typed configuration hierarchy.
                throw new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED, "env.yml not found in resources!");
            }
            config.load(envReader);
        } catch (IOException | InvalidConfigurationException e) {
            // GATE-05 group two (08-21): routed to the typed configuration hierarchy.
            throw ConfigurationException.loadFailed("env.yml", e);
        }
        return config;
    }

    @Override
    public void onLoad() {
        saveDefaultConfig();
        ultiTools = this;
        // #487: keep what the server logs from here on until the panel's log stream starts, so the
        // early boot reaches the panel too; released if the stream does not start in time. Not
        // attached at all when the logs capability is off (D-12).
        EarlyLogCapture.startIfLogsEnabled(getConfig().getStringList("ultipanel.logging.excluded-loggers"));
        // The panel's log stream mirrors the server console (as of 6.3.0): Paper's own output goes
        // through Log4j, which ConsoleMirror feeds into the same capture. Installed together with
        // the capture so the vanilla start-up lines are kept as well.
        if (Capability.LOGS.isEnabled()) {
            ConsoleMirror.install();
        }
        // Plugin classloader initialization
        URL serverJar = getServerJar();
        try {
            if (serverJar != null) {
                File serverFile = new File(serverJar.toURI());
                String name = serverFile.getName().split("\\.jar")[0];
                getLogger().info("Server Jar detected: " + name);
            }
        } catch (URISyntaxException e) {
            // GATE-05 group two (08-21): routed to the typed plugin-module hierarchy -- this is
            // the core plugin itself failing during its own onLoad, the same category as a
            // module's own load failure.
            throw PluginModuleException.loadFailed("UltiTools", e);
        }
    }

    @Override
    public void onEnable() {
        // #505: recorded module updates are put in place before the class loader opens any module
        // JAR, and decided after the modules load (initPluginModules). Their log lines wait for
        // the language to be loaded.
        moduleFileTransactions = new ModuleFileTransactions(getDataFolder());
        ultiToolsClassLoader = new URLClassLoader(getModuleUrls(), getClassLoader());
        this.eventBus = new EventBus();

        if (!initDependencies()) {
            moduleFileTransactions.flushReports(getLogger(), text -> text);
            return;
        }
        initLanguage();
        moduleFileTransactions.flushReports(getLogger(), this::i18n);
        initDataStore();
        initPluginModules();
        migrateCapabilitiesConfig();
        initWebSocketManagers();
        startMetrics(this, 8652, getLogger());

        boolean loginSuccess = attemptCloudLogin();
        if (loginSuccess) {
            // Explicitly arms the cloud-connection state machine. initWebSocket() itself no
            // longer sets this flag -- it is reused by reinitWebSocket, and setting it there
            // would let an in-flight reconnect resurrect a state machine that logout just tore
            // down.
            PluginInitiationUtils.enableCloud();
            initWebSocket();
            PluginInitiationUtils.startTokenRefreshScheduler();
        }

        registerCommands();
        Bukkit.getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
        scheduleStartupMessages(loginSuccess);
        releaseEarlyLogCapture(loginSuccess);
    }

    /**
     * Releases the early log capture (#487) when the panel's log stream cannot start soon: without
     * a cloud login no connection opens until an operator logs in, which may never happen.
     * Otherwise it is released once its time is up, even on a server that logs nothing more.
     */
    private void releaseEarlyLogCapture(boolean loginSuccess) {
        if (!loginSuccess) {
            EarlyLogCapture.release();
            return;
        }
        long ticks = EarlyLogCapture.RELEASE_AFTER_MS / 50L + 20L;
        Bukkit.getScheduler().runTaskLater(this, EarlyLogCapture::releaseIfExpired, ticks);
    }

    private boolean initDependencies() {
        try {
            dependenceManagers = new DependenceManagers(this, ultiToolsClassLoader);
            return true;
        } catch (Exception | NoClassDefFoundError error) {
            getLogger().log(Level.SEVERE, "Failed to initialize dependence managers", error);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    /**
     * Builds the framework's language from the {@code language} setting of {@code config.yml} (#608).
     * <p>
     * First writes the official files {@code lang/en.json} and {@code lang/zh.json} to the data folder so an
     * operator can copy them, restoring any that differ from the bundled version (official language files are
     * framework-owned, maintainer decision 2026-10-04; see {@link OfficialLanguageFiles#syncFrameworkFiles}). Then
     * resolves the setting: an official code is used as it is; a custom name such as {@code zh-myserver} reads the
     * operator's {@code lang/zh-myserver.json} from the data folder when it exists, and takes every message it
     * lacks from the official language its name starts with ({@code zh}); a name that starts with no official
     * code uses English for those messages and logs one warning. The official texts always come from the jar,
     * read as UTF-8. The custom file is only ever read.
     */
    private void initLanguage() {
        List<String> shipped = Arrays.asList(SHIPPED_LANGUAGES);
        List<File[]> restored = OfficialLanguageFiles.syncFrameworkFiles(getDataFolder(), shipped,
                getClass().getClassLoader(), getLogger());
        String configured = getConfig().getString("language");
        String official = Localized.officialLanguageOf(configured, shipped);
        String baseCode = official != null ? official : "en";
        Language bundled = readBundledLanguage(baseCode);
        boolean custom = configured != null && !configured.equals(official);
        Language customLanguage = custom ? OfficialLanguageFiles.readFrameworkCustomFile(getDataFolder(), configured,
                baseCode, getClass().getClassLoader(), getLogger()) : null;
        this.language = customLanguage != null ? customLanguage.withFallback(bundled) : bundled;
        if (official == null && (configured == null || !Localized.isSafeLanguageCode(configured))) {
            getLogger().warning("The language setting '" + configured + "' in config.yml is not a valid language "
                    + "name (an ASCII letter or digit first, then only ASCII letters, digits, '_' and '-'), so no "
                    + "custom file is read; framework messages use 'en'.");
        } else if (official == null) {
            getLogger().warning("The language setting '" + configured + "' in config.yml is neither a shipped "
                    + "language " + shipped + " nor a custom name that starts with one of them and a hyphen, "
                    + "such as zh-myserver; messages it does not provide use 'en'.");
        } else if (custom) {
            getLogger().info("Custom language '" + configured + "' is based on the official language '" + official
                    + "': framework messages come from lang/" + configured + ".json in " + getDataFolder()
                    + (customLanguage != null ? "" : " (not found)") + ", and every message it lacks from the "
                    + "official '" + official + "' file.");
        }
        for (File[] pair : restored) {
            getLogger().warning(String.format(i18n(OfficialLanguageFiles.RESTORED_LOG_KEY),
                    pair[0].getPath(), "UltiTools", pair[1].getPath()));
        }
    }

    /**
     * Reads the official catalogue {@code lang/<code>.json} from the framework jar as UTF-8.
     *
     * @return the catalogue, or an empty language when it is missing or unreadable (the reason is logged)
     */
    private Language readBundledLanguage(String code) {
        String lanPath = "lang/" + code + ".json";
        InputStream in = getFileResource(lanPath);
        if (in == null) {
            getLogger().log(Level.WARNING, "Language file not found: " + lanPath + ", using default language");
            return new Language("{}");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return new Language(reader.lines().collect(Collectors.joining("")));
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to read language file: " + lanPath, e);
            return new Language("{}");
        }
    }

    private void initDataStore() {
        configManager = new ConfigManager();
        boolean mysqlEnabled = getConfig().getBoolean("mysql.enable");
        boolean mysqlAvailable = false;
        if (mysqlEnabled) {
            MysqlDataStore mysqlDataStore = new MysqlDataStore();
            if (mysqlDataStore.getDataSource() != null) {
                DataStoreManager.register(mysqlDataStore);
                mysqlAvailable = true;
            }
        }
        DataStoreManager.register(new SQLiteDataStore());
        String storeType = getConfig().getString("datasource.type");
        //noinspection DataFlowIssue
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null) {
            dataStore = DataStoreManager.getDatastore("json");
        }
        // The backend the configuration asked for and the one actually obtained can be two
        // different things, and this fallback used to be completely silent. See issue #183.
        DataStoreManager.reportBackendSelection(getLogger(), storeType, mysqlEnabled, mysqlAvailable,
                dataStore.getStoreType());
    }

    private void initPluginModules() {
        pluginManager = new PluginManager();
        File file = ModuleFileTransactions.modulesFolder(getDataFolder());
        if (!file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
        }
        try {
            loadModulesThenObserve(moduleFileTransactions, () -> pluginManager.init(ultiToolsClassLoader),
                    pluginManager::getPluginList);
        } catch (IOException e) {
            // GATE-05 group two (08-21): routed to the typed plugin-module hierarchy -- this is
            // the module-loading subsystem itself failing to initialize, before any individual
            // module is even identified.
            throw new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED,
                    "Failed to initialize plugin module loading", e);
        } finally {
            moduleFileTransactions.flushReports(getLogger(), this::i18n);
        }
    }

    /** The module loading step {@link #loadModulesThenObserve} wraps. */
    @FunctionalInterface
    interface ModuleLoad {
        void run() throws IOException;
    }

    /**
     * Loads the modules, then decides every update applied at this start from what loaded (#505).
     * A load that throws counts as "not loaded" for every one of them.
     *
     * @param transactions this start's transactions
     * @param load         the module loading step
     * @param loaded       the modules loaded, read after {@code load} returns
     * @throws IOException when {@code load} does
     */
    static void loadModulesThenObserve(ModuleFileTransactions transactions, ModuleLoad load,
                                       Supplier<List<UltiToolsPlugin>> loaded)
            throws IOException {
        boolean completed = false;
        try {
            load.run();
            completed = true;
        } finally {
            transactions.observeAfterLoad(
                    completed ? loaded.get() : Collections.<UltiToolsPlugin>emptyList(),
                    ModuleFileTransactions::codeSourceOf);
        }
    }

    /**
     * Adds the panel keys the framework's own {@code config.yml} is missing, invoked from {@link #onEnable()}
     * after {@link #saveDefaultConfig()} (called in {@link #onLoad()}) and before
     * {@link #initWebSocketManagers()}. Reloads {@link #getConfig()} only when the file was written, so every
     * manager constructed afterward sees the merged file; when the write is refused nothing is reloaded and the
     * jar's {@code config.yml}, which {@link #getConfig()} uses as its defaults, answers for the missing keys.
     */
    private void migrateCapabilitiesConfig() {
        boolean changed = migrateCapabilitiesConfig(new File(getDataFolder(), "config.yml"), getLogger());
        if (changed) {
            reloadConfig();
        }
    }

    /**
     * The migration itself, taking explicit inputs rather than reading {@code this} - the same test-seam shape
     * {@link #parsePluginVersion(String)} already uses, so it is callable from a plain JUnit test with no live server.
     * <p>
     * <b>Why it cannot overwrite operator content</b> (maintainer decisions of 2026-10-04: "operator-written
     * configuration is never overwritten" and "what code may write, by file type" - a shipped file gets missing keys
     * and the framework's own comments, insert only; UltiKits/UltiTools-Reborn#605). The file is read through
     * {@link ConfigDocument#load(java.nio.file.Path)}; only keys absent from it are inserted, each with its comment, and
     * the write goes through the config write gate ({@link OperatorFileWriter}) owning exactly those keys: every other
     * byte of the file - values, hex numbers, dotted keys, quoting, layout, comments - must come out byte-identical, and
     * the file must still hold the bytes read. Otherwise nothing is written and the gate logs one WARNING naming the
     * keys and the reason. A key the operator set, even to an explicit null, is never touched. A file that cannot be read
     * or parsed is never written (logged at SEVERE, as before).
     * <p>
     * The server reads this file through Bukkit's {@link YamlConfiguration}, which splits a flat dotted key
     * ({@code ultipanel.capabilities.logs: false}) into a path, while the document layer keeps it one whole key. So a key
     * counts as present when either reading holds it, and the edit is first rendered and read the way Bukkit reads it:
     * if any value Bukkit reads for an existing key would change - a nested section added after a flat dotted key
     * replaces it - nothing is written, and one WARNING names the file and that key, never a value (PR #611 local Codex
     * run 2).
     *
     * @param configFile the {@code config.yml} file to migrate
     * @param logger     where to log a load or write failure
     * @return {@code true} if the file was written
     */
    private static boolean migrateCapabilitiesConfig(File configFile, Logger logger) {
        ConfigLoadResult loaded = ConfigDocument.load(configFile.toPath());
        if (loaded.state() != ConfigLoadResult.State.LOADED) {
            Exception cause = loaded.cause() != null ? loaded.cause()
                    : loaded.state() == ConfigLoadResult.State.ABSENT ? new FileNotFoundException(configFile.getPath())
                    : new ConfigParseException(String.valueOf(loaded.parserMessage()), null);
            logger.log(Level.SEVERE, "Cannot load config.yml for capability migration; the file is not written: "
                    + cause.getMessage(), cause);
            return false;
        }

        ConfigDocument document = loaded.document();
        YamlConfiguration server = bukkitReading(configFile, loaded.fingerprint());
        if (server == null) {
            return false;
        }
        Map<List<String>, Object> missing = new LinkedHashMap<>();
        Map<List<String>, List<String>> comments = new LinkedHashMap<>();
        for (Capability capability : Capability.values()) {
            if (capability.getConfigKey() == null) {
                continue; // NONE — never written to config.yml
            }
            addIfAbsent(document, server, missing, comments, capability.getConfigPath(), capability.getDefaultEnabled(),
                    capability.getCommentLines());
        }
        addIfAbsent(document, server, missing, comments, "ultipanel.commands.blocklist",
                DEFAULT_COMMAND_BLOCKLIST, COMMAND_BLOCKLIST_COMMENT);
        addIfAbsent(document, server, missing, comments, "ultipanel.files.editable-roots",
                DEFAULT_EDITABLE_ROOTS, EDITABLE_ROOTS_COMMENT);
        addIfAbsent(document, server, missing, comments, "ultipanel.logging.action-log.max-size-bytes",
                1_048_576, ACTION_LOG_SIZE_COMMENT);
        addIfAbsent(document, server, missing, comments, "ultipanel.logging.action-log.max-files",
                5, ACTION_LOG_FILES_COMMENT);
        if (missing.isEmpty()) {
            return false;
        }

        OwnedPaths.Builder owned = OwnedPaths.builder();
        for (List<String> path : missing.keySet()) {
            owned.value(path);
        }
        java.util.function.Consumer<ConfigDocument> insert = candidate -> {
            for (Map.Entry<List<String>, Object> entry : missing.entrySet()) {
                candidate.set(entry.getKey(), entry.getValue());
                candidate.setFrameworkComment(entry.getKey(), comments.get(entry.getKey()));
            }
        };
        try {
            String changed = keyBukkitWouldReadDifferently(configFile, loaded.fingerprint(), server, insert);
            if (changed != null) {
                logger.warning("Configuration file " + configFile.getAbsolutePath() + " was not written: inserting the"
                        + " missing panel keys would change how the server reads '" + changed + "' (a flat dotted key"
                        + " and a nested section name the same setting). The built-in defaults answer for the missing"
                        + " keys; the file is unchanged.");
                return false;
            }
            OperatorFileWriter.Result result = OperatorFileWriter.write(configFile.toPath(), owned.build(),
                    loaded.fingerprint(), insert);
            return result.outcome() == OperatorFileWriter.Outcome.WRITTEN;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "Failed to persist capability migration: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Records {@code dottedPath} (framework keys, no key contains a dot) as missing when neither the document nor the
     * server's Bukkit reading of the same bytes holds it (a flat dotted key is present only in the latter).
     */
    private static void addIfAbsent(ConfigDocument document, YamlConfiguration server, Map<List<String>, Object> missing,
                                    Map<List<String>, List<String>> comments, String dottedPath, Object value,
                                    List<String> commentLines) {
        List<String> path = Arrays.asList(dottedPath.split("\\."));
        if (!document.contains(path) && !server.contains(dottedPath)) {
            missing.put(path, value);
            comments.put(path, commentLines);
        }
    }

    /**
     * The file as the server reads it - Bukkit's {@link YamlConfiguration} over the same bytes the document was loaded
     * from - or {@code null} when those bytes cannot be read again unchanged or Bukkit cannot parse them (nothing is
     * written then; the next start tries again).
     */
    private static YamlConfiguration bukkitReading(File configFile, String fingerprint) {
        String text = textIfUnchanged(configFile, fingerprint);
        if (text == null) {
            return null;
        }
        YamlConfiguration reading = new YamlConfiguration();
        try {
            reading.loadFromString(text);
        } catch (InvalidConfigurationException | RuntimeException unparseable) {
            return null;
        }
        return reading;
    }

    /** The file's text when its bytes still have {@code fingerprint}, else {@code null}. */
    private static String textIfUnchanged(File configFile, String fingerprint) {
        ConfigLoadResult again = ConfigDocument.load(configFile.toPath());
        if (again.state() != ConfigLoadResult.State.LOADED || !again.fingerprint().equals(fingerprint)) {
            return null;
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(configFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * Renders {@code insert} on a fresh copy of the file and reads the result the way the server does: the first key
     * whose value Bukkit would read differently than {@code before} does, or {@code null} when every existing key reads
     * the same. A file that changed meanwhile, or a rendering Bukkit cannot read, names the whole file.
     */
    private static String keyBukkitWouldReadDifferently(File configFile, String fingerprint, YamlConfiguration before,
                                                        java.util.function.Consumer<ConfigDocument> insert) {
        ConfigLoadResult fresh = ConfigDocument.load(configFile.toPath());
        if (fresh.state() != ConfigLoadResult.State.LOADED || !fresh.fingerprint().equals(fingerprint)) {
            return "the whole file";
        }
        ConfigDocument candidate = fresh.document();
        insert.accept(candidate);
        YamlConfiguration after = new YamlConfiguration();
        try {
            after.loadFromString(candidate.render());
        } catch (InvalidConfigurationException | RuntimeException unreadable) {
            return "the whole file";
        }
        for (String key : before.getKeys(true)) {
            Object was = before.get(key);
            if (!(was instanceof org.bukkit.configuration.ConfigurationSection)
                    && !java.util.Objects.equals(was, after.get(key))) {
                return key;
            }
        }
        return null;
    }

    /**
     * Starts bStats metrics only when its shared file allows it without being written over.
     * <p>
     * <b>Why it cannot overwrite operator content</b> (maintainer foundational rule of 2026-10-04: a file an operator
     * may edit is never overwritten automatically; inventory B3, UltiKits/UltiTools-Reborn#606). The vendored
     * {@link Metrics} - not edited - saves its defaults over {@code plugins/bStats/config.yml}, a file shared by every
     * bStats plugin on the server, whenever it cannot read {@code serverUuid}; on a server an unparseable file loads as
     * empty, so its content would be replaced. This guard reads the file without writing it and constructs
     * {@code Metrics} only when the file is absent (bStats then creates it) or already holds {@code serverUuid} (bStats
     * then writes nothing). A file that cannot be read or parsed, or one without {@code serverUuid}, is left
     * byte-identical: one line names the file and the reason, never a value, and UltiTools' metrics stay off for this
     * run.
     *
     * @param plugin    the plugin bStats reports for; its data folder's parent holds {@code bStats/config.yml}
     * @param serviceId the bStats service id
     * @param logger    where the refusal line goes
     * @return the started metrics, or {@code null} when the shared file was left alone
     */
    static Metrics startMetrics(JavaPlugin plugin, int serviceId, Logger logger) {
        File shared = new File(new File(plugin.getDataFolder().getParentFile(), "bStats"), "config.yml");
        String refusal = bStatsFileRefusal(shared);
        if (refusal != null) {
            logger.warning("bStats metrics are off for this run: " + shared.getPath() + " " + refusal
                    + "; UltiTools leaves the file as it is.");
            return null;
        }
        return new Metrics(plugin, serviceId);
    }

    /** Why {@code shared} must not be handed to bStats, or {@code null}; reads only. */
    private static String bStatsFileRefusal(File shared) {
        if (!shared.exists()) {
            return null;
        }
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(shared.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return "cannot be read (" + unreadable.getClass().getSimpleName() + ")";
        }
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (InvalidConfigurationException | RuntimeException unparseable) {
            return "cannot be parsed";
        }
        return config.isSet("serverUuid") ? null : "has no serverUuid";
    }

    private void initWebSocketManagers() {
        serverMonitorManager = new ServerMonitorManager();
        commandExecutionManager = new CommandExecutionManager();
        fileOperationManager = new FileOperationManager();
        logStreamManager = LogStreamManager.getInstance();
        playerEventManager = new PlayerEventManager();
        serverPropertiesManager = new ServerPropertiesManager(new File(System.getProperty("user.dir")));
        errorReportCollector = new ErrorReportCollector();
        errorReportCollector.init();
        // Constructed unconditionally, gated by no Capability — D-32.
        remoteActionLog = new RemoteActionLog();
        remoteActionLog.init(getDataFolder());
        // Constructed unconditionally alongside the managers above — not gated by any
        // Capability (WIRE-16); see the field javadoc.
        panelResponderRegistry = new PanelResponderRegistry();
    }

    private boolean attemptCloudLogin() {
        // Delegated to PluginInitiationUtils.resumeSavedCredentialOnStartup() (plan 16-09, D-18):
        // loadSavedToken() and loginWithToken(TokenEntity) both had to stop being public statics
        // that accept/return a TokenEntity across a package boundary, so this method's old inline
        // body (load, log, rate-limit check, activate) moved to a package-private-reachable seam.
        return PluginInitiationUtils.resumeSavedCredentialOnStartup();
    }

    private void initWebSocket() {
        getLogger().log(Level.INFO, i18n("正在初始化配置编辑Websocket服务..."));
        try {
            PluginInitiationUtils.initWebsocket();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, i18n("配置编辑Websocket服务初始化失败！") + e.getMessage());
        }
    }

    private void registerCommands() {
        Bukkit.getServicesManager().register(
                PluginManager.class,
                this.pluginManager,
                this,
                ServicePriority.Normal
        );

        CommandManager commandManager = getCommandManager();
        commandManager.registerCoreCommand(new UltiToolsCommands());
        commandManager.registerCoreCommand(new PluginInstallCommands());
        commandManager.registerCoreCommand(new CloudLoginCommand());
    }

    private void scheduleStartupMessages(boolean loginSuccess) {
        getServer().getScheduler().scheduleSyncDelayedTask(this, () -> {
            if (loginSuccess) {
                getLogger().log(Level.INFO, i18n("UltiCloud: Connected!"));
                getLogger().log(Level.INFO, i18n("网页编辑器已启动！访问地址：https://panel.ultikits.com/manger"));
            } else {
                getLogger().log(Level.INFO, "UltiCloud: Not connected. Use /ulticloud login to authenticate.");
            }
            getLogger().log(Level.INFO, String.format(i18n("数据存储方式：%s"), dataStore.getStoreType()));
            getLogger().log(Level.INFO, String.format(i18n("UltiTools-API已启动，当前版本：%s"), getEnv().getString("version")));
            try {
                getLogger().log(Level.INFO, String.format(i18n("服务器UUID: %s"), getUltiToolsUUID()));
            } catch (IOException e) {
                getLogger().log(Level.WARNING, i18n("获取服务器UUID失败！") + e.getMessage());
            }

            // Start async update check
            updateManager = new UpdateManager(getLogger());
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    updateManager.checkUpdatesSync();
                }
            }.runTaskAsynchronously(UltiTools.this);

            // Register join listener for OP notifications
            Bukkit.getPluginManager().registerEvents(new UpdateJoinListener(updateManager), UltiTools.this);
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        EarlyLogCapture.release();
        ConsoleMirror.uninstall();

        if (eventBus != null) {
            eventBus.shutdown();
        }

        // Shut down the error-report collector
        if (errorReportCollector != null) {
            errorReportCollector.shutdown();
        }

        // Shut down the log stream manager
        if (logStreamManager != null) {
            logStreamManager.shutdown();
        }

        // Shut down the remote action log (WR-01, 06-REVIEW.md) -- detaches the FileHandler this
        // instance attached, otherwise the next onEnable after /reload would attach a second one
        // to the same static logger, causing every record to be written twice.
        if (remoteActionLog != null) {
            remoteActionLog.shutdown();
        }

        // Shut down the panel responder registry (WR-01, 06-REVIEW.md) -- stops its own
        // timeout-scheduling thread pool, otherwise every /reload would leak another
        // UltiTools-PanelResponderRegistry-Timeout thread.
        if (panelResponderRegistry != null) {
            panelResponderRegistry.shutdown();
        }

        PluginInitiationUtils.stopCredentialSchedulers();
        if (dependenceManagers != null) {
            dependenceManagers.closeAdventure();
        }
        stopWebsocket();
        if (pluginManager != null) {
            pluginManager.close();
        }
        if (dependenceManagers != null) {
            dependenceManagers.closeContext();
        }
        getCommandManager().close();
        DataStoreManager.close();
        if (configManager != null) {
            // Writes nothing: names, once, configuration changes still registered that were never saved (17-65).
            configManager.reportUnsavedAtStop();
        }
        Bukkit.getServicesManager().unregisterAll(this);
        if (ultiToolsClassLoader != null) {
            try {
                ultiToolsClassLoader.close();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to close module classloader", e);
            }
        }
    }

    /**
     * Reloads the UltiTools plugins by calling the reload method in the PluginManager.
     *
     * @throws IOException if an I/O error occurs during the reloading process
     */
    public void reloadPlugins() throws IOException {
        reloadPluginsAndReport();
    }

    /**
     * Reloads the framework configuration and language, then every module, and returns the
     * summary {@code /ul reload} shows its sender (#509). Each module is reloaded in isolation; see
     * {@code PluginManager#reloadAllAndReport()}.
     *
     * @return the summary lines, already localized
     * @throws IOException if an I/O error occurs during the reloading process
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public List<String> reloadPluginsAndReport() throws IOException {
        // Refresh Bukkit config from disk so language changes are picked up
        reloadConfig();
        // Reinitialize framework language based on (possibly changed) config
        initLanguage();
        return pluginManager.reloadAllAndReport();
    }

    /**
     * Returns the supported language codes.
     *
     * @return a list of supported language codes
     */
    @Override
    public List<String> supported() {
        return Arrays.asList(SHIPPED_LANGUAGES.clone());
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
            URL resource = this.getClass().getClassLoader().getResource(filename);
            if (resource == null) {
                return null;
            }
            return resource.openStream();
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Get server jar file URL.
     *
     * @return Server jar file URL
     */
    public URL getServerJar() {
        ProtectionDomain protectionDomain = Bukkit.class.getProtectionDomain();
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            return null;
        }
        if (codeSource.getLocation().toString().startsWith("union:")) {
            String replace = codeSource.getLocation().toString().replace("union:", "file:").split("%")[0];
            try {
                return new java.net.URI(replace).toURL();
            } catch (MalformedURLException | URISyntaxException e) {
                getLogger().log(Level.WARNING, "Failed to parse server JAR URL: " + replace, e);
            }
        }
        return codeSource.getLocation();
    }

    /**
     * Get URLs for module plugin JARs in the UltiTools/plugins directory.
     *
     * @return Array of URLs for module plugin JARs
     */
    private URL[] getModuleUrls() {
        return moduleClassPath(moduleFileTransactions, ModuleFileTransactions.modulesFolder(getDataFolder()),
                getServerJar());
    }

    /**
     * The module class path of this start: first the recorded module updates are put in place
     * (#505), then the modules folder is listed -- in that order, so the class loader is built over
     * the new JARs and never opens a JAR an update replaces.
     *
     * @param transactions  this start's transactions
     * @param modulesFolder the modules folder
     * @param serverJar     the server JAR, or {@code null}
     * @return the URLs to build the module class loader from
     */
    static URL[] moduleClassPath(ModuleFileTransactions transactions, File modulesFolder, URL serverJar) {
        transactions.applyBeforeLoad();
        List<URL> urls = new ArrayList<>();
        if (serverJar != null) {
            urls.add(serverJar);
        }
        urls.addAll(collectModuleJarUrls(modulesFolder));
        return urls.toArray(new URL[0]);
    }

    /**
     * Scan a directory for module plugin JARs and collect the URLs of the ones that pass
     * {@link SecurityPolicy#isValidModuleJar(File)} — a JAR is validated <b>before</b> its URL is
     * added, never after. A failing JAR is skipped and named in a WARNING; the scan continues and
     * never throws (D-05: module-granularity skip, not a bootstrap abort).
     *
     * <p>Package-private and static so it can be exercised directly by a test against a
     * {@code @TempDir}, without standing up the whole plugin.</p>
     *
     * @param pluginDir directory to scan for module JARs
     * @return collected URLs of the JARs that passed validation, empty if {@code pluginDir} is
     *         {@code null} or does not exist
     */
    static List<URL> collectModuleJarUrls(File pluginDir) {
        List<URL> urls = new ArrayList<>();
        if (pluginDir == null || !pluginDir.exists()) {
            return urls;
        }
        File[] pluginFiles = ModuleFileTransactions.moduleJars(pluginDir);
        if (pluginFiles == null) {
            return urls;
        }
        for (File f : pluginFiles) {
            if (!SecurityPolicy.isValidModuleJar(f)) {
                MODULE_SCAN_LOGGER.log(Level.WARNING,
                        "[UltiTools-API] Skipped module JAR (failed security validation), not added "
                                + "to module classpath: " + f.getName());
                continue;
            }
            try {
                urls.add(f.toURI().toURL());
            } catch (MalformedURLException e) {
                MODULE_SCAN_LOGGER.log(Level.WARNING, "Failed to add module JAR to classpath: " + f.getName(), e);
            }
        }
        return urls;
    }

    /**
     * Get the JavaPlugin class loader.
     * This ensures all class loading operations use the correct parent class loader.
     *
     * @return JavaPlugin class loader
     */
    public static ClassLoader getJavaPluginClassLoader() {
        UltiTools instance = getInstance();
        if (instance != null) {
            if (instance.ultiToolsClassLoader != null) {
                return instance.ultiToolsClassLoader;
            }
            return instance.getClass().getClassLoader();
        }
        // Fallback for testing environments where plugin is not initialized
        return Thread.currentThread().getContextClassLoader();
    }
}
