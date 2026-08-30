package com.ultikits.ultitools.abstracts.command;

import lombok.Builder;
import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable command execution context containing all information about a command invocation.
 * This class follows the Context Object pattern, encapsulating command execution data.
 * <p>
 * 不可变的命令执行上下文，包含命令调用的所有信息。
 * 此类遵循上下文对象模式，封装命令执行数据。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
@Getter
@Builder(toBuilder = true)
public final class CommandContext {
    
    /**
     * The sender of the command.
     * 命令的发送者。
     */
    private final CommandSender sender;
    
    /**
     * The Bukkit command object.
     * Bukkit 命令对象。
     */
    private final Command command;
    
    /**
     * The alias used to invoke the command.
     * 用于调用命令的别名。
     */
    private final String alias;
    
    /**
     * The raw arguments passed to the command.
     * 传递给命令的原始参数。
     */
    private final String[] rawArgs;
    
    /**
     * The parsed parameters mapped by their names.
     * 按名称映射的已解析参数。
     */
    @Builder.Default
    private final Map<String, String[]> parsedParams = Collections.emptyMap();
    
    /**
     * The matched method for execution.
     * 匹配的执行方法。
     */
    @Nullable
    private final Method matchedMethod;
    
    /**
     * The format string of the matched command.
     * 匹配命令的格式字符串。
     */
    @Nullable
    private final String matchedFormat;

    /**
     * The concrete {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor} class
     * dispatching this command -- {@code this.getClass()} captured in {@code onCommand}, i.e.
     * the SAME class {@code PluginManager}'s load-time contract gate inspects via
     * {@code executor.getClass()}. WR-02 (05-REVIEW.md): {@link #matchedMethod}'s {@code
     * getDeclaringClass()} is NOT always this class -- an inherited, unoverridden {@code
     * @CmdMapping} method's declaring class is whatever ancestor first declared it. Threading
     * the concrete executor class through the context lets {@code
     * com.ultikits.ultitools.utils.ReflectionUtil#resolveMethodOrClassAnnotation(Method, Class,
     * Class)} resolve a class-level {@code @CmdCD}/{@code @UsageLimit} against the class the
     * load-time gate actually checked, not an implementation detail of which ancestor happens to
     * declare the mapping method.
     * <p>
     * {@code null} for a context built without it (e.g. a unit test constructing {@link
     * CommandContext} directly) -- callers fall back to the pre-WR-02, declaring-class-only
     * resolution in that case; see {@code resolveMethodOrClassAnnotation}'s own javadoc.
     *
     * @since 6.3.0
     */
    @Nullable
    private final Class<?> executorClass;

    /**
     * Timestamp when the command was received.
     * 命令接收的时间戳。
     */
    @Builder.Default
    private final long timestamp = System.currentTimeMillis();
    
    /**
     * Checks if the sender is a player.
     * 检查发送者是否为玩家。
     *
     * @return true if sender is a player, false otherwise
     */
    public boolean isPlayer() {
        return sender instanceof Player;
    }
    
    /**
     * Gets the sender as a player.
     * 获取作为玩家的发送者。
     *
     * @return the player, or null if sender is not a player
     */
    @Nullable
    public Player getPlayer() {
        return isPlayer() ? (Player) sender : null;
    }
    
    /**
     * Gets the number of arguments.
     * 获取参数数量。
     *
     * @return the number of raw arguments
     */
    public int getArgCount() {
        return rawArgs != null ? rawArgs.length : 0;
    }
    
    /**
     * Gets an argument at the specified index.
     * 获取指定索引处的参数。
     *
     * @param index the argument index (0-based)
     * @return the argument value, or null if index is out of bounds
     */
    @Nullable
    public String getArg(int index) {
        if (rawArgs == null || index < 0 || index >= rawArgs.length) {
            return null;
        }
        return rawArgs[index];
    }
    
    /**
     * Gets a parsed parameter by name.
     * 按名称获取已解析的参数。
     *
     * @param name the parameter name
     * @return the parameter values, or null if not found
     */
    @Nullable
    public String[] getParam(String name) {
        return parsedParams.get(name);
    }
    
    /**
     * Gets a single parsed parameter value by name.
     * 按名称获取单个已解析的参数值。
     *
     * @param name the parameter name
     * @return the first parameter value, or null if not found
     */
    @Nullable
    public String getParamValue(String name) {
        String[] values = parsedParams.get(name);
        return (values != null && values.length > 0) ? values[0] : null;
    }
    
    /**
     * Creates a new context with additional parsed parameters.
     * 创建带有附加已解析参数的新上下文。
     *
     * @param params the parameters to add
     * @return a new context with the merged parameters
     */
    public CommandContext withParsedParams(Map<String, String[]> params) {
        Map<String, String[]> merged = new HashMap<>(this.parsedParams);
        merged.putAll(params);
        return this.toBuilder()
                .parsedParams(Collections.unmodifiableMap(merged))
                .build();
    }
    
    /**
     * Creates a new context with a matched method.
     * 创建带有匹配方法的新上下文。
     *
     * @param method the matched method
     * @param format the command format
     * @return a new context with the matched method
     */
    public CommandContext withMatchedMethod(Method method, String format) {
        return this.toBuilder()
                .matchedMethod(method)
                .matchedFormat(format)
                .build();
    }
}
