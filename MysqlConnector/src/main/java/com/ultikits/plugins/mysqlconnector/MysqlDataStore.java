package com.ultikits.plugins.mysqlconnector;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MysqlDataStore implements DataStore {
    private static final Map<Class<?>, DataOperator<?>> dataOperatorMap = new ConcurrentHashMap<>();
    private HikariDataSource dataSource;

    public MysqlDataStore() {
        MysqlConfig mysqlConfig = MysqlConnector.getMysqlConnector().getConfig("config/config.yml", MysqlConfig.class);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + mysqlConfig.getHost() + ":" + mysqlConfig.getPort() + "/" + mysqlConfig.getDatabase());
        config.setUsername(mysqlConfig.getUsername());
        config.setPassword(mysqlConfig.getPassword());
        config.addDataSourceProperty("cachePrepStmts", mysqlConfig.isCachePrepStmts());
        config.addDataSourceProperty("prepStmtCacheSize", mysqlConfig.getPrepStmtCacheSize());
        config.addDataSourceProperty("prepStmtCacheSqlLimit", mysqlConfig.getPrepStmtCacheSqlLimit());
        config.setConnectionTimeout(mysqlConfig.getConnectionTimeout());
        config.setKeepaliveTime(mysqlConfig.getKeepaliveTime());
        config.setMaxLifetime(mysqlConfig.getMaxLifetime());
        config.setConnectionTestQuery(mysqlConfig.getConnectionTestQuery());
        config.setMaximumPoolSize(mysqlConfig.getMaximumPoolSize());
        dataSource = new HikariDataSource(config);
        UltiTools.getInstance().setDataStore(this);
    }

    @Override
    public String getStoreType() {
        return "mysql";
    }

    @Override
    public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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
        dataSource.close();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }
}
