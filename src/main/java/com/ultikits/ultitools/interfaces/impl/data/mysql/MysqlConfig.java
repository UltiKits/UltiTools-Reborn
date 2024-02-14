package com.ultikits.ultitools.interfaces.impl.data.mysql;

import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

@Getter
public class MysqlConfig {
    private final boolean enable;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String database;
    private final int connectionTimeout;
    private final int keepaliveTime;
    private final int maxLifetime;
    private final String connectionTestQuery;
    private final int maximumPoolSize;
    private final boolean cachePrepStmts;
    private final int prepStmtCacheSize;
    private final int prepStmtCacheSqlLimit;

    public MysqlConfig(FileConfiguration config) {
        enable = config.getBoolean("mysql.enable");
        host = config.getString("mysql.host");
        port = config.getInt("mysql.port");
        username = config.getString("mysql.username");
        password = config.getString("mysql.password");
        database = config.getString("mysql.database");
        connectionTimeout = config.getInt("mysql.connectionTimeout");
        keepaliveTime = config.getInt("mysql.keepaliveTime");
        maxLifetime = config.getInt("mysql.maxLifetime");
        connectionTestQuery = config.getString("mysql.connectionTestQuery");
        maximumPoolSize = config.getInt("mysql.maximumPoolSize");
        cachePrepStmts = config.getBoolean("mysql.cachePrepStmts");
        prepStmtCacheSize = config.getInt("mysql.prepStmtCacheSize");
        prepStmtCacheSqlLimit = config.getInt("mysql.prepStmtCacheSqlLimit");
    }
}
