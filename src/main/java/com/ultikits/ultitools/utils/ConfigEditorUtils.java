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

    /**
     * Update a single configuration file from a JSON string.
     * <br>
     * 从JSON字符串更新单个配置文件。
     *
     * <p>面板按文件名下发配置时走这条：{@code configMapString} 是该文件自己的
     * {@code {配置项: 值}}，而不是 {@link #updateConfigMap(String)} 那种
     * {@code {插件名: {配置路径: {...}}}} 的全量结构。两种形状对应面板上两个不同的入口，
     * 混用会写不进去且不报错——见 issue #236。
     *
     * @param fileName        config file path as registered, e.g. {@code config/lang.yml}
     *                        <br> 注册时使用的配置文件路径
     * @param configMapString JSON string containing that file's entries <br> 该文件配置项的JSON字符串
     * @throws IOException if the file is unknown or ambiguous, or the update fails
     *                     <br> 如果文件找不到、跨插件重名或更新失败
     * @since 6.2.5
     */
    protected static void updateConfigMap(String fileName, String configMapString) throws IOException {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        configManager.loadFromJson(fileName, configMapString);
    }
}
