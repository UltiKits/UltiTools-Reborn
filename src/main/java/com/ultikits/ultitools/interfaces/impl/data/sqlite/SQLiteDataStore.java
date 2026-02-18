package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class SQLiteDataStore implements DataStore {
    private static final Map<Class<?>, DataOperator<?>> dataOperatorMap = new ConcurrentHashMap<>();

    @Override
    public String getStoreType() {
        return "sqlite";
    }

    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        DataOperator<T> tSQLiteDataOperator = (DataOperator<T>) dataOperatorMap.get(dataEntity);
        if (tSQLiteDataOperator == null) {
            File dataFolder = new File(UltiTools.getInstance().getDataFolder() + File.separator + "sqliteDB");
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite://" + dataFolder.getAbsolutePath() + "/" + plugin.getPluginName() + ".db");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(10);
            DataSource dataSource = new HikariDataSource(config);
            tSQLiteDataOperator = new SQLiteDataOperator<>(dataSource, dataEntity);
            dataOperatorMap.put(dataEntity, tSQLiteDataOperator);
        }
        return tSQLiteDataOperator;
    }

    @Override
    public void destroyAllOperators() {
    }

}
