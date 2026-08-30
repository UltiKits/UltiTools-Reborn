package com.ultikits.ultitools.abstracts;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandTabCompletionDispatch;
import com.ultikits.ultitools.abstracts.command.validation.CmdTargetComposition;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.utils.ReflectionUtil;

import lombok.Getter;

/**
 * This abstract class represents a command executor.
 * It implements the TabExecutor interface from the Bukkit API.
 * <p>
 * 这个抽象类代表了一个命令执行器。
 * 它实现了Bukkit API中的TabExecutor接口。
 *
 * @see TabExecutor
 * @see com.ultikits.ultitools.abstracts.command.BaseCommandExecutor
 * @deprecated This class is maintained for backward compatibility. 
 *             Use {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor} instead,
 *             which provides better architecture with Chain of Responsibility pattern.
 *             <p>
 *             此类仅为向后兼容而保留。
 *             请使用 {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor}，
 *             它提供了使用责任链模式的更好架构。
 * @since 6.2.0 deprecated
 */
@Deprecated(since = "6.2.0", forRemoval = true)
public abstract class AbstractCommandExecutor implements TabExecutor {
    private final BiMap<String, Method> mappings = HashBiMap.create();
    private final Map<UUID, Method> senderLock = new ConcurrentHashMap<>();
    private final Map<UUID, Method> serverLock = new ConcurrentHashMap<>();
    private final Map<UUID, Method> cmdCoolDown = new ConcurrentHashMap<>();

    @Getter
    private final Map<List<Class<?>>, Function<String, ?>> parsers = new LinkedHashMap<>();

    /**
     * Constructor that initializes parsers and scans command mappings.
     * <p>
     * 构造函数，初始化解析器并扫描命令映射。
     */
    public AbstractCommandExecutor() {
        initParsers();
        scanCommandMappings();
    }

    /**
     * Checks whether the sender matches the given effective target type.
     * <p>
     * Takes a resolved {@link CmdTarget.CmdTargetType} rather than a {@link CmdTarget} instance
     * so the caller does not have to synthesize a fake annotation just to carry a value that
     * {@link CmdTargetComposition#resolve} already computed. This is the only place that
     * actually compares a sender against a target type; everything above it is resolution.
     * <p>
     * 检查发送者是否匹配已解析出的有效目标类型。参数是已解析的枚举值而非注解实例，
     * 因为 CmdTargetComposition#resolve 已经算出这个值，调用方不必再合成一个假注解。
     *
     * @param sender     The sender of the command. <br> 命令的发送者。
     * @param targetType The effective target type, already resolved. <br> 已解析的有效目标类型。
     * @return Whether the sender is valid. <br> 发送者是否有效。
     */
    private boolean checkCmdTargetType(CommandSender sender, CmdTarget.CmdTargetType targetType) {
        if (targetType == CmdTarget.CmdTargetType.PLAYER && !(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只有游戏内可以执行这个指令！"));
            return false;
        }
        if (targetType == CmdTarget.CmdTargetType.CONSOLE && sender instanceof Player) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只可以在后台执行这个指令！"));
            return false;
        }
        return true;
    }

    /**
     * Gets a map of parameter match from a command.
     * <p>
     * 从命令中获取参数匹配的映射。
     *
     * @param args   The arguments of the command. <br> 命令的参数。
     * @param format The format of the command. <br> 命令的格式。
     * @return The map of the parameters. <br> 参数的映射。
     */
    private Map<String, String[]> getParams(String[] args, String format) {
        if (args.length == 0) {
            return Collections.emptyMap();
        }

        String[] formatArgs = format.split(" ");
        Map<String, String[]> params = new HashMap<>();
        List<String> paramList = new ArrayList<>();
        int index = 0;
        for (String arg : args) {
            if (index >= formatArgs.length) {
                break;
            }
            String currentFormatArg = formatArgs[index];
            if (currentFormatArg.startsWith("<")) {
                String paramName = currentFormatArg.substring(1, currentFormatArg.length() - (currentFormatArg.endsWith("...>") ? 4 : 1));
                if (currentFormatArg.endsWith("...>")) {
                    paramList.add(arg);
                } else {
                    params.put(paramName, new String[]{arg});
                }
            }
            if (!currentFormatArg.endsWith("...>")) {
                index++;
            }
        }

        if (index < formatArgs.length && formatArgs[index].endsWith("...>")) {
            // Put varargs even if empty
            String varargFormat = formatArgs[index];
            String varargName = varargFormat.substring(1, varargFormat.length() - 4);
            params.put(varargName, paramList.toArray(new String[0]));
        }

        return params;
    }

