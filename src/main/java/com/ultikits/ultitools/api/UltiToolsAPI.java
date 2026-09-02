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
 *
 * @since 6.2.2
 */
public final class UltiToolsAPI {
    private static final Map<JavaPlugin, ExternalPluginAdapter> adapters = new ConcurrentHashMap<>();

    private UltiToolsAPI() {}

    /**
     * Connect an external Bukkit plugin to UltiTools framework.
     * Scans the plugin's package for @Service, @CmdExecutor, @EventListener, etc.
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
     *
     * @param plugin            the external JavaPlugin to connect
     * @param additionalEntities entity classes owned by this plugin that live outside its own JAR
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
     *
     * @since 6.2.2
     */
    public static void disconnectAll() {
        for (JavaPlugin plugin : new ArrayList<>(adapters.keySet())) {
            disconnect(plugin);
        }
    }
}
