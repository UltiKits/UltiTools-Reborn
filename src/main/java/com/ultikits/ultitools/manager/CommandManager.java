package com.ultikits.ultitools.manager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.context.MergedAnnotationResolver;

/**
 * Command manager.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Constructs Bukkit's PluginCommand and reaches its private CommandMap field -- see 08-GATE05-TRIAGE.md
public class CommandManager {
    private final Map<UltiToolsPlugin, List<Command>> commandListMap = new HashMap<>();
    private final Map<String, List<Command>> externalCommandMap = new HashMap<>();

    /**
     * Manually register a command. Only used to register classes annotated with @CmdExecutor. Dependencies will be injected automatically.
     *
     * @param plugin      UltiTools Plugin instance
     * @param clazz       Command executor class
     * @param permission  Permission
     * @param description Description
     * @param aliases     Aliases
     */
    public void register(UltiToolsPlugin plugin, Class<? extends CommandExecutor> clazz, String permission, String description, String... aliases) {
        // #347: resolve from the MODULE's own container, matching the two-argument overload
        // below (register(plugin, clazz)) -- not from the core container. A module author who
        // registers a bean in their own container and calls this overload with an explicit
        // permission/description/aliases must get their own instance back, not a core-container
        // bean of the same type that happens to shadow it.
        CommandExecutor commandExecutor = plugin.getContext().getBean(clazz);
        register(plugin, commandExecutor, permission, description, aliases);
    }

    /**
     * Manually register a command, will not be managed by the container. Dependencies will not be injected automatically.
     *
     * @param plugin          UltiTools Plugin instance
     * @param commandExecutor Command executor instance
     * @param permission      Permission
     * @param description     Description
     * @param aliases         Aliases
     */
    private void register(UltiToolsPlugin plugin, CommandExecutor commandExecutor, String permission, String description, String... aliases) {
        PluginCommand command = registerCommandDirect(commandExecutor, permission, plugin.i18n(description), aliases);
        List<Command> commands = commandListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        if (!commands.contains(command)) {
            commands.add(command);
        }
    }

    /**
     * Manually register a command. Only used to register classes annotated with @CmdExecutor. Dependencies will be injected automatically.
     *
     * @param plugin UltiTools Plugin instance
     * @param clazz  Command executor class
     */
    public void register(UltiToolsPlugin plugin, Class<? extends CommandExecutor> clazz) {
        CommandExecutor commandExecutor = plugin.getContext().getBean(clazz);
        register(plugin, commandExecutor);
    }

    /**
     * Manually register a command, will not be managed by the container. Dependencies will not be injected automatically.
     *
     * @param plugin          UltiTools Plugin instance
     * @param commandExecutor Command executor instance
     */
    private void register(UltiToolsPlugin plugin, CommandExecutor commandExecutor) {
        Class<? extends CommandExecutor> clazz = commandExecutor.getClass();

        if (clazz.isAnnotationPresent(CmdExecutor.class)) {
            CmdExecutor cmdExecutor = clazz.getAnnotation(CmdExecutor.class);
            register(plugin, commandExecutor, cmdExecutor.permission(), plugin.i18n(cmdExecutor.description()), cmdExecutor.alias());
            return;
        }
        Bukkit.getLogger().warning("CommandExecutor " + clazz.getName() + " is not annotated with @CmdExecutor, please use legacy method to register command.");
        plugin.getContext().getAutowireCapableBeanFactory().autowireBean(commandExecutor);
        registerCommandDirect(commandExecutor);
    }

    /**
     * Registers {@code commandExecutor} with Bukkit's command map directly, with no plugin
     * container involvement -- the primitive behind {@link #registerCoreCommand(CommandExecutor)},
     * {@link #registerExternalCommand(String, CommandExecutor, CmdExecutor)}, and the
     * unannotated-class fallback in {@link #register(UltiToolsPlugin, CommandExecutor)}.
     * <p>
     * Returns the exact {@link PluginCommand} instance handed to {@link
     * CommandMap#register(String, Command)} so callers that need to unregister it later
     * (e.g. {@link #register(UltiToolsPlugin, CommandExecutor, String, String, String...)},
     * {@link #registerExternalCommand(String, CommandExecutor, CmdExecutor)}) can store and
     * unregister THAT instance directly, rather than reconstructing a separate {@link
     * #getCommand(String, Plugin)} decoy that was never actually registered (Codex review on
     * PR #457, plan 16-02: a name/namespaced-key relookup at unregister time can resolve a
     * DIFFERENT module's command if two modules share both the framework's fallback prefix
     * and the same primary alias).
     *
     * @param commandExecutor Command executor instance
     * @param permission      Permission
     * @param description     Description
     * @param aliases         Aliases
     * @return the actual registered {@link PluginCommand} instance
     */
    private PluginCommand registerCommandDirect(CommandExecutor commandExecutor, String permission, String description, String... aliases) {
        PluginCommand command = getCommand(aliases[0], UltiTools.getInstance());
        command.setAliases(Arrays.asList(aliases));
        command.setPermission(permission);
        command.setDescription(description);
        getCommandMap().register(UltiTools.getInstance().getDescription().getName(), command);
        command.setExecutor(commandExecutor);
        return command;
    }

    /**
     * Resolves {@code commandExecutor}'s {@code @CmdExecutor} annotation (if present) and
     * dispatches to {@link #registerCommandDirect(CommandExecutor, String, String, String...)};
     * logs and does nothing otherwise. The registered command is not tracked for later
     * unregistration -- this is the core-command path ({@link #registerCoreCommand(CommandExecutor)}),
     * whose commands live for the whole plugin lifetime.
     *
     * @param commandExecutor Command executor instance
     */
    private void registerCommandDirect(CommandExecutor commandExecutor) {
        Class<? extends CommandExecutor> clazz = commandExecutor.getClass();

        if (clazz.isAnnotationPresent(CmdExecutor.class)) {
            CmdExecutor cmdExecutor = clazz.getAnnotation(CmdExecutor.class);
            registerCommandDirect(commandExecutor, cmdExecutor.permission(), cmdExecutor.description(), cmdExecutor.alias());
        } else {
            Bukkit.getLogger().warning("CommandExecutor " + clazz.getName() + " is not annotated with @CmdExecutor, please use legacy method to register command.");
        }
    }

    /**
     * Register all classes annotated with @CmdExecutor in the specified package. Dependencies will be injected automatically.
     *
     * @param plugin UltiTools Plugin instance
     */
    public void registerAll(UltiToolsPlugin plugin) {
        for (String cmdBean : plugin.getContext().getBeanNamesForType(CommandExecutor.class)) {
            CommandExecutor commandExecutor = plugin.getContext().getBean(cmdBean, CommandExecutor.class);
            if (commandExecutor == null) continue;
            CmdExecutor annotation = MergedAnnotationResolver.find(commandExecutor.getClass(), CmdExecutor.class);
            if (annotation == null || annotation.manualRegister()) continue;
            register(plugin, commandExecutor);
        }
    }

    /**
     * Get the plugin instance by command.
     *
     * @param command Command
     * @return UltiTools plugin
     */
    public UltiToolsPlugin getPluginByCommand(Command command) {
        for (Map.Entry<UltiToolsPlugin, List<Command>> entry : commandListMap.entrySet()) {
            for (Command cmd : entry.getValue()) {
                if (cmd.getName().equals(command.getName())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Unregisters the live command labelled {@code name} from Bukkit, including its entries
     * in the command map's known-commands table.
     * <p>
     * This name-only overload is a best-effort public entry point for callers that hold
     * nothing but a label (no {@link Command} reference to hand back). It resolves the
     * actual currently-registered {@link Command} via its <em>namespaced</em> key ({@code
     * fallbackPrefix:name}) rather than the bare {@code name} -- {@code
     * SimpleCommandMap.register} lets a later, overridable registration steal the bare-label
     * slot, so {@code getCommand(name)} could resolve a <em>different plugin's</em> command
     * after such a takeover, while the namespaced key is put unconditionally regardless of
     * any bare-label conflict.
     * <p>
     * That said, the namespaced key is only unique to <em>this framework's own</em>
     * registrations: two different UltiTools modules sharing the same primary alias also
     * share this framework's fallback prefix, so {@code fallbackPrefix:name} collides
     * between them too, and this overload cannot tell them apart (Codex review on PR #457,
     * plan 16-02). {@link #unregisterAll(UltiToolsPlugin)} and {@link
     * #unregisterAllExternal(String)} do not have this ambiguity -- they hold the actual
     * registered {@link Command} instance from {@link #commandListMap}/{@link
     * #externalCommandMap} and unregister it directly via {@link #unregisterCommand(CommandMap,
     * Command)}, bypassing name resolution entirely.
     *
     * @param name Command name
     */
    public void unregister(String name) {
        CommandMap commandMap = getCommandMap();
        if (commandMap == null) {
            return;
        }
        Command realCommand = commandMap.getCommand(namespacedKey(name));
        if (realCommand == null) {
            return;
        }
        unregisterCommand(commandMap, realCommand);
    }

    /**
     * Unregisters the exact {@code command} instance from Bukkit: purges its entries from
     * the command map's known-commands table, then clears its own registration state.
     * <p>
     * This is the airtight form -- no name or key resolution is involved, so it cannot
     * mis-resolve a different module's command the way {@link #unregister(String)}'s
     * namespaced-key lookup can when two modules share both the framework's fallback prefix
     * and a primary alias.
     *
     * @param commandMap the live Bukkit command map
     * @param command    the specific, already-resolved command instance to unregister
     */
    private void unregisterCommand(CommandMap commandMap, Command command) {
        removeFromKnownCommands(commandMap, command);
        command.unregister(commandMap);
    }

    /**
     * Builds the namespaced known-commands key ({@code fallbackPrefix:label}) that {@link
     * #registerCommandDirect(CommandExecutor, String, String, String...)}'s {@link
     * CommandMap#register(String, Command)} call always populates for {@code label},
     * regardless of whether the bare label slot was won or lost to a bare-label conflict.
     * The fallback prefix mirrors {@code registerCommandDirect}'s own: {@code
     * UltiTools.getInstance().getDescription().getName()}.
     *
     * @param label the command's bare label or alias
     * @return the namespaced key that uniquely identifies this framework's own registration
     */
    private String namespacedKey(String label) {
        return UltiTools.getInstance().getDescription().getName() + ":" + label;
    }

    /**
     * Removes every entry from {@code commandMap}'s known-commands table whose value is
     * exactly {@code command} (reference identity, not key/label text matching).
     * <p>
     * {@link Command#unregister(CommandMap)} only clears the command's own {@code
     * commandMap}/{@code label}/{@code activeAliases} fields (verified by reading its
     * bytecode against this Paper version) -- it never touches {@link
     * CommandMap#getKnownCommands()}. {@code SimpleCommandMap.register(String, String,
     * Command)} puts a command under FOUR kinds of keys pointing at the same instance: the
     * bare label, every bare alias, {@code fallbackPrefix:label}, and {@code
     * fallbackPrefix:alias} for each alias. Left alone, every one of those keys keeps
     * resolving to the unregistered module's executor after unload (measured on a real
     * Paper 1.21.11 server: {@code /upm uninstall} followed by the module's own command
     * still executing -- PR #457 real-machine UAT, plan 16-02).
     * <p>
     * Matching by reference identity rather than by key text is deliberate: Bukkit allows a
     * later-registered overridable command to take over an earlier command's bare label
     * slot, in which case that key's VALUE is a different {@link Command} instance and must
     * be left untouched -- only entries whose value is {@code command} itself are removed.
     *
     * @param commandMap the live Bukkit command map
     * @param command    the specific command instance to purge
     */
    private void removeFromKnownCommands(CommandMap commandMap, Command command) {
        commandMap.getKnownCommands().values().removeIf(known -> known == command);
    }

    /**
     * Unregisters every command this framework registered for {@code plugin}, using the
     * actual registered {@link Command} instances tracked in {@link #commandListMap} (see
     * {@link #register(UltiToolsPlugin, CommandExecutor, String, String, String...)}) --
     * not a name-based relookup, which could mis-resolve a different module's command if
     * two modules share a primary alias under this framework's shared fallback prefix.
     *
     * @param plugin UltiTools Plugin instance
     */
    public void unregisterAll(UltiToolsPlugin plugin) {
        List<Command> commands = commandListMap.get(plugin);
        if (commands == null) return;
        CommandMap commandMap = getCommandMap();
        if (commandMap == null) return;
        for (Command command : commands) {
            unregisterCommand(commandMap, command);
        }
    }

    /**
     * Unregister all commands.
     */
    public void close() {
        for (UltiToolsPlugin plugin : commandListMap.keySet()) {
            unregisterAll(plugin);
        }
    }

    /**
     * Register command for core UltiTools commands that don't belong to a specific plugin module.
     * This method is specifically for commands that are part of the main UltiTools plugin.
     *
     * @param commandExecutor Command executor instance
     */
    public void registerCoreCommand(CommandExecutor commandExecutor) {
        registerCommandDirect(commandExecutor);
    }

    /**
     * Register all @CmdExecutor commands from an external plugin's IoC container.
     * Uses raw description (no i18n) since external plugins don't have UltiTools i18n.
     *
     * @param adapter the external plugin adapter
     * @since 6.2.2
     */
    public void registerAllExternal(ExternalPluginAdapter adapter) {
        if (adapter.getContext() == null) return;
        for (String cmdBean : adapter.getContext().getBeanNamesForType(CommandExecutor.class)) {
            CommandExecutor executor = adapter.getContext().getBean(cmdBean, CommandExecutor.class);
            if (executor == null) continue;
            CmdExecutor annotation = MergedAnnotationResolver.find(executor.getClass(), CmdExecutor.class);
            if (annotation == null || annotation.manualRegister()) continue;
            registerExternalCommand(adapter.getPluginName(), executor, annotation);
        }
    }

    /**
     * Unregister all commands for an external plugin, using the actual registered {@link
     * Command} instances tracked in {@link #externalCommandMap} (see {@link
     * #registerExternalCommand(String, CommandExecutor, CmdExecutor)}) -- not a name-based
     * relookup, for the same reason as {@link #unregisterAll(UltiToolsPlugin)}.
     *
     * @param pluginName the external plugin name
     * @since 6.2.2
     */
    public void unregisterAllExternal(String pluginName) {
        List<Command> commands = externalCommandMap.remove(pluginName);
        if (commands == null) return;
        CommandMap commandMap = getCommandMap();
        if (commandMap == null) return;
        for (Command command : commands) {
            unregisterCommand(commandMap, command);
        }
    }

    private void registerExternalCommand(String pluginName, CommandExecutor executor, CmdExecutor annotation) {
        PluginCommand command = registerCommandDirect(executor, annotation.permission(), annotation.description(), annotation.alias());
        externalCommandMap.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(command);
    }

    private PluginCommand getCommand(String name, Plugin plugin) {
        PluginCommand command = null;

        try {
            Constructor<PluginCommand> c = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            c.setAccessible(true);

            command = c.newInstance(name, plugin);
        } catch (Exception | Error e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to create PluginCommand: " + name, e);
        }

        return command;
    }

    private CommandMap getCommandMap() {
        CommandMap commandMap = null;

        try {
            if (Bukkit.getPluginManager() instanceof SimplePluginManager) {
                Field f = SimplePluginManager.class.getDeclaredField("commandMap");
                f.setAccessible(true);

                commandMap = (CommandMap) f.get(Bukkit.getPluginManager());
            }
        } catch (Exception | Error e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to get CommandMap", e);
        }

        return commandMap;
    }

}
