package com.ultikits.ultitools.manager;

import cn.hutool.core.exceptions.ExceptionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.utils.PackageScanUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * @author wisdomme
 * @version 1.0.0
 */
public class ConfigManager {

    private final Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = new HashMap<>();

    public void register(UltiToolsPlugin ultiToolsPlugin, AbstractConfigEntity configEntity) {
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
            }
        }
    }

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

    public void saveAll() {
        for (Map<String, AbstractConfigEntity> configMap : pluginConfigMap.values()) {
            for (AbstractConfigEntity config : configMap.values()) {
                try {
                    config.save();
                } catch (IOException e) {
                    UltiTools.getInstance().getLogger().log(Level.WARNING, "Configuration save failed！File path：" + config.getConfigFilePath());
                }
            }
        }
    }

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
