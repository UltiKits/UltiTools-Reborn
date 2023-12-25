package com.ultikits.ultitools.abstracts;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

public abstract class AbstractCommendExecutor implements TabExecutor {
    private final BiMap<String, Method> mappings = HashBiMap.create();
    private final BiMap<UUID, Method> SenderLock = HashBiMap.create();
    private final BiMap<UUID, Method> ServerLock = HashBiMap.create();
    private final BiMap<UUID, Method> CmdCoolDown = HashBiMap.create();

    public AbstractCommendExecutor() {
        scanCommandMappings();
    }

    public AbstractCommendExecutor getInstance() {
        return this;
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
            if (formatArgs[i].startsWith("[") && formatArgs[i].endsWith("]")) {
                params.put(formatArgs[i].substring(1, formatArgs[i].length() - 1), args[i]);
            }
        }
        return params;
    }

    private Method matchMethod(String[] args) {
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            String format = entry.getKey();
            if (format.isEmpty() && args.length == 0) {
                return entry.getValue();
            }
            String[] formatArgs = format.split(" ");
            String lastArg = formatArgs[formatArgs.length - 1];
            boolean match;
            if (lastArg.startsWith("[") && lastArg.endsWith("]")) {
                if (formatArgs.length - args.length == 1) {
                    continue;
                }
                match = true;
                for (int i = 0; i < formatArgs.length - 1; i++) {
                    String formatArg = formatArgs[i];
                    String actualArg = args[i];
                    if (formatArg.startsWith("<") && formatArg.endsWith(">")) {
                        continue;
                    }
                    if (!formatArg.equalsIgnoreCase(actualArg)) {
                        match = false;
                        break;
                    }
                }
            } else {
                if (formatArgs.length != args.length) {
                    continue;
                }
                match = true;
                for (int i = 0; i < formatArgs.length; i++) {
                    String formatArg = formatArgs[i];
                    String actualArg = args[i];
                    if (formatArg.startsWith("<") && formatArg.endsWith(">")) {
                        continue;
                    }
                    if (!formatArg.equalsIgnoreCase(actualArg)) {
                        match = false;
                        break;
                    }
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

    private boolean checkLock(CommandSender sender, Method method) {
        if (!method.isAnnotationPresent(UsageLimit.class)) {
            return false;
        }
        if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.SENDER)) {
            if (!(sender instanceof Player || method.getAnnotation(UsageLimit.class).ContainConsole())) {
                return false;
            }
            if (sender instanceof Player && SenderLock.get(((Player) sender).getUniqueId()).equals(method)) {
                sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("请先等待上一条命令执行完毕！"));
                return true;
            }
            return false;
        }
        if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.ALL)) {
            if (!(sender instanceof Player || method.getAnnotation(UsageLimit.class).ContainConsole())) {
                return false;
            }
            if (sender instanceof Player && ServerLock.get(((Player) sender).getUniqueId()).equals(method)) {
                sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("请先等待其他玩家发送的命令执行完毕！"));
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean checkCD(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return false;
        }
        Player player = (Player) sender;
        if (CmdCoolDown.containsKey(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("操作频繁，请稍后再试"));
            return true;
        }
        return false;
    }

    private Object[] parseParams(String[] strings, Method method, CommandSender commandSender) {
        Map<String, String> params = getParams(strings, mappings.inverse().get(method));
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }
        List<Object> ParamList = new ArrayList<>();
        for (Parameter parameter : parameters) {
            if (parameter.getType().equals(Player.class)) {
                Player player = (Player) commandSender;
                ParamList.add(parameter.isAnnotationPresent(CmdSender.class) ? player : null);
                continue;
            }
            if (parameter.getType().equals(CommandSender.class)) {
                ParamList.add(parameter.isAnnotationPresent(CmdSender.class) ? commandSender : null);
                continue;
            }
            if (parameter.isAnnotationPresent(CmdParam.class)) {
                CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
                String value = params.get(cmdParam.value());
                try {
                    if (parameter.getType() == float.class || parameter.getType() == Float.class) {
                        ParamList.add(Float.parseFloat(value));
                        continue;
                    }
                    if (parameter.getType() == double.class || parameter.getType() == Double.class) {
                        ParamList.add(Double.parseDouble(value));
                        continue;
                    }
                    if (parameter.getType() == int.class || parameter.getType() == Integer.class) {
                        ParamList.add(Integer.parseInt(value));
                        continue;
                    }
                } catch (NumberFormatException e) {
                    commandSender.sendMessage(
                            ChatColor.RED + String.format(
                                    UltiTools.getInstance().i18n("参数 '%s' 格式错误：'%s' 不是一个有效的 %s 类型"),
                                    cmdParam.value(), value, parameter.getType().getName()
                            ));
                    return null;
                }
                if (parameter.getType() == OfflinePlayer.class) {
                    ParamList.add(Bukkit.getOfflinePlayer(value));
                    continue;
                }
                if (parameter.getType() == Player.class) {
                    Player player = Bukkit.getPlayerExact(value);
                    if (player == null) {
                        commandSender.sendMessage(
                                ChatColor.RED + String.format(
                                        UltiTools.getInstance().i18n("玩家 \"%s\" 未找到"),
                                        cmdParam.value(), value, parameter.getType().getName()
                                ));
                        return null;
                    }
                    ParamList.add(player);
                }
                ParamList.add(value);
            } else {
                ParamList.add(null);
            }
            if (parameter.isAnnotationPresent(OptionalParam.class)) {
                if (parameter.getType() == Map.class) {
                    String format = method.getAnnotation(CmdMapping.class).format();
                    String OptionParams = params.get(format.split(" ")[format.split(" ").length - 1]);
                    ParamList.add(parseOptionalParams(OptionParams));
                } else {
                    ParamList.add(null);
                }
            }
        }
        return ParamList.toArray();
    }

    private Map<String, List<String>> parseOptionalParams(String OptionalParam) {
        Map<String, List<String>> resultMap = new HashMap<>();

        String[] optionGroups = OptionalParam.split(";");
        for (String optionGroup : optionGroups) {
            String[] parts = optionGroup.split("=");
            if (parts.length == 2) {
                String optionName = parts[0];
                String[] arguments = parts[1].split(",");

                resultMap.put(optionName, Arrays.asList(arguments));
            }
        }

        return resultMap;
    }

    private void setCoolDown(CommandSender commandSender, Method method) {
        if (!(commandSender instanceof Player)) {
            return;
        }
        CmdCD cmdCD = method.getAnnotation(CmdCD.class);
        if (cmdCD == null) {
            return;
        }
        if (cmdCD.value() == 0) {
            return;
        }
        Player player = (Player) commandSender;
        CmdCoolDown.put(player.getUniqueId(), method);
        new BukkitRunnable() {
            int time = cmdCD.value();

            @Override
            public void run() {
                if (time > 0) {
                    time--;
                } else {
                    CmdCoolDown.remove(player.getUniqueId(), method);
                    this.cancel();
                }
            }
        }.runTaskTimerAsynchronously(UltiTools.getInstance(), 0L, 20L);
    }


    abstract protected void handleHelp(CommandSender sender);

    protected void sendErrorMessage(CommandSender sender, Command command) {
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    protected List<String> suggest(Player player, String[] strings) {
        List<String> completions = new ArrayList<>();

        if (strings.length == 0) {
            for (String format : mappings.keySet()) {
                String arg = format.split(" ")[0];
                completions.add(arg);
            }
        } else {
            String partialCommand = strings[strings.length - 1];
            for (String cmd : mappings.keySet()) {
                if (cmd.startsWith(partialCommand)) {
                    completions.add(cmd);
                }
            }
        }
        return completions;
    }

    protected String getHelpCommand() {
        return "help";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (strings.length == 1 && getHelpCommand().equals(strings[0])) {
            handleHelp(commandSender);
            return true;
        }
        Method method = matchMethod(strings);
        if (method == null) {
            commandSender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("未知指令，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
            handleHelp(commandSender);
            return true;
        }
        if (!checkSender(commandSender) || !checkSender(commandSender, method)) {
            return true;
        }
        if (!checkPermission(commandSender) || !checkPermission(commandSender, method)) {
            return true;
        }
        if (!checkOp(commandSender) || !checkOp(commandSender, method)) {
            return true;
        }
        if (checkLock(commandSender, method)) {
            return true;
        }
        if (checkCD(commandSender)) {
            return true;
        }
        Object[] params = parseParams(strings, method, commandSender);
        BukkitRunnable bukkitRunnable = new BukkitRunnable() {
            @Override
            public void run() {
                if (method.isAnnotationPresent(UsageLimit.class)) {
                    if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.ALL)) {
                        ServerLock.put(((Player) commandSender).getUniqueId(), method);
                    }
                    if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.SENDER)) {
                        if (commandSender instanceof Player) {
                            SenderLock.put(((Player) commandSender).getUniqueId(), method);
                        }
                    }
                }
                try {
                    setCoolDown(commandSender, method);
                    method.invoke(getInstance(), params);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    sendErrorMessage(commandSender, command);
                    throw new RuntimeException(e);
                } finally {
                    if (method.isAnnotationPresent(UsageLimit.class)) {
                        if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.ALL)) {
                            ServerLock.remove(((Player) commandSender).getUniqueId());
                        }
                        if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.SENDER)) {
                            if (commandSender instanceof Player) {
                                SenderLock.remove(((Player) commandSender).getUniqueId());
                            }
                        }
                    }
                }
            }
        };
        if (method.isAnnotationPresent(RunAsync.class)) {
            bukkitRunnable.runTaskAsynchronously(UltiTools.getInstance());
        } else {
            bukkitRunnable.runTask(UltiTools.getInstance());
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
        return suggest((Player) commandSender, strings);
    }
}
