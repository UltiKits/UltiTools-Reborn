package com.ultikits.ultitools.abstracts;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.*;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.Function;

public abstract class AbstractCommendExecutor implements TabExecutor {
    private final BiMap<String, Method> mappings = HashBiMap.create();
    private final BiMap<UUID, Method> SenderLock = HashBiMap.create();
    private final BiMap<UUID, Method> ServerLock = HashBiMap.create();
    private final BiMap<UUID, Method> CmdCoolDown = HashBiMap.create();

    @Getter
    private final Map<List<Class<?>>, Function<String, ?>> parsers = new HashMap<>();

    public AbstractCommendExecutor() {
        initParsers();
        scanCommandMappings();
    }

    public AbstractCommendExecutor getInstance() {
        return this;
    }

    @SuppressWarnings("deprecation")
    private void initParsers() {
        parsers.put(Arrays.asList(Boolean[].class, Boolean.class, boolean[].class, boolean.class), Boolean::parseBoolean);
        parsers.put(Arrays.asList(Double[].class, Double.class, double[].class, double.class), Double::parseDouble);
        parsers.put(Arrays.asList(Integer[].class, Integer.class, int[].class, int.class), Integer::parseInt);
        parsers.put(Arrays.asList(Float[].class, Float.class, float[].class, float.class), Float::parseFloat);
        parsers.put(Arrays.asList(Short[].class, Short.class, short[].class, short.class), Short::parseShort);
        parsers.put(Arrays.asList(Short[].class, Short.class, short[].class, short.class), Byte::parseByte);
        parsers.put(Arrays.asList(OfflinePlayer[].class, OfflinePlayer.class), Bukkit::getOfflinePlayer);
        parsers.put(Arrays.asList(Long[].class, Long.class, long[].class, long.class), Long::parseLong);
        parsers.put(Arrays.asList(Material[].class, Material.class), Material::getMaterial);
        parsers.put(Arrays.asList(Player[].class, Player.class), Bukkit::getPlayerExact);
        parsers.put(Arrays.asList(UUID[].class, UUID.class), UUID::fromString);
        parsers.put(Arrays.asList(String[].class, String.class), s -> s);
    }

    private <T> Function<String, T> getParser(Class<T> type) {
        //noinspection unchecked
        return (Function<String, T>) parsers.keySet().stream()
                .filter(classes -> classes.stream().anyMatch(clazz -> clazz.isAssignableFrom(type)))
                .findFirst()
                .map(parsers::get)
                .orElse(null);
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

    private static Map<String, String[]> getParams(String[] args, String format) {
        if (args.length == 0) {
            return Collections.emptyMap();
        }

        String[] formatArgs = format.split(" ");
        Map<String, String[]> params = new HashMap<>();
        List<String> paramList = new ArrayList<>();
        int index = 0;

        for (String arg : args) {
            String currentFormatArg = formatArgs[index];

            if (currentFormatArg.startsWith("<") && currentFormatArg.endsWith("...>")) {
                paramList.add(arg);
            } else if (currentFormatArg.startsWith("<") && currentFormatArg.endsWith(">")) {
                String paramName = currentFormatArg.substring(1, currentFormatArg.length() - 1);
                params.put(paramName, new String[]{arg});
            }

            index = (index + 1) % formatArgs.length;
        }

        if (!paramList.isEmpty()) {
            params.put(formatArgs[index].substring(1, formatArgs[index].length() - 1), paramList.toArray(new String[0]));
        }

        return params;
    }

    private Method matchMethod(String[] args) {
        if (args.length == 0) {
            return mappings.getOrDefault("", null);
        }
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            String format = entry.getKey();
            String[] formatArgs = format.split(" ");
            boolean match = true;

            for (int i = 0; i < formatArgs.length - 1; i++) {
                if (!matchesArgument(formatArgs[i], args[i])) {
                    match = false;
                    break;
                }
            }

            if (match && matchesLastArgument(formatArgs[formatArgs.length - 1], args[formatArgs.length - 1])) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean matchesArgument(String formatArg, String actualArg) {
        return formatArg.startsWith("<") && formatArg.endsWith(">") || formatArg.equalsIgnoreCase(actualArg);
    }

    private boolean matchesLastArgument(String formatArg, String actualArg) {
        if (formatArg.endsWith("...>")) {
            return true;
        }
        return matchesArgument(formatArg, actualArg);
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
        String permission = cmdExecutor.permission();

        if (permission.isEmpty() || sender.hasPermission(permission)) {
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

    private Object[] buildParams(String[] strings, Method method, CommandSender commandSender) {
        Map<String, String[]> params = getParams(strings, mappings.inverse().get(method));
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return new Object[0];
        }

        List<Object> paramList = new ArrayList<>();

        for (Parameter parameter : parameters) {
            Class<?> paramType = parameter.getType();

            if (paramType.equals(Player.class) || paramType.equals(CommandSender.class)) {
                boolean isCmdSenderAnnotationPresent = parameter.isAnnotationPresent(CmdSender.class);

                if (paramType.equals(Player.class) && commandSender instanceof Player) {
                    paramList.add(isCmdSenderAnnotationPresent ? commandSender : null);
                } else if (paramType.equals(CommandSender.class)) {
                    paramList.add(isCmdSenderAnnotationPresent ? commandSender : null);
                }

                continue;
            }

            if (parameter.isAnnotationPresent(CmdParam.class)) {
                CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
                String[] value = params.get(cmdParam.value());
                try {
                    paramList.add(parseType(value, paramType));
                } catch (Exception e) {
                    commandSender.sendMessage(ChatColor.RED + e.getMessage());
                    //noinspection CallToPrintStackTrace
                    e.printStackTrace();
                    return null;
                }
            } else {
                paramList.add(null);
            }
        }
        return paramList.toArray();
    }

    private <T> Object parseType(String[] value, Class<T> type) {
        Function<String, T> parser = getParser(type);
        if (type.isArray()) {
            Object array = Array.newInstance(type.getComponentType(), value.length);
            for (int i = 0; i < value.length; i++) {
                Array.set(array, i, parser.apply(value[i]));
            }
            return array;
        } else {
            return parser.apply(value[0]);
        }
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
        Object[] params = buildParams(strings, method, commandSender);
        if (params == null) {
            return true;
        }
        BukkitRunnable bukkitRunnable = new BukkitRunnable() {
            @Override
            public void run() {
                UsageLimit usageLimit = method.getAnnotation(UsageLimit.class);

                if (usageLimit != null) {
                    if (usageLimit.value().equals(UsageLimit.LimitType.ALL)) {
                        ServerLock.put(((Player) commandSender).getUniqueId(), method);
                    } else if (usageLimit.value().equals(UsageLimit.LimitType.SENDER) && commandSender instanceof Player) {
                        SenderLock.put(((Player) commandSender).getUniqueId(), method);
                    }
                }

                try {
                    setCoolDown(commandSender, method);
                    method.invoke(getInstance(), params);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    sendErrorMessage(commandSender, command);
                    throw new RuntimeException(e);
                } finally {
                    if (usageLimit != null) {
                        if (usageLimit.value().equals(UsageLimit.LimitType.ALL)) {
                            ServerLock.remove(((Player) commandSender).getUniqueId());
                        } else if (usageLimit.value().equals(UsageLimit.LimitType.SENDER) && commandSender instanceof Player) {
                            SenderLock.remove(((Player) commandSender).getUniqueId());
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
