package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.ConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author wisdomme
 * @version 1.0.0
 */
public class ConfigManager {

    private final Map<UltiToolsPlugin, Map<Class<?>, ConfigEntity>> pluginConfigMap = new HashMap<>();

    public void register(UltiToolsPlugin ultiToolsPlugin, ConfigEntity configEntity) {
        configEntity.init(ultiToolsPlugin);
        Map<Class<?>, ConfigEntity> configMap = pluginConfigMap.get(ultiToolsPlugin);
        if (configMap == null) {
            configMap = new HashMap<>();
            configMap.put(configEntity.getClass(), configEntity);
            pluginConfigMap.put(configEntity.getUltiToolsPlugin(), configMap);
            return;
        }
        configMap.put(configEntity.getClass(), configEntity);
        pluginConfigMap.put(configEntity.getUltiToolsPlugin(), configMap);
    }

    public <T extends ConfigEntity> T getConfigEntity(UltiToolsPlugin plugin, Class<T> type) {
        Map<Class<?>, ConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return null;
        }
        ConfigEntity configEntity = configMap.get(type);
        if (configEntity == null) {
            return null;
        }
        return type.cast(configEntity);
    }

    public void saveAll() {
        for (Map<Class<?>, ConfigEntity> configMap : pluginConfigMap.values()) {
            for (ConfigEntity config : configMap.values()) {
                try {
                    config.save();
                } catch (IOException e) {
                    System.out.println("配置保存失败！文件位置：" + config.getConfigFilePath());
                }
            }
        }
    }
}
