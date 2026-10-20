package com.ultikits.ultitools.abstracts;

import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Reads the persisted entity file independently of the entity's internal storage implementation. */
final class ConfigFileView {

    private ConfigFileView() {
        // Test-only static helper.
    }

    static YamlConfiguration read(AbstractConfigEntity entity)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration view = new YamlConfiguration();
        view.options().parseComments(true);
        view.load(entity.getUltiToolsPlugin().getConfigFile(entity.getConfigFilePath()));
        return view;
    }
}
