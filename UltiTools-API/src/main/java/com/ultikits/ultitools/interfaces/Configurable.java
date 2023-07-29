package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public interface Configurable {
    default List<AbstractConfigEntity> getAllConfigs() {
        return Collections.emptyList();
    }

    <T extends AbstractConfigEntity> T getConfig(Class<T> configType);

    <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType);

    <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException;
}