    /**
     * Gets the instance of the command executor.
     * <p>
     * 获取命令执行器的实例。
     *
     * @return The instance of the command executor. <br> 命令执行器的实例。
     */
    public AbstractCommandExecutor getInstance() {
        return this;
    }

    /**
     * Initializes the parsers.
     * <p>
     * 初始化解析器。
     */
    @SuppressWarnings("deprecation")
    private void initParsers() {
        parsers.put(Arrays.asList(Boolean[].class, Boolean.class, boolean[].class, boolean.class), Boolean::parseBoolean);
        parsers.put(Arrays.asList(Double[].class, Double.class, double[].class, double.class), Double::parseDouble);
        parsers.put(Arrays.asList(Integer[].class, Integer.class, int[].class, int.class), Integer::parseInt);
        parsers.put(Arrays.asList(Float[].class, Float.class, float[].class, float.class), Float::parseFloat);
        parsers.put(Arrays.asList(Short[].class, Short.class, short[].class, short.class), Short::parseShort);
        parsers.put(Arrays.asList(Byte[].class, Byte.class, byte[].class, byte.class), Byte::parseByte);
        parsers.put(Arrays.asList(Player[].class, Player.class), Bukkit::getPlayer);
        parsers.put(Arrays.asList(OfflinePlayer[].class, OfflinePlayer.class), Bukkit::getOfflinePlayer);
        parsers.put(Arrays.asList(Long[].class, Long.class, long[].class, long.class), Long::parseLong);
        parsers.put(Arrays.asList(Material[].class, Material.class), Material::getMaterial);
        parsers.put(Arrays.asList(UUID[].class, UUID.class), UUID::fromString);
        parsers.put(Arrays.asList(String[].class, String.class), s -> s);
    }

    /**
     * Gets the parser of the parameter.
     * <p>
     * 获取参数的解析器。
     *
     * @param type The type of the parameter. <br> 参数的类型。
     * @param <T>  The type of the parameter. <br> 参数的类型。
     * @return The parser of the parameter. <br> 参数的解析器。
     */
    @SuppressWarnings("unchecked")
    private <T> Function<String, T> getParser(Class<T> type) {
        return (Function<String, T>) parsers.keySet().stream()
                .filter(classes -> classes.stream().anyMatch(clazz -> clazz.isAssignableFrom(type)))
                .findFirst()
                .map(parsers::get)
                .orElse(null);
    }

    /**
     * Scans the command mappings.
     * <p>
     * 扫描命令映射。
     */
    private void scanCommandMappings() {
        // Walk the hierarchy: on an AOP proxy, getDeclaredMethods() returns only the intercepted
        // overrides, so scanning it directly would drop every other @CmdMapping on the class. See
        // issue #190. putIfAbsent keeps the most specific override's format when a subclass reuses
        // a parent's format string - see BaseCommandExecutor.scanCommandMappings for the same call.
        for (Method method : ReflectionUtil.getAllMethods(this.getClass())) {
            if (method.isAnnotationPresent(CmdMapping.class)) {
                mappings.putIfAbsent(method.getAnnotation(CmdMapping.class).format(), method);
            }
        }
    }

    /**
     * Matches the command.
     * <p>
     * 匹配命令。
     *
     * @param args The arguments of the command. <br> 命令的参数。
     * @return The method that matches the command. <br> 匹配命令的方法。
     */
    private Method matchMethod(String[] args) {
        if (args.length == 0) {
            return mappings.getOrDefault("", null);
        }
        Method partialMatch = null;
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            String format = entry.getKey();
            String[] formatArgs = format.split(" ");

            boolean match = true;

            // 检查参数长度是否一致
            if (formatArgs.length != args.length) {
                // 参数长度不一致 取可配对参数的最小值
                int min = Math.min(formatArgs.length, args.length);

                // 逐个匹配
                for (int i = 0; i < min; i++) {
                    if (!matchesArgument(formatArgs[i], args[i])) {
                        match = false;
                        break;
                    }
                }

                // 如果所有参数都匹配，记录这个方法作为备选
                if (match && partialMatch == null) {
                    partialMatch = entry.getValue();
                }

                // 如果不完全匹配，继续下一次循环
                continue;
            }

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
        return partialMatch;
    }

