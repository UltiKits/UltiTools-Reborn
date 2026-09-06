package com.ultikits.ultitools.abstracts;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.context.ConditionalRegistrationEvaluator;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.Configurable;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.DependencyUtils;
import com.ultikits.ultitools.utils.FileUtils;
import com.ultikits.ultitools.utils.VersionComparatorUtil;

import lombok.Getter;
import lombok.Setter;

/**
 * Abstract class representing a plugin module.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class UltiToolsPlugin implements IPlugin, Localized, Configurable {
    /**
     * Language file extensions, in the order they are tried.
     * <p>
     * {@code .json} stays first so a module shipping both keeps exactly the behaviour it had
     * before 6.3.0. {@code .yml} and {@code .yaml} were added for #389: eight modules ship YAML,
     * and the loader silently produced an empty dictionary for every one of them.
     */
    private static final String[] LANGUAGE_EXTENSIONS = {".json", ".yml", ".yaml"};

    private Language language;
    @Getter
    private final String version;
    @Getter
    private final String pluginName;
    @Getter
    private final List<String> authors;
    @Getter
    private final List<String> loadAfter;
    @Getter
    private final int minUltiToolsVersion;
    @Getter
    private final String mainClass;
    @Getter
    private final String identifyString;
    @Getter
    @Setter
    private String resourceFolderPath;
    @Getter
    private SimpleContainer context;
    private com.ultikits.ultitools.manager.DataScope dataScope;


    /**
     * Constructor for UltiToolsPlugin. For module development only.
     */
    protected UltiToolsPlugin() {
        YamlConfiguration pluginConfig = loadPluginConfiguration();

        if (!pluginConfig.contains("name")) {
            // D-16: a module with no `name:` key used to silently become "unknown" and share
            // sqliteDB/unknown.db with every other name-less module (measured on-disk: 10 tables
            // from 8 modules, one of them - world_settings - holding the same logical rows as a
            // properly-named module's own .db file). Fail fast at load instead, naming the JAR, so
            // the operator sees this at startup rather than discovering it as missing data later.
            throw new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED,
                    "Module JAR '" + resolveJarFileNameForError() + "' has no 'name:' key in its "
                            + "plugin.yml. Refusing to load rather than silently sharing "
                            + "sqliteDB/unknown.db with other unnamed modules - add a 'name:' key.");
        }

        version = pluginConfig.getString("version", "unknown");
        pluginName = pluginConfig.getString("name");
        authors = pluginConfig.getStringList("authors");
        loadAfter = pluginConfig.getStringList("loadAfter");
        minUltiToolsVersion = pluginConfig.getInt("api-version", 0);
        mainClass = pluginConfig.getString("main", "unknown");
        identifyString = pluginConfig.getString("identify-string", null);

        resourceFolderPath = UltiTools.getInstance().getDataFolder().getAbsolutePath() + File.separator + "pluginConfig" + File.separator + this.getPluginName();
        language = initializeLanguage();
        saveResources();
        try{
            initConfig();
        } catch (IOException e) {
            getLogger().error(e);
        }
    }

    /**
     * Initializes the language object
     * @return Language object
     */
    private Language initializeLanguage() {
        return createLanguageFromPath(resourceFolderPath);
    }

    /**
     * Resolves which language code to actually load, consulting {@link Localized#supported()}
     * before {@link #createLanguageFromPath(String)} picks a file (D-20/D-21/WIRE-10). Prefers
     * the configured code; if it is absent from a non-empty {@code supported()}, prefers
     * {@code "en"} when {@code supported()} contains it, otherwise the first entry in
     * {@code supported()}'s iteration order. An empty {@code supported()} is "no information" -
     * the configured code is returned unchanged and nothing is logged.
     *
     * @return the language code to actually load
     */
    private String resolveLanguageCode() {
        String configured = getLanguageCode();
        List<String> supportedCodes = this.supported();
        if (supportedCodes == null || supportedCodes.isEmpty()) {
            return configured;
        }
        if (configured != null && supportedCodes.contains(configured)) {
            return configured;
        }
        String fallback = supportedCodes.contains("en") ? "en" : supportedCodes.get(0);
        getLogger().warn("Module '" + getPluginName() + "' is configured for language '" + configured
                + "' but only ships " + supportedCodes + " - falling back to '" + fallback + "'.");
        return fallback;
    }

    /**
     * Creates a Language object from the given resource folder path
     * @param folderPath the resource folder path
     * @return Language object
     */
    private Language createLanguageFromPath(String folderPath) {
        String resolvedCode = resolveLanguageCode();
        for (String extension : LANGUAGE_EXTENSIONS) {
            Language onDisk = loadLanguageFromDisk(folderPath, resolvedCode, extension);
            if (onDisk != null) {
                return onDisk;
            }
            Language inJar = loadLanguageFromJar(resolvedCode, extension);
            if (inJar != null) {
                return inJar;
            }
        }
        // #389: this used to return an empty dictionary without a word. Language.get then falls
        // back to the key, so every message in the module rendered as its own raw key -- which is
        // what a player sees, and what nobody sees in the log. Eight of sixteen modules were in
        // this state for a whole release because they ship lang/*.yml and only .json was looked
        // for. Whatever the cause next time, it will say so.
        getLogger().warn("Module '" + getPluginName() + "' has no loadable language file for '"
                + resolvedCode + "'. Looked for lang/" + resolvedCode + " with extensions "
                + Arrays.toString(LANGUAGE_EXTENSIONS) + ", on disk under " + folderPath
                + " and inside the module jar. Every i18n(...) call in this module will render its "
                + "own key until one is added.");
        return new Language("{}");
    }

    /**
     * Reads {@code <folderPath>/lang/<code><extension>} if it exists, else {@code null}.
     */
    private Language loadLanguageFromDisk(String folderPath, String code, String extension) {
        File file = new File(folderPath + File.separator + "lang" + File.separator + code + extension);
        if (!file.exists()) {
            return null;
        }
        if (".json".equals(extension)) {
            return new Language(file);
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return Language.fromYaml(reader);
        } catch (IOException e) {
            getLogger().error("Failed to read language file " + file.getPath(), e);
            return new Language("{}");
        }
    }

    /**
     * Reads {@code lang/<code><extension>} from the module's own {@link CodeSource} location if
     * present, else {@code null}. Mirrors {@link Localized#scanLangResources(URL)}'s directory/jar
     * branch (13-REVIEW CR-01, issue #412 follow-up): an exploded classpath (dev workspace, IDE
     * launch, or a module test that instantiates a real {@code UltiToolsPlugin} subclass) has a
     * directory as its {@code CodeSource}, and {@link Localized#supported()} already scans that
     * directory directly -- this method must not disagree by unconditionally trying (and failing)
     * to open the directory as a {@code JarFile} first.
     * <p>
     * The resource path is built with {@code '/'} for the jar-entry lookup and {@link
     * File#separator} for the on-disk lookup: jar entry names always use a forward slash, so the
     * separator form would silently find nothing on a Windows host, and the reverse holds for a
     * real file path.
     */
    private Language loadLanguageFromJar(String code, String extension) {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        if (src == null || src.getLocation() == null) {
            return null;
        }
        String rawPath = src.getLocation().getPath();
        File location = new File(rawPath.startsWith("/") ? rawPath : rawPath.substring(1));
        if (location.isDirectory()) {
            // Exploded classpath (dev workspace, IDE launch, test) -- Localized.scanLangResources()
            // already treats this shape as first-class; loadLanguageFromJar must not disagree.
            File resource = new File(location, "lang" + File.separator + code + extension);
            if (!resource.isFile()) {
                return null;
            }
            try (BufferedReader reader = Files.newBufferedReader(resource.toPath(), StandardCharsets.UTF_8)) {
                return parseLanguageResource(reader, extension);
            } catch (IOException e) {
                getLogger().error(e, "Failed to read language resource " + resource + " from " + location);
                return new Language("{}");
            }
        }
        String entryName = "lang/" + code + extension;
        try (JarFile jarFile = new JarFile(location)) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                return parseLanguageResource(reader, extension);
            }
        } catch (IOException e) {
            getLogger().error(e, "Failed to read language resource " + entryName + " from " + location);
            return new Language("{}");
        }
    }

    /**
     * Parses a {@code lang/*} resource already opened as a {@link BufferedReader} -- shared by
     * both the on-disk (exploded directory) and in-jar branches of {@link
     * #loadLanguageFromJar(String, String)} so the two stay in sync.
     */
    private static Language parseLanguageResource(BufferedReader reader, String extension) throws IOException {
        if (".json".equals(extension)) {
            return new Language(reader.lines().collect(Collectors.joining("")));
        }
        // Joining with "" is fine for JSON and destroys YAML, whose structure is the line
        // breaks -- so YAML is handed the reader rather than a flattened string.
        return Language.fromYaml(reader);
    }

    /**
     * Loads the plugin configuration from plugin.yml
     * @return YamlConfiguration object with default values if loading fails
     */
    private YamlConfiguration loadPluginConfiguration() {
        try (InputStream inputStream = getInputStream()) {
            if (inputStream == null) {
                getLogger().error("Cannot find plugin.yml in the plugin jar");
                return new YamlConfiguration();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            getLogger().error("Failed to load plugin configuration", e);
            return new YamlConfiguration();
        }
    }

    /**
     * Constructor for UltiToolsPlugin. For plugin connector.
     *
     * @param pluginName          the name of the plugin
     * @param version             the version of the plugin
     * @param authors             the authors of the plugin
     * @param loadAfter           the plugins which should be loaded before this plugin
     * @param minUltiToolsVersion the minimum version of UltiTools required by this plugin
     * @param mainClass           the main class of the plugin
     * @param resourceFolderPath  the path to the resource folder
     */
    public UltiToolsPlugin(String pluginName, String version, List<String> authors, List<String> loadAfter, int minUltiToolsVersion, String mainClass, String resourceFolderPath) {
        this.pluginName = pluginName;
        this.version = version;
        this.authors = authors;
        this.loadAfter = loadAfter;
        this.minUltiToolsVersion = minUltiToolsVersion;
        this.mainClass = mainClass;
        this.identifyString = null; // Connector plugins don't have identify-string
        this.resourceFolderPath = resourceFolderPath;
        language = createLanguageFromPath(resourceFolderPath);
        saveResources();
        try {
            initConfig();
        } catch (IOException e) {
            // GATE-05 group two (08-21): routed to the typed plugin-module hierarchy -- this
            // constructor failing to complete means the whole connector plugin failed to load.
            throw PluginModuleException.loadFailed(pluginName, e);
        }
    }

    /**
     * Injects the IoC container created for this plugin.
     * <p>
     * Called by {@code PluginManager} while a module is being loaded, before
     * {@code registerSelf()} runs. It is not part of the module-facing API — a
     * module that calls it replaces the container the framework already wired up,
     * losing every bean that was injected into it.
     * <p>
     * Deliberately still public: {@code PluginManager} lives in another package, so
     * this cannot be narrowed to package-private, and deleting it outright would
     * remove a public method, which the compatibility policy forbids in a PATCH
     * release. The annotation is a signal to humans and IDEs; it enforces nothing
     * at runtime.
     *
     * @param context the container created for this plugin
     */
    @ApiStatus.Internal
    public void setContext(SimpleContainer context) {
        this.context = context;
    }

    /**
     * Injects the {@link com.ultikits.ultitools.manager.DataScope} credential {@code
     * PluginManager} minted for this module (D-17). Called by {@code PluginManager} right after
     * minting, before {@code wireAop} runs -- the same lifecycle point {@link #setContext}
     * documents.
     * <p>
     * Deliberately still public, for the same reason {@link #setContext} is: {@code
     * PluginManager} lives in another package and cannot be narrowed to package-private, and the
     * compatibility policy forbids removing a public method in a PATCH release. The annotation is
     * a signal to humans and IDEs; it enforces nothing at runtime.
     *
     * @param scope the scope minted for this plugin
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public void setDataScope(com.ultikits.ultitools.manager.DataScope scope) {
        this.dataScope = scope;
    }

    /**
     * @return the config manager
     */
    public static ConfigManager getConfigManager() {
        return UltiTools.getInstance().getConfigManager();
    }

    /**
     * @return the listener manager
     */
    public static ListenerManager getListenerManager() {
        return UltiTools.getInstance().getListenerManager();
    }

    /**
     * @return the command manager
     */
    public static CommandManager getCommandManager() {
        return UltiTools.getInstance().getCommandManager();
    }

    /**
     * @return the plugin manager
     */
    public static PluginManager getPluginManager() {
        return UltiTools.getInstance().getPluginManager();
    }

    /**
     * Initializes the configuration entity.
     */
    private void initConfig() throws IOException {
        EnableAutoRegister annotation = MergedAnnotationResolver.find(this.getClass(), EnableAutoRegister.class);
        if (annotation != null && annotation.config()) {
            for (String packageName : DependencyUtils.getPluginPackages(this)) {
                UltiTools.getInstance().getConfigManager().registerAll(
                        this, packageName, UltiTools.getJavaPluginClassLoader()
                );
            }
            // D-06: diff getAllConfigs() against what package-scan auto-registration actually
            // registered. Runs only on this branch - on the config = false branch below,
            // getAllConfigs() is the sole registration path and there is nothing to diff against.
            diffGetAllConfigsOverride();
            return;
        }
        List<AbstractConfigEntity> allConfigs = this.getAllConfigs();
        for (AbstractConfigEntity configEntity : allConfigs) {
            UltiToolsPlugin.getConfigManager().register(this, configEntity);
        }
    }

    /**
     * Diffs a {@link #getAllConfigs()} override against what auto-registration already
     * registered for this module (D-06 / SILENT-18 / #336), called once right after
     * package-scan auto-registration finishes.
     * <p>
     * An empty override (the interface default - the module never wrote {@link #getAllConfigs()})
     * has nothing to compare and nothing to log. A non-empty override whose every {@code
     * configFilePath} was already registered by the package scan is pure redundancy, logged at
     * {@link Level#FINE} only. A non-empty override naming a {@code configFilePath} the scan
     * never registered is real capability loss - #336's warning-only ask cannot tell these two
     * cases apart, so this refuses the module and names every missing entity instead of guessing.
     *
     * @throws ConfigurationException if the override names a {@code configFilePath} auto-registration
     *                                 never registered
     */
    private void diffGetAllConfigsOverride() {
        List<AbstractConfigEntity> override = this.getAllConfigs();
        if (override.isEmpty()) {
            return;
        }
        Set<String> overridePaths = new LinkedHashSet<>();
        for (AbstractConfigEntity entity : override) {
            overridePaths.add(entity.getConfigFilePath());
        }

        Map<String, AbstractConfigEntity> registered = UltiToolsPlugin.getConfigManager().getAllConfigEntities(this);
        Set<String> registeredPaths = registered != null ? registered.keySet() : Collections.<String>emptySet();

        List<String> missing = new ArrayList<>();
        for (String path : overridePaths) {
            if (!registeredPaths.contains(path)) {
                missing.add(path);
            }
        }

        if (missing.isEmpty()) {
            getLogger().debug("getAllConfigs() override registers " + overridePaths.size()
                    + " entit" + (overridePaths.size() == 1 ? "y" : "ies")
                    + " already found by package-scan auto-registration - the override adds nothing.");
            return;
        }

        List<String> violations = new ArrayList<>();
        for (String path : missing) {
            violations.add("getAllConfigs() registers '" + path
                    + "' but package-scan auto-registration never found it - the entity is lost");
        }
        throw ConfigurationException.validationFailed(getPluginName(), "getAllConfigs() override", violations);
    }

    private InputStream getInputStream() throws IOException {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        String path = jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1);
        try {
            URL url = new java.net.URI("jar:file:" + path + "!/plugin.yml").toURL();
            JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
            return jarConnection.getInputStream();
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid URL format", e);
        }
    }

    /**
     * Best-effort resolution of this module's own JAR file name, for the {@code name:}-missing
     * refusal message only. Never throws -- falls back to the class name if the code source is
     * unavailable (e.g. when running from unpacked classes in a test).
     *
     * @return the JAR file name, or this class's name if it cannot be determined
     */
    private String resolveJarFileNameForError() {
        try {
            CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                String path = src.getLocation().getPath();
                int slash = path.lastIndexOf('/');
                return slash >= 0 ? path.substring(slash + 1) : path;
            }
        } catch (Exception ignored) {
            // Best effort only - fall through to the class-name fallback below.
        }
        return this.getClass().getName();
    }

    protected final String getConfigFolder() {
        return this.resourceFolderPath;
    }

    protected final File getConfigFile(String path) {
        return new File(getConfigFolder() + File.separator + path);
    }

    public <T extends AbstractConfigEntity> T getConfig(Class<T> configType) {
        return getConfigManager().getConfigEntity(this, configType);
    }

    public <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType) {
        return getConfigManager().getConfigEntity(this, path, configType);
    }

    public <T extends AbstractConfigEntity> List<T> getConfigs(Class<T> configType) {
        return getConfigManager().getConfigEntities(this, configType);
    }

    public <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException {
        getConfigManager().getConfigEntity(this, path, configType).save();
    }

    /**
     * Extracts this module's embedded {@code res}/{@code lang}/{@code config} jar resources into
     * {@link #resourceFolderPath}. Guarantees canonical-path validation before every write: each
     * extracted entry's resolved destination is checked against the resource folder's own
     * canonical path, and any entry whose path would resolve outside it (a Zip Slip attempt) is
     * skipped with a warning rather than written.
     */
    private void saveResources() {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        try (JarFile jarFile = new JarFile(
                jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1)
        )) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                String fileName = jarEntry.getName();
                if ((!fileName.startsWith("res") && !fileName.startsWith("lang")
                        && !fileName.startsWith("config")) || !fileName.contains(".")) {
                    continue;
                }
                try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
                    if (inputStream == null) {
                        throw new IllegalArgumentException("The embedded resource '" + fileName + "' cannot be found in " + fileName);
                    }
                    File outFile = new File(resourceFolderPath, fileName);
                    // Zip Slip protection: ensure extracted file stays within resource folder
                    String canonicalDest = outFile.getCanonicalPath();
                    String canonicalBase = new File(resourceFolderPath).getCanonicalPath() + File.separator;
                    if (!canonicalDest.startsWith(canonicalBase)) {
                        getLogger().warn("Skipping jar entry with path traversal: " + fileName);
                        continue;
                    }
                    try {
                        if (outFile.exists()) {
                            continue;
                        }
                        FileUtils.touch(outFile);
                        try (OutputStream out = Files.newOutputStream(outFile.toPath())) {
                            byte[] buf = new byte[1024];
                            int len;
                            while ((len = inputStream.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                        }
                    } catch (IOException ex) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING, "Could not save " + outFile.getName() + " to " + outFile);
                    }
                }
            }
        } catch (IOException e) {
            getLogger().error("Failed to save resources from jar", e);
        }
    }

    private InputStream getResource(String filename) {
        try {
            ClassLoader classLoader = this.getClass().getClassLoader();
            URL resource = classLoader.getResource(filename);
            if (resource == null) {
                return null;
            }
            return resource.openStream();
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Gets data operator. Refuses outright if {@code dataClazz} is not registered to this module
     * (D-14) -- checked against the same {@link com.ultikits.ultitools.manager.DataScope} minted
     * for this module at load, via the same refusal {@code DataStore.getOperator(DataScope,
     * Class)} builds, so the exception type, error code, and message shape are identical
     * regardless of which entry point a caller reaches.
     * <p>
     * <strong>02-13 (CR-03):</strong> before this, {@code dataScope.owns(...)} was checked here
     * inline and this method then delegated to the deprecated {@code getOperator(UltiToolsPlugin,
     * Class)} overload directly -- so {@code DataStore.getOperator(DataScope, Class)}, the method
     * D-17/02-07 built specifically as the credential-typed supported path, had zero real callers
     * anywhere in the framework. This now routes through it, so production actually uses the path
     * it was built for. {@code dataScope} is normally non-null by the time any module calls this
     * (set by {@code PluginManager} right after minting, before {@code registerSelf()} runs); the
     * {@code null} fallback below only covers a bare instance constructed outside the normal
     * {@code PluginManager} load flow (e.g. a test), where the deprecated overload's own {@code
     * checkOwnership(...)} call still refuses correctly on its own.
     *
     * @param dataClazz the class of the data entity
     * @param <T>       the type of the data entity
     * @return the data operator
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataClazz} is not
     *         registered to this module
     */
    public final <T extends BaseDataEntity<String>> DataOperator<T> getDataOperator(Class<T> dataClazz) {
        if (dataScope != null) {
            return UltiTools.getInstance().getDataStore().getOperator(dataScope, dataClazz);
        }
        return UltiTools.getInstance().getDataStore().getOperator(this, dataClazz);
    }

    /**
     * @return language code
     */
    public final String getLanguageCode() {
        return UltiTools.getInstance().getConfig().getString("language");
    }

    /**
     * @return the language
     */
    public final Language getLanguage() {
        return language;
    }

    /**
     * @param str the string to be localized
     * @return the localized string
     */
    public String i18n(String str) {
        return this.getLanguage().getLocalizedText(str);
    }

    @Override
    public final String i18n(String code, String str) {
        return this.getLanguage().getLocalizedText(str);
    }

    /**
     * @param plugin the plugin to be checked
     * @return whether the plugin is newer than the given plugin
     */
    public boolean isNewerVersionThan(UltiToolsPlugin plugin) {
        if (plugin == null || plugin.getVersion() == null || this.getVersion() == null) {
            return false;
        }
        return VersionComparatorUtil.compare(this.getVersion(), plugin.getVersion()) > 0;
    }

    @Override
    public void unregisterSelf() {
        getCommandManager().unregisterAll(this);
        getListenerManager().unregisterAll(this);
    }

    /**
     * Reload this plugin's configuration and language files.
     * <p>
     * Also reports (but does not act on) any {@code @ConditionalOnConfig} drift: the condition
     * is evaluated once, at component-scan time during startup, so a reload can only log that a
     * watched key has changed direction since then -- it never registers, unregisters, or
     * rebuilds anything (issue #392, D-01). A module overriding {@code reloadSelf()} without
     * calling {@code super.reloadSelf()} will not get this report; that is pre-existing
     * behaviour for the two statements above too, stated here so it is not a surprise.
     */
    @Override
    public void reloadSelf() {
        getConfigManager().reloadConfigs(this);
        // Reinitialize language in case language setting changed
        language = createLanguageFromPath(resourceFolderPath);
        // @ConditionalOnConfig is evaluated once at component-scan time; a reload can only
        // report drift on a watched key, never re-register or rebuild anything (#392, D-01).
        ConditionalRegistrationEvaluator.reportDrift(this);
    }

    /**
     * @return plugin logger
     */
    public PluginLogger getLogger() {
        return new PluginLogger(this.pluginName, UltiTools.getInstance().getLogger());
    }
}
