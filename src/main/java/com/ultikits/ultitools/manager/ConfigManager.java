package com.ultikits.ultitools.manager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.utils.DependencyUtils;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.ReflectionUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * @author wisdomme
 * @version 1.0.0
 */
public class ConfigManager {

    private final Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = new HashMap<>();
    private final ThreadLocal<InitializationBatch> initializationBatch = new ThreadLocal<>();

    private static final class InitializationBatch {
        private int depth;
        private final Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> before = new LinkedHashMap<>();
        private final Set<AbstractConfigEntity> entities = new LinkedHashSet<>();
    }

    /** Shared refusal guard, checked before any configuration monitor or registry access.
     * @param plugin module, possibly absent for an uninitialized entity
     * @param operation operation name or entity path
     * @return whether the current caller may perform the operation
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static boolean permitsConfigThread(UltiToolsPlugin plugin, String operation) {
        if (org.bukkit.Bukkit.getServer() == null || org.bukkit.Bukkit.isPrimaryThread()) { return true; }
        org.bukkit.Bukkit.getLogger().log(Level.WARNING, "Configuration " + operation + " for module "
                + (plugin == null ? "<uninitialized>" : plugin.getPluginName()) + " called off the server thread ("
                + Thread.currentThread().getName() + "); operation refused");
        return false;
    }

    private static void requireConfigThread(UltiToolsPlugin plugin, String operation) {
        if (!permitsConfigThread(plugin, operation)) {
            throw new IllegalStateException("Configuration " + operation + " requires the server thread");
        }
    }

    private void beginBatch(UltiToolsPlugin plugin) {
        InitializationBatch batch = initializationBatch.get();
        if (batch == null) { batch = new InitializationBatch(); initializationBatch.set(batch); }
        batch.depth++;
        batch.before.computeIfAbsent(plugin, this::snapshotRegisteredEntities);
    }

    private void endBatch(boolean accepted) {
        InitializationBatch batch = initializationBatch.get();
        if (batch == null) { return; }
        if (!accepted) {
            initializationBatch.remove();
            batch.entities.forEach(AbstractConfigEntity::discardInitializationWrite);
            batch.before.forEach(this::rollBackRegisteredEntities);
            return;
        }
        if (--batch.depth != 0) { return; }
        initializationBatch.remove();
        for (AbstractConfigEntity entity : batch.entities) {
            try { entity.flushInitializationWrite(); }
            catch (IOException failure) {
                java.util.logging.Logger.getLogger(ConfigManager.class.getName()).warning(
                        "Configuration initialization write failed: " + entity.getConfigFilePath()
                        + "; file will not be overwritten until reload: " + failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Register config entity.
     *
     * @param ultiToolsPlugin UltiTools module
     * @param configEntity    Config entity
     */
    public void register(UltiToolsPlugin ultiToolsPlugin, AbstractConfigEntity configEntity) throws IOException {
        if (!permitsConfigThread(ultiToolsPlugin, "register")) { return; }
        ConfigEntity annotation = ReflectionUtil.getAnnotation(configEntity.getClass(), ConfigEntity.class);
        boolean directory = annotation != null && new File(ultiToolsPlugin.getResourceFolderPath(), annotation.value()).isDirectory();
        if (!directory) { registerImmediate(ultiToolsPlugin, configEntity); return; }
        beginBatch(ultiToolsPlugin);
        boolean accepted = false;
        try { registerImmediate(ultiToolsPlugin, configEntity); accepted = true; }
        finally { endBatch(accepted); }
    }

