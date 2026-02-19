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
            UltiTools.getInstance().getPluginManager().registerExternal(adapter);
            adapter.setConnected(true);
            Bukkit.getLogger().log(Level.INFO,
                    "[UltiTools-API] External plugin connected: " + plugin.getName() + " v" + adapter.getVersion());
        } catch (Exception e) {
            adapters.remove(plugin);
            throw new RuntimeException("Failed to connect plugin " + plugin.getName() + " to UltiTools", e);
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
     * Data is stored in the plugin's own data folder.
     * <p>
     * 获取外部插件的数据操作器。数据存储在插件自己的数据文件夹中。
     *
     * @param plugin the external JavaPlugin
     * @param dataEntity the data entity class (must have @Table annotation)
     * @param <T> entity type
     * @return data operator
     */
    public static <T extends BaseDataEntity<String>> DataOperator<T> getDataOperator(JavaPlugin plugin, Class<T> dataEntity) {
        ExternalPluginAdapter adapter = adapters.get(plugin);
        if (adapter == null) {
            throw new IllegalStateException("Plugin " + plugin.getName() + " is not connected to UltiTools");
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
