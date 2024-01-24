package com.ultikits.ultitools.webserver.service.impl;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.webserver.service.ConfigEditorService;

import java.io.IOException;

public class ConfigEditorServiceImpl implements ConfigEditorService {

    @Override
    public String getConfigMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.toJson();
    }

    @Override
    public String getCommentMapString() {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        return configManager.getComments();
    }

    @Override
    public void updateConfigMap(String configMapString) throws IOException {
        ConfigManager configManager = UltiTools.getInstance().getConfigManager();
        configManager.loadFromJson(configMapString);
    }
}