    private void registerImmediate(UltiToolsPlugin ultiToolsPlugin, AbstractConfigEntity configEntity) throws IOException {
        ConfigEntity annotation = ReflectionUtil.getAnnotation(configEntity.getClass(), ConfigEntity.class);
        if (annotation == null) {
            return;
        }
        if (annotation.value().isEmpty()) {
            return;
        }
        ConverterRegistry registry = prepareConverters(ultiToolsPlugin);
        registry.checkEntityFields(configEntity.getClass(), ultiToolsPlugin.getPluginName(), annotation.value());
        File file = new File(ultiToolsPlugin.getResourceFolderPath(), annotation.value());
        if (file.isDirectory()) {
            if (!file.exists()) {
                if (!file.mkdirs()) {
                    throw new IOException("Failed to create directory: " + file.getPath());
                }
            }
            // #358 Part 1: a directory config expands to one entity per matching file in the
            // loop below - a later file's refusal must not leave an earlier file's entity
            // stranded in pluginConfigMap. Snapshot what this plugin already held before this
            // call and roll back to exactly that snapshot on any refusal, so this directory's
            // registration is all-or-nothing rather than a half-populated map.
            Map<String, AbstractConfigEntity> registeredBeforeThisCall = snapshotRegisteredEntities(ultiToolsPlugin);
            for (File listFile : file.listFiles()) {
                if (!listFile.isFile() || !listFile.getName().endsWith(".yml")) {
                    continue;
                }
                // Gate-1 review finding (line 62): constructing abstractConfigEntity used to sit
                // OUTSIDE this try block, so a path-dependent constructor failure on a LATER file
                // (ReflectionUtil.newInstance wraps a reflective constructor failure as a
                // RuntimeException) escaped this loop without ever reaching the catch below -
                // every earlier file's entity stayed registered, un-rolled-back. Construction is
                // now inside the guarded region too.
                try {
                    AbstractConfigEntity abstractConfigEntity = ReflectionUtil.newInstance(configEntity.getClass(), listFile.getPath().replace(ultiToolsPlugin.getResourceFolderPath() + File.separator, "").replaceAll("\\\\", "/"));
                    addConfigEntity(ultiToolsPlugin, abstractConfigEntity);
                } catch (RuntimeException e) {
                    rollBackRegisteredEntities(ultiToolsPlugin, registeredBeforeThisCall);
                    throw e;
                }
            }
        } else {
            addConfigEntity(ultiToolsPlugin, configEntity);
        }
    }

    private ConverterRegistry prepareConverters(UltiToolsPlugin plugin) {
        if (ConverterRegistry.hasModule(plugin)) {
            return ConverterRegistry.forModule(plugin);
        }
        return ConverterRegistry.prepareModule(plugin, DependencyUtils.getPluginPackages(plugin),
                plugin.getClass().getClassLoader());
    }

    private void addConfigEntity(UltiToolsPlugin ultiToolsPlugin, AbstractConfigEntity configEntity) {
        prepareConverters(ultiToolsPlugin).checkEntityFields(configEntity.getClass(),
                ultiToolsPlugin.getPluginName(), configEntity.getConfigFilePath());
        try {
            InitializationBatch batch = initializationBatch.get();
            if (batch == null) { configEntity.init(ultiToolsPlugin); }
            else {
                batch.entities.add(configEntity);
                configEntity.initForBatch(ultiToolsPlugin);
            }
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration initialization failed! File path: " + configEntity.getConfigFilePath());
        }
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.computeIfAbsent(ultiToolsPlugin, k -> new HashMap<>());
        configMap.put(configEntity.getConfigFilePath(), configEntity);
    }

    /**
     * Snapshots the complete config-file-path-to-entity mapping {@code ultiToolsPlugin} already
     * holds in {@link #pluginConfigMap}, before a caller is about to register a batch of entities
     * in one call. Used by {@link #register(UltiToolsPlugin, AbstractConfigEntity)}'s directory
     * branch, {@link #registerAll(UltiToolsPlugin, String, ClassLoader)}, and {@link
     * #registerAll(UltiToolsPlugin, String[], ClassLoader)} to restore precisely what existed
     * before this call, on a refusal, without touching anything a prior call already committed
     * (#358 Part 1).
     * <p>
     * Gate-1 review finding: an earlier version of this snapshot captured only the KEY set, and
     * {@link #rollBackRegisteredEntities} retained those keys via {@code retainAll} rather than
     * restoring their values. That is correct only when a batch never replaces an already-
     * registered path with a different entity instance before failing - but a supported flow
     * (e.g. {@code registerAll} scanning a path a module also registered directly via {@code
     * getAllConfigs()}) can do exactly that, per {@code ConfigManagerDualPathRegistrationTest}.
     * Capturing entity VALUES, not just keys, is what lets a rollback actually restore the
     * original entity rather than silently keeping whatever replaced it.
     *
     * @param ultiToolsPlugin the plugin whose current registrations to snapshot
     * @return a defensive copy of the currently-registered path-to-entity map, or an empty map
     *         if none
     */
    private Map<String, AbstractConfigEntity> snapshotRegisteredEntities(UltiToolsPlugin ultiToolsPlugin) {
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(ultiToolsPlugin);
        return configMap == null ? Collections.emptyMap() : new HashMap<>(configMap);
    }

