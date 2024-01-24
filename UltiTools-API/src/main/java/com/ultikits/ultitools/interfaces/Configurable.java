package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Configurable data operation interface.
 * <p>
 * 可配置的数据操作接口
 */
public interface Configurable {
    /**
     * @return All configs <br> 所有配置
     */
    default List<AbstractConfigEntity> getAllConfigs() {
        return Collections.emptyList();
    }

    /**
     * Get config by config type.
     * <p>
     * 通过配置类型获取配置
     *
     * @param configType Config type <br> 配置类型
     * @param <T>        Config type <br> 配置类型
     * @return Config <br> 配置
     */
    <T extends AbstractConfigEntity> T getConfig(Class<T> configType);

    /**
     * Get config by config path and config type.
     * <p>
     * 通过配置路径和配置类型获取配置
     *
     * @param path       Config path <br> 配置路径
     * @param configType Config type <br> 配置类型
     * @param <T>        Config type <br> 配置类型
     * @return Config <br> 配置
     */
    <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType);

    /**
     * Save config by config type.
     * <p>
     * 通过配置类型保存配置
     *
     * @param path       Config path <br> 配置路径
     * @param configType Config type <br> 配置类型
     * @param <T>        Config type <br> 配置类型
     * @throws IOException IOException <br> IO异常
     */
    <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException;
}
