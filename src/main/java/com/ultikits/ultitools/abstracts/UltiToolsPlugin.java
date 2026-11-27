package com.ultikits.ultitools.abstracts;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.text.internal.ModuleTexts;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;


import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.context.ConditionalRegistrationEvaluator;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.Configurable;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.ManagerKeys;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.CodeSourceLocations;
import com.ultikits.ultitools.utils.DependencyUtils;
import com.ultikits.ultitools.utils.FileUtils;
import com.ultikits.ultitools.utils.ResourceHashSidecar;
import com.ultikits.ultitools.utils.VersionComparatorUtil;

import lombok.Getter;
import lombok.Setter;

/**
 * Abstract class representing a plugin module.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class UltiToolsPlugin implements IPlugin, Configurable {
    /** Named logger for module reload outcomes. */
    private static final Logger LOGGER = Logger.getLogger(UltiToolsPlugin.class.getName());

    /**
     * The partial reason in the report {@link #reloadWithReport()} returns when the call was
     * refused because it was made off the server thread (#538). Not an i18n key: nothing
     * reaches the operator through it on the framework's own reload paths, which run on the
     * server thread; it is for a module that calls {@link #reloadWithReport()} itself.
     */
    static final String RELOAD_REFUSED_OFF_THREAD_REASON = "the reload was refused: it was called off the server thread";

    private TextEngine textEngine;
    private TextEngine.Prepared preparedMessages;
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
    private DataScope dataScope;

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
        if (UltiTools.getInstance().getDependenceManagers() != null) {
            textEngine = UltiTools.getInstance().getDependenceManagers().getContext().getBean(TextEngine.class);
            if (textEngine != null) {
                CodeSource source = getClass().getProtectionDomain().getCodeSource();
                preparedMessages = textEngine.prepare(MessageKey.namespaceFor(pluginName), pluginName,
                        source == null ? null : source.getLocation(),
                        new File(resourceFolderPath, "messages").toPath(), pluginConfig.getString("source-language"));
            }
        }
        prepareConfigConverters();
        // Extract bundled config and other resources before binding configuration entities.
        saveResources();
        try{
            initConfig();
        } catch (IOException e) {
            getLogger().error(e);
        }
    }

    /** Publishes an accepted candidate's prepared message catalogue before container assembly. */
    @ApiStatus.Internal
    public final void publishMessageCatalogue() {
        if (textEngine != null && preparedMessages != null) {
            textEngine.publish(preparedMessages, this);
        }
    }

    /** Restores the prior message catalogue when an accepted candidate subsequently fails to load. */
    @ApiStatus.Internal
    public final void rollbackMessagePublication() {
        if (textEngine != null && preparedMessages != null) {
            textEngine.rollback(preparedMessages.loaded().catalogue().namespace(), this);
        }
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
        prepareConfigConverters();
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
        if (textEngine != null) {
            context.registerSingleton(Messages.class.getName(),
                    textEngine.messagesFor(MessageKey.namespaceFor(pluginName)));
        }
    }

    /**
     * Exposes the module's text lookup without making its prepared catalogue visible to other modules.
     * @return an empty result when no module messages service was prepared
     */
    @ApiStatus.Internal
    public final Optional<ModuleTexts> moduleTexts() {
        return textEngine == null || preparedMessages == null ? Optional.empty()
                : Optional.of(new ModuleTexts(textEngine, preparedMessages));
    }

    /**
     * Injects the {@link DataScope} credential {@code
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
    public void setDataScope(DataScope scope) {
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
     * Prepares converters and checks only enabled auto-config entities before resources are extracted.
     */
    private void prepareConfigConverters() {
        String[] packages = DependencyUtils.getPluginPackages(this);
        ClassLoader loader = this.getClass().getClassLoader();
        ConverterRegistry.prepareModule(this, packages, loader);
        EnableAutoRegister annotation = MergedAnnotationResolver.find(this.getClass(), EnableAutoRegister.class);
        if (annotation != null && annotation.config()) {
            ConverterRegistry.prepareSelectedConfigs(this, packages, loader);
        }
    }

    /**
     * Initializes the configuration entity.
     */
    private void initConfig() throws IOException {
        EnableAutoRegister annotation = MergedAnnotationResolver.find(this.getClass(), EnableAutoRegister.class);
        if (annotation != null && annotation.config()) {
            // CR-01 (#358 Part 1 gate-1 finding): registered as one plugin-scoped batch across
            // every scan package, not once per package with an independent rollback each -
            // otherwise a later package's refusal would leave an earlier package's already-
            // registered entries stranded. See ConfigManager#registerAll(UltiToolsPlugin,
            // String[], ClassLoader)'s own javadoc for the full reasoning.
            UltiTools.getInstance().getConfigManager().registerAll(
                    this, DependencyUtils.getPluginPackages(this), UltiTools.getJavaPluginClassLoader()
            );
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
     * <p>
     * A directory-backed entity is the exception to the path comparison. Auto-registration expands
     * a {@code configFilePath} that names a directory under {@code getResourceFolderPath()} (the
     * same {@code isDirectory()} test {@code ConfigManager#register} applies) into one registration
     * per file inside it, and registers nothing for an empty directory, so the unexpanded path is
     * never a registered key. Such an override entity counts as found when its class is among the
     * config classes package-scan auto-registration selected - the same
     * {@link ConverterRegistry#prepareSelectedConfigs} selection over the same scan packages and
     * class loader {@link #initConfig()} hands to {@code ConfigManager#registerAll}. An entity class
     * the scan did not select is still refused. This method only reads registrations, the selected
     * classes and whether a path is a directory; it never writes or creates a configuration file.
     *
     * @throws ConfigurationException if the override names a {@code configFilePath} auto-registration
     *                                 never registered
     */
    private void diffGetAllConfigsOverride() {
        List<AbstractConfigEntity> override = this.getAllConfigs();
        if (override.isEmpty()) {
            return;
        }
        Map<String, AbstractConfigEntity> registered = UltiToolsPlugin.getConfigManager().getAllConfigEntities(this);
        Set<String> registeredPaths = registered != null ? registered.keySet() : Collections.<String>emptySet();

        Set<String> overridePaths = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        // Computed only when a directory-backed override entity needs it.
        Set<Class<?>> selectedByScan = null;
        for (AbstractConfigEntity entity : override) {
            String path = entity.getConfigFilePath();
            overridePaths.add(path);
            if (registeredPaths.contains(path)) {
                continue;
            }
            if (namesConfigDirectory(path)) {
                if (selectedByScan == null) {
                    selectedByScan = ConverterRegistry.prepareSelectedConfigs(this,
                            DependencyUtils.getPluginPackages(this), UltiTools.getJavaPluginClassLoader());
                }
                if (selectedByScan.contains(entity.getClass())) {
                    continue;
                }
            }
            missing.add(path);
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

    /**
     * @param configFilePath a {@code configFilePath} from a {@link #getAllConfigs()} override
     * @return whether it names an existing directory under this module's resource folder, the
     *         test {@code ConfigManager#register} uses to expand a directory-backed entity
     */
    private boolean namesConfigDirectory(String configFilePath) {
        String folder = getResourceFolderPath();
        return configFilePath != null && folder != null && new File(folder, configFilePath).isDirectory();
    }

    private InputStream getInputStream() throws IOException {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        URL fileUrl = CodeSourceLocations.toFile(jar).toURI().toURL();
        URL url = new URL("jar:" + fileUrl.toExternalForm() + "!/plugin.yml");
        JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
        return jarConnection.getInputStream();
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
                return CodeSourceLocations.toFile(src.getLocation()).getName();
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

    /**
     * Saves this module's configuration registered at {@code path} through {@link AbstractConfigEntity#save()}: only the
     * settings the module changed since the last load or save are written, and only where the file still holds the
     * value the module started from; every other byte of the file stays as the operator left it, and a change that
     * cannot be written stays in memory and is named in one warning. It cannot overwrite operator content: a value the
     * operator edited, deleted or wrote unusably is never written over (maintainer decision 2026-10-04). Use it for a
     * change the operator asked for through the module, or for shipped text re-rendered after a language switch.
     *
     * @param path       the registered configuration file path, for example {@code config/config.yml}
     * @param configType the configuration entity class
     * @param <T>        the configuration entity type
     * @throws IOException if publishing the verified file fails
     */
    public <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException {
        getConfigManager().getConfigEntity(this, path, configType).save();
    }

    /**
     * Extracts missing bundled resources and configuration into {@link #resourceFolderPath}.
     * Validates each destination before writing, leaves existing files intact, and records the
     * hashes of extracted files in a single batch for later configuration ownership checks.
     */
    private void saveResources() {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        // Codex round 4, P2: accumulated across the whole pass and persisted ONCE via
        // ResourceHashSidecar.recordAll after the loop, instead of one record(...) call (one
        // read-modify-write cycle of the WHOLE sidecar) per extracted file -- see recordAll's own
        // javadoc for why that was quadratic for a module bundling many resources.
        //
        // Codex round 10, P2: recordAll is now called from a `finally` block (below), not as the
        // last statement inside the try -- if a LATER entry throws before the while loop reaches
        // it (e.g. File#getCanonicalPath() failing for a malformed name), the exception used to
        // propagate straight past this call to the outer catch, silently discarding every hash
        // already accumulated for entries that extracted successfully BEFORE the failure. The
        // files those entries wrote remain on disk regardless of how the loop ends, so their
        // provenance must be persisted regardless too -- recordAll is itself a no-op for an empty
        // map, so this costs nothing on the ordinary all-succeeded path.
        Map<String, String> hashesToRecord = new LinkedHashMap<>();
        // #676 case 26 sibling: the JAR is opened through the shared resolver, which decodes the
        // URL's percent escapes, so a module installed under a path with a space or a non-ASCII
        // character still extracts its bundled resources.
        try (JarFile jarFile = new JarFile(CodeSourceLocations.toFile(jar))) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                String fileName = jarEntry.getName();
                if ((!fileName.startsWith("res") && !fileName.startsWith("config")) || !fileName.contains(".")) {
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
                        hashesToRecord.put(fileName, ResourceHashSidecar.sha256(outFile));
                    } catch (IOException | UncheckedIOException ex) {
                        // Codex round 5, P1: ResourceHashSidecar.sha256(File) wraps a read
                        // failure in UncheckedIOException (a RuntimeException), which an
                        // IOException-only catch here does NOT catch -- so a resource that was
                        // successfully extracted but could not be reopened for hashing (e.g. a
                        // write-only default ACL, or a filesystem error on the second open) let
                        // that exception escape saveResources() entirely and abort this whole
                        // module's construction, even though the file itself extracted fine.
                        UltiTools.getInstance().getLogger().log(Level.WARNING, "Could not save " + outFile.getName() + " to " + outFile);
                    }
                }
            }
        } catch (IOException e) {
            getLogger().error("Failed to save resources from jar", e);
        } finally {
            ResourceHashSidecar.recordAll(new File(resourceFolderPath), hashesToRecord);
        }
    }

    /**
     * Gets data operator. Refuses outright if {@code dataClazz} is not registered to this module
     * (D-14) -- checked against the same {@link DataScope} minted
     * for this module at load, via the same refusal {@code DataStore.getOperator(DataScope,
     * Class)} builds, so the exception type, error code, and message shape are identical
     * regardless of which entry point a caller reaches.
     * <p>
     * <strong>02-13 (CR-03):</strong> before this, {@code dataScope.owns(...)} was checked here
     * inline and this method then delegated to the deprecated {@code getOperator(UltiToolsPlugin,
     * Class)} overload directly -- so {@code DataStore.getOperator(DataScope, Class)}, the method
     * D-17/02-07 built specifically as the credential-typed supported path, had zero real callers
     * anywhere in the framework. This now routes through it, so production actually uses the path
     * it was built for. {@code dataScope} is non-null by the time any module calls this (set by
     * {@code PluginManager} right after minting, before {@code registerSelf()} runs). A bare
     * instance constructed outside the normal {@code PluginManager} load flow has no scope and is
     * refused with {@code ENTITY_NOT_OWNED}; before 6.4.0 it fell back to the deprecated {@code
     * DataStore.getOperator(UltiToolsPlugin, Class)} overload, which was removed in 6.4.0.
     *
     * @param dataClazz the class of the data entity
     * @param <T>       the type of the data entity
     * @return the data operator
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataClazz} is not
     *         registered to this module, or this module was not loaded by the framework and so
     *         holds no data scope
     */
    public final <T extends BaseDataEntity<String>> DataOperator<T> getDataOperator(Class<T> dataClazz) {
        if (dataScope == null) {
            throw new com.ultikits.ultitools.exceptions.DataAccessException(ErrorCode.ENTITY_NOT_OWNED,
                    "Module '" + getPluginName() + "' has no data scope -- it was not loaded by the "
                            + "UltiTools plugin manager, so it cannot request a data operator for "
                            + dataClazz.getName() + ".");
        }
        return UltiTools.getInstance().getDataStore().getOperator(dataScope, dataClazz);
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

    /**
     * Extension point for a module's own unload cleanup.
     * <p>
     * Called by {@link #unregisterSelf()} <em>before</em> the framework unregisters this
     * plugin's commands and listeners (D-02), so the module's own beans are still alive
     * while this runs. {@link #unregisterSelf()} is {@code final} and always calls this
     * hook and the framework's own unregistration afterward -- a module cannot skip either
     * by overriding {@link #unregisterSelf()} itself, because that is no longer possible
     * (D-01). The default body does nothing; override this method, not
     * {@link #unregisterSelf()}, to add cleanup work.
     * <p>
     * This hook may throw anything: an unchecked exception, an {@link Error}, or a checked
     * exception it does not declare (Lombok {@code @SneakyThrows}, or a rethrow helper). Whatever
     * it throws, {@link #unregisterSelf()} still unregisters this module's own commands and
     * listeners before the exception propagates -- a throwing
     * hook is surfaced to the caller, not swallowed, but it cannot skip the framework's own
     * cleanup the way an unguarded {@code super} call could. The exception reaches the caller
     * unchanged: the same instance, of the same type, never wrapped. If a cleanup step also
     * fails, that failure does not replace this hook's own exception: it is attached to it via
     * {@link Throwable#addSuppressed} instead, so the module author's own failure is always the
     * one that propagates and is never silently lost behind a secondary framework failure (Codex
     * review on #457, issue #484).
     */
    // The empty body IS the design: it is what keeps every existing module unaffected --
    // a module with no unload work needs no override at all (PMD.UncommentedEmptyMethodBody,
    // matching CommandValidator#onComplete's established precedent for this exact shape).
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    protected void onUnregister() {
    }

    /**
     * Unloads this module's own registrations: runs {@link #onUnregister()}, then unregisters this
     * module's commands, then its listeners. {@code final}, so a module cannot skip either cleanup step.
     * <p>
     * Every step runs whatever an earlier step threw, including a checked exception the hook does not
     * declare. When a step failed, the first failure, in that order, is rethrown afterwards as the same
     * instance and type, never wrapped; every later failure is attached to it via
     * {@link Throwable#addSuppressed}. A checked exception therefore reaches the caller although this
     * method declares none.
     */
    @Override
    public final void unregisterSelf() {
        // Every step below runs regardless of an earlier step's failure (Codex review on #457:
        // "Run listener cleanup even if command cleanup throws") -- a single flat try/finally
        // would let CommandManager.unregisterAll(this) throwing skip
        // ListenerManager.unregisterAll(this) entirely. But running every step is not enough on
        // its own: plain finally-block semantics also let a LATER step's exception silently
        // replace an EARLIER one, discarding it (Codex review on #457, issue #484: "Preserve the
        // hook failure when cleanup also throws"). So failures are collected instead -- the
        // FIRST one, in source order (hook, then command cleanup, then listener cleanup), is the
        // one that propagates, and every later failure is attached to it via addSuppressed()
        // rather than overwriting it.
        Throwable failure = runUnregisterStep(null, this::onUnregister);
        failure = runUnregisterStep(failure, () -> getCommandManager().unregisterAll(this));
        failure = runUnregisterStep(failure, () -> getListenerManager().unregisterAll(this));
        failure = runUnregisterStep(failure, () -> {
            if (textEngine != null) {
                textEngine.withdraw(MessageKey.namespaceFor(pluginName), this);
            }
        });
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        } else if (failure instanceof Error) {
            throw (Error) failure;
        } else if (failure != null) {
            // A checked exception the hook threw without declaring it: rethrown as itself, not wrapped,
            // so the caller sees the hook's own exception -- as performReload() does for onReload().
            rethrowUnchanged(failure);
        }
    }

    /**
     * Runs one {@link #unregisterSelf()} cleanup step, collecting any failure onto {@code
     * previousFailure} instead of letting it replace or be replaced by another step's failure.
     * <p>
     * {@link #onUnregister()}, {@code CommandManager#unregisterAll} and {@code
     * ListenerManager#unregisterAll} declare no checked exceptions, but module code can still throw
     * one without declaring it (Lombok {@code @SneakyThrows}, or a rethrow helper). Catching only
     * {@code RuntimeException | Error} let such an exception escape this barrier and skip every
     * later step, leaving the module's commands and listeners registered after its unload. So this
     * catches {@code Exception | Error}: every {@link Throwable} a step can really throw (this is a
     * deliberate cleanup barrier, not a broad catch: {@code unregisterSelf()} always runs every
     * remaining step and always rethrows exactly one collected failure unchanged, per its own
     * javadoc).
     *
     * @param previousFailure the failure already collected from an earlier step, or {@code null}
     *                        if every earlier step (if any) succeeded
     * @param step            the cleanup step to run
     * @return {@code previousFailure}, with {@code step}'s own failure (if any) attached to it
     *     via {@link Throwable#addSuppressed}; or, if {@code previousFailure} was {@code null},
     *     {@code step}'s own failure, or {@code null} if {@code step} completed normally
     */
    // Deliberate cleanup barrier, also for checked exceptions a step throws without declaring them -- see javadoc above.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static Throwable runUnregisterStep(Throwable previousFailure, Runnable step) {
        try {
            step.run();
        } catch (Exception | Error e) {
            if (previousFailure == null) {
                return e;
            }
            previousFailure.addSuppressed(e);
        }
        return previousFailure;
    }

    /**
     * Throws {@code failure} itself -- the same instance, of its own type, unwrapped -- although it may
     * be a checked exception this method's callers do not declare. {@link #unregisterSelf()} uses it to
     * hand a hook's undeclared checked exception to its caller unchanged.
     *
     * @param failure the collected failure to rethrow
     * @param <T>     inferred as an unchecked type at the call site, so no {@code throws} clause is needed
     * @throws T always: {@code failure}
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrowUnchanged(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * Extension point for a module's own reload work.
     * <p>
     * Called by {@link #reloadSelf()} <em>after</em> the framework's own reload steps -- config
     * reload, config-bound timing refresh (#531), language-catalogue refresh and
     * {@code @ConditionalOnConfig} drift report (D-02) -- so a real-work override sees the
     * already-reloaded configuration rather than the stale one. The framework-owned per-module
     * reload line (D-03) is logged only after this hook returned; if it throws, a failure line
     * naming the module is logged instead and the exception propagates to the caller (#509).
     * {@link #reloadSelf()} is
     * {@code final} and always runs its own steps first; a module cannot skip them by
     * overriding {@link #reloadSelf()} itself, because that is no longer possible (D-01). The
     * default body does nothing; override this method, not {@link #reloadSelf()}, to add reload
     * work.
     */
    // The empty body IS the design: it is what keeps every existing module unaffected --
    // a module with no reload work needs no override at all (PMD.UncommentedEmptyMethodBody,
    // matching CommandValidator#onComplete's established precedent for this exact shape).
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    protected void onReload() {
    }

    /**
     * Extension point for a module's own reload work, with a way to report a partial reload
     * (#529).
     * <p>
     * Called by {@link #reloadSelf()} and {@link #reloadWithReport()} at the point {@link
     * #onReload()} is documented to run, with a fresh {@link ReloadReport}. A module that reloads
     * only part of what it should records each part that did not reload with {@link
     * ReloadReport#partial(String)} and returns normally; the framework then reports the reload
     * as partial, naming those parts, instead of as a success. The default body calls {@link
     * #onReload()}, so a module that overrides only that method behaves exactly as before 6.3.0.
     * Override one of the two, not both: an override of this method replaces the call to
     * {@link #onReload()} unless it calls {@code super.onReload(report)}.
     *
     * @param report the report for this reload, to record the parts that did not reload
     * @since 6.3.0
     */
    protected void onReload(ReloadReport report) {
        onReload();
    }

    /**
     * Reload this plugin's configuration and language files.
     * <p>
     * Also reports (but does not act on) any {@code @ConditionalOnConfig} drift: the condition
     * is evaluated once, at component-scan time during startup, so a reload can only log that a
     * watched key has changed direction since then -- it never registers, unregisters, or
     * rebuilds anything (issue #392, D-01). Right after the configuration reload, and only if it
     * succeeded, the module's config-bound {@code @Scheduled} tasks and {@code @CmdCD} cooldowns
     * pick up their reloaded values (#531; see {@code PluginManager#applyReloadedConfigBindings});
     * a value that step refuses and keeps, or a part of it that fails, is recorded in the
     * {@link ReloadReport} as a part that did not reload (#595).
     * {@code final} and always runs its own steps, then calls {@link #onReload()} -- a module can
     * no longer skip any of this by overriding {@code reloadSelf()} itself, because that override
     * point no longer exists (D-01). The hook it calls is {@link #onReload(ReloadReport)}, whose
     * default body calls {@link #onReload()}.
     * <p>
     * Only when every step and the hook returned does it log one framework-owned line naming this
     * module (D-03): an INFO success line, or a WARNING naming the parts that did not reload when
     * the hook recorded any in its {@link ReloadReport} (#529). If anything throws, it logs one
     * SEVERE line naming the module and the cause instead, never the success line, and rethrows the
     * failure unchanged, so a caller can report it -- {@code /ul reload <name>} replies failure, and
     * a full {@code /ul reload} carries on with the next module and names this one in its summary
     * (#509).
     * <p>
     * The signature is unchanged since 6.2; {@link #reloadWithReport()} runs the same reload and
     * returns the report.
     */
    @Override
    public final void reloadSelf() {
        performReload();
    }

    /**
     * Reloads this module exactly as {@link #reloadSelf()} does, and returns what its reload hook
     * reported (#529).
     * <p>
     * {@code /ul reload} and {@code /ul reload <name>} call this to tell the operator which parts
     * of a module did not reload. A module's own reload command can call it for the same reason.
     *
     * @return the report of this reload, never {@code null}; empty when every part reloaded
     * @throws RuntimeException whatever a reload step or the module's hook threw, after it was logged
     *                          (a checked exception the hook threw without declaring it propagates
     *                          unchanged in the same way)
     * @throws Error            whatever a reload step or the module's hook threw, after it was logged
     * @since 6.3.0
     */
    public final ReloadReport reloadWithReport() {
        return performReload();
    }

    /**
     * The body of {@link #reloadSelf()} and {@link #reloadWithReport()}. Private, so both public
     * entry points always run exactly this.
     *
     * @return the report the module's hook filled in; when the call was refused off the server
     *         thread (#538), a report whose only partial reason is that refusal
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // logged and rethrown unchanged -- see reloadSelf()
    private ReloadReport performReload() {
        ReloadReport report = new ReloadReport();
        // #538: off the server thread both entry points are refused before anything runs --
        // the guard logs its one warning and nothing else is logged, read or changed. The
        // returned report names the refusal, so reloadWithReport() never presents a reload
        // that did not run as a complete one.
        if (!ConfigManager.permitsConfigThread(this, "reloadSelf")) {
            report.partial(RELOAD_REFUSED_OFF_THREAD_REASON);
            return report;
        }
        try {
            runReloadSteps(report);
        } catch (Exception | Error e) {
            // Exception, not RuntimeException: a hook can throw a checked exception it does not
            // declare (Lombok @SneakyThrows); it gets the failure line too. Rethrown unchanged.
            if (LOGGER.isLoggable(Level.SEVERE)) {
                LOGGER.log(Level.SEVERE, FrameworkMessages.plain(ManagerKeys.RELOAD_MODULE_FAILED,
                        Arg.string("module", getPluginName()), Arg.string("error", describeFailure(e))), e);
            }
            throw e;
        }
        if (report.isPartial()) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, FrameworkMessages.plain(ManagerKeys.RELOAD_PARTIAL,
                        Arg.string("module", getPluginName()),
                        Arg.string("reasons", String.join("; ", report.getPartialReasons()))));
            }
        } else if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.log(Level.INFO, FrameworkMessages.plain(ManagerKeys.RELOAD_MODULE_DONE,
                    Arg.string("module", getPluginName())));
        }
        return report;
    }

    /**
     * The reload steps, in order, ending with the module's own hook.
     *
     * @param report the report handed to the module's hook
     */
    private void runReloadSteps(ReloadReport report) {
        getConfigManager().reloadConfigs(this);
        // #531: apply the reloaded values to config-bound @Scheduled/@CmdCD. Only reached when
        // reloadConfigs did not throw, so a refused reload leaves the running timings alone.
        PluginManager pluginManager = UltiTools.getInstance().getPluginManager();
        if (pluginManager != null) {
            // #595: a value the step keeps, or a part of it that fails, is recorded in the report.
            pluginManager.applyReloadedConfigBindings(this, report);
        }
        // Only reload this module's message overrides; the server-wide locale stays in force.
        if (textEngine != null) {
            textEngine.reloadOverrides(MessageKey.namespaceFor(pluginName));
        }
        // #594: refresh framework-owned comments with the new catalogue before invoking the module's hook.
        getConfigManager().refreshFrameworkComments(this);
        String pendingLanguage = pendingLanguageSetting();
        if (pendingLanguage != null) {
            report.partial(pendingLanguage);
        }
        // @ConditionalOnConfig is evaluated once at component-scan time; a reload can only
        // report drift on a watched key, never re-register or rebuild anything (#392, D-01).
        ConditionalRegistrationEvaluator.reportDrift(this);
        onReload(report);
    }

    /**
     * The framework's {@code language} setting as it is in {@code config.yml} on disk now, when it
     * differs from the value the framework runs with (#502).
     * <p>
     * The framework reads its configuration once and re-reads it only on a full {@code /ul
     * reload}. A missing, unreadable or unparseable file, or one without the key, is not a pending
     * change: that is reported, if at all, by the full reload that reads it.
     *
     * @return the reason reported for a pending change, or {@code null} if none was detected
     */
    private String pendingLanguageSetting() {
        UltiTools framework = UltiTools.getInstance();
        File dataFolder = framework.getDataFolder();
        if (dataFolder == null) {
            return null;
        }
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.isFile()) {
            return null;
        }
        YamlConfiguration onDisk = new YamlConfiguration();
        try {
            onDisk.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
        String diskLanguage = onDisk.getString("language");
        if (diskLanguage == null) {
            return null;
        }
        String runningLanguage = framework.getConfig().getString("language");
        return diskLanguage.equals(runningLanguage) ? null : FrameworkMessages.plain(ManagerKeys.RELOAD_LANGUAGE_PENDING,
                Arg.string("from", String.valueOf(runningLanguage)), Arg.string("to", diskLanguage));
    }

    /**
     * A failure's message for a one-line report, or its class name when it carries none.
     *
     * @param failure the failure to describe
     * @return a non-null description
     */
    private static String describeFailure(Throwable failure) {
        String message = failure.getMessage();
        return message != null ? message : failure.getClass().getName();
    }

    /**
     * @return plugin logger
     */
    public PluginLogger getLogger() {
        return new PluginLogger(this.pluginName, UltiTools.getInstance().getLogger());
    }
}
