package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.annotations.command.CmdParam;

/**
 * Central manager for tab completion functionality.
 * Provides a unified API for both old and new command systems.
 * <p>
 * Tab 补全功能的中央管理器。
 * 为新旧命令系统提供统一的 API。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class TabCompletionManager {
    
    /**
     * Built-in completer prefix for players.
     */
    public static final String PLAYERS = "@players";
    
    /**
     * Built-in completer prefix for worlds.
     */
    public static final String WORLDS = "@worlds";
    
    /**
     * Built-in completer prefix for materials.
     */
    public static final String MATERIALS = "@materials";
    
    /**
     * Built-in completer prefix for blocks.
     */
    public static final String BLOCKS = "@blocks";
    
    /**
     * Built-in completer prefix for items.
     */
    public static final String ITEMS = "@items";
    
    /**
     * Built-in completer prefix for boolean values.
     */
    public static final String BOOLEAN = "@boolean";
    
    /**
     * Built-in completer prefix for toggle values.
     */
    public static final String TOGGLE = "@toggle";
    
    private static volatile TabCompletionManager instance;
    
    private final Map<String, TabCompleter> completers = new ConcurrentHashMap<>();
    private final MethodInvocationCompleter methodCompleter = new MethodInvocationCompleter();
    
    /**
     * Private constructor - use getInstance().
     */
    private TabCompletionManager() {
        registerBuiltInCompleters();
    }
    
    /**
     * Gets the singleton instance.
     * 获取单例实例。
     *
     * @return the tab completion manager instance
     */
    public static TabCompletionManager getInstance() {
        if (instance == null) {
            synchronized (TabCompletionManager.class) {
                if (instance == null) {
                    instance = new TabCompletionManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Registers all built-in completers.
     * 注册所有内置补全器。
     */
    private void registerBuiltInCompleters() {
        completers.put(PLAYERS, new OnlinePlayersCompleter());
        completers.put(WORLDS, new WorldsCompleter());
        completers.put(MATERIALS, new MaterialsCompleter());
        completers.put(BLOCKS, MaterialsCompleter.blocksOnly());
        completers.put(ITEMS, MaterialsCompleter.itemsOnly());
        completers.put(BOOLEAN, StaticSuggestionsCompleter.forBoolean());
        completers.put(TOGGLE, StaticSuggestionsCompleter.forToggle());
    }
    
    /**
     * Registers a custom completer with a key.
     * 使用键注册自定义补全器。
     *
     * @param key       the key to register with (e.g., "@custom")
     * @param completer the completer to register
     */
    public void register(String key, TabCompleter completer) {
        if (key == null || completer == null) {
            throw new IllegalArgumentException("Key and completer must not be null");
        }
        completers.put(key, completer);
    }
    
    /**
     * Unregisters a completer.
     * 注销补全器。
     *
     * @param key the key to unregister
     */
    public void unregister(String key) {
        completers.remove(key);
    }
    
    /**
     * Gets a registered completer by key.
     * 根据键获取已注册的补全器。
     *
     * @param key the completer key
     * @return the completer or null if not found
     */
    public TabCompleter getCompleter(String key) {
        return completers.get(key);
    }
    
    /**
     * Generates suggestions using the appropriate completer.
     * 使用适当的补全器生成建议。
     *
     * @param player  the player requesting completion
     * @param command the command
     * @param args    the current arguments
     * @return list of suggestions
     */
    public List<String> suggest(Player player, Command command, String[] args) {
        TabCompletionContext context = TabCompletionContext.of(player, command, args);
        return suggest(context);
    }
    
    /**
     * Generates suggestions using the given context.
     * 使用给定的上下文生成建议。
     *
     * @param context the completion context
     * @return list of suggestions
     */
    public List<String> suggest(TabCompletionContext context) {
        if (context == null) {
            return Collections.emptyList();
        }
        
        // Check for built-in completer by parameter name
        String paramName = context.getParameterName();
        if (paramName != null && paramName.startsWith("@")) {
            TabCompleter completer = completers.get(paramName);
            if (completer != null) {
                return completer.complete(context);
            }
        }
        
        // Try method invocation completer
        return methodCompleter.complete(context);
    }

    /**
     * Resolves suggestions using an explicitly-resolved {@code @CmdParam.suggest()} value,
     * rather than {@code context}'s {@link TabCompletionContext#getParameterName()} -- which
     * carries {@code @CmdParam.value()}, the parameter's DISPLAY NAME, not its suggestion
     * (05-06 / D-07 Pitfall 2, T-05-28). {@link #suggest(TabCompletionContext)} above is left
     * untouched for existing callers; this overload is the dual-notation entry point a caller
     * that has already resolved the real {@code suggest()} value (see {@link
     * #resolveSuggestValue(Method, String)}) should use instead.
     * <p>
     * A {@code resolvedSuggest} beginning with {@code @} resolves as a built-in or registered
     * completer key. Any other value -- including {@code null} or empty -- falls through to the
     * existing method-invocation completer (and its i18n hint-text fallback), unchanged.
     * <p>
     * 使用一个显式解析出的 {@code @CmdParam.suggest()} 值来生成建议，而不是 {@code context} 的
     * {@link TabCompletionContext#getParameterName()}——后者携带的是 {@code @CmdParam.value()}，
     * 即参数的显示名，而不是它的补全建议（05-06 / D-07 陷阱2, T-05-28）。
     *
     * @param context         the completion context <br> 补全上下文
     * @param resolvedSuggest the already-resolved {@code @CmdParam.suggest()} string for the
     *                        parameter being completed; may be {@code null} or empty
     *                        <br> 已解析出的 {@code @CmdParam.suggest()} 字符串；可以为
     *                        {@code null} 或空
     * @return the suggestions; never null <br> 建议列表；永不为 null
     * @since 6.3.0
     */
    public List<String> suggest(TabCompletionContext context, String resolvedSuggest) {
        if (context == null) {
            return Collections.emptyList();
        }

        if (resolvedSuggest != null && resolvedSuggest.startsWith("@")) {
            TabCompleter completer = completers.get(resolvedSuggest);
            if (completer != null) {
                return completer.complete(context);
            }
            return Collections.emptyList();
        }

        return methodCompleter.complete(context);
    }

    /**
     * Resolves {@code matchedMethod}'s {@code @CmdParam.suggest()} value for the parameter whose
     * {@code @CmdParam.value()} equals {@code parameterName} -- i.e. it keys the lookup off the
     * parameter's DISPLAY NAME to find the right parameter, then returns that parameter's
     * suggestion, never the display name itself (05-06 / D-07). This mirrors {@code
     * MethodInvocationCompleter.getSuggestName}'s existing lookup shape so the two stay
     * consistent, without depending on that private method directly.
     * <p>
     * 解析 {@code matchedMethod} 中 {@code @CmdParam.value()} 等于 {@code parameterName} 的那个
     * 参数的 {@code @CmdParam.suggest()} 值。
     *
     * @param matchedMethod the matched {@code @CmdMapping} method, or {@code null}
     *                      <br> 匹配到的 {@code @CmdMapping} 方法，可以为 {@code null}
     * @param parameterName the parameter's display name ({@code @CmdParam.value()}) to look up
     *                      <br> 要查找的参数显示名（{@code @CmdParam.value()}）
     * @return the resolved {@code suggest()} value, or {@code null} if {@code matchedMethod} is
     *         {@code null} or no parameter's {@code value()} matches {@code parameterName}
     *         <br> 解析出的 {@code suggest()} 值；若 {@code matchedMethod} 为 {@code null} 或没有
     *         参数的 {@code value()} 匹配 {@code parameterName}，则为 {@code null}
     * @since 6.3.0
     */
    public static String resolveSuggestValue(Method matchedMethod, String parameterName) {
        if (matchedMethod == null || parameterName == null) {
            return null;
        }
        for (Parameter parameter : matchedMethod.getParameters()) {
            CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
            if (cmdParam != null && parameterName.equals(cmdParam.value())) {
                return cmdParam.suggest();
            }
        }
        return null;
    }

    /**
     * Suggests completions for a specific parameter type.
     * 为特定参数类型建议补全。
     *
     * @param context   the completion context
     * @param completerKey the key of the completer to use
     * @return list of suggestions
     */
    public List<String> suggestWith(TabCompletionContext context, String completerKey) {
        TabCompleter completer = completers.get(completerKey);
        if (completer != null) {
            return completer.complete(context);
        }
        return Collections.emptyList();
    }
    
    /**
     * Creates a context for tab completion with common defaults.
     * 创建带有常用默认值的 Tab 补全上下文。
     *
     * @param player  the player
     * @param command the command
     * @param args    the arguments
     * @return a tab completion context
     */
    public TabCompletionContext createContext(
            Player player, Command command, String[] args) {
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
    
    /**
     * Helper method to filter suggestions by current input.
     * 过滤建议以匹配当前输入的辅助方法。
     *
     * @param suggestions the suggestions to filter
     * @param input       the current input
     * @return filtered suggestions
     */
    public static List<String> filterByInput(List<String> suggestions, String input) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }
        
        if (input == null || input.isEmpty()) {
            return new ArrayList<>(suggestions);
        }
        
        String lowerInput = input.toLowerCase();
        List<String> filtered = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(lowerInput)) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }
    
    /**
     * Suggests command format first arguments from mappings.
     * 从映射中建议命令格式的第一个参数。
     *
     * @param mappings the command mappings (format -> method)
     * @param context  the completion context
     * @return list of first argument suggestions
     */
    public List<String> suggestFirstArgs(Map<String, Method> mappings, TabCompletionContext context) {
        if (mappings == null || mappings.isEmpty()) {
            return Collections.emptyList();
        }
        
        String input = context.getCurrentInput().toLowerCase();
        List<String> suggestions = new ArrayList<>();
        
        for (String format : mappings.keySet()) {
            String[] parts = format.split(" ");
            if (parts.length > 0) {
                String firstArg = parts[0];
                // Skip parameter placeholders
                if (!firstArg.startsWith("<") && !firstArg.endsWith(">")) {
                    if (firstArg.toLowerCase().startsWith(input)) {
                        if (!suggestions.contains(firstArg)) {
                            suggestions.add(firstArg);
                        }
                    }
                }
            }
        }
        
        Collections.sort(suggestions);
        return suggestions;
    }
}
