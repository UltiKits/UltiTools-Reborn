package com.ultikits.ultitools.utils;

import java.io.IOException;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * Utility class for configuration editor operations.
 * Provides methods to get and update configuration data in JSON format.
 * This class is used internally by the configuration editor system.
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class ConfigEditorUtils {

    /**
     * Get the configuration map as a JSON string.
     *
     * @return JSON string representation of the configuration map
     */
    protected static String getConfigMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.toJson();
    }

    /**
     * Get the comment map as a JSON string.
     *
     * @return JSON string representation of the comment map
     */
    protected static String getCommentMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.getComments();
    }

    /**
     * Update the configuration map from a JSON string.
     *
     * @param configMapString JSON string containing the new configuration
     * @throws IOException if an I/O error occurs during update
     */
    protected static void updateConfigMap(String configMapString) throws IOException {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        configManager.loadFromJson(configMapString);
    }

    /**
     * Update a single configuration file from a JSON string.
     *
     * <p>This is the path taken when the panel pushes configuration by file name:
     * {@code configMapString} is that file's own {@code {configEntry: value}}, not the full
     * {@code {pluginName: {configPath: {...}}}} structure that {@link #updateConfigMap(String)}
     * expects. The two shapes correspond to two distinct entry points on the panel; mixing them
     * silently fails to write and reports no error -- see issue #236.
     *
     * @param fileName        config file path as registered, e.g. {@code config/lang.yml}
     * @param configMapString JSON string containing that file's entries
     * @throws IOException if the file is unknown or ambiguous, or the update fails
     * @since 6.2.5
     */
    protected static void updateConfigMap(String fileName, String configMapString) throws IOException {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        configManager.loadFromJson(fileName, configMapString);
    }
}
