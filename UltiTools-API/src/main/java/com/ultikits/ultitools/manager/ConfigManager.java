package com.ultikits.ultitools.manager;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
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

    private final Map<UltiToolsPlugin, Map<String, ConfigEntity>> pluginConfigMap = new HashMap<>();

    public void register(UltiToolsPlugin ultiToolsPlugin, ConfigEntity configEntity) {
        configEntity.init(ultiToolsPlugin);
        Map<String, ConfigEntity> configMap = pluginConfigMap.get(ultiToolsPlugin);
        if (configMap == null) {
            configMap = new HashMap<>();
            configMap.put(configEntity.getConfigFilePath(), configEntity);
            pluginConfigMap.put(configEntity.getUltiToolsPlugin(), configMap);
            return;
        }
        configMap.put(configEntity.getConfigFilePath(), configEntity);
        pluginConfigMap.put(configEntity.getUltiToolsPlugin(), configMap);
    }

    public <T extends ConfigEntity> T getConfigEntity(UltiToolsPlugin plugin, String path, Class<T> type) {
        Map<String, ConfigEntity> configMap = pluginConfigMap.get(plugin);
        if (configMap == null) {
            return null;
        }
        ConfigEntity configEntity = configMap.get(path);
        if (configEntity == null) {
            return null;
        }
        return type.cast(configEntity);
    }

    public void saveAll() {
        for (Map<String, ConfigEntity> configMap : pluginConfigMap.values()) {
            for (ConfigEntity config : configMap.values()) {
                try {
                    config.save();
                } catch (IOException e) {
                    System.out.println("配置保存失败！文件位置：" + config.getConfigFilePath());
                }
            }
        }
    }

    public final String getComments(){
        Map<String, Map<String, JSONObject>> res = new HashMap<>();
        for (Map.Entry<UltiToolsPlugin, Map<String, ConfigEntity>> entry : pluginConfigMap.entrySet()) {
            Map<String, JSONObject> stringStringMap = res.computeIfAbsent(entry.getKey().pluginName(), k -> new HashMap<>());
            for (Map.Entry<String, ConfigEntity> entityEntry : entry.getValue().entrySet()){
                stringStringMap.put(entityEntry.getKey(), entityEntry.getValue().getComments());
            }
            res.put(entry.getKey().pluginName(), stringStringMap);
        }
        return JSON.toJSONString(res);
    }

    public final String toJson() {
        Map<String, Map<String, JSONObject>> res = new HashMap<>();
        for (Map.Entry<UltiToolsPlugin, Map<String, ConfigEntity>> entry : pluginConfigMap.entrySet()) {
            Map<String, JSONObject> stringStringMap = res.computeIfAbsent(entry.getKey().pluginName(), k -> new HashMap<>());
            for (Map.Entry<String, ConfigEntity> entityEntry : entry.getValue().entrySet()){
                stringStringMap.put(entityEntry.getKey(), entityEntry.getValue().toJsonObject());
            }
            res.put(entry.getKey().pluginName(), stringStringMap);
        }
        return JSON.toJSONString(res);
    }

    public final void loadFromJson(String json){
        Map<String, Map<String, JSONObject>> parseObject = JSONObject.parseObject(json, new TypeReference<Map<String, Map<String, JSONObject>>>() {
        });
        for (String pluginName : parseObject.keySet()){
            for (UltiToolsPlugin ultiToolsPlugin : pluginConfigMap.keySet()){
                if (!ultiToolsPlugin.pluginName().equals(pluginName)) {
                    continue;
                }
                Map<String, ConfigEntity> configEntityMap = pluginConfigMap.get(ultiToolsPlugin);
                for (String configPath : configEntityMap.keySet()){
                    for (String pathName : parseObject.get(pluginName).keySet()){
                        if (!configPath.equals(pathName)) {
                            continue;
                        }
                        ConfigEntity config = configEntityMap.get(configPath);
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