    /**
     * Restores {@code ultiToolsPlugin}'s entry in {@link #pluginConfigMap} to exactly {@code
     * entitiesToRestore} - not just the same key set, but the same path-to-entity VALUES, so a
     * path this call replaced with a different entity before failing is restored to the entity
     * that was actually there before the call started (see {@link
     * #snapshotRegisteredEntities}'s javadoc for why key-only retention was insufficient). The
     * cleanup half of #358 Part 1's all-or-nothing registration.
     *
     * @param ultiToolsPlugin  the plugin to roll back
     * @param entitiesToRestore the path-to-entity map to restore, as captured before this call
     *                          started
     */
    private void rollBackRegisteredEntities(UltiToolsPlugin ultiToolsPlugin, Map<String, AbstractConfigEntity> entitiesToRestore) {
        if (entitiesToRestore.isEmpty()) {
            pluginConfigMap.remove(ultiToolsPlugin);
            return;
        }
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.computeIfAbsent(ultiToolsPlugin, k -> new HashMap<>());
        configMap.clear();
        configMap.putAll(entitiesToRestore);
    }

    /**
     * Register all config entities in the specified package.
     *
     * @param plugin      UltiTools module
     * @param packageName Package name
     * @param classLoader Class loader
     */
    public void registerAll(UltiToolsPlugin plugin, String packageName, ClassLoader classLoader) {
        if (!permitsConfigThread(plugin, "registerAll")) { return; }
        beginBatch(plugin);
        boolean accepted = false;
        try { registerAllImmediate(plugin, packageName, classLoader); accepted = true; }
        finally { endBatch(accepted); }
    }

    private void registerAllImmediate(UltiToolsPlugin plugin, String packageName, ClassLoader classLoader) {
        Set<Class<?>> classes = ConverterRegistry.prepareSelectedConfigs(
                plugin, new String[]{packageName}, classLoader);
        // #358 Part 1: a package can carry more than one @ConfigEntity class, and
        // PackageScanUtils.scanAnnotatedClasses returns them in an unspecified (HashSet) order.
        // A validation refusal on any one of them must not leave a sibling that already
        // registered successfully stranded in pluginConfigMap for a module that is about to be
        // refused as a whole - snapshot what this plugin held before this scan and roll back to
        // exactly that on any refusal, regardless of which class failed or when.
        Map<String, AbstractConfigEntity> registeredBeforeThisCall = snapshotRegisteredEntities(plugin);
        for (Class<?> clazz : classes) {
            String path = clazz.getAnnotation(ConfigEntity.class).value();
            try {
                AbstractConfigEntity configEntity;
                try {
                    configEntity =
                            (AbstractConfigEntity) clazz.getDeclaredConstructor(String.class).newInstance(path);
                } catch (NoSuchMethodException e) {
                    // Try no-arg constructor (class may hardcode path via super() call)
                    configEntity =
                            (AbstractConfigEntity) clazz.getDeclaredConstructor().newInstance();
                }
                register(plugin, configEntity);
            } catch (InstantiationException |
                     InvocationTargetException |
                     IllegalAccessException |
                     NoSuchMethodException e) {
                // Neither the (String) nor the no-arg idiom resolved - refuse by name instead of
                // vanishing silently (D-03). The no-arg-only idiom itself is untouched: it still
                // succeeds on the first catch-free path above and never reaches this branch.
                rollBackRegisteredEntities(plugin, registeredBeforeThisCall);
                throw ConfigurationException.unconstructable(clazz.getName(), e);
            } catch (IOException e) {
                // GATE-05 group two (08-21): routed to the typed configuration hierarchy. The
                // only IOException register() itself declares comes from its directory-config
                // branch's mkdirs() failure -- but that branch is guarded by
                // "if (file.isDirectory())", which for a not-yet-created directory is always
                // false (isDirectory() implies exists()), so mkdirs() is never actually reached
                // via this call chain today. Typed anyway for defense in depth against that
                // guard being fixed later, and because register()'s own declared "throws
                // IOException" makes no promise about which branch produced it.
                rollBackRegisteredEntities(plugin, registeredBeforeThisCall);
                throw ConfigurationException.loadFailed(path, e);
            } catch (RuntimeException e) {
                // #358 Part 1's actual reproduction: a ConfigurationException from
                // validateFields()/ensureConstructable(), raised inside init() deep beneath
                // register() -> addConfigEntity(), is unchecked and was never caught here - it
                // propagated straight out of this loop, leaving every entity this call had
                // already registered stranded for a module that is refused as a whole.
                rollBackRegisteredEntities(plugin, registeredBeforeThisCall);
                throw e;
            }
        }
    }

