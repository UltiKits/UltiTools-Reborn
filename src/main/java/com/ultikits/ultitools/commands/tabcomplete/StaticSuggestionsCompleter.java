package com.ultikits.ultitools.commands.tabcomplete;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Completer that provides static suggestions from a predefined list.
 * <p>
 * 从预定义列表提供静态建议的补全器。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class StaticSuggestionsCompleter implements TabCompleter {
    
    private final List<String> suggestions;
    private final boolean caseSensitive;
    
    /**
     * Creates a completer with static suggestions (case-insensitive matching).
     * 使用静态建议创建补全器（不区分大小写匹配）。
     *
     * @param suggestions the suggestions to provide
     */
    public StaticSuggestionsCompleter(String... suggestions) {
        this(Arrays.asList(suggestions), false);
    }
    
    /**
     * Creates a completer with static suggestions (case-insensitive matching).
     * 使用静态建议创建补全器（不区分大小写匹配）。
     *
     * @param suggestions the suggestions to provide
     */
    public StaticSuggestionsCompleter(List<String> suggestions) {
        this(suggestions, false);
    }
    
    /**
     * Creates a completer with static suggestions.
     * 使用静态建议创建补全器。
     *
     * @param suggestions   the suggestions to provide
     * @param caseSensitive whether matching should be case-sensitive
     */
    public StaticSuggestionsCompleter(List<String> suggestions, boolean caseSensitive) {
        this.suggestions = new ArrayList<>(suggestions);
        this.caseSensitive = caseSensitive;
    }
    
    @Override
    public List<String> complete(TabCompletionContext context) {
        String input = context.getCurrentInput();
        if (input.isEmpty()) {
            return new ArrayList<>(suggestions);
        }
        
        String matchInput = caseSensitive ? input : input.toLowerCase();
        List<String> filtered = new ArrayList<>();
        
        for (String suggestion : suggestions) {
            String matchSuggestion = caseSensitive ? suggestion : suggestion.toLowerCase();
            if (matchSuggestion.startsWith(matchInput)) {
                filtered.add(suggestion);
            }
        }
        
        Collections.sort(filtered);
        return filtered;
    }
    
    /**
     * Creates a completer for boolean values.
     * 创建布尔值的补全器。
     *
     * @return a completer suggesting "true" and "false"
     */
    public static StaticSuggestionsCompleter forBoolean() {
        return new StaticSuggestionsCompleter("true", "false");
    }
    
    /**
     * Creates a completer for common toggle values.
     * 创建常用开关值的补全器。
     *
     * @return a completer suggesting common toggle options
     */
    public static StaticSuggestionsCompleter forToggle() {
        return new StaticSuggestionsCompleter("on", "off", "enable", "disable");
    }
}
