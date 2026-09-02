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

    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        checkOwnership(plugin, dataEntity);
        return getOperatorForPath(dbPathForPlugin(plugin.getPluginName()), dataEntity);
    }

    @Override
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
     * core folder the way routing through the public {@code getOperator(File, Class)} overload
     * used to (the exact regression 02-07 guarded against and CR-01 found reopened as a security
     * bypass).
     */
    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperatorUnchecked(DataScope scope, Class<T> dataEntity) {
        return getOperatorForPath(dbPathFor(scope), dataEntity);
    }

    /**
     * Shared operator-construction body for all three entry points above, once ownership has
     * already been established by whichever one of them was called. Single-sourced so the pool
     * cache (keyed by backing-file path), the operator cache (keyed by (path, entity)), and the
     * {@code transactionManagerFor(...)} wiring are each written in exactly one place.
     */
    @SuppressWarnings("unchecked")
    private <T extends BaseDataEntity<String>> DataOperator<T> getOperatorForPath(String dbPath, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
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
     * Resolves {@code scope}'s backing .db file path, matching whichever legacy {@code
     * getOperator} overload the same scope would actually route through: the internal ({@code
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
     * Shared by {@link #getOperator(UltiToolsPlugin, Class)} and {@link #dbPathFor(DataScope)}.
     */
    private static String dbPathForPlugin(String pluginName) {
        File dataFolder = new File(UltiTools.getInstance().getDataFolder(), "sqliteDB");
        ensureExists(dataFolder);
        return canonicalPath(new File(dataFolder, pluginName + ".db"));
    }

    /**
     * The external-plugin .db path shape: {@code <plugin's own data folder>/data.db}. Shared by
     * {@link #getOperator(File, Class)} and {@link #dbPathFor(DataScope)}.
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
            config.setJdbcUrl("jdbc:sqlite://" + path);
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(10);
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
