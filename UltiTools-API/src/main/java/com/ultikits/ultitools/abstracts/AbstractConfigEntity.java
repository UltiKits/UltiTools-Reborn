package com.ultikits.ultitools.abstracts;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.util.ReflectUtil;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Set;

/**
 * Abstract class representing a configuration entity.
 * <p>
 * 配置实体的抽象类。
 */
@Getter
public abstract class AbstractConfigEntity {
    private final String configFilePath;
    private UltiToolsPlugin ultiToolsPlugin;
    private YamlConfiguration config;

    /**
     * Constructor for AbstractConfigEntity.
     * <p>
     * AbstractConfigEntity的构造函数。
     *
     * @param configFilePath the path to the configuration file, for example: config/config.yml <br> 配置文件在resource文件夹的路径，例如：config/config.yml
     */
    public AbstractConfigEntity(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /**
     * Saves the configuration to the file.
     * <p>
     * 将配置保存到文件。
     *
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
     */
    @SuppressWarnings("unchecked")
    public void save() throws IOException {
        for (Field field : ReflectUtil.getFields(this.getClass())) {
            if (!field.isAnnotationPresent(ConfigEntry.class)) {
                continue;
            }
            field.setAccessible(true);
            ConfigEntry annotation = AnnotationUtil.getAnnotation(field, ConfigEntry.class);
            String path = annotation.path();
            if (path.isEmpty()) {
                path = field.getName();
            }
            Object fieldValue = ReflectUtil.getFieldValue(this, field);
            if (fieldValue == null) {
                continue;
            }
            Object serialized = ReflectUtil.newInstance(annotation.parser()).serialize(fieldValue);
            config.set(path, serialized);
        }
        config.save(new File(ultiToolsPlugin.getConfigFolder() + File.separator + configFilePath));
    }

    /**
     * Initializes the configuration entity.
     * <p>
     * 初始化配置实体。
     *
     * @param ultiToolsPlugin the plugin instance <br> 插件实例
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
     */
    public final void init(UltiToolsPlugin ultiToolsPlugin) throws IOException {
        this.ultiToolsPlugin = ultiToolsPlugin;
        config = YamlConfiguration.loadConfiguration(ultiToolsPlugin.getConfigFile(configFilePath));
        boolean upToDate = true;
        for (Field field : ReflectUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = AnnotationUtil.getAnnotation(field, ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                Object configValue = config.get(path);
                if (configValue != null) {
                    Object parse = ReflectUtil.newInstance(annotation.parser()).parse(configValue);
                    ReflectUtil.setFieldValue(this, field, parse);
                } else {
                    upToDate = false;
                    config.set(path, ReflectUtil.getFieldValue(this, field));
                }
            }
        }
        if (!upToDate) {
            config.save(ultiToolsPlugin.getConfigFile(configFilePath));
        }
    }

    /**
     * Updates the properties of the configuration entity.
     * <p>
     * 更新配置实体的属性。
     *
     * @param jsonObject the JSON object containing the new properties <br> 包含新属性的JSON对象
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
     */
    public void updateProperties(JSONObject jsonObject) throws IOException {
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                Object configValue = jsonObject.getObject(path, field.getType());
                if (configValue != null) {
                    ReflectUtil.setFieldValue(this, field, configValue);
                    config.set(path, configValue);
                }
            }
        }
        config.save(ultiToolsPlugin.getConfigFile(configFilePath));
    }

    /**
     * Converts the configuration entity to a JSON object.
     * <p>
     * 将配置实体转换为JSON对象。
     *
     * @return the JSON object representation of the configuration entity <br> 配置实体的JSON对象表示
     */
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

    /**
     * Gets the comments of the configuration entity.
     * <p>
     * 获取配置实体的注释。
     *
     * @return a JSON object containing the comments <br> 包含注释的JSON对象
     */
    public JSONObject getComments() {
        JSONObject jsonObject = new JSONObject();
        Field[] declaredFields = this.getClass().getDeclaredFields();
        for (Field field : declaredFields) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                jsonObject.put(annotation.path(), annotation.comment());
            }
        }
        return jsonObject;
    }
}
