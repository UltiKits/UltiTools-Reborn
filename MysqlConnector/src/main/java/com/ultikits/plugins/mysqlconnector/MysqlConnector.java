package com.ultikits.plugins.mysqlconnector;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.DataStoreManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class MysqlConnector extends UltiToolsPlugin {
    private static MysqlConnector mysqlConnector;

    public static MysqlConnector getMysqlConnector() {
        return mysqlConnector;
    }

    @Override
    public boolean registerSelf() {
        mysqlConnector = this;
        if (MysqlConnector.getMysqlConnector().getConfig("config/config.yml", MysqlConfig.class).isEnable()) {
            DataStoreManager.register(new MysqlDataStore());
        }
        return true;
    }

    @Override
    public void unregisterSelf() {

    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new MysqlConfig("config/config.yml")
        );
    }
}
