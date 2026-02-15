package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.reflect.Method;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;

import lombok.Builder;
import lombok.Getter;

/**
 * Context object containing all information needed for tab completion.
 * Immutable and thread-safe.
 * <p>
 * 包含 Tab 补全所需所有信息的上下文对象。
 * 不可变且线程安全。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
@Getter
@Builder
public class TabCompletionContext {
    
    /**
     * The player requesting completion.
     * 请求补全的玩家。
     */
    private final Player player;
    
    /**
     * The command being completed.
     * 正在补全的命令。
     */
    private final Command command;
    
    /**
     * The current arguments typed by the player.
     * 玩家当前输入的参数。
     */
    private final String[] args;
    
    /**
     * The index of the current argument being completed (0-based).
     * 当前正在补全的参数索引（从0开始）。
     */
    private final int currentArgIndex;
    
    /**
     * The partial text of the current argument (may be empty).
     * 当前参数的部分文本（可能为空）。
     */
    private final String partialArg;
    
    /**
     * The matched method for the current command format (may be null).
     * 当前命令格式匹配的方法（可能为null）。
     */
    private final Method matchedMethod;
    
    /**
     * The parameter name being completed (from format like &lt;paramName&gt;).
     * 正在补全的参数名（从格式如 &lt;paramName&gt; 中提取）。
     */
    private final String parameterName;
    
    /**
     * The command executor instance for invoking suggestion methods.
     * 用于调用建议方法的命令执行器实例。
     */
    private final Object executorInstance;
    
    /**
     * Gets the current partial argument or empty string if none.
     * 获取当前的部分参数，如果没有则返回空字符串。
     *
     * @return the partial argument being typed
     */
    public String getCurrentInput() {
        if (args == null || args.length == 0) {
            return "";
        }
        return args.length > currentArgIndex ? args[currentArgIndex] : "";
    }
    
    /**
     * Checks if the current input starts with the given prefix (case-insensitive).
     * 检查当前输入是否以给定前缀开头（不区分大小写）。
     *
     * @param prefix the prefix to check
     * @return true if the current input starts with the prefix
     */
    public boolean inputStartsWith(String prefix) {
        String input = getCurrentInput();
        return input.toLowerCase().startsWith(prefix.toLowerCase());
    }
    
    /**
     * Creates a context from command arguments.
     * 从命令参数创建上下文。
     *
     * @param player  the player
     * @param command the command
     * @param args    the arguments
     * @return a new context
     */
    public static TabCompletionContext of(Player player, Command command, String[] args) {
        int currentIndex = args.length > 0 ? args.length - 1 : 0;
        String partial = args.length > 0 ? args[args.length - 1] : "";
        
        return TabCompletionContext.builder()
                .player(player)
                .command(command)
                .args(args)
                .currentArgIndex(currentIndex)
                .partialArg(partial)
                .build();
    }
}