    /**
     * Compare the actual argument with the format argument.
     * <p>
     * 将实际参数与格式参数进行比较。
     *
     * @param formatArg The format argument. <br> 格式参数。
     * @param actualArg The actual argument. <br> 实际参数。
     * @return Whether the actual argument matches the format argument. <br> 实际参数是否与格式参数匹配。
     */
    private boolean matchesArgument(String formatArg, String actualArg) {
        return formatArg.startsWith("<") && formatArg.endsWith(">") || formatArg.equalsIgnoreCase(actualArg);
    }

    /**
     * Compare the actual argument with the format argument.
     * <p>
     * 将实际参数与格式参数进行比较。
     *
     * @param formatArg The format argument. <br> 格式参数。
     * @param actualArg The actual argument. <br> 实际参数。
     * @return Whether the actual argument matches the format argument. <br> 实际参数是否与格式参数匹配。
     */
    private boolean matchesLastArgument(String formatArg, String actualArg) {
        if (formatArg.endsWith("...>")) {
            return true;
        }
        return matchesArgument(formatArg, actualArg);
    }

    /**
     * Checks whether the sender is valid for the matched method, resolving the class-level and
     * method-level {@code @CmdTarget} through the shared composition rule (D-01/D-04).
     * <p>
     * This used to be two independent checks - one against the class-level annotation, one
     * against the method-level annotation - combined with {@code ||} at the call site, which is
     * a boolean AND and therefore an intersection: both had to pass. That intersection reading
     * disagreed with {@code SenderTypeValidator}'s unguarded override, so migrating a command
     * class between the two executor generations silently changed who could invoke it. A single
     * resolved type collapses that gap: by the time a class reaches this method,
     * {@code ComponentScanner} has already refused any composition that would make the two
     * readings disagree, so there is only one type left to check against.
     * <p>
     * 检查发送者对匹配方法是否有效，通过共享的组合规则解析类级与方法级 @CmdTarget。
     * 以前是两个独立检查在调用处用 || 组合——那其实是布尔与，即取交集，
     * 与 SenderTypeValidator 的无守卫覆盖语义不一致。
     *
     * @param sender The sender of the command. <br> 命令的发送者。
     * @param method The method that matches the command. <br> 匹配命令的方法。
     * @return Whether the sender is valid. <br> 发送者是否有效。
     */
    private boolean checkSender(CommandSender sender, Method method) {
        Class<? extends AbstractCommandExecutor> clazz = this.getClass();
        CmdTarget.CmdTargetType classLevel = clazz.isAnnotationPresent(CmdTarget.class)
                ? clazz.getAnnotation(CmdTarget.class).value()
                : CmdTarget.CmdTargetType.BOTH;
        CmdTarget.CmdTargetType effectiveType = CmdTargetComposition.resolve(classLevel, method);
        return checkCmdTargetType(sender, effectiveType);
    }

