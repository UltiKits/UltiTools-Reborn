package com.ultikits.ultitools.interfaces.impl.data.mysql;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DataScope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * MySQL-backed {@link DataStore}.
 * <p>
 * There is exactly one global {@link HikariDataSource} for the whole server (constructed once
 * from {@code config.yml}'s {@code mysql.*} settings), so re-scoping does not move any MySQL data
 * -- but the operator cache below is keyed by (requesting identity, entity class), not by entity
 * class alone, so two plugins sharing an entity class no longer share one {@link DataOperator}
 * instance and cannot read each other's rows through it (SILENT-04). Neither map is {@code
 * static}: {@code DataStoreManager} registers exactly one {@code MysqlDataStore} instance.
 * <p>
 * 基于 MySQL 的 {@link DataStore} 实现。整个服务器只有一个全局 {@link HikariDataSource}
 * （从 {@code config.yml} 的 {@code mysql.*} 配置中构造一次），因此重新划定作用域不会迁移任何
 * MySQL 数据——但下面的操作器缓存按（请求方身份，实体类）而非仅按实体类分组，因此两个共享同一
 * 实体类的插件不再共享同一个 {@link DataOperator} 实例，也就无法通过它读到对方的数据行
 * （SILENT-04）。两个 Map 均非 {@code static}：{@code DataStoreManager} 只注册一个
 * {@code MysqlDataStore} 实例。
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class MysqlDataStore implements DataStore {

    /**
     * Operator cache, keyed by (requesting identity, entity class). Not static -- see class
     * javadoc. Deliberately not Lombok-{@code @Getter}'d -- it must stay encapsulated, unlike
     * {@link #dataSource} below which the framework already exposes.
     */
    private final Map<OperatorKey, DataOperator<?>> dataOperatorMap = new ConcurrentHashMap<>();
    @Getter
    private HikariDataSource dataSource;

    public MysqlDataStore() {
        MysqlConfig mysqlConfig = new MysqlConfig(UltiTools.getInstance().getConfig());

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
        try {
            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.SEVERE, UltiTools.getInstance().i18n("Mysql数据库连接失败：") + e.getMessage());
        }
    }

    @Override
    public String getStoreType() {
        return "mysql";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        return (DataOperator<T>) dataOperatorMap.computeIfAbsent(new OperatorKey(plugin.getPluginName(), dataEntity),
                key -> new MysqlDataOperator<>(dataSource, dataEntity));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(File dataFolder, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        return (DataOperator<T>) dataOperatorMap.computeIfAbsent(new OperatorKey(canonicalPath(dataFolder), dataEntity),
                key -> new MysqlDataOperator<>(dataSource, dataEntity));
    }

    @Override
    public javax.sql.DataSource getDataSource(DataScope scope) {
        return dataSource;
    }

    private static String canonicalPath(File dataFolder) {
        try {
            return dataFolder.getCanonicalPath();
        } catch (IOException e) {
            return dataFolder.getAbsolutePath();
        }
    }

    @Override
    public void destroyAllOperators() {
        dataSource.close();
        dataOperatorMap.clear();
    }

    /**
     * Composite operator-cache key: the requesting identity (plugin name on the plugin path,
     * canonical data-folder path on the external {@code File} path) plus the entity class. Both
     * {@code getOperator} overloads resolve their key through this same shape so the two entry
     * paths cannot diverge again.
     */
    private static final class OperatorKey {
        private final String identity;
        private final Class<?> entityClass;

        OperatorKey(String identity, Class<?> entityClass) {
            this.identity = identity;
            this.entityClass = entityClass;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OperatorKey)) {
                return false;
            }
            OperatorKey that = (OperatorKey) o;
            return identity.equals(that.identity) && entityClass.equals(that.entityClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identity, entityClass);
        }
    }
}
