package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Data store manager.
 */
@ApiStatus.Internal
public class DataStoreManager {
    private static final Map<String, DataStore> dataMap = new HashMap<>();

    /**
     * Register data store.
     *
     * @param dataStore Data store
     */
    public static synchronized void register(DataStore dataStore) {
        dataMap.put(dataStore.getStoreType(), dataStore);
    }

    /**
     * Unregister data store.
     *
     * @param dataStore Data store
     */
    public static synchronized void unregister(DataStore dataStore) {
        dataStore.destroyAllOperators();
        dataMap.remove(dataStore.getStoreType(), dataStore);
    }

    /**
     * Unregister all data stores.
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
     *
     * @param type Data store type
     * @return Data store
     */
    public static DataStore getDatastore(String type) {
        if (type == null) {
            type = "json";
        }
        if ("json".equals(type) && dataMap.get(type) == null) {
            return new JsonStore(UltiTools.getInstance().getDataFolder().getAbsolutePath() + File.separator + "data");
        }
        return dataMap.get(type) == null ? dataMap.get("json") : dataMap.get(type);
    }

    /**
     * Report at SEVERE level that the data store actually in use is not the configured one.
     * When the configured and actual types disagree, writes what config asked for, what is
     * actually in use, and why into the log.
     *
     * <p>The fallback itself is silent: {@link #getDatastore(String)} falls back to json when it
     * cannot find the requested type, the server starts normally, and what an operator actually
     * sees is "the server came up but all the player data is gone". The INFO-level
     * "datasource: json" line in the startup banner does not solve this -- it only reports the
     * actual value, never mentioning that config asked for something else. See issue #183.
     *
     * <p>Produces no log at all when config and actual agree (including the default case where
     * {@code datasource.type} is not configured).
     *
     * @param logger         Logger to write to
     * @param configured     Configured {@code datasource.type}, may be null
     * @param mysqlEnabled   Whether {@code mysql.enable} is true
     * @param mysqlAvailable Whether the MySQL data source was actually created
     * @param actual         Store type actually in use
     */
    public static void reportBackendSelection(Logger logger, String configured, boolean mysqlEnabled,
                                              boolean mysqlAvailable, String actual) {
        // getDatastore treats null as json -- this must use the same convention, or a server
        // that never set datasource.type would get a false degradation warning on every start.
        String requested = configured == null ? "json" : configured;
        if (requested.equals(actual)) {
            return;
        }
        String reason;
        if ("mysql".equals(requested) && !mysqlEnabled) {
            // This branch used to produce no log at all: MysqlDataStore is never even
            // constructed, so the SEVERE line in its constructor never fires either.
            reason = localize("mysql.enable 为 false，MySQL 数据源从未初始化");
        } else if ("mysql".equals(requested) && !mysqlAvailable) {
            reason = localize("MySQL 连接失败，数据源不可用（详见上方的连接错误）");
        } else {
            reason = localize("没有已注册的数据存储提供该类型");
        }
        // The type is compared literally, so a config of 'MySQL' or with stray whitespace also
        // reaches this branch -- quote the original value verbatim so case and whitespace
        // problems are visible in the log at a glance.
        logger.log(Level.SEVERE, String.format(
                localize("数据存储降级：config.yml 里 datasource.type 配置为 '%s'，实际使用的是 '%s'。原因：%s"),
                requested, actual, reason));
        // The second line names only the actual backend: when the configured type is simply
        // wrong (e.g. 'postgres'), that backend never existed, so saying "it does not share data
        // with 'postgres'" would falsely imply a postgres dataset exists somewhere.
        logger.log(Level.SEVERE, String.format(
                localize("玩家数据将读写 '%s' 存储；不同后端之间的数据并不互通，如果这不是你要的，请停服修正 config.yml 后重启"),
                actual));
    }

    /**
     * Goes through i18n, but must not let that break the diagnostic itself. This path only
     * runs once something has already gone wrong, so falling back to the original text when
     * the language dictionary is unavailable is better than throwing an NPE.
     */
    private static String localize(String text) {
        try {
            String localized = UltiTools.getInstance().i18n(text);
            return localized == null ? text : localized;
        } catch (Exception e) {
            return text;
        }
    }

}
