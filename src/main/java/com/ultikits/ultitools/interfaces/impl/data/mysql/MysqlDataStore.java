package com.ultikits.ultitools.interfaces.impl.data.mysql;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.DataSourceTransactionManager;
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

    /**
     * {@link JdbcTransactionManager} cache, keyed by requesting identity -- one manager per
     * plugin container even though every plugin shares the one global {@link #dataSource} (D-02):
     * {@code contextHolder} is a per-instance {@code ThreadLocal} (FOUND-04), so two plugins each
     * doing a {@code @Transactional} write on MySQL must never cross transaction state, exactly
     * as {@code PluginManager.wireTransactional}'s own javadoc already documents for the JDBC
     * path. Not static -- same reload-safety reasoning as {@link #dataOperatorMap}.
     * <p>
     * The single seam through which both {@link #transactionManagerFor(DataScope)} (called by
     * {@code PluginManager.wireTransactional} for the AOP interceptor) and {@link #getOperator}
     * (below) resolve a manager for the same identity -- {@code computeIfAbsent} guarantees the
     * second caller gets back the exact instance the first one built (T-02-TAM-11).
     */
    private final Map<String, JdbcTransactionManager> transactionManagerMap = new ConcurrentHashMap<>();

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
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        checkOwnership(plugin, dataEntity);
        return getOperatorForIdentity(plugin.getPluginName(), dataEntity);
    }

    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(File dataFolder, Class<T> dataEntity) {
        checkOwnership(dataFolder, dataEntity);
        return getOperatorForIdentity(canonicalPath(dataFolder), dataEntity);
    }

    /**
     * Internal, unchecked construction path (CR-01/CR-03, 02-13): {@link
     * com.ultikits.ultitools.interfaces.DataStore#getOperator(DataScope, Class)}'s default body
     * calls this only after its own {@code scope.owns(...)} check has already passed. Resolves
     * {@code scope} to the exact same identity {@link #identityFor(DataScope)} already derives for
     * {@link #transactionManagerFor(DataScope)} -- internal scopes resolve to the plugin's own
     * name, external scopes to the canonical data-folder path -- so an internal scope's operator
     * cache entry is keyed identically whether reached through here or through {@link
     * #getOperator(UltiToolsPlugin, Class)}.
     */
    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperatorUnchecked(DataScope scope, Class<T> dataEntity) {
        return getOperatorForIdentity(identityFor(scope), dataEntity);
    }

    /**
     * Shared operator-construction body for all three entry points above, once ownership has
     * already been established by whichever one of them was called. Single-sourced so the
     * operator cache (keyed by (identity, entity)) and the {@code transactionManagerForIdentity}
     * wiring are each written in exactly one place.
     */
    @SuppressWarnings("unchecked")
    private <T extends BaseDataEntity<String>> DataOperator<T> getOperatorForIdentity(String identity, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            // GATE-05 group two (08-21): routed to the typed data-access hierarchy -- the entity
            // itself is misconfigured, not a runtime data-operation failure.
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID, "No Table annotation is presented!");
        }
        return (DataOperator<T>) dataOperatorMap.computeIfAbsent(new OperatorKey(identity, dataEntity),
                key -> {
                    MysqlDataOperator<T> operator = new MysqlDataOperator<>(dataSource, dataEntity);
                    operator.setTransactionManager(transactionManagerForIdentity(identity));
                    return operator;
                });
    }

    @Override
    public javax.sql.DataSource getDataSource(DataScope scope) {
        return dataSource;
    }

    /**
     * Resolves the same per-plugin-container {@link JdbcTransactionManager} instance {@link
     * #getOperator} wires onto the operators it hands out for {@code scope}'s own identity -- the
     * seam {@code PluginManager.wireTransactional} calls to bind the AOP {@code @Transactional}
     * interceptor to it, so a module's data operators and its {@code @Transactional} beans share
     * one transaction, not two (T-02-TAM-11), despite every plugin sharing the one global {@link
     * #dataSource}.
     *
     * @param scope the identity token to resolve the manager for
     * @return the shared manager for that scope's identity
     */
    public JdbcTransactionManager transactionManagerFor(DataScope scope) {
        return transactionManagerForIdentity(identityFor(scope));
    }

    /**
     * Resolves {@code scope}'s operator-cache identity, matching whichever legacy {@code
     * getOperator} overload the same scope would actually route through: the internal (plugin
     * name) shape when {@code scope} is an internal plugin's own scope (its {@code dataFolder} is
     * exactly the core framework's own data folder, {@code DataScope.forPlugin}'s construction --
     * the same disambiguation {@code JsonStore.identityOf(DataScope)} and {@code
     * SQLiteDataStore.dbPathFor(DataScope)} use, 02-05), the external (canonical folder path)
     * shape otherwise. Shared by {@link #transactionManagerFor(DataScope)} and {@link
     * #getOperatorUnchecked(DataScope, Class)} (02-13) so the two never disagree on identity.
     */
    private static String identityFor(DataScope scope) {
        UltiTools ultiTools = UltiTools.getInstance();
        boolean internal = ultiTools != null && ultiTools.getDataFolder() != null
                && ultiTools.getDataFolder().equals(scope.getDataFolder());
        return internal ? scope.getPluginName() : canonicalPath(scope.getDataFolder());
    }

    /**
     * Returns the manager for {@code identity}, building it on first touch.
     */
    private JdbcTransactionManager transactionManagerForIdentity(String identity) {
        return transactionManagerMap.computeIfAbsent(identity, key -> new DataSourceTransactionManager(dataSource));
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
        transactionManagerMap.clear();
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
