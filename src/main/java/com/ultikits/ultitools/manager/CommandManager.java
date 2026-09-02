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
 * <p>
 * 命令管理器
 */
public class CommandManager {
    private final Map<UltiToolsPlugin, List<Command>> commandListMap = new HashMap<>();
    private final Map<String, List<Command>> externalCommandMap = new HashMap<>();

    /**
     * Manually register a command. Only used to register classes annotated with @CmdExecutor. Dependencies will be injected automatically.
     * <p>
     * 手动注册一个命令。仅用于注册被@CmdExecutor注解的类。会自动注入依赖。
     *
     * @param plugin      UltiTools Plugin instance <br> 模块实例
     * @param clazz       Command executor class <br> 命令执行器类
     * @param permission  Permission <br> 权限
     * @param description Description <br> 描述
     * @param aliases     Aliases <br> 别名
     */
    public void register(UltiToolsPlugin plugin, Class<? extends CommandExecutor> clazz, String permission, String description, String... aliases) {
        CommandExecutor commandExecutor = UltiTools.getInstance().getDependenceManagers().getContext().getBean(clazz);
        register(plugin, commandExecutor, permission, description, aliases);
    }

    /**
     * Manually register a command, will not be managed by the container. Dependencies will not be injected automatically.
     * <p>
     * 手动注册一个命令，不会被容器管理。不会自动注入依赖。
     *
     * @param plugin          UltiTools Plugin instance <br> 模块实例
     * @param commandExecutor Command executor instance <br> 命令执行器实例
     * @param permission      Permission <br> 权限
     * @param description     Description <br> 描述
     * @param aliases         Aliases <br> 别名
     */
    private void register(UltiToolsPlugin plugin, CommandExecutor commandExecutor, String permission, String description, String... aliases) {
        registerCommandDirect(commandExecutor, permission, plugin.i18n(description), aliases);
        PluginCommand command = getCommand(aliases[0], UltiTools.getInstance());
        List<Command> commands = commandListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        if (!commands.contains(command)) {
            commands.add(command);
        }
    }

    /**
     * Manually register a command. Only used to register classes annotated with @CmdExecutor. Dependencies will be injected automatically.
     * <p>
     * 手动注册一个命令。仅用于注册被@CmdExecutor注解的类。会自动注入依赖。
     *
     * @param plugin UltiTools Plugin instance <br> 模块实例
     * @param clazz  Command executor class <br> 命令执行器类
     */
    public void register(UltiToolsPlugin plugin, Class<? extends CommandExecutor> clazz) {
        CommandExecutor commandExecutor = plugin.getContext().getBean(clazz);
        register(plugin, commandExecutor);
    }

    /**
     * Manually register a command, will not be managed by the container. Dependencies will not be injected automatically.
     * <p>
     * 手动注册一个命令，不会被容器管理。不会自动注入依赖。
     *
     * @param plugin          UltiTools Plugin instance <br> 模块实例
     * @param commandExecutor Command executor instance <br> 命令执行器实例
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
     * 直接向 Bukkit 的命令表注册 {@code commandExecutor}，不涉及任何插件容器——是
     * {@link #registerCoreCommand(CommandExecutor)}、
     * {@link #registerExternalCommand(String, CommandExecutor, CmdExecutor)}，以及
     * {@link #register(UltiToolsPlugin, CommandExecutor)} 中未注解类回退路径共用的底层原语。
     *
     * @param commandExecutor Command executor instance <br> 命令执行器实例
     * @param permission      Permission <br> 权限
     * @param description     Description <br> 描述
     * @param aliases         Aliases <br> 别名
     */
    private void registerCommandDirect(CommandExecutor commandExecutor, String permission, String description, String... aliases) {
        PluginCommand command = getCommand(aliases[0], UltiTools.getInstance());
        command.setAliases(Arrays.asList(aliases));
        command.setPermission(permission);
        command.setDescription(description);
        getCommandMap().register(UltiTools.getInstance().getDescription().getName(), command);
        command.setExecutor(commandExecutor);
    }

    /**
     * Resolves {@code commandExecutor}'s {@code @CmdExecutor} annotation (if present) and
     * dispatches to {@link #registerCommandDirect(CommandExecutor, String, String, String...)};
     * logs and does nothing otherwise.
     * <p>
     * 解析 {@code commandExecutor} 的 {@code @CmdExecutor} 注解（如果存在）并派发给
     * {@link #registerCommandDirect(CommandExecutor, String, String, String...)}；否则记录日志
     * 并且不做任何事。
     *
     * @param commandExecutor Command executor instance <br> 命令执行器实例
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
     * <p>
     *
     * @param plugin UltiTools Plugin instance <br> 模块实例
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
     * <p>
     * 通过命令获取模块实例
     *
     * @param command Command <br> 命令
     * @return UltiTools plugin <br> 模块实例
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
     * @param name Command name <br> 命令名
     */
    public void unregister(String name) {
        PluginCommand command = getCommand(name, UltiTools.getInstance());
        command.unregister(getCommandMap());
    }

    /**
     * @param plugin UltiTools Plugin instance <br> 模块实例
     */
    public void unregisterAll(UltiToolsPlugin plugin) {
        List<Command> commands = commandListMap.get(plugin);
        if (commands == null) return;
        for (Command command : commands) {
            unregister(command.getName());
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
     * <p>
     * 为不属于特定插件模块的核心UltiTools命令注册命令。
     * 此方法专门用于主UltiTools插件的命令。
     *
     * @param commandExecutor Command executor instance <br> 命令执行器实例
     */
    public void registerCoreCommand(CommandExecutor commandExecutor) {
        registerCommandDirect(commandExecutor);
    }

    /**
     * Register all @CmdExecutor commands from an external plugin's IoC container.
     * Uses raw description (no i18n) since external plugins don't have UltiTools i18n.
     * <p>
     * 注册外部插件 IoC 容器中所有 @CmdExecutor 命令。
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
     * Unregister all commands for an external plugin.
     * <p>
     * 注销外部插件的所有命令。
     *
     * @param pluginName the external plugin name
     * @since 6.2.2
     */
    public void unregisterAllExternal(String pluginName) {
        List<Command> commands = externalCommandMap.remove(pluginName);
        if (commands == null) return;
        for (Command command : commands) {
            command.unregister(getCommandMap());
        }
    }

    private void registerExternalCommand(String pluginName, CommandExecutor executor, CmdExecutor annotation) {
        registerCommandDirect(executor, annotation.permission(), annotation.description(), annotation.alias());
        PluginCommand command = getCommand(annotation.alias()[0], UltiTools.getInstance());
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
