package com.ultikits.ultitools.manager;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.util.ReflectUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.utils.PackageScanUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
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
        ConfigEntity annotation = AnnotationUtil.getAnnotation(configEntity.getClass(), ConfigEntity.class);
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
                AbstractConfigEntity abstractConfigEntity = ReflectUtil.newInstance(configEntity.getClass(), listFile.getPath().replace(ultiToolsPlugin.getResourceFolderPath() + File.separator, "").replaceAll("\\\\", "/"));
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
        Map<String, AbstractConfigEntity> configMap = pluginConfigMap.get(ultiToolsPlugin);
        if (configMap == null) {
            configMap = new HashMap<>();
            configMap.put(configEntity.getConfigFilePath(), configEntity);
            pluginConfigMap.put(configEntity.getUltiToolsPlugin(), configMap);
            return;
        }
        configMap.put(configEntity.getConfigFilePath(), configEntity);
        pluginConfigMap.put(configEntity.getUltiToolsPlugin(), configMap);
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
                AbstractConfigEntity configEntity =
                        (AbstractConfigEntity) clazz.getDeclaredConstructor(String.class).newInstance(path);
                register(plugin, configEntity);
            } catch (InstantiationException |
                     InvocationTargetException |
                     IllegalAccessException |
                     NoSuchMethodException ignored) {
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
     * Get all comments.
     * <br>
     * 获取所有注释
     *
     * @return all comments <br> 所有注释
     */
    public final String getComments() {
        Map<String, Map<String, JSONObject>> res = new HashMap<>();
        for (Map.Entry<UltiToolsPlugin, Map<String, AbstractConfigEntity>> entry : pluginConfigMap.entrySet()) {
            Map<String, JSONObject> stringStringMap = res.computeIfAbsent(entry.getKey().getPluginName(), k -> new HashMap<>());
            for (Map.Entry<String, AbstractConfigEntity> entityEntry : entry.getValue().entrySet()) {
                stringStringMap.put(entityEntry.getKey(), entityEntry.getValue().getComments());
            }
            res.put(entry.getKey().getPluginName(), stringStringMap);
        }
        return JSON.toJSONString(res);
    }

    /**
     * Cast config to JSON format.
     * <br>
     * 将配置转换为JSON格式
     *
     * @return config in JSON format <br> JSON格式的配置
     */
    public final String toJson() {
        Map<String, Map<String, JSONObject>> res = new HashMap<>();
        for (Map.Entry<UltiToolsPlugin, Map<String, AbstractConfigEntity>> entry : pluginConfigMap.entrySet()) {
            Map<String, JSONObject> stringStringMap = res.computeIfAbsent(entry.getKey().getPluginName(), k -> new HashMap<>());
            for (Map.Entry<String, AbstractConfigEntity> entityEntry : entry.getValue().entrySet()) {
                stringStringMap.put(entityEntry.getKey(), entityEntry.getValue().toJsonObject());
            }
            res.put(entry.getKey().getPluginName(), stringStringMap);
        }
        return JSON.toJSONString(res);
    }

    /**
     * Load config from JSON string.
     * <br>
     * 从JSON字符串加载配置
     *
     * @param json JSON string <br> JSON字符串
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
     */
    public final void loadFromJson(String json) throws IOException {
        Map<String, Map<String, JSONObject>> parseObject = JSONObject.parseObject(json, new TypeReference<Map<String, Map<String, JSONObject>>>() {
        });
        for (String pluginName : parseObject.keySet()) {
            for (UltiToolsPlugin ultiToolsPlugin : pluginConfigMap.keySet()) {
                if (!ultiToolsPlugin.getPluginName().equals(pluginName)) {
                    continue;
                }
                Map<String, AbstractConfigEntity> configEntityMap = pluginConfigMap.get(ultiToolsPlugin);
                for (String configPath : configEntityMap.keySet()) {
                    for (String pathName : parseObject.get(pluginName).keySet()) {
                        if (!configPath.equals(pathName)) {
                            continue;
                        }
                        AbstractConfigEntity config = configEntityMap.get(configPath);
                        config.updateProperties(parseObject.get(pluginName).get(pathName));
                        configEntityMap.put(configPath, config);
                        break;
                    }
                }
                pluginConfigMap.put(ultiToolsPlugin, configEntityMap);
                break;
            }
        }
    }
}
