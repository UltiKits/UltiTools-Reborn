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
 * <p>
 * 数据存储管理器
 */
@ApiStatus.Internal
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
     * <br>
     * 实际使用的数据存储与配置不一致时，把「配置要的是什么、实际用的是什么、为什么」写进日志。
     * <p>
     * 降级本身是静默的：{@link #getDatastore(String)} 找不到目标类型就回退到 json，服务器照常
     * 启动，运维者看到的现象是「服务器起来了但玩家数据全没了」。启动横幅里那条 INFO 级的
     * 「数据存储方式：json」不解决问题——它只报实际值，不会提配置里写的是别的东西。见 issue #183。
     * <p>
     * 配置与实际一致时（含没配 {@code datasource.type} 的默认情况）不产生任何日志。
     *
     * @param logger         Logger to write to <br> 写入的日志器
     * @param configured     Configured {@code datasource.type}, may be null <br> 配置的 {@code datasource.type}，可为 null
     * @param mysqlEnabled   Whether {@code mysql.enable} is true <br> {@code mysql.enable} 是否为 true
     * @param mysqlAvailable Whether the MySQL data source was actually created <br> MySQL 数据源是否真的建起来了
     * @param actual         Store type actually in use <br> 实际使用的存储类型
     */
    public static void reportBackendSelection(Logger logger, String configured, boolean mysqlEnabled,
                                              boolean mysqlAvailable, String actual) {
        // getDatastore 把 null 当 json，这里必须用同一套口径，否则没写 datasource.type 的服务器
        // 每次启动都会收到一条假的降级告警。
        String requested = configured == null ? "json" : configured;
        if (requested.equals(actual)) {
            return;
        }
        String reason;
        if ("mysql".equals(requested) && !mysqlEnabled) {
            // 这条分支原先连一条日志都没有：MysqlDataStore 压根没被 new 出来，
            // 它构造器里那条 SEVERE 自然也不会响。
            reason = localize("mysql.enable 为 false，MySQL 数据源从未初始化");
        } else if ("mysql".equals(requested) && !mysqlAvailable) {
            reason = localize("MySQL 连接失败，数据源不可用（详见上方的连接错误）");
        } else {
            reason = localize("没有已注册的数据存储提供该类型");
        }
        // 类型是逐字比对的，所以配成 'MySQL' 或带空格也会走到这里 —— 把原值原样引出来，
        // 让大小写和空白问题在日志里一眼可见。
        logger.log(Level.SEVERE, String.format(
                localize("数据存储降级：config.yml 里 datasource.type 配置为 '%s'，实际使用的是 '%s'。原因：%s"),
                requested, actual, reason));
        // 第二条只点实际后端：配错类型时（比如 'postgres'）那个后端根本不存在，
        // 说「它与 'postgres' 的数据不互通」是在暗示有一份 postgres 数据，误导人。
        logger.log(Level.SEVERE, String.format(
                localize("玩家数据将读写 '%s' 存储；不同后端之间的数据并不互通，如果这不是你要的，请停服修正 config.yml 后重启"),
                actual));
    }

    /**
     * 走 i18n，但不让它把诊断本身弄崩。这条路径只在已经出问题的时候才跑，
     * 拿不到语言字典时用原文，比抛 NPE 强。
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
