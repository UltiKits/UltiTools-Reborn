package com.ultikits.ultitools.utils;

import java.io.IOException;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * Utility class for configuration editor operations.
 * Provides methods to get and update configuration data in JSON format.
 * This class is used internally by the configuration editor system.
 * <br>
 * 配置编辑器操作的实用工具类。
 * 提供以JSON格式获取和更新配置数据的方法。
 * 此类由配置编辑器系统内部使用。
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class ConfigEditorUtils {

    /**
     * Get the configuration map as a JSON string.
     * <br>
     * 获取配置映射的JSON字符串。
     *
     * @return JSON string representation of the configuration map <br> 配置映射的JSON字符串表示
     */
    protected static String getConfigMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.toJson();
    }

    /**
     * Get the comment map as a JSON string.
     * <br>
     * 获取注释映射的JSON字符串。
     *
     * @return JSON string representation of the comment map <br> 注释映射的JSON字符串表示
     */
    protected static String getCommentMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.getComments();
    }

    /**
     * Update the configuration map from a JSON string.
     * <br>
     * 从JSON字符串更新配置映射。
     *
     * @param configMapString JSON string containing the new configuration <br> 包含新配置的JSON字符串
     * @throws IOException if an I/O error occurs during update <br> 如果更新过程中发生I/O错误
     */
    protected static void updateConfigMap(String configMapString) throws IOException {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        configManager.loadFromJson(configMapString);
    }
}
