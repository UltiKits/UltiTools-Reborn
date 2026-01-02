package com.ultikits.ultitools.abstracts;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.ConfigChangeListener;
import com.ultikits.ultitools.utils.ReflectionUtil;

import lombok.Getter;

/**
 * Abstract class representing a configuration entity.
 * <p>
 * 配置实体的抽象类。
 */
@Getter
public abstract class AbstractConfigEntity {
    private static final Logger LOGGER = Logger.getLogger(AbstractConfigEntity.class.getName());
    
    private final String configFilePath;
    private final List<ConfigChangeListener> changeListeners = new CopyOnWriteArrayList<>();
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
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (!field.isAnnotationPresent(ConfigEntry.class)) {
                continue;
            }
            field.setAccessible(true);
            ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
            String path = annotation.path();
            if (path.isEmpty()) {
                path = field.getName();
            }
            Object fieldValue = ReflectionUtil.getFieldValue(this, field);
            if (fieldValue == null) {
                continue;
            }
            Object serialized = ReflectionUtil.newInstance(annotation.parser()).serialize(fieldValue);
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
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                Object configValue = config.get(path);
                if (configValue != null) {
                    Object parse = ReflectionUtil.newInstance(annotation.parser()).parse(configValue);
                    ReflectionUtil.setFieldValue(this, field, parse);
                } else {
                    upToDate = false;
                    config.set(path, ReflectionUtil.getFieldValue(this, field));
                }
            }
        }
        if (!upToDate) {
            config.save(ultiToolsPlugin.getConfigFile(configFilePath));
        }
        
        // Notify listeners after initialization
        notifyChangeListeners();
    }

    /**
     * Updates the properties of the configuration entity.
     * <p>
     * 更新配置实体的属性。
     *
     * @param jsonObject the JSON object containing the new properties <br> 包含新属性的JSON对象
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
     */
    public void updateProperties(JsonObject jsonObject) throws IOException {
        Gson gson = new Gson();
        for (Field field : this.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                if (jsonObject.has(path)) {
                    Object configValue = gson.fromJson(jsonObject.get(path), field.getType());
                    if (configValue != null) {
                        ReflectionUtil.setFieldValue(this, field, configValue);
                        config.set(path, configValue);
                    }
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
    public JsonObject toJsonObject() {
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();
        Set<String> keys = config.getKeys(true);
        for (String key : keys) {
            if (!config.isConfigurationSection(key)) {
                Object value = config.get(key);
                jsonObject.add(key, gson.toJsonTree(value));
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
    public JsonObject getComments() {
        JsonObject jsonObject = new JsonObject();
        Field[] declaredFields = this.getClass().getDeclaredFields();
        for (Field field : declaredFields) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                jsonObject.addProperty(annotation.path(), annotation.comment());
            }
        }
        return jsonObject;
    }
    
    // ==================== Configuration Change Listener Support ====================
    
    /**
     * Adds a configuration change listener.
     * The listener will be notified when the configuration is reloaded.
     * <p>
     * 添加配置变更监听器。当配置重载时，监听器将被通知。
     *
     * @param listener the listener to add <br> 要添加的监听器
     */
    public void addChangeListener(ConfigChangeListener listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }
    
    /**
     * Removes a configuration change listener.
     * <p>
     * 移除配置变更监听器。
     *
     * @param listener the listener to remove <br> 要移除的监听器
     */
    public void removeChangeListener(ConfigChangeListener listener) {
        changeListeners.remove(listener);
    }
    
    /**
     * Removes all configuration change listeners.
     * <p>
     * 移除所有配置变更监听器。
     */
    public void clearChangeListeners() {
        changeListeners.clear();
    }
    
    /**
     * Gets the number of registered change listeners.
     * <p>
     * 获取已注册的变更监听器数量。
     *
     * @return the number of listeners <br> 监听器数量
     */
    public int getChangeListenerCount() {
        return changeListeners.size();
    }
    
    /**
     * Notifies all registered listeners about the configuration change.
     * Individual listener exceptions do not affect other listeners.
     * <p>
     * 通知所有已注册的监听器配置已变更。单个监听器的异常不影响其他监听器。
     */
    protected void notifyChangeListeners() {
        for (ConfigChangeListener listener : changeListeners) {
            try {
                listener.onConfigReload(this);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, 
                    "Config change listener failed for " + this.getClass().getSimpleName(), e);
            }
        }
    }
    
    /**
     * Reloads the configuration from file and notifies all listeners.
     * <p>
     * 从文件重新加载配置并通知所有监听器。
     *
     * @throws IOException if an I/O error occurs <br> 如果发生I/O错误
     */
    public void reload() throws IOException {
        if (ultiToolsPlugin == null) {
            throw new IllegalStateException("Config not initialized. Call init() first.");
        }
        
        // Reload from file
        config = YamlConfiguration.loadConfiguration(ultiToolsPlugin.getConfigFile(configFilePath));
        
        // Update field values
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                Object configValue = config.get(path);
                if (configValue != null) {
                    Object parse = ReflectionUtil.newInstance(annotation.parser()).parse(configValue);
                    ReflectionUtil.setFieldValue(this, field, parse);
                }
            }
        }
        
        // Notify listeners
        notifyChangeListeners();
    }
}
