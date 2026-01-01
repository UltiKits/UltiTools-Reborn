package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;

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
