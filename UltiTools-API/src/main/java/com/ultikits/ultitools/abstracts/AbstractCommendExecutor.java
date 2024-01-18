package com.ultikits.ultitools.abstracts;

import cn.hutool.core.util.ReflectUtil;
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

import java.lang.annotation.Annotation;
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

    private Map<String, String[]> getParams(String[] args, String format) {
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
                continue;
            }
            if (currentFormatArg.startsWith("<") && currentFormatArg.endsWith(">")) {
                String paramName = currentFormatArg.substring(1, currentFormatArg.length() - 1);
                params.put(paramName, new String[]{arg});
            }

            index++;
        }

        if (!paramList.isEmpty()) {
            params.put(formatArgs[index].substring(1, formatArgs[index].length() - 1), paramList.toArray(new String[0]));
        }

        return params;
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

    protected List<String> suggest(Player player, Command command, String[] strings) {
        List<String> completions = new ArrayList<>();
        if (strings.length == 1) {
            for (Map.Entry<String, Method> entry : mappings.entrySet()) {
                Method method = entry.getValue();
                String format = entry.getKey();
                if (!checkPermission(player, method) || !checkOp(player, method)) {
                    continue;
                }
                String arg = format.split(" ")[0];
                if (arg.startsWith("<") && arg.endsWith(">")) {
                    getArgSuggestion(player, command, strings, method, arg, completions);
                    continue;
                }
                completions.add(arg);
            }
            return completions;
        } else {
            List<Method> methodsByArg = getMethodsByArg(player, String.join(" ", strings));
            for (Method method : methodsByArg) {
                String formatByMethod = getFormatByMethod(method);
                String arg = getArgAt(formatByMethod, strings.length - 1);
                if (arg.startsWith("<") && arg.endsWith(">")) {
                    getArgSuggestion(player, command, strings, method, arg, completions);
                } else {
                    for (String format : mappings.keySet()) {
                        String[] args = format.split(" ");
                        if (args.length < strings.length) {
                            continue;
                        }
                        String sug = args[strings.length - 1];
                        if (sug.startsWith("<") && sug.endsWith(">")) {
                            continue;
                        }
                        completions.add(sug);
                    }
                }
            }
        }
        return completions;
    }

    private void getArgSuggestion(Player player, Command command, String[] strings, Method method, String arg, List<String> completions) {
        String suggestName = getSuggestName(method, arg.substring(1, arg.length() - 1));
        if (suggestName == null) {
            return;
        }
        Method[] suggestMethod = getSuggestMethodByName(suggestName);
        UltiToolsPlugin pluginByCommand = UltiTools.getInstance().getCommandManager().getPluginByCommand(command);
        if (suggestMethod == null || suggestMethod.length == 0) {
            completions.add(pluginByCommand.i18n(suggestName));
            return;
        }
        Class<?> declaringClass = suggestMethod[0].getDeclaringClass();
        Collection<?> suggestObject;
        if (this.getClass() != declaringClass) {
            Object bean = pluginByCommand.getContext().getBean(declaringClass);
            suggestObject = invokeSuggestMethod(bean, suggestMethod[0], player, command, strings);
        } else {
            suggestObject = invokeSuggestMethod(this, suggestMethod[0], player, command, strings);
        }
        if (suggestObject != null) {
            for (Object o : suggestObject) {
                completions.add(o.toString());
            }
        }
    }

    private String getFormatByMethod(Method method) {
        return mappings.inverse().get(method);
    }

    private String getArgAt(String format, int index) {
        String[] args = format.split(" ");
        if (args.length <= index) {
            return "";
        }
        return args[index];
    }

    private String getSuggestName(Method method, String paramName) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (Annotation[] annotations : parameterAnnotations) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof CmdParam) {
                    CmdParam cmdParam = (CmdParam) annotation;
                    if (!paramName.equals(cmdParam.value())) {
                        continue;
                    }
                    return cmdParam.suggest();
                }
            }
        }
        return null;
    }

    private Method[] getSuggestMethodByName(String suggestName) {
        if (suggestName.endsWith("()")) {
            suggestName = suggestName.substring(0, suggestName.length() - 2);
        }
        Method[] localSuggestMethod = getMethod(suggestName);
        if (localSuggestMethod != null && localSuggestMethod.length > 0) return localSuggestMethod;
        if (this.getClass().isAnnotationPresent(CmdSuggest.class)) {
            Class<?>[] value = this.getClass().getAnnotation(CmdSuggest.class).value();
            for (Class<?> clazz : value) {
                Method[] method = getMethod(clazz, suggestName);
                if (method != null) {
                    return method;
                }
            }
        }
        return null;
    }

    @Nullable
    private Method[] getMethod(String suggestName) {
        return getMethod(this.getClass(), suggestName);
    }

    @Nullable
    private Method[] getMethod(Class<?> clazz, String suggestName) {
        return ReflectUtil.getMethods(clazz, method -> method.getName().equals(suggestName));
    }

    private Collection<?> invokeSuggestMethod(Object object, Method method, Player player, Command command, String[] strings) {
        Parameter[] parameters = method.getParameters();
        Object[] params = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Class<?> type = parameter.getType();
            if (type.equals(Player.class)) {
                params[i] = player;
            } else if (type.equals(Command.class)) {
                params[i] = command;
            } else if (type.equals(String[].class)) {
                params[i] = strings;
            }
        }
        return ReflectUtil.invoke(object, method, params);
    }

    private List<Method> getMethodsByArg(Player player, String command) {
        List<Method> perfectMatch = new ArrayList<>();
        List<Method> methods = new ArrayList<>();
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            Method method = entry.getValue();
            String format = entry.getKey();
            if (format.startsWith(command.substring(0, command.lastIndexOf(" ")))) {
                if (checkPermission(player, method) && checkOp(player, method)) {
                    perfectMatch.add(method);
                }
            } else {
                String[] formatArgs = format.split(" ");
                String[] commandArgs = command.split(" ");
                boolean match = true;
                for (int i = 0; i < Math.min(commandArgs.length, formatArgs.length); i++) {
                    if (!matchesArgument(formatArgs[i], commandArgs[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    if (checkPermission(player, method) && checkOp(player, method)) {
                        methods.add(method);
                    }
                }
            }
        }
        if (!perfectMatch.isEmpty()) {
            return perfectMatch;
        } else {
            return methods;
        }
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
        return suggest((Player) commandSender, command, strings);
    }
}
