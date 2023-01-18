package com.ultikits.plugins.mysqlconnector;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MysqlConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "enable", comment = "")
    private boolean enable = false;
    @ConfigEntry(path = "host", comment = "")
    private String host = "localhost";
    @ConfigEntry(path = "port", comment = "")
    private int port = 3306;
    @ConfigEntry(path = "username", comment = "")
    private String username = "root";
    @ConfigEntry(path = "password", comment = "")
    private String password = "password";
    @ConfigEntry(path = "database", comment = "")
    private String database = "ultitools";
    @ConfigEntry(path = "connectionTimeout", comment = "")
    private int connectionTimeout = 30000;
    @ConfigEntry(path = "keepaliveTime", comment = "")
    private int keepaliveTime = 60000;
    @ConfigEntry(path = "maxLifetime", comment = "")
    private int maxLifetime = 1800000;
    @ConfigEntry(path = "connectionTestQuery", comment = "")
    private String connectionTestQuery = "select 1";
    @ConfigEntry(path = "maximumPoolSize", comment = "")
    private int maximumPoolSize = 8;
    @ConfigEntry(path = "cachePrepStmts", comment = "")
    private boolean cachePrepStmts = true;
    @ConfigEntry(path = "prepStmtCacheSize", comment = "")
    private int prepStmtCacheSize = 250;
    @ConfigEntry(path = "prepStmtCacheSqlLimit", comment = "")
    private int prepStmtCacheSqlLimit = 2048;

    public MysqlConfig(String configFilePath) {
        super(configFilePath);
    }
}
