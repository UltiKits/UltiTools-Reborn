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
     * <br>
     * 注册配置实体
     *
     * @param ultiToolsPlugin UltiTools module <br> UltiTools模块
     * @param configEntity    Config entity <br> 配置实体
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
     * <br>
     * 注册指定包下的所有配置实体
     *
     * @param plugin      UltiTools module <br> UltiTools模块
     * @param packageName Package name <br> 包名
     * @param classLoader Class loader <br> 类加载器
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
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Get config entity.
     * <br>
     * 获取配置实体
     *
     * @param plugin UltiTools module <br> UltiTools模块
     * @param type   Config entity type <br> 配置实体类型
     * @param <T>    Config entity type <br> 配置实体类型
     * @return Config entity <br> 配置实体
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
     * <br>
     * 通过路径获取配置实体
     *
     * @param plugin UltiTools module <br> UltiTools模块
     * @param path   Config entity path <br> 配置实体路径
     * @param type   Config entity type <br> 配置实体类型
     * @param <T>    Config entity type <br> 配置实体类型
     * @return Config entity <br> 配置实体
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
     * <br>
     * 通过类型获取所有配置实体
     *
     * @param plugin UltiTools module <br> UltiTools模块
     * @param type   Config entity type <br> 配置实体类型
     * @param <T>    Config entity type <br> 配置实体类型
     * @return Config entity list <br> 配置实体列表
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
     * <br>
     * 获取插件的所有配置实体
     *
     * @param plugin UltiTools module <br> UltiTools模块
     * @return All config entities <br> 所有配置实体
     */
    public Map<String, AbstractConfigEntity> getAllConfigEntities(UltiToolsPlugin plugin) {
        return pluginConfigMap.get(plugin);
    }

    /**
     * Reload all configs.
     * <br>
     * 重新加载所有配置
     *
     * @param plugin UltiTools module <br> UltiTools模块
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
     * <br>
     * 保存所有配置
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
     * <br>
     * 使用提供的提取函数从所有配置实体构建JSON字符串
     *
     * @param extractor function to extract JsonObject from config entity <br> 从配置实体提取JsonObject的函数
     * @return JSON string <br> JSON字符串
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
     * <br>
     * 获取所有注释
     *
     * @return all comments <br> 所有注释
     */
    public final String getComments() {
        return buildJsonFromConfigs(AbstractConfigEntity::getComments);
    }

    /**
     * Cast config to JSON format.
     * <br>
     * 将配置转换为JSON格式
     *
     * @return config in JSON format <br> JSON格式的配置
     */
    public final String toJson() {
        return buildJsonFromConfigs(AbstractConfigEntity::toJsonObject);
    }

    /**
     * Load config from JSON string.
     * <br>
     * 从JSON字符串加载配置
     *
     * <p>
     * Since 6.3.0, a value violating its {@code @Range}/{@code @NotEmpty}/{@code @Size}/
     * {@code @Pattern} constraint refuses with {@link com.ultikits.ultitools.exceptions.ConfigurationException}
     * instead of being written - the operator's file is not modified for that config entity
     * (SILENT-14).
     * <p>
     * 自 6.3.0 起，违反 {@code @Range}/{@code @NotEmpty}/{@code @Size}/{@code @Pattern} 约束的值
     * 会以 {@link com.ultikits.ultitools.exceptions.ConfigurationException} 拒绝而不是被写入——
     * 该配置实体对应的操作员文件不会被修改（SILENT-14）。
     *
     * @param json JSON string <br> JSON字符串
     * @throws IOException              if an I/O error occurs <br> 如果发生I/O错误
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a value violates its
     *                                 validation constraint <br> 若某个值违反了校验约束
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
     * <br>
     * 从JSON字符串加载单个配置文件
     *
     * <p>{@link #loadFromJson(String)} 接受的是 {@code {插件名: {配置路径: {配置项: 值}}}}
     * 这样的全量嵌套结构，也就是 {@link #toJson()} 的产物。本方法接受的是最里面那一层
     * ——某一个配置文件自己的 {@code {配置项: 值}}——由 {@code configFilePath} 指定写到哪。
     * 面板按文件名下发单个配置时用的是后一种形状，见 issue #236。
     *
     * <p>配置路径在单个插件内唯一（{@code pluginConfigMap} 的内层 key 就是它），
     * 跨插件则可能重名。找不到和命中多个都抛异常而不是静默跳过：
     * 「调用方以为写了、实际什么也没发生」正是这条链路上原来的毛病。
     *
     * <p>
     * Since 6.3.0, a value violating its {@code @Range}/{@code @NotEmpty}/{@code @Size}/
     * {@code @Pattern} constraint refuses with {@link com.ultikits.ultitools.exceptions.ConfigurationException}
     * instead of being written - the operator's file is not modified (SILENT-14).
     * <p>
     * 自 6.3.0 起，违反 {@code @Range}/{@code @NotEmpty}/{@code @Size}/{@code @Pattern} 约束的值
     * 会以 {@link com.ultikits.ultitools.exceptions.ConfigurationException} 拒绝而不是被写入——
     * 操作员的文件不会被修改（SILENT-14）。
     *
     * @param configFilePath config file path as registered, e.g. {@code config/lang.yml}
     *                       <br> 注册时使用的配置文件路径
     * @param json           JSON object of that file's entries <br> 该文件配置项的JSON对象
     * @throws IOException if the path is blank, unknown, ambiguous, or the JSON is not an object,
     *                     or if saving fails <br> 路径为空、找不到、跨插件重名、JSON不是对象或保存失败
     * @throws com.ultikits.ultitools.exceptions.ConfigurationException if a value violates its
     *                                 validation constraint <br> 若某个值违反了校验约束
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
