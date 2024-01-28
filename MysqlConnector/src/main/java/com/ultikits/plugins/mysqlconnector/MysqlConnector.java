package com.ultikits.plugins.mysqlconnector;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.manager.DataStoreManager;
import lombok.Getter;

@UltiToolsModule(i18n = {"zh", "en"})
public class MysqlConnector extends UltiToolsPlugin {
    @Getter
    private static MysqlConnector mysqlConnector;

    @Override
    public boolean registerSelf() {
        mysqlConnector = this;
        if (getConfig(MysqlConfig.class).isEnable()) {
            DataStoreManager.register(new MysqlDataStore());
        }
        return true;
    }

    @Override
    public void unregisterSelf() {

    }
}
