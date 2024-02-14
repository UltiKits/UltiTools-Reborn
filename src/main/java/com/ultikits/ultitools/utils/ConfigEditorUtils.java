package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;

import java.io.IOException;

public class ConfigEditorUtils {

    protected static String getConfigMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.toJson();
    }

    protected static String getCommentMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.getComments();
    }

    protected static void updateConfigMap(String configMapString) throws IOException {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        configManager.loadFromJson(configMapString);
    }
}
