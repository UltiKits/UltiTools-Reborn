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
            for (File listFile : file.listFiles()) {
                if (!listFile.isFile() || !listFile.getName().endsWith(".yml")) {
                    continue;
                }
                AbstractConfigEntity abstractConfigEntity = ReflectionUtil.newInstance(configEntity.getClass(), listFile.getPath().replace(ultiToolsPlugin.getResourceFolderPath() + File.separator, "").replaceAll("\\\\", "/"));
                addConfigEntity(ultiToolsPlugin, abstractConfigEntity);
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
                throw ConfigurationException.loadFailed(path, e);
            }
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
     *
     * @param json JSON string
     * @throws IOException              if an I/O error occurs
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a value violates its
     *                                 validation constraint
     */
    public final void loadFromJson(String json) throws IOException {
        Gson gson = new Gson();
        Type mapType = new TypeToken<Map<String, Map<String, JsonObject>>>() {}.getType();
        Map<String, Map<String, JsonObject>> parseObject = gson.fromJson(json, mapType);
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
                        config.updateProperties(pluginParseData.get(configPath));
                        configEntityMap.put(configPath, config);
                    }
                }
                pluginConfigMap.put(ultiToolsPlugin, configEntityMap);
            }
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