    /**
     * Checks whether the sender has permission.
     * <p>
     * 检查发送者是否有权限。
     *
     * @param sender The sender of the command. <br> 命令的发送者。
     * @return Whether the sender has permission. <br> 发送者是否有权限。
     */
    private boolean checkPermission(CommandSender sender) {
        Class<? extends AbstractCommandExecutor> clazz = this.getClass();
        if (!clazz.isAnnotationPresent(CmdExecutor.class)) {
            return true;
        }
        CmdExecutor cmdExecutor = clazz.getAnnotation(CmdExecutor.class);
        String permission = cmdExecutor.permission();
        if (permission.isEmpty() || sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(String.format(UltiTools.getInstance().i18n("需要权限"), permission));
        return false;
    }


    /**
     * Checks whether the sender need to be an OP.
     * <p>
     * 检查发送者是否需要是OP。
     *
     * @param sender The sender of the command. <br> 命令的发送者。
     * @return Whether the sender need to be an OP. <br> 发送者是否需要是OP。
     */
    private boolean checkOp(CommandSender sender) {
        Class<? extends AbstractCommandExecutor> clazz = this.getClass();
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

    /**
     * Checks whether the sender need to wait for the previous command to finish.
     * <p>
     * 检查发送者是否需要等待上一条命令执行完毕。
     *
     * @param sender The sender of the command. <br> 命令的发送者。
     * @param method The method that matches the command. <br> 匹配命令的方法。
     * @return Whether the sender need to wait for the previous command to finish. <br> 发送者是否需要等待上一条命令执行完毕。
     */
    private boolean checkLock(CommandSender sender, Method method) {
        if (!method.isAnnotationPresent(UsageLimit.class)) {
            return false;
        }
        if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.SENDER)) {
            if (!(sender instanceof Player || method.getAnnotation(UsageLimit.class).ContainConsole())) {
                return false;
            }
            if (sender instanceof Player) {
                Method lockedMethod = senderLock.get(((Player) sender).getUniqueId());
                if (lockedMethod != null && lockedMethod.equals(method)) {
                    sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("请先等待上一条命令执行完毕！"));
                    return true;
                }
            }
            return false;
        }
        if (method.getAnnotation(UsageLimit.class).value().equals(UsageLimit.LimitType.ALL)) {
            if (!(sender instanceof Player || method.getAnnotation(UsageLimit.class).ContainConsole())) {
                return false;
            }
            if (sender instanceof Player) {
                Method lockedMethod = serverLock.get(((Player) sender).getUniqueId());
                if (lockedMethod != null && lockedMethod.equals(method)) {
                    sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("请先等待其他玩家发送的命令执行完毕！"));
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * Checks whether the sender need to wait for the command cool down.
     * <p>
     * 检查发送者是否需要等待命令冷却。
     *
     * @param sender The sender of the command. <br> 命令的发送者。
     * @return Whether the sender need to wait for command cool down. <br> 发送者是否需要等待命令冷却。
     */
    private boolean checkCD(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return false;
        }
        Player player = (Player) sender;
        if (cmdCoolDown.containsKey(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("操作频繁，请稍后再试"));
            return true;
        }
        return false;
    }

    /**
     * Builds the parameters of the command.
     * <p>
     * 构建命令的参数。
     *
     * @param strings       The arguments of the command. <br> 命令的参数。
     * @param method        The method that matches the command. <br> 匹配命令的方法。
     * @param commandSender The sender of the command. <br> 命令的发送者。
     * @return Parameters of the command. <br> 命令的参数。
     */
    private Object[] buildParams(String[] strings, Method method, CommandSender commandSender) {
        Map<String, String[]> params = getParams(strings, mappings.inverse().get(method));
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return new Object[0];
        }

        List<Object> paramList = new ArrayList<>();

        for (Parameter parameter : parameters) {
            Class<?> paramType = parameter.getType();

            if ((paramType.equals(Player.class) || paramType.equals(CommandSender.class)) && !parameter.isAnnotationPresent(CmdParam.class)) {
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
                } catch (Exception | Error e) {
                    commandSender.sendMessage(ChatColor.RED + e.getMessage());
                    Bukkit.getLogger().log(Level.SEVERE, "Failed to parse command parameter: " + cmdParam.value(), e);
                    return null;
                }
            } else {
                paramList.add(null);
            }
        }
        return paramList.toArray();
    }

    /**
     * Parses the type of the parameter.
     * <p>
     * 解析参数的类型。
     *
     * @param value The value of the parameter. <br> 参数的值。
     * @param type  The type of the parameter. <br> 参数的类型。
     * @param <T>   The type of the parameter. <br> 参数的类型。
     * @return The parsed parameter. <br> 解析后的参数。
     */
    private <T> Object parseType(String[] value, Class<T> type) {
        Function<String, T> parser = getParser(type);
        if (type.isArray()) {
            Object array = Array.newInstance(type.getComponentType(), value.length);
            for (int i = 0; i < value.length; i++) {
                Array.set(array, i, parser.apply(value[i]));
            }
            return array;
        } else {
            if (value.length == 0) {
                // For empty varargs, return empty string for String type
                if (type.equals(String.class)) {
                    return "";
                }
                return null;
            }
            if (type.equals(String.class) && value.length > 1) {
                return String.join(" ", value);
            }
            return parser.apply(value[0]);
        }
    }

    /**
     * Sets the cool down of the command.
     * <p>
     * 设置命令的冷却。
     *
     * @param commandSender The sender of the command. <br> 命令的发送者。
     * @param method        The method that matches the command. <br> 匹配命令的方法。
     */
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
        cmdCoolDown.put(player.getUniqueId(), method);
        new BukkitRunnable() {
            int time = cmdCD.value();

            @Override
            public void run() {
                CoolDownTickResult result = processCoolDownTick(player.getUniqueId(), method, time);
                time = result.remainingTime;
                if (result.shouldCancel) {
                    this.cancel();
                }
            }
        }.runTaskTimerAsynchronously(UltiTools.getInstance(), 0L, 20L);
    }

    /**
     * Result object for cooldown tick processing.
     */
    protected static class CoolDownTickResult {
        final boolean shouldCancel;
        final int remainingTime;

        CoolDownTickResult(boolean shouldCancel, int remainingTime) {
            this.shouldCancel = shouldCancel;
            this.remainingTime = remainingTime;
        }
    }

    /**
     * Process a single tick of the command cooldown.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param playerUUID the UUID of the player
     * @param method     the method that is on cooldown
     * @param time       the current remaining time
     * @return the result containing whether to cancel and the new remaining time
     */
    protected CoolDownTickResult processCoolDownTick(UUID playerUUID, Method method, int time) {
        if (time > 0) {
            return new CoolDownTickResult(false, time - 1);
        } else {
            cmdCoolDown.remove(playerUUID, method);
            return new CoolDownTickResult(true, 0);
        }
    }

    /**
     * Execute a method with usage limit lock handling.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param commandSender the sender of the command
     * @param method        the method to execute
     * @param params        the parameters to pass to the method
     */
    protected void executeMethodWithLock(CommandSender commandSender, Method method, Object[] params) {
        UsageLimit usageLimit = method.getAnnotation(UsageLimit.class);

        if (usageLimit != null) {
            if (usageLimit.value().equals(UsageLimit.LimitType.ALL)) {
                serverLock.put(((Player) commandSender).getUniqueId(), method);
            } else if (usageLimit.value().equals(UsageLimit.LimitType.SENDER) && commandSender instanceof Player) {
                senderLock.put(((Player) commandSender).getUniqueId(), method);
            }
        }

        try {
            setCoolDown(commandSender, method);
            ReflectionUtil.invoke(getInstance(), method, params);
        } finally {
            if (usageLimit != null) {
                if (usageLimit.value().equals(UsageLimit.LimitType.ALL)) {
                    serverLock.remove(((Player) commandSender).getUniqueId());
                } else if (usageLimit.value().equals(UsageLimit.LimitType.SENDER) && commandSender instanceof Player) {
                    senderLock.remove(((Player) commandSender).getUniqueId());
                }
            }
        }
    }


    /**
     * Abstract method that handles the help command.
     * <p>
     * 处理帮助命令的抽象方法。
     *
     * @param sender The sender of the command. <br> 命令的发送者。
     */
    abstract protected void handleHelp(CommandSender sender);

    /**
     * Sends the error message.
     * <p>
     * 发送错误信息。
     *
     * @param sender  The sender of the command. <br> 命令的发送者。
     * @param command The command that was executed. <br> 执行的命令。
     */
    protected void sendErrorMessage(CommandSender sender, Command command) {
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    /**
     * Tab complete method. Returns a list of possible completions for the specified command string.
     * By rewriting this method, you can customize the tab completion of the command.
     * <p>
     * Reduces to a one-line delegation into {@link CommandTabCompletionDispatch#suggest(BiMap,
     * Player, Command, String[], Object)} -- the single tab-completion dispatch implementation
     * both base-class generations now share (WIRE-01 / D-06). This class's eight former private
     * reflection helpers (argument-position resolution, the reflective suggestion-method
     * invocation half) are gone; the latter is now reached through {@code
     * commands/tabcomplete/MethodInvocationCompleter}, which walks the class hierarchy (issue
     * #190) so an AOP-proxied executor's suggestion method resolves correctly, which this class's
     * own helpers did not do.
     * <p>
     * 补全方法。返回指定命令字符串的可能补全列表。
     * 通过重写此方法，您可以自定义命令的补全。
     *
     * @param player  The player who will see the suggestions. <br> 看到补全的玩家
     * @param command The command that was typed in. <br> 需要补全的命令
     * @param strings The arguments of the command that was typed in. <br> 目前输入的命令参数
     * @return The suggestions. <br> 补全的建议
     */
    protected List<String> suggest(Player player, Command command, String[] strings) {
        return CommandTabCompletionDispatch.suggest(mappings, player, command, strings, this);
    }

    /**
     * @return The help command. <br> 帮助命令。
     * @see #handleHelp(CommandSender)
     */
    protected String getHelpCommand() {
        return "help";
    }

    /**
     * Executes the command, returning its success.
     * <p>
     * 执行命令，返回是否成功。
     *
     * @param commandSender Source of the command <br> 命令的发送者
     * @param command       Command which was executed <br> 命令
     * @param s             Alias of the command which was used <br> 命令的别名
     * @param strings       Passed command arguments <br> 命令的参数
     * @return true if a valid command, otherwise false <br> 如果是有效的命令则返回true，否则返回false
     */
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
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
        // 检查参数长度
        if (!checkParameters(strings, method, commandSender,command)) {
            return true;
        }
        if (!checkSender(commandSender, method)) {
            return true;
        }
        if (!checkPermission(commandSender)
                || !CommandTabCompletionDispatch.checkPermission(commandSender, method)) {
            return true;
        }
        if (!checkOp(commandSender) || !CommandTabCompletionDispatch.checkOp(commandSender, method)) {
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
                executeMethodWithLock(commandSender, method, params);
            }
        };
        if (method.isAnnotationPresent(RunAsync.class)) {
            bukkitRunnable.runTaskAsynchronously(UltiTools.getInstance());
        } else {
            bukkitRunnable.runTask(UltiTools.getInstance());
        }
        return true;
    }

    private boolean checkParameters(String[] args, Method method, CommandSender commandSender, Command command) {
        // 从 mappings 中获取 method 对应的格式字符串
        String format = mappings.inverse().get(method);
        // Handle zero-arg commands: empty format means no parameters expected
        if (format.isEmpty()) {
            return args.length == 0;
        }
        // 按空格分割格式字符串
        String[] formatArgs = format.split(" ");

        // 如果格式字符串中的参数数量与传入的参数数量不一致
        if (formatArgs.length != args.length) {
            int min = Math.min(formatArgs.length, args.length);

            // 如果传入的参数多于格式字符串中的参数
            if (formatArgs.length < args.length) {
                for (int i = 0; i < min; i++) {
                    // 检查最后一个格式参数是否是变长参数
                    if (formatArgs[formatArgs.length - 1].endsWith("...>")) {
                        return true;
                    }
                    // 如果当前参数不匹配
                    if (matchesArgument(formatArgs[i], args[i])) {
                        // 拼接传入的参数字符串
                        String commandArgsStr = " ";
                        for (int j = 0; j < min; j++) {
                            commandArgsStr += ("§7" + args[j] + " ");
                        }
                        // 告知错误位置
                        commandSender.sendMessage(String.format(UltiTools.getInstance().i18n("参数错误"), command.getName(), commandArgsStr, args[min]));
                        break;
                    }
                }
            } else {
                // 如果传入的参数少于格式字符串中的参数
                // 拼接传入的参数字符串
                String commandArgsStr = " ";
                for (int j = 0; j < min; j++) {
                    commandArgsStr += ("§7" + args[j] + " ");
                }
                // 拼接缺少的参数字符串
                String missingParameters = "";
                for (int j = min; j < formatArgs.length; j++) {
                    missingParameters += ("§c§n" + formatArgs[j] + " ");
                }
                missingParameters = missingParameters.trim();
                // 告知缺少参数的位置
                commandSender.sendMessage(String.format(UltiTools.getInstance().i18n("缺少参数"), command.getName(), commandArgsStr, missingParameters));
            }
            // 提示正确用法
            commandSender.sendMessage(String.format(UltiTools.getInstance().i18n("正确用法"), command.getName(), format));
            return false;
        }
        return true;
    }

    /**
     * Requests a list of possible completions for a command argument.
     * <p>
     * 请求命令参数的可能补全列表。
     *
     * @param commandSender Source of the command.  For players tab-completing a
     *                      command inside of a command block, this will be the player, not
     *                      the command block.
     * @param command       Command which was executed
     * @param s             Alias of the command which was used
     * @param strings       The arguments passed to the command, including final
     *                      partial argument to be completed
     * @return A List of possible completions for the final argument, or null
     */
    @Nullable
    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
        Class<? extends AbstractCommandExecutor> clazz = this.getClass();
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
