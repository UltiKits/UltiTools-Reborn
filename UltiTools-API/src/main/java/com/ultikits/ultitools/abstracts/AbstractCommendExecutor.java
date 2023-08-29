package com.ultikits.ultitools.abstracts;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

public abstract class AbstractCommendExecutor implements TabExecutor {
    private final BiMap<String, Method> mappings = HashBiMap.create();

    public AbstractCommendExecutor() {
        scanCommandMappings();
    }

    private void scanCommandMappings() {
        Class<? extends AbstractCommendExecutor> clazz = this.getClass();
        Method[] methods = clazz.getDeclaredMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(CmdMapping.class)) {
                mappings.put(method.getAnnotation(CmdMapping.class).format(), method);
            }
        }
    }

    private Map<String, String> getParams(String[] args, String format) {
        String[] formatArgs = format.split(" ");
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < formatArgs.length; i++) {
            if (formatArgs[i].startsWith("<") && formatArgs[i].endsWith(">")) {
                params.put(formatArgs[i].substring(1, formatArgs[i].length() - 1), args[i]);
            }
        }
        return params;
    }

    private Method matchMethod(String[] args) {
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            String format = entry.getKey();
            String[] formatArgs = format.split(" ");
            if (formatArgs.length != args.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < formatArgs.length; i++) {
                String formatArg = formatArgs[i];
                String actualArg = args[i];
                if (formatArg.startsWith("<") && formatArg.endsWith(">")) {
                    continue;
                }
                if (!formatArg.equals(actualArg)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean checkSender(CommandSender sender) {
        Class<? extends AbstractCommendExecutor> clazz = this.getClass();
        if (!clazz.isAnnotationPresent(CmdTarget.class)) {
            return true;
        }
        CmdTarget cmdTarget = clazz.getAnnotation(CmdTarget.class);
        if (cmdTarget.value().equals(CmdTarget.CmdTargetType.PLAYER) && !(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只有游戏内可以执行这个指令！"));
            return false;
        }
        if (cmdTarget.value().equals(CmdTarget.CmdTargetType.CONSOLE) && sender instanceof Player) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只可以在后台执行这个指令！"));
            return false;
        }
        return true;
    }

    private boolean checkSender(CommandSender sender, Method method) {
        if (!method.isAnnotationPresent(CmdTarget.class)) {
            return true;
        }
        CmdTarget cmdTarget = method.getAnnotation(CmdTarget.class);
        if (cmdTarget.value().equals(CmdTarget.CmdTargetType.PLAYER) && !(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只有游戏内可以执行这个指令！"));
            return false;
        }
        if (cmdTarget.value().equals(CmdTarget.CmdTargetType.CONSOLE) && sender instanceof Player) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只可以在后台执行这个指令！"));
            return false;
        }
        return true;
    }

    private boolean checkPermission(CommandSender sender) {
        Class<? extends AbstractCommendExecutor> clazz = this.getClass();
        if (!clazz.isAnnotationPresent(CmdExecutor.class)) {
            return true;
        }
        CmdExecutor cmdExecutor = clazz.getAnnotation(CmdExecutor.class);
        if (cmdExecutor.permission().isEmpty()) {
            return true;
        }
        String permission = cmdExecutor.permission();
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("你没有权限执行这个指令！"));
        return false;
    }

    private boolean checkPermission(CommandSender sender, Method method) {
        if (!method.isAnnotationPresent(CmdMapping.class)) {
            return true;
        }
        CmdMapping cmdMapping = method.getAnnotation(CmdMapping.class);
        if (cmdMapping.permission().isEmpty()) {
            return true;
        }
        String permission = cmdMapping.permission();
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("你没有权限执行这个指令！"));
        return false;
    }

    private boolean checkOp(CommandSender sender) {
        Class<? extends AbstractCommendExecutor> clazz = this.getClass();
        if (!clazz.isAnnotationPresent(CmdExecutor.class)) {
            return true;
        }
        CmdExecutor cmdExecutor = clazz.getAnnotation(CmdExecutor.class);
        if (cmdExecutor.requireOp() && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("你没有权限执行这个指令！"));
            return false;
        }
        return true;
    }

    private boolean checkOp(CommandSender sender, Method method) {
        if (!method.isAnnotationPresent(CmdMapping.class)) {
            return true;
        }
        CmdMapping cmdMapping = method.getAnnotation(CmdMapping.class);
        if (cmdMapping.requireOp() && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("你没有权限执行这个指令！"));
            return false;
        }
        return true;
    }

    abstract protected void handleHelp(CommandSender sender);

    protected void sendErrorMessage(CommandSender sender, Command command) {
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    protected List<String> suggest(String[] strings) {
        List<String> suggestions = new ArrayList<>();

        for (String format : mappings.keySet()) {
            String arg = format.split(" ")[strings.length - 1];
            suggestions.add(arg);
        }
        return suggestions;
    }

    protected String getHelpCommand(){
        return "help";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if(!checkSender(commandSender)){
            return true;
        }
        if (!checkPermission(commandSender)) {
            return true;
        }
        if (!checkOp(commandSender)) {
            return true;
        }
        if (strings.length > 0 && getHelpCommand().equals(strings[0])){
            handleHelp(commandSender);
            return true;
        }
        Method method = matchMethod(strings);
        if (method == null) {
            commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("未知指令！"));
            handleHelp(commandSender);
            return true;
        }
        Map<String, String> params = getParams(strings, mappings.inverse().get(method));
        Parameter[] parameters = method.getParameters();
        ArrayList<Object> ParamList = new ArrayList<>();
        if (parameters.length == 0) {
            try {
                if (!checkSender(commandSender, method)) {
                    return true;
                }
                if (!checkPermission(commandSender, method)) {
                    return true;
                }
                if (!checkOp(commandSender, method)) {
                    return true;
                }
                method.invoke(this);
            } catch (IllegalAccessException | InvocationTargetException e) {
                sendErrorMessage(commandSender, command);
                throw new RuntimeException(e);
            }
            return true;
        }
        for (Parameter parameter : parameters) {
            if (parameter.getType().equals(Player.class)) {
                Player player = (Player) commandSender;
                ParamList.add(player);
                continue;
            }
            if (parameter.getType().equals(CommandSender.class)) {
                ParamList.add(commandSender);
                continue;
            }
            if (parameter.getType().equals(Command.class)) {
                ParamList.add(command);
                continue;
            }
            if (parameter.isAnnotationPresent(CmdParam.class)) {
                CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
                String value = params.get(cmdParam.value());
                ParamList.add(value);
            } else {
                ParamList.add(null);
            }
        }
        try {
            if (!checkSender(commandSender, method)) {
                return true;
            }
            if (!checkPermission(commandSender, method)) {
                return true;
            }
            if (!checkOp(commandSender, method)) {
                return true;
            }
            method.invoke(this, ParamList.toArray());
        } catch (IllegalAccessException | InvocationTargetException e) {
            sendErrorMessage(commandSender, command);
            throw new RuntimeException(e);
        }
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        Class<? extends AbstractCommendExecutor> clazz = this.getClass();
        if (!(commandSender instanceof Player)) {
            return null;
        }
        if (!clazz.isAnnotationPresent(CmdTarget.class)) {
            return null;
        }
        CmdTarget cmdTarget = clazz.getAnnotation(CmdTarget.class);
        if (cmdTarget.value().equals(CmdTarget.CmdTargetType.CONSOLE)) {
            return null;
        }
        return suggest(strings);
    }
}
