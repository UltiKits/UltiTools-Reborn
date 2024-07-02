package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.utils.PackageScanUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * Command manager.
 * <p>
 * 命令管理器
 */
public class CommandManager {
    private final Map<UltiToolsPlugin, List<Command>> commandListMap = new HashMap<>();

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
        register(commandExecutor, permission, plugin.i18n(description), aliases);
        PluginCommand command = getCommand(aliases[0], UltiTools.getInstance());
        commandListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        List<Command> commands = commandListMap.get(plugin);
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
        } else {
            Bukkit.getLogger().warning("CommandExecutor " + clazz.getName() + " is not annotated with @CmdExecutor, please use legacy method to register command.");
        }
        plugin.getContext().getAutowireCapableBeanFactory().autowireBean(commandExecutor);
        register(commandExecutor);
    }

    /**
     * Register all classes annotated with @CmdExecutor in the specified package. Dependencies will be injected automatically.
     * <p>
     * 注册指定包下所有被@CmdExecutor注解的类。会自动注入依赖。
     *
     * @param plugin      UltiTools Plugin instance <br> 模块实例
     * @param packageName Package name <br> 包名
     */
    public void registerAll(UltiToolsPlugin plugin, String packageName) {
        Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                CmdExecutor.class,
                packageName,
                UltiTools.getInstance().getUltiToolsClassLoader()
        );
        for (Class<?> clazz : classes) {
            try {
                AbstractCommendExecutor commandExecutor =
                        (AbstractCommendExecutor) clazz.getDeclaredConstructor().newInstance();
                register(plugin, commandExecutor);
            } catch (InstantiationException |
                     InvocationTargetException |
                     IllegalAccessException |
                     NoSuchMethodException ignored) {
            }
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
            if (commandExecutor.getClass().getAnnotation(CmdExecutor.class).manualRegister()) continue;
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
     * Don't use this method to register commands, use {@link #register(UltiToolsPlugin, Class, String, String, String...)} instead.
     * <p>
     * 不要使用此方法注册命令，使用{@link #register(UltiToolsPlugin, Class, String, String, String...)}代替。
     *
     * @param commandExecutor Command executor instance <br> 命令执行器实例
     * @param permission      Permission <br> 权限
     * @param description     Description <br> 描述
     * @param aliases         Aliases <br> 别名
     */
    @Deprecated
    public void register(CommandExecutor commandExecutor, String permission, String description, String... aliases) {
        PluginCommand command = getCommand(aliases[0], UltiTools.getInstance());
        command.setAliases(Arrays.asList(aliases));
        command.setPermission(permission);
        command.setDescription(description);
        getCommandMap().register(UltiTools.getInstance().getDescription().getName(), command);
        command.setExecutor(commandExecutor);
    }

    /**
     * Don't use this method to register commands, use {@link #register(UltiToolsPlugin, Class)} instead.
     * <p>
     * 不要使用此方法注册命令，使用{@link #register(UltiToolsPlugin, Class)}代替。
     *
     * @param commandExecutor Command executor instance <br> 命令执行器实例
     */
    @Deprecated
    public void register(CommandExecutor commandExecutor) {
        Class<? extends CommandExecutor> clazz = commandExecutor.getClass();

        if (clazz.isAnnotationPresent(CmdExecutor.class)) {
            CmdExecutor cmdExecutor = clazz.getAnnotation(CmdExecutor.class);
            register(commandExecutor, cmdExecutor.permission(), cmdExecutor.description(), cmdExecutor.alias());
        } else {
            Bukkit.getLogger().warning("CommandExecutor " + clazz.getName() + " is not annotated with @CmdExecutor, please use legacy method to register command.");
        }
    }

    private PluginCommand getCommand(String name, Plugin plugin) {
        PluginCommand command = null;

        try {
            Constructor<PluginCommand> c = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            c.setAccessible(true);

            command = c.newInstance(name, plugin);
        } catch (Exception | Error e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }

        return commandMap;
    }

}
