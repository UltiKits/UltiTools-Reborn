package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.scheduler.BukkitRunnable;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.Cached;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.ultikits.ultitools.manager.JsonTransactionManager;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Json Data store.
 * <p>
 * The operator cache is keyed by (requesting identity, entity class), not by entity class alone
 * -- two callers resolving to two different identities (two plugins on the plugin path, or two
 * data folders on the external {@code File} path) never share a {@link DataOperator} instance
 * (SILENT-04). Not {@code static}: {@code DataStoreManager} registers exactly one {@code
 * JsonStore} instance, and instance scoping is what stops the cache from outliving a {@code
 * /reload}.
 * <br>
 * Json存储方式抽象类。操作器缓存按（请求方身份，实体类）而非仅按实体类分组——解析到两个不同身份
 * 的调用方（插件路径上的两个插件，或外部 {@code File} 路径上的两个数据文件夹）永远不会共享同一个
 * {@link DataOperator} 实例（SILENT-04）。非 {@code static}：{@code DataStoreManager} 只注册一个
 * {@code JsonStore} 实例，实例级作用域正是防止缓存在 {@code /reload} 后继续存活的原因。
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class JsonStore implements DataStore {

    /**
     * Operator cache, keyed by (requesting identity, entity class). Not static -- see class
     * javadoc.
     */
    private final Map<OperatorKey, Cached> dataOperatorMap = new ConcurrentHashMap<>();
    private static volatile boolean schedulerInitialized = false;

    private final String storeLocation;

    /**
     * Per-identity {@link JsonTransactionManager}, one per requesting identity -- the same
     * identity {@link #dataOperatorMap} already keys by -- shared by every {@link
     * SimpleJsonDataOperator} this store hands out for that identity (02-05, D-03). Not static,
     * same reasoning as {@link #dataOperatorMap}.
     */
    private final Map<String, JsonTransactionManager> transactionManagers = new ConcurrentHashMap<>();

    /**
     * Initialize the flush scheduler. Called lazily when first JsonStore is created.
     * This avoids static initializer issues in test environments.
     * <p>
     * Only the first {@code JsonStore} instance in a JVM ever starts the periodic flush task
     * (guarded by the {@code static} {@link #schedulerInitialized} flag, matching pre-existing
     * behavior); in production there is exactly one {@code JsonStore} instance, so this flushes
     * that instance's own cache, not a shared static one.
     */
    private void initScheduler() {
        synchronized (JsonStore.class) {
            if (schedulerInitialized) {
                return;
            }
            try {
                int flushRate = UltiTools.getInstance().getConfig().getInt("datasource.flushRate");
                flushRate = flushRate == 0 ? 10 : flushRate;
                new BukkitRunnable() {

                    @Override
                    public void run() {
                        flushAllCaches();
                    }
                }.runTaskTimerAsynchronously(UltiTools.getInstance(), 20L, 20L * flushRate);
                schedulerInitialized = true;
            } catch (Exception e) {
                // Scheduler initialization failed - likely in test environment
                // This is acceptable as tests don't need periodic flushing
            }
        }
    }

    /**
     * Flush all cached data operators.
     * This method is extracted from BukkitRunnable for testability.
     */
    void flushAllCaches() {
        for (Cached cached : dataOperatorMap.values()) {
            cached.flush();
            cached.gc();
        }
    }

    /**
     * Get the number of registered data operators. Used for testing.
     */
    int getDataOperatorCount() {
        return dataOperatorMap.size();
    }

    /**
     * Reset the scheduler state. Used for testing.
     */
    void resetSchedulerState() {
        schedulerInitialized = false;
    }

    public JsonStore(String storeLocation) {
        initScheduler();
        DataStoreManager.register(this);
        this.storeLocation = storeLocation;
    }

    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        checkOwnership(plugin, dataEntity);
        ensureTableAnnotation(dataEntity);
        String identity = plugin.getPluginName();
        return getOperatorForIdentity(identity, internalLocation(identity, dataEntity), dataEntity);
    }

    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(File dataFolder, Class<T> dataEntity) {
        checkOwnership(dataFolder, dataEntity);
        ensureTableAnnotation(dataEntity);
        String identity = canonicalPath(dataFolder);
        return getOperatorForIdentity(identity, externalLocation(dataFolder, dataEntity), dataEntity);
    }

    /**
     * Internal, unchecked construction path (CR-01/CR-03, 02-13): {@link
     * com.ultikits.ultitools.interfaces.DataStore#getOperator(DataScope, Class)}'s default body
     * calls this only after its own {@code scope.owns(...)} check has already passed. Branches
     * internal/external exactly like {@link #identityOf(DataScope)} already does for {@link
     * #transactionManagerFor(DataScope)} -- an internal scope resolves to {@link
     * #internalLocation(String, Class)} keyed on the plugin's own name (never the framework's
     * shared core folder, the collapse regression 02-07 guarded against and CR-01 found reopened
     * as a bypass), an external scope to {@link #externalLocation(File, Class)}.
     */
    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperatorUnchecked(DataScope scope, Class<T> dataEntity) {
        ensureTableAnnotation(dataEntity);
        if (isInternalScope(scope)) {
            String identity = scope.getPluginName();
            return getOperatorForIdentity(identity, internalLocation(identity, dataEntity), dataEntity);
        }
        File dataFolder = scope.getDataFolder();
        String identity = canonicalPath(dataFolder);
        return getOperatorForIdentity(identity, externalLocation(dataFolder, dataEntity), dataEntity);
    }

    /**
     * Shared operator-construction body for all three entry points above, once ownership and the
     * {@code @Table} annotation have already been established by whichever one of them was
     * called. Single-sourced so the operator cache (keyed by (identity, entity)) and the {@code
     * transactionManagerFor(...)} wiring are each written in exactly one place.
     */
    @SuppressWarnings("unchecked")
    private <T extends BaseDataEntity<String>> DataOperator<T> getOperatorForIdentity(
            String identity, String location, Class<T> dataEntity) {
        Cached cached = dataOperatorMap.computeIfAbsent(new OperatorKey(identity, dataEntity),
                key -> new SimpleJsonDataOperator<>(location, dataEntity));
        SimpleJsonDataOperator<T> operator = (SimpleJsonDataOperator<T>) cached;
        operator.bindTransactionManager(transactionManagerFor(identity));
        return operator;
    }

    private static void ensureTableAnnotation(Class<?> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new IllegalArgumentException("No @Table annotation is present on: " + dataEntity.getName());
        }
    }

    /**
     * The internal-plugin storage location shape: {@code <store location>/<plugin name>/<@Table
     * value>}. Shared by {@link #getOperator(UltiToolsPlugin, Class)} and {@link
     * #getOperatorUnchecked(DataScope, Class)}'s internal branch.
     */
    private String internalLocation(String identity, Class<?> dataEntity) {
        return storeLocation + File.separator + identity + File.separator
                + ReflectionUtil.getAnnotation(dataEntity, Table.class).value();
    }

    /**
     * The external-plugin storage location shape: {@code <plugin's own data folder>/data/<@Table
     * value>}. Shared by {@link #getOperator(File, Class)} and {@link
     * #getOperatorUnchecked(DataScope, Class)}'s external branch.
     */
    private static String externalLocation(File dataFolder, Class<?> dataEntity) {
        return dataFolder.getAbsolutePath() + File.separator + "data" + File.separator
                + ReflectionUtil.getAnnotation(dataEntity, Table.class).value();
    }

    /**
     * Whether {@code scope} is an internal plugin's own scope -- its {@code dataFolder} is exactly
     * the core framework's own data folder ({@code DataScope.forPlugin}'s construction). The same
     * disambiguation {@link #identityOf(DataScope)} already uses for {@link
     * #transactionManagerFor(DataScope)} (02-05), extracted so {@link
     * #getOperatorUnchecked(DataScope, Class)} can also pick the matching location-construction
     * formula, not just the matching identity string.
     */
    private static boolean isInternalScope(DataScope scope) {
        File coreDataFolder = UltiTools.getInstance() == null ? null : UltiTools.getInstance().getDataFolder();
        return coreDataFolder != null
                && Objects.equals(canonicalPath(scope.getDataFolder()), canonicalPath(coreDataFolder));
    }

    /**
     * Resolves (creating on first use) the {@link JsonTransactionManager} for {@code scope},
     * giving the JSON backend a real {@link com.ultikits.ultitools.interfaces.TransactionManager}
     * so {@code @Transactional} is no longer refused on {@code datasource.type: json} (02-05,
     * D-03). Called by {@code PluginManager.wireAop}'s JSON branch, a different package -- public
     * for that reason, even though it is not part of the officially-extensible {@link DataStore}
     * contract; the actual snapshot mechanics it drives stay package-private on {@link
     * SimpleJsonDataOperator}, reached only from inside this class.
     * <p>
     * Shares one instance with every {@link SimpleJsonDataOperator} {@link #getOperator} hands
     * out for the same identity, so a single {@code @Transactional} method's writes across
     * several of that plugin's entities are governed by the same transaction.
     *
     * @param scope the identity token minted for the caller <br> 为调用方铸造的身份令牌
     * @return this store's manager for that caller's identity <br> 该调用方身份对应的管理器
     */
    public JsonTransactionManager transactionManagerFor(DataScope scope) {
        return transactionManagerFor(identityOf(scope));
    }

    private JsonTransactionManager transactionManagerFor(String identity) {
        return transactionManagers.computeIfAbsent(identity, JsonTransactionManager::new);
    }

    /**
     * Derives the same identity {@link #getOperator(UltiToolsPlugin, Class)} and {@link
     * #getOperator(File, Class)} each already resolve for {@link OperatorKey}, from a {@link
     * DataScope} instead of a raw plugin or {@link File}.
     * <p>
     * {@link DataScope} carries both a plugin name and a data folder but no explicit
     * internal-vs-external discriminator, so this disambiguates the same way {@link
     * DataScope#forPlugin} itself is built: an internal scope's data folder is always the core
     * UltiTools plugin's own folder (shared by every internal plugin), which no external plugin's
     * own folder can equal. When it matches, the plugin-path identity ({@link
     * DataScope#getPluginName()}) is used; otherwise the external-path identity ({@link
     * #canonicalPath}) is used.
     */
    private static String identityOf(DataScope scope) {
        return isInternalScope(scope) ? scope.getPluginName() : canonicalPath(scope.getDataFolder());
    }

    private static String canonicalPath(File dataFolder) {
        if (dataFolder == null) {
            return null;
        }
        try {
            return dataFolder.getCanonicalPath();
        } catch (IOException e) {
            return dataFolder.getAbsolutePath();
        }
    }

    @Override
    public void destroyAllOperators() {
        Iterator<Cached> iterator = dataOperatorMap.values().iterator();
        iterator.forEachRemaining(each -> {
            each.flush();
            each.gc();
        });
    }

    @Override
    public String getStoreType() {
        return "json";
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