    /**
     * Registers every {@code @ConfigEntity} class found across MULTIPLE scan packages for one
     * plugin, as ONE atomic batch (CR-01, #358 Part 1 gate-1 finding).
     * <p>
     * {@code UltiToolsPlugin.initConfig()} calls {@link #registerAll(UltiToolsPlugin, String,
     * ClassLoader)} once per surviving entry of {@code DependencyUtils.getPluginPackages(plugin)}
     * - and #362's de-duplication only collapses NESTED scan packages, so two unrelated sibling
     * packages (declared via {@code @ComponentScan(basePackages = {...})} or {@code
     * @UltiToolsModule(scanBasePackages = {...})}) both survive and are scanned in two SEPARATE
     * calls. Each single-package call's own snapshot/rollback is correctly scoped to not disturb
     * a PRIOR call's successful work - which means a refusal on the SECOND package left the
     * FIRST package's already-committed entries stranded, because neither call's snapshot ever
     * captured "before this plugin's whole scan", only "before this one call". This overload
     * snapshots once, before any package in {@code packageNames} is scanned, and rolls back to
     * that ONE snapshot if any package's scan refuses - so a plugin whose scan packages span more
     * than one package registers all of them, or none.
     *
     * @param plugin       UltiTools module
     * @param packageNames every package name to scan, in the order {@code
     *                     DependencyUtils.getPluginPackages} returns them
     * @param classLoader  Class loader
     */
    public void registerAll(UltiToolsPlugin plugin, String[] packageNames, ClassLoader classLoader) {
        if (!permitsConfigThread(plugin, "registerAll")) { return; }
        beginBatch(plugin);
        boolean accepted = false;
        try { registerAllPackages(plugin, packageNames, classLoader); accepted = true; }
        finally { endBatch(accepted); }
    }

    private void registerAllPackages(UltiToolsPlugin plugin, String[] packageNames, ClassLoader classLoader) {
        ConverterRegistry.prepareSelectedConfigs(plugin, packageNames, classLoader);
        Map<String, AbstractConfigEntity> registeredBeforeThisPlugin = snapshotRegisteredEntities(plugin);
        try {
            for (String packageName : packageNames) {
                registerAll(plugin, packageName, classLoader);
            }
        } catch (RuntimeException e) {
            rollBackRegisteredEntities(plugin, registeredBeforeThisPlugin);
            throw e;
        }
    }

