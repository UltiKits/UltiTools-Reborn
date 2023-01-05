package com.ultikits.ultitools.abstracts;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.SneakyThrows;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

@Getter
public abstract class ConfigEntity {
    private UltiToolsPlugin ultiToolsPlugin;
    private YamlConfiguration config;
    private String configFilePath;

    public ConfigEntity(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    public void save() throws IOException {
        config.save(new File(configFilePath));
    }

    @SneakyThrows
    public final void init(UltiToolsPlugin ultiToolsPlugin) {
        this.ultiToolsPlugin = ultiToolsPlugin;
        config = YamlConfiguration.loadConfiguration(ultiToolsPlugin.getConfigFile(configFilePath));
        boolean upToDate = true;
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = AnnotationUtil.getAnnotation(field, ConfigEntry.class);
                String path = annotation.path();
                if (path.equals("")) {
                    path = field.getName();
                }
                Object configValue = config.get(path);
                if (configValue != null) {
                    field.set(this, configValue);
                } else {
                    upToDate = false;
                    config.set(path, field.get(this));
                }
            }
        }
        if (!upToDate) {
            config.save(ultiToolsPlugin.getConfigFile(configFilePath));
        }
    }

    @SneakyThrows
    public void updateProperties(JSONObject jsonObject){
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                Object configValue = jsonObject.get(path);
                if (configValue != null) {
                    field.set(this, configValue);
                    config.set(path, configValue);
                }
            }
        }
        config.save(ultiToolsPlugin.getConfigFile(configFilePath));
    }

    public JSONObject toJsonObject() {
        JSONObject jsonObject = new JSONObject();
        Set<String> keys = config.getKeys(true);
        for (String key : keys) {
            if (!config.isConfigurationSection(key)) {
                jsonObject.put(key, config.get(key));
            }
        }
        return jsonObject;
    }

    public JSONObject getComments() {
        JSONObject jsonObject = new JSONObject();
        Field[] declaredFields = this.getClass().getDeclaredFields();
        for (Field field : declaredFields){
            if (field.isAnnotationPresent(ConfigEntry.class)){
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                jsonObject.put(annotation.path(), annotation.comment());
            }
        }
        return jsonObject;
    }
}
