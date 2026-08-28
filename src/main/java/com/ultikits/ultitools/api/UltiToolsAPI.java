package com.ultikits.ultitools.api;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.interfaces.DataOperator;

/**
 * Public API for external Bukkit plugins to integrate with UltiTools framework.
 * <p>
 * Usage:
 * <pre>
 * public class MyPlugin extends JavaPlugin {
 *     public void onEnable() {
 *         UltiToolsAPI.connect(this);
 *     }
 *     public void onDisable() {
 *         UltiToolsAPI.disconnect(this);
 *     }
 * }
 * </pre>
 * <p>
 * 外部 Bukkit 插件接入 UltiTools 框架的公开 API。
 *
 * @since 6.2.2
 */
public final class UltiToolsAPI {
    private static final Map<JavaPlugin, ExternalPluginAdapter> adapters = new ConcurrentHashMap<>();

    private UltiToolsAPI() {}

    /**
     * Connect an external Bukkit plugin to UltiTools framework.
     * Scans the plugin's package for @Service, @CmdExecutor, @EventListener, etc.
     * <p>
     * 将外部 Bukkit 插件连接到 UltiTools 框架。
     *
     * @param plugin the external JavaPlugin to connect
     * @throws IllegalStateException if UltiTools is not loaded
     */
    public static void connect(JavaPlugin plugin) {
        connect(plugin, new Class<?>[0]);
    }

    /**
     * Connect an external Bukkit plugin to UltiTools framework, declaring entity classes that
     * legitimately live outside the plugin's own JAR (D-19) -- a shared library JAR, or a
     * multi-module build's common artifact. The plugin's own JAR is always scanned for
     * {@code @Table} classes automatically; {@code additionalEntities} is additive to that scan,
     * not a replacement for it. The single-argument {@link #connect(JavaPlugin)} overload is
     * unchanged and delegates here with an empty array.
     * <p>
     * <b>{@code additionalEntities} is validated, not trusted (02-14).</b> Each class must live on
     * {@code plugin}'s own classpath -- either its own JAR, or a jar/module not already known to
     * belong to a different, currently-loaded plugin. A class that structurally belongs to a
     * DIFFERENT plugin (its own JAR, or one already recorded as owned by another plugin) is
     * refused with an {@link IllegalStateException} wrapping a
     * {@code com.ultikits.ultitools.exceptions.PluginModuleException}, and no partial registration
     * is left behind -- {@code additionalEntities} cannot be used to obtain a working
     * {@link DataOperator} for another plugin's entity, only to declare entities this plugin
     * genuinely owns but does not package.
     * <p>
     * 将外部 Bukkit 插件连接到 UltiTools 框架，并声明合法存放在插件自身 JAR 之外的实体类
     * （D-19）——共享库 JAR，或多模块构建的公共产物。插件自身 JAR 中的 {@code @Table} 类始终会
     * 被自动扫描；{@code additionalEntities} 是对该扫描结果的补充，而非替代。单参数的
     * {@link #connect(JavaPlugin)} 重载保持不变，以空数组委托到本方法。
     * <p>
     * <b>{@code additionalEntities} 会被校验，而非被信任（02-14）。</b>其中每一个类都必须存放在
     * {@code plugin} 自身的 classpath 上——要么是它自己的 JAR，要么是一个尚未被记录为归属于
     * 另一个当前已加载插件的 jar/模块。若某个类在结构上被确认属于另一个不同的插件（它自身的
     * JAR，或已记录归属于另一个插件），则会被拒绝，抛出包裹了
     * {@code com.ultikits.ultitools.exceptions.PluginModuleException} 的
     * {@link IllegalStateException}，且不会留下任何部分注册的状态——{@code additionalEntities}
     * 无法被用来获得另一个插件实体的可用 {@link DataOperator}，只能用来声明本插件确实拥有、
     * 但未打包在自身 JAR 中的实体。
     *
     * @param plugin            the external JavaPlugin to connect <br> 待连接的外部 JavaPlugin
     * @param additionalEntities entity classes owned by this plugin that live outside its own JAR
     *                            <br> 该插件拥有、但存放在自身 JAR 之外的实体类
     * @throws IllegalStateException if UltiTools is not loaded, or if an entity in
     *                                {@code additionalEntities} does not live on {@code plugin}'s
     *                                own classpath (02-14)
     * @since 6.3.0
     */
    public static void connect(JavaPlugin plugin, Class<?>... additionalEntities) {
        if (UltiTools.getInstance() == null) {
            throw new IllegalStateException("UltiTools is not loaded! Add 'depend: [UltiTools]' to your plugin.yml");
        }
        if (adapters.containsKey(plugin)) {
            Bukkit.getLogger().log(Level.WARNING,
                    "[UltiTools-API] Plugin " + plugin.getName() + " is already connected. Ignoring duplicate connect().");
            return;
        }

        ExternalPluginAdapter adapter = new ExternalPluginAdapter(plugin);
        adapters.put(plugin, adapter);

        try {
            UltiTools.getInstance().getPluginManager().registerExternal(adapter, additionalEntities);
            adapter.setConnected(true);
            Bukkit.getLogger().log(Level.INFO,
                    "[UltiTools-API] External plugin connected: " + plugin.getName() + " v" + adapter.getVersion());
        } catch (Exception e) {
            adapters.remove(plugin);
            throw new IllegalStateException("Failed to connect plugin " + plugin.getName() + " to UltiTools", e);
        }
    }

