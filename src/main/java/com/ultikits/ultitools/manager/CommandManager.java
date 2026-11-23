package com.ultikits.ultitools.manager;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.command.engine.CommandEngine;
import com.ultikits.ultitools.context.MergedAnnotationResolver;

/** Owns annotation command registrations without reflecting into Bukkit internals. */
public class CommandManager {
    private static final String CORE_OWNER = "UltiTools";
    private final CommandEngine engine;

    /** Creates the Paper-backed command manager. */
    public CommandManager() { this(new CommandEngine()); }

    /**
     * Internal constructor for an explicitly supplied engine.
     * @param engine the command engine
     */
    @ApiStatus.Internal
    public CommandManager(CommandEngine engine) { this.engine = engine; }

    /**
     * Registers an executor obtained from its module's own container.
     * @param plugin the owning module
     * @param clazz the executor class
     * @param permission the root permission
     * @param description the untranslated description
     * @param aliases the primary label followed by aliases
     */
    public void register(UltiToolsPlugin plugin, Class<? extends BaseCommandExecutor> clazz, String permission,
            String description, String... aliases) {
        BaseCommandExecutor executor = plugin.getContext().getBean(clazz);
        CmdExecutor annotation = annotation(executor);
        if (annotation != null) engine.register(plugin, plugin.getPluginName(), executor, plugin.i18n(description),
                permission, annotation.requireOp(), Arrays.asList(aliases));
    }

    /**
     * Registers a manually managed annotation executor from the module container.
     * @param plugin the owning module
     * @param clazz the executor class
     */
    public void register(UltiToolsPlugin plugin, Class<? extends BaseCommandExecutor> clazz) {
        registerExecutor(plugin, plugin.getPluginName(), plugin.getContext().getBean(clazz), plugin);
    }

    /**
     * Registers eligible module executors once per bean identity.
     * @param plugin the owning module
     */
    public void registerAll(UltiToolsPlugin plugin) {
        Set<BaseCommandExecutor> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : plugin.getContext().getBeanNamesForType(BaseCommandExecutor.class)) {
            BaseCommandExecutor executor = plugin.getContext().getBean(name, BaseCommandExecutor.class);
            if (executor == null || !handled.add(executor)) continue;
            CmdExecutor annotation = annotation(executor);
            if (annotation != null && !annotation.manualRegister()) registerExecutor(plugin, plugin.getPluginName(), executor, plugin);
        }
    }

    /**
     * Resolves module ownership by label, including aliases.
     * @param label the root label
     * @return the owning module, or null for core/external/unbound commands
     */
    @Nullable
    public UltiToolsPlugin getPluginByLabel(String label) {
        Object owner = engine.ownerByLabel(label);
        return owner instanceof UltiToolsPlugin plugin ? plugin : null;
    }

    /**
     * Hides a module's existing proxy trees and releases their live instances.
     * @param plugin the owning module
     */
    public void unregisterAll(UltiToolsPlugin plugin) { engine.unregister(plugin); }

    /** Releases all live command bindings. */
    public void close() { engine.close(); }

    /**
     * Registers a framework-owned command during enable.
     * @param executor the executor
     */
    public void registerCoreCommand(BaseCommandExecutor executor) { registerExecutor(CORE_OWNER, CORE_OWNER, executor, null); }

    /**
     * Registers external executors once per bean identity, using raw descriptions.
     * @param adapter the external plugin adapter
     */
    public void registerAllExternal(ExternalPluginAdapter adapter) {
        if (adapter.getContext() == null) return;
        Set<BaseCommandExecutor> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : adapter.getContext().getBeanNamesForType(BaseCommandExecutor.class)) {
            BaseCommandExecutor executor = adapter.getContext().getBean(name, BaseCommandExecutor.class);
            if (executor == null || !handled.add(executor)) continue;
            CmdExecutor annotation = annotation(executor);
            if (annotation != null && !annotation.manualRegister()) registerExecutor(adapter.getPluginName(), adapter.getPluginName(), executor, null);
        }
    }

    /**
     * Releases only this external plugin's command bindings.
     * @param pluginName the external owner name
     */
    public void unregisterAllExternal(String pluginName) { engine.unregister(pluginName); }

    private void registerExecutor(Object owner, String ownerName, BaseCommandExecutor executor, UltiToolsPlugin module) {
        CmdExecutor annotation = annotation(executor);
        if (annotation == null) return;
        String description = module == null ? annotation.description() : module.i18n(annotation.description());
        engine.register(owner, ownerName, executor, description, annotation.permission(), annotation.requireOp(), Arrays.asList(annotation.alias()));
    }

    @Nullable
    private static CmdExecutor annotation(BaseCommandExecutor executor) {
        CmdExecutor annotation = executor == null ? null : MergedAnnotationResolver.find(executor.getClass(), CmdExecutor.class);
        if (annotation == null) Bukkit.getLogger().severe("Cannot register executor without @CmdExecutor: "
                + (executor == null ? "null" : executor.getClass().getName()));
        return annotation;
    }
}
