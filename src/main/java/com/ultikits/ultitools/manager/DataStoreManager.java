package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Data store manager.
 * <p>
 * 数据存储管理器
 */
public class DataStoreManager {
    private static final Map<String, DataStore> dataMap = new HashMap<>();

    /**
     * Register data store.
     * <br>
     * 注册数据存储
     *
     * @param dataStore Data store <br> 数据存储
     */
    public static synchronized void register(DataStore dataStore) {
        dataMap.put(dataStore.getStoreType(), dataStore);
    }

    /**
     * Unregister data store.
     * <br>
     * 注销数据存储
     *
     * @param dataStore Data store <br> 数据存储
     */
    public static synchronized void unregister(DataStore dataStore) {
        dataStore.destroyAllOperators();
        dataMap.remove(dataStore.getStoreType(), dataStore);
    }

    /**
     * Unregister all data stores.
     * <br>
     * 注销所有数据存储
     */
    public static void close() {
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Unregistering all data operators...");
        for (DataStore dataStore : dataMap.values()) {
            dataStore.destroyAllOperators();
        }
        dataMap.clear();
    }

    /**
     * Get data store. If the data store does not exist, it will return the default Json store.
     * <br>
     * 获取数据存储。如果数据存储不存在，则返回默认的Json存储。
     *
     * @param type Data store type <br> 数据存储类型
     * @return Data store <br> 数据存储
     */
    public static DataStore getDatastore(String type) {
        if (type.equals("json") && dataMap.get(type) == null) {
            return new JsonStore(UltiTools.getInstance().getDataFolder().getAbsolutePath() + File.separator + "data");
        }
        return dataMap.get(type) == null ? dataMap.get("json") : dataMap.get(type);
    }

}