    /**
     * Disconnect an external Bukkit plugin from UltiTools framework.
     * <p>
     * 断开外部 Bukkit 插件与 UltiTools 框架的连接。
     *
     * @param plugin the external JavaPlugin to disconnect
     */
    public static void disconnect(JavaPlugin plugin) {
        ExternalPluginAdapter adapter = adapters.remove(plugin);
        if (adapter == null) {
            return;
        }
        try {
            if (UltiTools.getInstance() != null && UltiTools.getInstance().getPluginManager() != null) {
                UltiTools.getInstance().getPluginManager().unregisterExternal(adapter);
            }
        } finally {
            adapter.setConnected(false);
            try {
                Bukkit.getLogger().log(Level.INFO,
                        "[UltiTools-API] External plugin disconnected: " + plugin.getName());
            } catch (NullPointerException ignored) {
                // Bukkit.server may be null during shutdown or in test environments
            }
        }
    }

    /**
     * Get a DataOperator for an external plugin's data entity.
     * Data is stored in the plugin's own data folder. Refuses outright if {@code dataEntity} is
     * not registered to {@code plugin} (D-14) -- checked against the same {@link
     * com.ultikits.ultitools.manager.DataScope} minted for this plugin at {@code connect(...)}
     * time, via the same refusal {@code DataStore.getOperator(DataScope, Class)} builds, so the
     * exception type, error code, and message shape are identical regardless of which entry point
     * a caller reaches.
     * <p>
     * <strong>02-13 (CR-03):</strong> before this, {@code scope.owns(...)} was checked here inline
     * and this method then delegated to the deprecated {@code getOperator(File, Class)} overload
     * directly -- so {@code DataStore.getOperator(DataScope, Class)}, the method D-17/02-07 built
     * specifically as the credential-typed supported path, had zero real callers anywhere in the
     * framework. This now routes through it, so production actually uses the path it was built
     * for. {@code scope} is normally non-null by the time a connected plugin calls this (set by
     * {@code PluginManager.registerExternal} right after minting); the {@code null} fallback below
     * only covers an adapter that predates that wiring, where the deprecated overload's own {@code
     * checkOwnership(...)} call still refuses correctly on its own.
     * <p>
     * 获取外部插件的数据操作器。数据存储在插件自己的数据文件夹中。若 {@code dataEntity} 未向
     * {@code plugin} 注册则直接拒绝（D-14）——校验依据是该插件 {@code connect(...)} 时铸造的同一个
     * {@link com.ultikits.ultitools.manager.DataScope}，构造拒绝信息的方式与
     * {@code DataStore.getOperator(DataScope, Class)} 完全相同，因此无论调用方走到哪一个入口，
     * 异常类型、错误码和消息形态都一致。
     * <p>
     * <strong>02-13（CR-03）：</strong>在此之前，这里内联检查 {@code scope.owns(...)}，然后本方法
     * 直接委托给已废弃的 {@code getOperator(File, Class)} 重载——于是
     * {@code DataStore.getOperator(DataScope, Class)}，即 D-17/02-07 专门构建的、携带凭证的受支持
     * 路径，在整个框架里没有任何真实调用方。现在改为通过它路由，生产环境真正用上了它当初构建的
     * 目的。到已连接插件调用本方法时 {@code scope} 通常已经非空（由
     * {@code PluginManager.registerExternal} 在铸造之后立即设置）；下面的 {@code null} 回退分支只
     * 覆盖早于该接入逻辑的 adapter，此时已废弃重载自身的 {@code checkOwnership(...)} 调用依然能
     * 正确拒绝。
     *
     * @param plugin the external JavaPlugin
     * @param dataEntity the data entity class (must have @Table annotation)
     * @param <T> entity type
     * @return data operator
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataEntity} is not
     *         registered to {@code plugin}
     */
    public static <T extends BaseDataEntity<String>> DataOperator<T> getDataOperator(JavaPlugin plugin, Class<T> dataEntity) {
        ExternalPluginAdapter adapter = adapters.get(plugin);
        if (adapter == null) {
            throw new IllegalStateException("Plugin " + plugin.getName() + " is not connected to UltiTools");
        }
        com.ultikits.ultitools.manager.DataScope scope = adapter.getDataScope();
        if (scope != null) {
            return UltiTools.getInstance().getDataStore().getOperator(scope, dataEntity);
        }
        return UltiTools.getInstance().getDataStore().getOperator(adapter.getDataFolder(), dataEntity);
    }

