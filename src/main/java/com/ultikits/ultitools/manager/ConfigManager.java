package com.ultikits.ultitools.manager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.PackageScanUtils;
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

    /**
     * Register config entity.
     *
     * @param ultiToolsPlugin UltiTools module
     * @param configEntity    Config entity
     */
    public void register(UltiToolsPlugin ultiToolsPlugin, AbstractConfigEntity configEntity) throws IOException {
        ConfigEntity annotation = ReflectionUtil.getAnnotation(configEntity.getClass(), ConfigEntity.class);
        if (annotation == null) {
            return;
        }
        if (annotation.value().isEmpty()) {
            return;
        }
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
            Set<String> registeredBeforeThisCall = snapshotRegisteredPaths(ultiToolsPlugin);
            for (File listFile : file.listFiles()) {
                if (!listFile.isFile() || !listFile.getName().endsWith(".yml")) {
                    continue;
                }
                AbstractConfigEntity abstractConfigEntity = ReflectionUtil.newInstance(configEntity.getClass(), listFile.getPath().replace(ultiToolsPlugin.getResourceFolderPath() + File.separator, "").replaceAll("\\\\", "/"));
                try {
                    addConfigEntity(ultiToolsPlugin, abstractConfigEntity);
                } catch (RuntimeException e) {
                    rollBackRegisteredPaths(ultiToolsPlugin, registeredBeforeThisCall);
                    throw e;
                }
            }
        } else {
            addConfigEntity(ultiToolsPlugin, configEntity);
        }
    }

    private void addConfigEntity(UltiToolsPlugin ultiToolsPlugin, AbstractConfigEntity configEntity) {
        try {
            configEntity.init(ultiToolsPlugin);
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration initialization failed！File path：" + configEntity.getConfigFilePath());
        }
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.computeIfAbsent(ultiToolsPlugin, k -> new HashMap<>());
        configMap.put(configEntity.getConfigFilePath(), configEntity);
    }

    /**
     * Snapshots the config-file-path keys {@code ultiToolsPlugin} already holds in {@link
     * #pluginConfigMap}, before a caller is about to register a batch of entities in one call.
     * Used by {@link #register(UltiToolsPlugin, AbstractConfigEntity)}'s directory branch and
     * {@link #registerAll(UltiToolsPlugin, String, ClassLoader)} to roll back precisely what
     * this call added, on a refusal, without touching anything a prior call already committed
     * (#358 Part 1).
     *
     * @param ultiToolsPlugin the plugin whose current registrations to snapshot
     * @return an immutable copy of the currently-registered path set, or an empty set if none
     */
    private Set<String> snapshotRegisteredPaths(UltiToolsPlugin ultiToolsPlugin) {
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(ultiToolsPlugin);
        return configMap == null ? Collections.emptySet() : new HashSet<>(configMap.keySet());
    }

    /**
     * Restores {@code ultiToolsPlugin}'s entry in {@link #pluginConfigMap} to exactly {@code
     * pathsToKeep}, discarding every path this call added since the matching {@link
     * #snapshotRegisteredPaths(UltiToolsPlugin)} - the cleanup half of #358 Part 1's
     * all-or-nothing registration.
     *
     * @param ultiToolsPlugin the plugin to roll back
     * @param pathsToKeep     the path set to restore, as captured before this call started
     */
    private void rollBackRegisteredPaths(UltiToolsPlugin ultiToolsPlugin, Set<String> pathsToKeep) {
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(ultiToolsPlugin);
        if (configMap == null) {
            return;
        }
        configMap.keySet().retainAll(pathsToKeep);
        if (configMap.isEmpty()) {
            pluginConfigMap.remove(ultiToolsPlugin);
        }
    }

    /**
     * Register all config entities in the specified package.
     *
     * @param plugin      UltiTools module
     * @param packageName Package name
     * @param classLoader Class loader
     */
    public void registerAll(UltiToolsPlugin plugin, String packageName, ClassLoader classLoader) {
        Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                ConfigEntity.class,
                packageName,
                classLoader
        );
        // #358 Part 1: a package can carry more than one @ConfigEntity class, and
        // PackageScanUtils.scanAnnotatedClasses returns them in an unspecified (HashSet) order.
        // A validation refusal on any one of them must not leave a sibling that already
        // registered successfully stranded in pluginConfigMap for a module that is about to be
        // refused as a whole - snapshot what this plugin held before this scan and roll back to
        // exactly that on any refusal, regardless of which class failed or when.
        Set<String> registeredBeforeThisCall = snapshotRegisteredPaths(plugin);
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
                rollBackRegisteredPaths(plugin, registeredBeforeThisCall);
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
                rollBackRegisteredPaths(plugin, registeredBeforeThisCall);
                throw ConfigurationException.loadFailed(path, e);
            } catch (RuntimeException e) {
                // #358 Part 1's actual reproduction: a ConfigurationException from
                // validateFields()/ensureConstructable(), raised inside init() deep beneath
                // register() -> addConfigEntity(), is unchecked and was never caught here - it
                // propagated straight out of this loop, leaving every entity this call had
                // already registered stranded for a module that is refused as a whole.
                rollBackRegisteredPaths(plugin, registeredBeforeThisCall);
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
        Set<String> registeredBeforeThisPlugin = snapshotRegisteredPaths(plugin);
        try {
            for (String packageName : packageNames) {
                registerAll(plugin, packageName, classLoader);
            }
        } catch (RuntimeException e) {
            rollBackRegisteredPaths(plugin, registeredBeforeThisPlugin);
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
        return pluginConfigMap.get(plugin);
    }

    /**
     * Reload all configs.
     *
     * @param plugin UltiTools module
     */
    public void reloadConfigs(UltiToolsPlugin plugin) {
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return;
        }
        for (AbstractConfigEntity configEntity : configMap.values()) {
            try {
                configEntity.init(plugin);
            } catch (IOException e) {
                UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration initialization failed！File path：" + configEntity.getConfigFilePath());
            }
        }
    }

    /**
     * Save all configs.
     */
    public void saveAll() {
        for (Map<String, AbstractConfigEntity> configMap : pluginConfigMap.values()) {
            for (AbstractConfigEntity config : configMap.values()) {
                if (new File(config.getConfigFilePath()).isDirectory()) {
                    continue;
                }
                try {
                    config.save();
                } catch (IOException e) {
                    UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration save failed！File path：" + config.getConfigFilePath());
                }
            }
        }
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
        return buildJsonFromConfigs(AbstractConfigEntity::getComments);
    }

    /**
     * Cast config to JSON format.
     *
     * @return config in JSON format
     */
    public final String toJson() {
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
     * Since gate-1 CR-02 (#358 Part 2), the WHOLE batch this call touches - potentially several
     * config entities across several plugins in one JSON payload - is validated before any of
     * them is persisted: a first pass calls {@link AbstractConfigEntity#validateProposedProperties}
     * on every touched entity (applying nothing to disk, restoring every field it touched
     * regardless of outcome), and only once every entity in the batch has passed does a second
     * pass call {@link AbstractConfigEntity#updateProperties} on each to actually apply and
     * persist. A refusal on entity N therefore leaves entities 1..N-1 exactly as they were before
     * this call - none of them written to disk - rather than the pre-CR-02 behaviour where files
     * 1..N-1 were already applied and persisted by the time entity N's refusal was discovered.
     *
     * @param json JSON string
     * @throws IOException              if an I/O error occurs
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a value violates its
     *                                 validation constraint - nothing in this call's batch is
     *                                 persisted when this is thrown
     */
    public final void loadFromJson(String json) throws IOException {
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
                        config.validateProposedProperties(payload);
                        touchedEntities.add(config);
                        touchedPayloads.add(payload);
                    }
                }
            }
        }

        // Phase two: every entity in this batch passed validation - apply and persist each for
        // real. updateProperties() re-validates (cheap on the documented construction idiom,
        // per #363) before it writes, so this is never the first validation an entity sees.
        for (int i = 0; i < touchedEntities.size(); i++) {
            touchedEntities.get(i).updateProperties(touchedPayloads.get(i));
        }
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
