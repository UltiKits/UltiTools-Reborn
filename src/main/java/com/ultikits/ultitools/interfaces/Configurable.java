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
     * @return the entity registered for {@code path}, or {@code null} if none is registered
     *         (a registered entity of another type throws {@link ClassCastException})
     */
    <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType);

    /**
     * Saves the configuration entity registered for {@code path} in this module. See
     * {@link com.ultikits.ultitools.abstracts.UltiToolsPlugin#saveConfig(String, Class)} for what
     * is written. Throws {@link IllegalStateException} naming the module and path if no entity is
     * registered for the path. If the registered entity is not an instance of {@code configType},
     * throws {@link ClassCastException} naming the module, path, expected type and actual type.
     *
     * @param path       Config path
     * @param configType Config type
     * @param <T>        Config type
     * @throws IOException IOException
     */
    <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException;
}