    /**
     * Get the UltiTools EventBus for inter-plugin communication.
     * <p>
     * 获取 UltiTools 事件总线。
     *
     * @return the EventBus instance
     */
    public static EventBus getEventBus() {
        return UltiTools.getInstance().getEventBus();
    }

    /**
     * Check if a plugin is connected.
     * @param plugin the plugin to check
     * @return true if connected
     */
    public static boolean isConnected(JavaPlugin plugin) {
        return adapters.containsKey(plugin);
    }

    // --- Package-private methods for internal use and testing ---

    static ExternalPluginAdapter getAdapter(JavaPlugin plugin) {
        return adapters.get(plugin);
    }

    static void registerAdapter(JavaPlugin plugin, ExternalPluginAdapter adapter) {
        adapters.put(plugin, adapter);
    }

    static void removeAdapter(JavaPlugin plugin) {
        adapters.remove(plugin);
    }

    /**
     * Reset all state. For testing only.
     */
    static void reset() {
        adapters.clear();
    }

    /**
     * Auto-disconnect a plugin when it is disabled.
     * Called by the PluginDisableEvent listener in PluginManager.
     * <p>
     * 插件禁用时自动断开连接。由 PluginManager 中的 PluginDisableEvent 监听器调用。
     *
     * @param plugin the plugin being disabled
     * @since 6.2.2
     */
    public static void onPluginDisable(JavaPlugin plugin) {
        if (adapters.containsKey(plugin)) {
            disconnect(plugin);
        }
    }

    /**
     * Disconnect all external plugins. Called during UltiTools shutdown.
     * <p>
     * 断开所有外部插件的连接。在 UltiTools 关闭时调用。
     *
     * @since 6.2.2
     */
    public static void disconnectAll() {
        for (JavaPlugin plugin : new ArrayList<>(adapters.keySet())) {
            disconnect(plugin);
        }
    }
}
