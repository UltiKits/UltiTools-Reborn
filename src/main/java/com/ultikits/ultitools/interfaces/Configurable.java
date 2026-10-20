package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Configurable data operation interface.
 */
public interface Configurable {
    /**
     * @return All configs
     */
    default List<AbstractConfigEntity> getAllConfigs() {
        return Collections.emptyList();
    }

    /**
     * Get config by config type.
     *
     * @param configType Config type
     * @param <T>        Config type
     * @return Config
     */
    <T extends AbstractConfigEntity> T getConfig(Class<T> configType);

    /**
     * Get config by config path and config type.
     *
     * @param path       Config path
     * @param configType Config type
     * @param <T>        Config type
     * @return Config
     */
    <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType);

    /**
     * Save config by config type.
     *
     * @param path       Config path
     * @param configType Config type
     * @param <T>        Config type
     * @throws IOException IOException
     */
    <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException;
}
