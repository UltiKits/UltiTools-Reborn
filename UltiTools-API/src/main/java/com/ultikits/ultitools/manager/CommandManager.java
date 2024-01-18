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

public class CommandManager {
    private final Map<UltiToolsPlugin, List<Command>> commandListMap = new HashMap<>();

    /**
     * 手动注册一个命令。仅用于注册被@CmdExecutor注解的类。会自动注入依赖。
     *
     * @param plugin      插件实例
     * @param clazz       命令执行器类
     * @param permission  权限
     * @param description 描述
     * @param aliases     别名
     */
    public void register(UltiToolsPlugin plugin, Class<? extends CommandExecutor> clazz, String permission, String description, String... aliases) {
        CommandExecutor commandExecutor = UltiTools.getInstance().getContext().getBean(clazz);
        register(plugin, commandExecutor, permission, description, aliases);
    }

    /**
     * 手动注册一个命令，不会被容器管理。不会自动注入依赖。
     *
     * @param plugin          插件实例
     * @param commandExecutor 命令执行器实例
     * @param permission      权限
     * @param description     描述
     * @param aliases         别名
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
     * 手动注册一个命令。仅用于注册被@CmdExecutor注解的类。会自动注入依赖。
     *
     * @param plugin 插件实例
     * @param clazz  命令执行器类
     */
    public void register(UltiToolsPlugin plugin, Class<? extends CommandExecutor> clazz) {
        CommandExecutor commandExecutor = plugin.getContext().getBean(clazz);
        register(plugin, commandExecutor);
    }

    /**
     * 手动注册一个命令，不会被容器管理。不会自动注入依赖。
     *
     * @param plugin          插件实例
     * @param commandExecutor 命令执行器实例
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

    public void registerAll(UltiToolsPlugin plugin, String packageName) {
        Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                CmdExecutor.class,
                packageName,
                Objects.requireNonNull(plugin.getClass().getClassLoader())
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

    public void registerAll(UltiToolsPlugin plugin) {
        for (String cmdBean : plugin.getContext().getBeanNamesForType(CommandExecutor.class)) {
            CommandExecutor commandExecutor = plugin.getContext().getBean(cmdBean, CommandExecutor.class);
            if (commandExecutor.getClass().getAnnotation(CmdExecutor.class).manualRegister()) continue;
            register(plugin, commandExecutor);
        }
    }

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

    public void unregister(String name) {
        PluginCommand command = getCommand(name, UltiTools.getInstance());
        command.unregister(getCommandMap());
    }

    public void unregisterAll(UltiToolsPlugin plugin) {
        List<Command> commands = commandListMap.get(plugin);
        if (commands == null) return;
        for (Command command : commands) {
            unregister(command.getName());
        }
    }

    public void close() {
        for (UltiToolsPlugin plugin : commandListMap.keySet()) {
            unregisterAll(plugin);
        }
    }

    @Deprecated
    public void register(CommandExecutor commandExecutor, String permission, String description, String... aliases) {
        PluginCommand command = getCommand(aliases[0], UltiTools.getInstance());
        command.setAliases(Arrays.asList(aliases));
        command.setPermission(permission);
        command.setDescription(description);
        getCommandMap().register(UltiTools.getInstance().getDescription().getName(), command);
        command.setExecutor(commandExecutor);
    }

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
        } catch (Exception e) {
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
        } catch (Exception e) {
            e.printStackTrace();
        }

        return commandMap;
    }

}