    /**
     * Get config entity.
     *
     * @param plugin UltiTools module
     * @param type   Config entity type
     * @param <T>    Config entity type
     * @return Config entity
     */
    public <T extends AbstractConfigEntity> T getConfigEntity(UltiToolsPlugin plugin, Class<T> type) {
        requireConfigThread(plugin, "getConfigEntity");
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return null;
        }
        for (AbstractConfigEntity configEntity : configMap.values()) {
            if (type.isInstance(configEntity)) {
                return type.cast(configEntity);
            }
        }
        return null;
    }

    /**
     * Get config entity by path.
     *
     * @param plugin UltiTools module
     * @param path   Config entity path
     * @param type   Config entity type
     * @param <T>    Config entity type
     * @return Config entity
     */
    public <T extends AbstractConfigEntity> T getConfigEntity(UltiToolsPlugin plugin, String path, Class<T> type) {
        requireConfigThread(plugin, "getConfigEntity " + path);
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return null;
        }
        AbstractConfigEntity configEntity = configMap.get(path);
        if (configEntity == null) {
            return null;
        }
        return type.cast(configEntity);
    }

    /**
     * Get all config entities by type.
     *
     * @param plugin UltiTools module
     * @param type   Config entity type
     * @param <T>    Config entity type
     * @return Config entity list
     */
    public <T extends AbstractConfigEntity> List<T> getConfigEntities(UltiToolsPlugin plugin, Class<T> type) {
        requireConfigThread(plugin, "getConfigEntities");
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return Collections.emptyList();
        }
        List<T> configs = new ArrayList<>();
        for (AbstractConfigEntity configEntity : configMap.values()) {
            if (type.isInstance(configEntity)) {
                configs.add(type.cast(configEntity));
            }
        }
        return configs;
    }

    /**
     * Get all config entities for a plugin.
     *
     * @param plugin UltiTools module
     * @return All config entities
     */
    public Map<String, AbstractConfigEntity> getAllConfigEntities(UltiToolsPlugin plugin) {
        requireConfigThread(plugin, "getAllConfigEntities");
        Map<String, AbstractConfigEntity> registered = pluginConfigMap.get(plugin);
        return registered == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(registered));
    }

    /** Releases one module's configuration entities after unload.
     * @param plugin unloaded module instance
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public void unregisterAll(UltiToolsPlugin plugin) {
        if (!permitsConfigThread(plugin, "unregisterAll")) { return; }
        pluginConfigMap.remove(plugin);
    }

    Set<UltiToolsPlugin> registeredOwners(Class<?> moduleClass) {
        requireConfigThread(null, "registeredOwners");
        Set<UltiToolsPlugin> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (UltiToolsPlugin owner : pluginConfigMap.keySet()) {
            if (owner.getClass() == moduleClass) { owners.add(owner); }
        }
        return owners;
    }

    List<String> unsavedPaths(UltiToolsPlugin plugin) {
        requireConfigThread(plugin, "unsavedPaths");
        List<String> paths = new ArrayList<>();
        Map<String, AbstractConfigEntity> entities = pluginConfigMap.get(plugin);
        if (entities != null) {
            for (String path : new TreeSet<>(entities.keySet())) {
                for (String key : entities.get(path).unsavedEntryPaths()) { paths.add(path + ":" + key); }
            }
        }
        return paths;
    }

    /**
     * Reload all configs.
     *
     * @param plugin UltiTools module
     */
    public void reloadConfigs(UltiToolsPlugin plugin) {
        if (!permitsConfigThread(plugin, "reloadConfigs")) { return; }
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return;
        }
        for (AbstractConfigEntity configEntity : configMap.values()) {
            try {
                configEntity.reload();
            } catch (IOException e) {
                UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration initialization failed! File path: " + configEntity.getConfigFilePath());
            }
        }
    }

    /**
     * Rewrites the framework comment lines of every configuration of {@code plugin} in the module's current
     * language (#594), through {@link AbstractConfigEntity#refreshFrameworkComments()}: a comment-only write
     * through the config write gate, owning only the comment lines the framework identifies as its own, never a
     * value, a key or another comment line. The module's reload calls this right after it rebuilds the module's
     * language, which happens after {@link #reloadConfigs} read the files, and before the module's own reload
     * hook. One entity's failure is one warning naming its file and the failure's class, and the others still run.
     *
     * @param plugin UltiTools module
     * @since 6.3.0
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // one entity's failure must not stop the others or the reload
    public void refreshFrameworkComments(UltiToolsPlugin plugin) {
        if (!permitsConfigThread(plugin, "refreshFrameworkComments")) { return; }
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return;
        }
        for (AbstractConfigEntity configEntity : configMap.values()) {
            try {
                configEntity.refreshFrameworkComments();
            } catch (RuntimeException failure) {
                // Values are deliberately omitted: the failure's message may quote file content.
                UltiTools.getInstance().getLogger().log(Level.WARNING, "Cannot refresh comments in "
                        + configEntity.getConfigFilePath() + ": " + failure.getClass().getSimpleName()
                        + "; the file keeps its comments");
            }
        }
    }

    /**
     * Writes nothing; reports, as {@link #reportUnsavedAtStop()} does, every registered configuration that holds module
     * changes never saved.
     * <p>
     * Until 6.3.0 this saved every changed configuration at server stop. As of 6.3.0 nothing writes configuration at
     * server stop, module unload or module replacement (maintainer decision of 2026-10-04, "what code may write, by file
     * type": no shutdown save of whole entities), because a lifecycle event is not an operator's request to write and a
     * whole-entity save at stop could write over what the operator changed on disk. A module persists a change when it
     * makes it: {@link AbstractConfigEntity#save()} for a change the operator asked for through the module (it writes
     * only what the module changed, where the file still holds what was read), or
     * {@link AbstractConfigEntity#saveOperatorChange(String...)} to write exactly the settings an operator's command names.
     *
     * @deprecated as of 6.3.0 this writes nothing; persist changes with {@link AbstractConfigEntity#save()} or
     *             {@link AbstractConfigEntity#saveOperatorChange(String...)} when they are made
     */
    @Deprecated(since = "6.3.0")
    public void saveAll() {
        reportUnsavedAtStop();
    }

    /**
     * Reports, at server stop, every registered configuration that holds module changes never saved: one WARNING per
     * configuration names its file and the changed entry keys, never a value, and says they are not written. Nothing is
     * written - no configuration file is touched at server stop (maintainer decision of 2026-10-04). A configuration whose
     * file could not be read or parsed the last time it was loaded is named once instead, as before (#510).
     * <p>
     * Framework-internal: {@code UltiTools#onDisable()} calls it for configurations still registered after every module
     * was unloaded; each module's own configurations are reported once, just before their release, during its unload at
     * stop. Registry access runs on the server thread; one entity's failure is logged and the others are still reported.
     *
     * @since 6.3.0
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public void reportUnsavedAtStop() {
        if (!permitsConfigThread(null, "reportUnsavedAtStop")) { return; }
        for (Map<String, AbstractConfigEntity> configMap : pluginConfigMap.values()) {
            reportUnsaved(configMap);
        }
    }

    // At shutdown and at a normal unload or uninstall (17-65 review round 1 R65-I5): reports this exact owner's
    // never-saved changes, after its unload hook and before its release. Writes nothing (maintainer decision
    // 2026-10-04); the name is historical.
    void saveForShutdown(UltiToolsPlugin plugin) {
        if (!permitsConfigThread(plugin, "saveForShutdown")) { return; }
        Map<String, AbstractConfigEntity> entities = pluginConfigMap.get(plugin);
        if (entities != null) { reportUnsaved(entities); }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // one entity's failure must not stop the others' reports
    private void reportUnsaved(Map<String, AbstractConfigEntity> configMap) {
        for (AbstractConfigEntity config : configMap.values()) {
            try {
                synchronized (config) {
                    if (config.isLastLoadUnparseable()) {
                        warnUnparseableFileLeftAlone(config);
                        continue;
                    }
                    if (!config.isModifiedSinceSnapshot()) {
                        continue;
                    }
                    List<String> keys = new ArrayList<>();
                    for (String key : config.unsavedEntryPaths()) { keys.add("'" + key + "'"); }
                    UltiToolsPlugin owner = config.getUltiToolsPlugin();
                    File file = new File(owner.getResourceFolderPath(), config.getConfigFilePath());
                    // Values are deliberately omitted: any key may hold a credential.
                    UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration file " + file.getAbsolutePath()
                            + " holds module " + owner.getPluginName() + " changes that were never saved; they are not"
                            + " written and are dropped as the module is unloaded: " + String.join(", ", keys));
                }
            } catch (RuntimeException e) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                        "Cannot report unsaved configuration changes: " + config.getConfigFilePath(), e);
            }
        }
    }

    /**
     * Logs, at server stop, that a configuration file was left alone because the framework could not
     * parse it the last time it read it (#510), and that any in-memory change to that configuration
     * was therefore not written.
     *
     * @param config the entity that was skipped
     */
    private void warnUnparseableFileLeftAlone(AbstractConfigEntity config) {
        UltiToolsPlugin owner = config.getUltiToolsPlugin();
        File file = new File(owner.getResourceFolderPath(), config.getConfigFilePath());
        UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration file "
                + file.getAbsolutePath() + " could not be parsed the last time it was read, so it was left"
                + " untouched and module " + owner.getPluginName() + "'s in-memory changes to this"
                + " configuration were not saved. Fix the file, then reload or restart.");
    }

    /**
     * Builds a JSON string from all config entities using the provided extractor function.
     *
     * @param extractor function to extract JsonObject from config entity
     * @return JSON string
     */
    private String buildJsonFromConfigs(Function<AbstractConfigEntity, JsonObject> extractor) {
        Gson gson = new Gson();
        Map<String, Map<String, JsonObject>> res = new HashMap<>();
        for (Map.Entry<UltiToolsPlugin, Map<String, AbstractConfigEntity>> entry : pluginConfigMap.entrySet()) {
            Map<String, JsonObject> stringStringMap = res.computeIfAbsent(entry.getKey().getPluginName(), k -> new HashMap<>());
            for (Map.Entry<String, AbstractConfigEntity> entityEntry : entry.getValue().entrySet()) {
                stringStringMap.put(entityEntry.getKey(), extractor.apply(entityEntry.getValue()));
            }
            res.put(entry.getKey().getPluginName(), stringStringMap);
        }
        return gson.toJson(res);
    }

    /**
     * Get all comments.
     *
     * @return all comments
     */
    public final String getComments() {
        requireConfigThread(null, "getComments");
        return buildJsonFromConfigs(AbstractConfigEntity::getComments);
    }

    /**
     * Cast config to JSON format.
     *
     * @return config in JSON format
     */
    public final String toJson() {
        requireConfigThread(null, "toJson");
        return buildJsonFromConfigs(AbstractConfigEntity::toJsonObject);
    }

    /**
     * Load config from JSON string.
     *
     * <p>
     * Since 6.3.0, a value violating its {@code @Range}/{@code @NotEmpty}/{@code @Size}/
     * {@code @Pattern} constraint refuses with {@link com.ultikits.ultitools.exceptions.ConfigurationException}
     * instead of being written - the operator's file is not modified for that config entity
     * (SILENT-14).
     * <p>
     * Every touched entity is validated before persistence. All changed files are staged before
     * any replacement; effective and raw acknowledgments advance only after every commit succeeds.
     * An I/O or preparation refusal restores attempted files and every entity checkpoint, discards
     * temporaries, and rethrows the original failure. Recovery failures are attached as suppressed
     * exceptions: persistently unavailable storage can prevent restoration and is not reported as success.
     * This is in-process recovery, not crash-safe multi-file storage; a JVM crash between moves remains
     * outside this guarantee (UltiKits/UltiTools-Reborn#545).
     *
     * @param json JSON string
     * @throws IOException              if staging or replacement fails; recovery failures are suppressed
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a value violates its
     *                                 validation constraint - nothing in this call's batch is
     *                                 persisted when this is thrown, since validation runs to
     *                                 completion across the whole batch before persistence starts
     */
    public final void loadFromJson(String json) throws IOException {
        requireConfigThread(null, "loadFromJson");
        Gson gson = new Gson();
        Type mapType = new TypeToken<Map<String, Map<String, JsonObject>>>() {}.getType();
        Map<String, Map<String, JsonObject>> parseObject = gson.fromJson(json, mapType);

        // Phase one: collect every (entity, payload) pair this batch touches, in the same
        // traversal order the pre-CR-02 implementation applied them in, and validate each
        // WITHOUT persisting - a refusal here must not have written anything for ANY entity yet.
        List<AbstractConfigEntity> touchedEntities = new ArrayList<>();
        List<JsonObject> touchedPayloads = new ArrayList<>();
        for (String pluginName : parseObject.keySet()) {
            for (UltiToolsPlugin ultiToolsPlugin : pluginConfigMap.keySet()) {
                if (!ultiToolsPlugin.getPluginName().equals(pluginName)) {
                    continue;
                }
                Map<String, AbstractConfigEntity> configEntityMap = pluginConfigMap.get(ultiToolsPlugin);
                Map<String, JsonObject> pluginParseData = parseObject.get(pluginName);
                for (String configPath : configEntityMap.keySet()) {
                    if (pluginParseData.containsKey(configPath)) {
                        AbstractConfigEntity config = configEntityMap.get(configPath);
                        JsonObject payload = pluginParseData.get(configPath);
                        touchedEntities.add(config);
                        touchedPayloads.add(payload);
                    }
                }
            }
        }

        List<AbstractConfigEntity> monitors = new ArrayList<>(new LinkedHashSet<>(touchedEntities));
        monitors.sort(Comparator.comparing((AbstractConfigEntity entity) -> entity.getUltiToolsPlugin().getPluginName())
                .thenComparing(AbstractConfigEntity::getConfigFilePath));
        withPanelMonitors(monitors, 0, touchedEntities, touchedPayloads);
    }

    private void withPanelMonitors(List<AbstractConfigEntity> monitors, int index,
            List<AbstractConfigEntity> entities, List<JsonObject> payloads) throws IOException {
        if (index < monitors.size()) {
            synchronized (monitors.get(index)) { withPanelMonitors(monitors, index + 1, entities, payloads); }
            return;
        }
        persistPanelBatch(entities, payloads);
    }

    private void persistPanelBatch(List<AbstractConfigEntity> touchedEntities, List<JsonObject> touchedPayloads) throws IOException {
        for (int i = 0; i < touchedEntities.size(); i++) {
            touchedEntities.get(i).validateProposedProperties(touchedPayloads.get(i));
        }
        List<AbstractConfigEntity.PanelWrite> writes = new ArrayList<>();
        try {
            for (int i = 0; i < touchedEntities.size(); i++) {
                AbstractConfigEntity.PanelWrite write = touchedEntities.get(i).preparePanelWrite(touchedPayloads.get(i));
                if (write != null) { writes.add(write); }
            }
            for (AbstractConfigEntity.PanelWrite write : writes) { write.commit(); }
        } catch (IOException | RuntimeException failure) {
            for (int i = writes.size() - 1; i >= 0; i--) {
                try { writes.get(i).rollback(); }
                catch (IOException | RuntimeException recovery) { failure.addSuppressed(recovery); }
            }
            throw failure;
        } finally {
            for (AbstractConfigEntity.PanelWrite write : writes) { write.discard(); }
        }
        for (AbstractConfigEntity.PanelWrite write : writes) { write.acknowledge(); }
    }

    /**
     * Load a single config file from a JSON string.
     *
     * <p>{@link #loadFromJson(String)} accepts the full nested structure
     * {@code {pluginName: {configPath: {key: value}}}} -- the same shape {@link #toJson()}
     * produces. This method accepts just the innermost layer -- one config file's own
     * {@code {key: value}} map -- and writes it to whichever file {@code configFilePath}
     * names. The panel uses this narrower shape when pushing a single config by filename;
     * see issue #236.
     *
     * <p>A config path is unique within a single plugin (it is the inner key of
     * {@code pluginConfigMap}), but may collide across plugins. Both "not found" and
     * "matched more than one" throw rather than silently doing nothing -- "the caller
     * thinks it wrote something and nothing actually happened" was exactly the original
     * defect on this path.
     *
     * <p>
     * Since 6.3.0, a value violating its {@code @Range}/{@code @NotEmpty}/{@code @Size}/
     * {@code @Pattern} constraint refuses with {@link com.ultikits.ultitools.exceptions.ConfigurationException}
     * instead of being written - the operator's file is not modified (SILENT-14).
     *
     * @param configFilePath config file path as registered, e.g. {@code config/lang.yml}
     * @param json           JSON object of that file's entries
     * @throws IOException if the path is blank, unknown, ambiguous, or the JSON is not an object,
     *                     or if saving fails
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a value violates its
     *                                 validation constraint
     * @since 6.2.5
     */
    public final void loadFromJson(String configFilePath, String json) throws IOException {
        requireConfigThread(null, "loadFromJson");
        if (configFilePath == null || configFilePath.trim().isEmpty()) {
            throw new IOException("Config file path is required");
        }
        JsonObject properties;
        try {
            properties = new Gson().fromJson(json, JsonObject.class);
        } catch (RuntimeException e) {
            throw new IOException("Config content is not valid JSON: " + configFilePath, e);
        }
        if (properties == null) {
            throw new IOException("Config content is not a JSON object: " + configFilePath);
        }

        List<AbstractConfigEntity> matches = new ArrayList<>();
        for (Map<String, AbstractConfigEntity> configMap : pluginConfigMap.values()) {
            AbstractConfigEntity entity = configMap.get(configFilePath);
            if (entity != null) {
                matches.add(entity);
            }
        }
        if (matches.isEmpty()) {
            throw new IOException("No registered config matches path: " + configFilePath);
        }
        if (matches.size() > 1) {
            throw new IOException("Config path is ambiguous across plugins: " + configFilePath);
        }
        matches.get(0).updateProperties(properties);
    }
}
