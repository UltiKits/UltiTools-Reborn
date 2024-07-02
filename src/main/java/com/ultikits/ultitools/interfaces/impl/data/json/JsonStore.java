package com.ultikits.ultitools.interfaces.impl.data.json;

import cn.hutool.core.annotation.AnnotationUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.Cached;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DataStoreManager;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    static {
        int flushRate = UltiTools.getInstance().getConfig().getInt("datasource.flushRate");
        flushRate = flushRate == 0 ? 10 : flushRate;
        new BukkitRunnable() {

            @Override
            public void run() {
                for (Cached cached : dataOperatorMap.values()) {
                    cached.flush();
                    cached.gc();
                }
            }
        }.runTaskTimerAsynchronously(UltiTools.getInstance(), 20L, 20L * flushRate);
    }

    private final String storeLocation;

    public JsonStore(String storeLocation) {
        DataStoreManager.register(this);
        this.storeLocation = storeLocation;
    }

    @Override
    public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
        if (!dataEntity.isAnnotationPresent(Table.class)) {
            throw new RuntimeException("No Table annotation is presented!");
        }
        Cached dataOperator = dataOperatorMap.get(dataEntity);
        if (dataOperator == null) {
            SimpleJsonDataOperator<T> tSimpleJsonDataOperator = new SimpleJsonDataOperator<>(
                    storeLocation +
                            File.separator +
                            plugin.getPluginName() +
                            File.separator +
                            AnnotationUtil.getAnnotation(dataEntity, Table.class).value()
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
