package com.ultikits.testfixtures.textwiring;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import java.util.List;

/** Uses the normal module constructor and its explicit config registration path. */
public class ConfigTextProbeModule extends UltiToolsPlugin {
    public static final String PATH = "config/prepared.yml";

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return List.of(new ProbeConfig(PATH));
    }

    @Override
    public boolean registerSelf() {
        return true;
    }

    @ConfigEntity(PATH)
    public static final class ProbeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limit", comment = "{config.probe.limit}")
        private int limit = 10;

        public ProbeConfig(String path) {
            super(path);
        }
    }
}
