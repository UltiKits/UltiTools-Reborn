package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.listeners.UiQuitListener;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.chest.ChestBackend;
import com.ultikits.ultitools.ui.chest.ChestListener;
import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.dialog.DialogBackend;
import com.ultikits.ultitools.ui.dialog.DialogPresenter;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Framework-owned assembly and per-module-copy UI lifetime.
 * All methods must be called on the server thread.
 */
@ApiStatus.Internal
public final class UiManager implements AutoCloseable {
    private final UiRuntime runtime;
    private final ChestListener chestListener;
    private final UiQuitListener quitListener;
    private final Map<UltiToolsPlugin, UiService> services = new IdentityHashMap<>();

    /** Retains the runtime and the listeners that belong to it. */
    public UiManager(UiRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        chestListener = new ChestListener(runtime);
        quitListener = new UiQuitListener(runtime);
    }

    /** Assembles both backends on the framework scheduler, never on a module's scheduler. */
    public static UiManager create(Scheduler frameworkScheduler, Messages frameworkMessages, ItemRenderer items,
                                   Logger logger, Thread serverThread, DialogPresenter presenter) {
        FrameworkContainerRuntime containers = new FrameworkContainerRuntime(
                () -> serverThread.equals(Thread.currentThread()), frameworkMessages::send);
        UiRuntime runtime = new UiRuntime(frameworkScheduler, containers, logger, serverThread);
        runtime.register(new DialogBackend(frameworkMessages, presenter, logger, runtime::ownerName));
        runtime.register(new ChestBackend(runtime, items, frameworkMessages, frameworkScheduler));
        return new UiManager(runtime);
    }

    /** Issues exactly one service per module instance; names do not identify a module copy. */
    public UiService serviceFor(UltiToolsPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return services.computeIfAbsent(plugin, owner -> runtime.service(owner.getPluginName()));
    }

    /** Ends a copy's sessions before its tasks, storage or unload hook can be retired. */
    public void unload(UltiToolsPlugin plugin) {
        UiService service = services.remove(plugin);
        if (service != null) { runtime.unload(service); }
    }

    /** Installs event routing before any module can display a screen. */
    public void registerListeners(Plugin bukkitPlugin) {
        Bukkit.getPluginManager().registerEvents(chestListener, bukkitPlugin);
        Bukkit.getPluginManager().registerEvents(quitListener, bukkitPlugin);
    }

    UiRuntime runtime() { return runtime; }

    /** Starts the common storage-wait budget before the framework unloads its first module. */
    public void beginShutdown() { runtime.beginShutdown(); }

    /** Explicitly closed before root-container destruction retires the framework scheduler. */
    @Override
    public void close() {
        try { runtime.close(); } finally { services.clear(); }
    }
}
