package com.ultikits.plugins.mysqlconnector;

import com.ultikits.ultitools.abstracts.DataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MysqlDataStore implements DataStore {
    private static final Map<Class<?>, DataOperator<?>> dataOperatorMap = new ConcurrentHashMap<>();
    private HikariDataSource dataSource;

    public MysqlDataStore() {
        YamlConfiguration mysqlConfig = MysqlConnector.getMysqlConnector().getConfig("res/config/config.yml");
        String host = mysqlConfig.getString("host");
        String port = mysqlConfig.getString("port");
        String database = mysqlConfig.getString("database");
        String username = mysqlConfig.getString("username");
        String password = mysqlConfig.getString("password");
        long connectionTimeout = mysqlConfig.getLong("connectionTimeout");
        long keepaliveTime = mysqlConfig.getLong("keepaliveTime");
        long maxLifetime = mysqlConfig.getLong("maxLifetime");
        String connectionTestQuery = mysqlConfig.getString("connectionTestQuery");
        int maximumPoolSize = mysqlConfig.getInt("maximumPoolSize");
        String cachePrepStmts = mysqlConfig.getString("cachePrepStmts");
        String prepStmtCacheSize = mysqlConfig.getString("prepStmtCacheSize");
        String prepStmtCacheSqlLimit = mysqlConfig.getString("prepStmtCacheSqlLimit");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);
        config.addDataSourceProperty("cachePrepStmts", cachePrepStmts);
        config.addDataSourceProperty("prepStmtCacheSize", prepStmtCacheSize);
        config.addDataSourceProperty("prepStmtCacheSqlLimit", prepStmtCacheSqlLimit);
        config.setConnectionTimeout(connectionTimeout);
        config.setKeepaliveTime(keepaliveTime);
        config.setMaxLifetime(maxLifetime);
        config.setConnectionTestQuery(connectionTestQuery);
        config.setMaximumPoolSize(maximumPoolSize);
        dataSource = new HikariDataSource(config);

        DataStoreManager.register(this);
    }

    @Override
    public String getStoreType() {
        return "mysql";
    }

    @Override
    public <T extends DataEntity> DataOperator<T> getOperator(IPlugin plugin, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        DataOperator<T> tMysqlDataOperator = (DataOperator<T>) dataOperatorMap.get(dataEntity);
        if (tMysqlDataOperator == null) {
            tMysqlDataOperator = new MysqlDataOperator<>(dataSource, dataEntity);
            dataOperatorMap.put(dataEntity, tMysqlDataOperator);
        }
        return tMysqlDataOperator;
    }

    @Override
    public void destroyAllOperators() {

    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }
}
