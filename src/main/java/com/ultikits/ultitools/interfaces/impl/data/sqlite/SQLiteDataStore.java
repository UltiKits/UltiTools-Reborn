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
import com.ultikits.ultitools.manager.DataScope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class SQLiteDataStore implements DataStore {
    private static final Map<Class<?>, DataOperator<?>> dataOperatorMap = new ConcurrentHashMap<>();
    // Keyed by resolved .db file path, not by entity class -- independent of dataOperatorMap above.
    // A JDBC connection pool needs only a file path, not @Table metadata. SILENT-03's re-key of
    // dataOperatorMap itself is a later plan's work; this map exists solely to serve
    // getDataSource(DataScope) before any entity operator has ever been requested.
    private static final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();

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
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(File dataFolder, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        DataOperator<T> tSQLiteDataOperator = (DataOperator<T>) dataOperatorMap.get(dataEntity);
        if (tSQLiteDataOperator == null) {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite://" + dataFolder.getAbsolutePath() + "/data.db");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(10);
            DataSource dataSource = new HikariDataSource(config);
            tSQLiteDataOperator = new SQLiteDataOperator<>(dataSource, dataEntity);
            dataOperatorMap.put(dataEntity, tSQLiteDataOperator);
        }
        return tSQLiteDataOperator;
    }

    @Override
    public DataSource getDataSource(DataScope scope) {
        File dataFolder = new File(scope.getDataFolder(), "sqliteDB");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        String dbPath = dataFolder.getAbsolutePath() + "/" + scope.getPluginName() + ".db";
        DataSource cached = dataSourceMap.get(dbPath);
        if (cached != null) {
            return cached;
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite://" + dbPath);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(10);
        DataSource dataSource = new HikariDataSource(config);
        dataSourceMap.put(dbPath, dataSource);
        return dataSource;
    }

    @Override
    public void destroyAllOperators() {
    }

}
