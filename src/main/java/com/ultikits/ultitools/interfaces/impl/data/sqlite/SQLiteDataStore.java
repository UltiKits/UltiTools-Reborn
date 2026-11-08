package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

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

/**
 * SQLite-backed {@link DataStore}.
 * <p>
 * A connection pool is keyed by the canonical path of the backing {@code .db} file, independent
 * of any single entity class -- two entity classes stored in the same file share exactly one
 * {@link HikariDataSource}. The operator cache is keyed by (that same file path, entity class), so
 * two callers resolving to two different files (two plugins, or the plugin path vs. the external
 * {@code File} path) never share a {@link DataOperator} instance. Neither map is {@code static}:
 * {@code DataStoreManager} registers exactly one {@code SQLiteDataStore} instance, and instance
 * scoping is what stops both maps from outliving a {@code /reload}.
 *
 * @author wisdomme
 * @since 1.0.0
 */
public class SQLiteDataStore implements DataStore {

    private static final Logger LOGGER = Logger.getLogger(SQLiteDataStore.class.getName());

    /**
     * Operator cache, keyed by (backing .db file canonical path, entity class). Not static -- see
     * class javadoc.
     */
    private final Map<OperatorKey, DataOperator<?>> dataOperatorMap = new ConcurrentHashMap<>();

    /**
     * Connection pool cache, keyed by the backing .db file's canonical path. One pool per file,
     * regardless of how many entity classes or callers resolve to it. Not static -- see class
     * javadoc.
     */
    private final Map<String, HikariDataSource> dataSourceMap = new ConcurrentHashMap<>();

    /**
     * {@link JdbcTransactionManager} cache, keyed identically to {@link #dataSourceMap} -- the
     * backing .db file's canonical path (D-02: one JDBC {@link DataSource} per file already means
     * one manager per file is "one per plugin container", since each plugin has its own file).
     * Not static -- same reload-safety reasoning as the other two maps.
     * <p>
     * This is the single seam through which BOTH {@link #transactionManagerFor(DataScope)}
     * (called by {@code PluginManager.wireTransactional} for the AOP interceptor) and {@link
     * #getOperator} (below, for the operators this store hands module authors) resolve a manager
     * for the same file -- {@code computeIfAbsent} guarantees the second caller to touch a given
     * key gets back the exact instance the first one built, never a second, independent one
     * (T-02-TAM-11: two managers over one file would give a {@code @Transactional} method calling
     * {@code insertAll} two independent transactions, which is worse than no transaction at all
     * because it looks correct).
     */
    private final Map<String, JdbcTransactionManager> transactionManagerMap = new ConcurrentHashMap<>();

    @Override
    public String getStoreType() {
        return "sqlite";
    }

