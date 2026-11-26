package com.ultikits.ultitools.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.command.BrigadierCommand;
import com.ultikits.ultitools.command.engine.CommandEngine;
import com.ultikits.ultitools.command.engine.CommandCompilationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.context.SimpleContainer;

/** Owns annotation command registrations without reflecting into Bukkit internals. */
public class CommandManager {
    private static final String CORE_OWNER = "UltiTools";
    private final CommandEngine engine;
    private final List<BaseCommandExecutor> coreExecutors = new ArrayList<>();

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
        if (annotation != null) registerOwned(plugin, plugin.getPluginName(), executor, () ->
                engine.register(plugin, plugin.getPluginName(), executor, moduleDescription(plugin, description), permission,
                        annotation.requireOp(), Arrays.asList(aliases.length == 0 ? annotation.alias() : aliases)));
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
        registerBeans(plugin, plugin.getPluginName(), plugin.getContext(), plugin);
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

    /** Installs command event recovery beside the framework's other enable-time listeners. */
    @ApiStatus.Internal
    public void registerInternalListeners() { engine.registerInternalListeners(); }

    /** Releases all live command bindings. */
    public void close() {
        engine.close();
        coreExecutors.clear();
    }

    /** Drains explicitly registered core executors before any framework service shuts down. */
    @ApiStatus.Internal
    public void awaitCoreAsyncCommands() {
        CommandRuntime.awaitAsyncCommands(coreExecutors);
    }

    /**
     * Registers a framework-owned command during enable.
     * @param executor the executor
     */
    public void registerCoreCommand(BaseCommandExecutor executor) {
        CmdExecutor annotation = annotation(executor);
        if (annotation != null) {
            engine.register(CORE_OWNER, CORE_OWNER, executor, annotation.description(),
                    annotation.permission(), annotation.requireOp(), Arrays.asList(annotation.alias()));
            coreExecutors.add(executor);
        }
    }

    /**
     * Registers external executors once per bean identity, using raw descriptions.
     * @param adapter the external plugin adapter
     */
    public void registerAllExternal(ExternalPluginAdapter adapter) {
        if (adapter.getContext() != null) {
            registerBeans(adapter.getPluginName(), adapter.getPluginName(), adapter.getContext(), null);
        }
    }

    private void registerBeans(Object owner, String ownerName, SimpleContainer context, UltiToolsPlugin module) {
        Set<Object> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> type : new Class<?>[]{BaseCommandExecutor.class, BrigadierCommand.class}) {
            for (String name : context.getBeanNamesForType(type)) {
                Object bean = context.getBean(name, Object.class);
                if (bean == null || !handled.add(bean)) continue;
                if (bean instanceof BaseCommandExecutor executor) {
                    CmdExecutor annotation = annotation(executor);
                    if (annotation != null && !annotation.manualRegister()) registerExecutor(owner, ownerName, executor, module);
                } else if (bean instanceof BrigadierCommand command) {
                    registerOwned(owner, ownerName, command, () -> engine.register(owner, ownerName, command));
                }
            }
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
        String description = module == null ? annotation.description() : moduleDescription(module, annotation.description());
        registerOwned(owner, ownerName, executor, () -> engine.register(owner, ownerName, executor, description,
                annotation.permission(), annotation.requireOp(), Arrays.asList(annotation.alias())));
    }

    private static String moduleDescription(UltiToolsPlugin module, String description) {
        return module.moduleTexts().flatMap(texts -> texts.resolve(description)).orElse(description);
    }

    private void registerOwned(Object owner, String ownerName, Object executor, Runnable registration) {
        try {
            registration.run();
        } catch (CommandCompilationException failure) {
            engine.unregister(owner);
            String message = "Cannot load module " + ownerName + ": executor " + executor.getClass().getName()
                    + ": " + failure.getMessage() + ". Change @CmdMapping formats to use distinct literal prefixes, "
                    + "one parameter name/type per position, terminal <name...> arrays, and registered custom type parsers.";
            Bukkit.getLogger().severe(message);
            throw new PluginModuleException(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE, message, failure);
        }
    }

    @Nullable
    private static CmdExecutor annotation(BaseCommandExecutor executor) {
        CmdExecutor annotation = executor == null ? null : MergedAnnotationResolver.find(executor.getClass(), CmdExecutor.class);
        if (annotation == null) Bukkit.getLogger().severe("Cannot register executor without @CmdExecutor: "
                + (executor == null ? "null" : executor.getClass().getName()));
        return annotation;
    }
}
