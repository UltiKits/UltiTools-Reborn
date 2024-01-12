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

    public void register (UltiToolsPlugin plugin, CommandExecutor commandExecutor, String permission, String description, String... aliases) {
        PluginCommand command = getCommand(aliases[0], UltiTools.getInstance());
        command.setAliases(Arrays.asList(aliases));
        command.setPermission(permission);
        command.setDescription(description);
        getCommandMap().register(UltiTools.getInstance().getDescription().getName(), command);
        command.setExecutor(commandExecutor);
        commandListMap.computeIfAbsent(plugin, k -> new ArrayList<>());
        List<Command> commands = commandListMap.get(plugin);
        if (!commands.contains(command)) {
            commands.add(command);
        }
    }

    public void register(UltiToolsPlugin plugin, CommandExecutor commandExecutor) {
        Class<? extends CommandExecutor> clazz = commandExecutor.getClass();

        if (clazz.isAnnotationPresent(CmdExecutor.class)) {
            CmdExecutor cmdExecutor = clazz.getAnnotation(CmdExecutor.class);
            register(commandExecutor, cmdExecutor.permission(), cmdExecutor.description(), cmdExecutor.alias());
        } else {
            Bukkit.getLogger().warning("CommandExecutor " + clazz.getName() + " is not annotated with @CmdExecutor, please use legacy method to register command.");
        }
        if (plugin.getContext().getBeanNamesForType(CommandExecutor.class).length == 0) {
            plugin.getContext().getAutowireCapableBeanFactory().autowireBean(commandExecutor);
        }
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
        for(String cmdBean : plugin.getContext().getBeanNamesForType(CommandExecutor.class)) {
            CommandExecutor commandExecutor = plugin.getContext().getBean(cmdBean, CommandExecutor.class);
            register(plugin, commandExecutor);
        }
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
