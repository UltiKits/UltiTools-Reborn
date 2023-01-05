package com.ultikits.plugins.mysqlconnector;

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
        getConfigManager().register(this, new MysqlConfig("res/config/config.yml"));
        if (MysqlConnector.getMysqlConnector().getConfig("res/config/config.yml", MysqlConfig.class).isEnable()) {
            DataStoreManager.register(new MysqlDataStore());
        }
        return true;
    }

    @Override
    public void unregisterSelf() {

    }

    @Override
    public String pluginName() {
        return "UltiTools-MysqlConnector";
    }

    @Override
    public int minUltiToolsVersion() {
        return 600;
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
