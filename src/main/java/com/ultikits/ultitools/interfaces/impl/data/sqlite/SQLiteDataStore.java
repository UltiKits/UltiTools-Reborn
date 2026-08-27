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
import com.ultikits.ultitools.manager.DataScope;
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
 * <p>
 * 基于 SQLite 的 {@link DataStore} 实现。连接池按底层 {@code .db} 文件的规范路径分组，与具体
 * 实体类无关——存储在同一文件中的多个实体类共享同一个 {@link HikariDataSource}。操作器缓存按
 * （同一个文件路径，实体类）的组合键分组，因此解析到两个不同文件的调用方（两个插件，或插件路径
 * 与外部 {@code File} 路径）永远不会共享同一个 {@link DataOperator} 实例。两个 Map 均非
 * {@code static}：{@code DataStoreManager} 只注册一个 {@code SQLiteDataStore} 实例，实例级作用域
 * 正是防止两个 Map 在 {@code /reload} 后继续存活的原因。
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

    @Override
    public String getStoreType() {
        return "sqlite";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        checkOwnership(plugin, dataEntity);
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        File dataFolder = new File(UltiTools.getInstance().getDataFolder(), "sqliteDB");
        ensureExists(dataFolder);
        String dbPath = canonicalPath(new File(dataFolder, plugin.getPluginName() + ".db"));
        return (DataOperator<T>) dataOperatorMap.computeIfAbsent(new OperatorKey(dbPath, dataEntity),
                key -> new SQLiteDataOperator<>(poolFor(dbPath), dataEntity));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(File dataFolder, Class<T> dataEntity) {
        checkOwnership(dataFolder, dataEntity);
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        ensureExists(dataFolder);
        String dbPath = canonicalPath(new File(dataFolder, "data.db"));
        return (DataOperator<T>) dataOperatorMap.computeIfAbsent(new OperatorKey(dbPath, dataEntity),
                key -> new SQLiteDataOperator<>(poolFor(dbPath), dataEntity));
    }

    @Override
    public DataSource getDataSource(DataScope scope) {
        File dataFolder = new File(scope.getDataFolder(), "sqliteDB");
        ensureExists(dataFolder);
        String dbPath = canonicalPath(new File(dataFolder, scope.getPluginName() + ".db"));
        return poolFor(dbPath);
    }

    /**
     * Returns the pool for {@code dbPath}, building it on first touch. {@link Map#computeIfAbsent}
     * makes a concurrent first touch by two callers build exactly one pool, never two.
     *
     * @param dbPath canonical path of the backing .db file <br> 底层 .db 文件的规范路径
     * @return the shared pool for that file <br> 该文件共享的连接池
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
     * <p>
     * 关闭本 store 持有的每一个连接池，并清空两个缓存。幂等——因为第一次调用已经清空了
     * {@link #dataSourceMap}，第二次调用不会关闭任何东西。关闭某个连接池失败只会被记录，
     * 不会中断其余连接池的关闭。
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