    /**
     * Until 6.4.0 this overrode the {@code DataStore} overload of the same signature, which 6.4.0
     * removed. It still checks ownership first and behaves as in 6.3.x.
     *
     * @param plugin     the requesting plugin
     * @param dataEntity the data entity class
     * @param <T>        the entity type
     * @return the data operator
     * @deprecated Kept only for binary compatibility with the {@code DataStore} overload removed in
     *             6.4.0. Use {@link com.ultikits.ultitools.interfaces.DataStore#getOperator(DataScope, Class)}
     *             instead (a module calls {@code UltiToolsPlugin#getDataOperator(Class)}).
     * @removeIn 6.5.0
     */
    @Deprecated(since = "6.4.0", forRemoval = true)
    @SuppressWarnings("removal") // calls the deprecated DataStore#checkOwnership, removed with it in 6.5.0
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        checkOwnership(plugin, dataEntity);
        return getOperatorForPath(dbPathForPlugin(plugin.getPluginName()), dataEntity);
    }

    /**
     * Until 6.4.0 this overrode the {@code DataStore} overload of the same signature, which 6.4.0
     * removed. It still checks ownership first and behaves as in 6.3.x.
     *
     * @param dataFolder the external plugin's data folder
     * @param dataEntity the data entity class
     * @param <T>        the entity type
     * @return the data operator
     * @deprecated Kept only for binary compatibility with the {@code DataStore} overload removed in
     *             6.4.0. Use {@link com.ultikits.ultitools.interfaces.DataStore#getOperator(DataScope, Class)}
     *             instead (a module calls {@code UltiToolsPlugin#getDataOperator(Class)}).
     * @removeIn 6.5.0
     */
    @Deprecated(since = "6.4.0", forRemoval = true)
    @SuppressWarnings("removal") // calls the deprecated DataStore#checkOwnership, removed with it in 6.5.0
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(File dataFolder, Class<T> dataEntity) {
        checkOwnership(dataFolder, dataEntity);
        return getOperatorForPath(dbPathForFolder(dataFolder), dataEntity);
    }

    /**
     * Internal, unchecked construction path (CR-01/CR-03, 02-13): {@link
     * com.ultikits.ultitools.interfaces.DataStore#getOperator(DataScope, Class)}'s default body
     * calls this only after its own {@code scope.owns(...)} check has already passed. Resolves
     * {@code scope} to the exact same {@code .db} path {@link #dbPathFor(DataScope)} already
     * derives for {@link #transactionManagerFor(DataScope)} -- internal scopes resolve to {@link
     * #dbPathForPlugin(String)} keyed on the plugin's own name, external scopes to {@link
     * #dbPathForFolder(File)} -- so an internal scope never collapses onto the framework's shared
     * core folder (the regression 02-07 guarded against and CR-01 found reopened as a security
     * bypass).
     */
    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperatorUnchecked(DataScope scope, Class<T> dataEntity) {
        return getOperatorForPath(dbPathFor(scope), dataEntity);
    }

    /**
     * Operator-construction body for {@link #getOperatorUnchecked(DataScope, Class)}, once
     * ownership has already been established. Single-sourced so the pool
     * cache (keyed by backing-file path), the operator cache (keyed by (path, entity)), and the
     * {@code transactionManagerFor(...)} wiring are each written in exactly one place.
     */
    @SuppressWarnings("unchecked")
    private <T extends BaseDataEntity<String>> DataOperator<T> getOperatorForPath(String dbPath, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            // GATE-05 group two (08-21): routed to the typed data-access hierarchy -- the entity
            // itself is misconfigured, not a runtime data-operation failure.
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID, "No Table annotation is presented!");
        }
        return (DataOperator<T>) dataOperatorMap.computeIfAbsent(new OperatorKey(dbPath, dataEntity),
                key -> {
                    SQLiteDataOperator<T> operator = new SQLiteDataOperator<>(poolFor(dbPath), dataEntity);
                    operator.setTransactionManager(transactionManagerForPath(dbPath));
                    return operator;
                });
    }

    @Override
    public DataSource getDataSource(DataScope scope) {
        return poolFor(dbPathFor(scope));
    }

    /**
     * Resolves the same per-plugin-container {@link JdbcTransactionManager} instance {@link
     * #getOperator} wires onto the operators it hands out for {@code scope}'s own database file
     * -- the seam {@code PluginManager.wireTransactional} calls to bind the AOP {@code
     * @Transactional} interceptor to it, so a module's data operators and its {@code
     * @Transactional} beans share one transaction, not two (T-02-TAM-11).
     *
     * @param scope the identity token to resolve the manager for
     * @return the shared manager for that scope's database file
     */
    public JdbcTransactionManager transactionManagerFor(DataScope scope) {
        return transactionManagerForPath(dbPathFor(scope));
    }

    /**
     * Returns the manager for {@code dbPath}, building it on first touch -- the same {@code
     * computeIfAbsent} single-construction guarantee {@link #poolFor(String)} already gives the
     * connection pool for that file.
     */
    private JdbcTransactionManager transactionManagerForPath(String dbPath) {
        return transactionManagerMap.computeIfAbsent(dbPath, path -> new DataSourceTransactionManager(poolFor(path)));
    }

    /**
     * Resolves {@code scope}'s backing .db file path: the internal ({@code
     * dbPathForPlugin}) shape when {@code scope} is an internal plugin's own scope (its {@code
     * dataFolder} is exactly the core framework's own data folder, {@code DataScope.forPlugin}'s
     * construction -- the same disambiguation {@code JsonStore.identityOf(DataScope)} uses, 02-05),
     * the external ({@code dbPathForFolder}) shape otherwise.
     */
    private static String dbPathFor(DataScope scope) {
        UltiTools ultiTools = UltiTools.getInstance();
        boolean internal = ultiTools != null && ultiTools.getDataFolder() != null
                && ultiTools.getDataFolder().equals(scope.getDataFolder());
        return internal ? dbPathForPlugin(scope.getPluginName()) : dbPathForFolder(scope.getDataFolder());
    }

    /**
     * The internal-plugin .db path shape: {@code <core data folder>/sqliteDB/<plugin name>.db}.
     * Used by {@link #dbPathFor(DataScope)}.
     */
    private static String dbPathForPlugin(String pluginName) {
        File dataFolder = new File(UltiTools.getInstance().getDataFolder(), "sqliteDB");
        ensureExists(dataFolder);
        return canonicalPath(new File(dataFolder, pluginName + ".db"));
    }

    /**
     * The external-plugin .db path shape: {@code <plugin's own data folder>/data.db}. Used by
     * {@link #dbPathFor(DataScope)}.
     */
    private static String dbPathForFolder(File dataFolder) {
        ensureExists(dataFolder);
        return canonicalPath(new File(dataFolder, "data.db"));
    }

    /**
     * Returns the pool for {@code dbPath}, building it on first touch. {@link Map#computeIfAbsent}
     * makes a concurrent first touch by two callers build exactly one pool, never two.
     *
     * @param dbPath canonical path of the backing .db file
     * @return the shared pool for that file
     */
    private HikariDataSource poolFor(String dbPath) {
        return dataSourceMap.computeIfAbsent(dbPath, path -> {
            HikariConfig config = new HikariConfig();
            config.setMaximumPoolSize(10);
            // The data source alone names the database file and opens its connections; Hikari
            // ignores jdbcUrl and driverClassName once a data source is set, so neither is set.
            config.setDataSource(new SQLiteBusyDataSource(path));
            return new HikariDataSource(config);
        });
    }

    private static void ensureExists(File dataFolder) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    private static String canonicalPath(File dbFile) {
        try {
            return dbFile.getCanonicalPath();
        } catch (IOException e) {
            return dbFile.getAbsolutePath();
        }
    }

    /**
     * Closes every pool this store holds and empties both caches. Idempotent -- calling it twice
     * closes nothing the second time, since the first call already emptied {@link #dataSourceMap}.
     * A failure closing one pool is logged and does not stop the rest from being closed.
     */
    @Override
    public void destroyAllOperators() {
        for (Map.Entry<String, HikariDataSource> entry : dataSourceMap.entrySet()) {
            HikariDataSource pool = entry.getValue();
            try {
                if (!pool.isClosed()) {
                    pool.close();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to close SQLite connection pool for " + entry.getKey(), e);
            }
        }
        dataSourceMap.clear();
        dataOperatorMap.clear();
        transactionManagerMap.clear();
    }

    /**
     * Composite operator-cache key: the backing .db file's canonical path plus the entity class.
     * Both {@code getOperator} overloads resolve their key through this same shape so the two
     * entry paths cannot diverge again (see 02-CONTEXT.md's "Per-backend path rules differ between
     * the two entry paths" note).
     */
    private static final class OperatorKey {
        private final String dbPath;
        private final Class<?> entityClass;

        OperatorKey(String dbPath, Class<?> entityClass) {
            this.dbPath = dbPath;
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
            return dbPath.equals(that.dbPath) && entityClass.equals(that.entityClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dbPath, entityClass);
        }
    }
}
