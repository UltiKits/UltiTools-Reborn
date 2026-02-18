package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.scheduler.BukkitRunnable;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.Cached;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Json Data store.
 * <br>
 * Json存储方式抽象类
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class JsonStore implements DataStore {
    private static final Map<Class<?>, Cached> dataOperatorMap = new ConcurrentHashMap<>();
    private static volatile boolean schedulerInitialized = false;

    private final String storeLocation;

    /**
     * Initialize the flush scheduler. Called lazily when first JsonStore is created.
     * This avoids static initializer issues in test environments.
     */
    private static synchronized void initScheduler() {
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

    /**
     * Flush all cached data operators.
     * This method is extracted from BukkitRunnable for testability.
     */
    static void flushAllCaches() {
        for (Cached cached : dataOperatorMap.values()) {
            cached.flush();
            cached.gc();
        }
    }

    /**
     * Get the number of registered data operators. Used for testing.
     */
    static int getDataOperatorCount() {
        return dataOperatorMap.size();
    }

    /**
     * Reset the scheduler state. Used for testing.
     */
    static void resetSchedulerState() {
        schedulerInitialized = false;
    }

    public JsonStore(String storeLocation) {
        initScheduler();
        DataStoreManager.register(this);
        this.storeLocation = storeLocation;
    }

    @Override
    public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new IllegalArgumentException("No @Table annotation is present on: " + dataEntity.getName());
        }
        Cached dataOperator = dataOperatorMap.get(dataEntity);
        if (dataOperator == null) {
            SimpleJsonDataOperator<T> tSimpleJsonDataOperator = new SimpleJsonDataOperator<>(
                    storeLocation +
                            File.separator +
                            plugin.getPluginName() +
                            File.separator +
                            ReflectionUtil.getAnnotation(dataEntity, Table.class).value()
                    , dataEntity
            );
            dataOperatorMap.putIfAbsent(dataEntity, tSimpleJsonDataOperator);
            return tSimpleJsonDataOperator;
        }
        return (DataOperator<T>) dataOperator;
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
}
