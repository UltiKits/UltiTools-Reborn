package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.impl.JsonStore;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class DataStoreManager {
    private static final Map<String, DataStore> dataMap = new HashMap<>();

    public static synchronized void register(DataStore dataStore) {
        dataMap.put(dataStore.getStoreType(), dataStore);
    }

    public static synchronized void unregister(DataStore dataStore) {
        dataStore.destroyAllOperators();
        dataMap.remove(dataStore.getStoreType(), dataStore);
    }

    public static void close() {
        Bukkit.getLogger().log(Level.INFO, "Unregistering all data operators...");
        for (DataStore dataStore : dataMap.values()) {
            dataStore.destroyAllOperators();
        }
        dataMap.clear();
    }

    public static DataStore getDatastore(String type) {
        if (type.equals("json") && dataMap.get(type) == null) {
            return new JsonStore(UltiTools.getInstance().getDataFolder().getAbsolutePath() + File.separator + "data");
        }
        return dataMap.get(type) == null ? dataMap.get("json") : dataMap.get(type);
    }

}
