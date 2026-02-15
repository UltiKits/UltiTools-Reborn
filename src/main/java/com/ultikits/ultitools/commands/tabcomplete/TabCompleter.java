package com.ultikits.ultitools.commands.tabcomplete;

import java.util.List;

/**
 * Strategy interface for providing tab completion suggestions.
 * Implementations can provide different types of suggestions based on context.
 * <p>
 * Tab 补全建议的策略接口。
 * 实现可以根据上下文提供不同类型的建议。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
@FunctionalInterface
public interface TabCompleter {
    
    /**
     * Generates completion suggestions based on the given context.
     * 根据给定的上下文生成补全建议。
     *
     * @param context the tab completion context containing command state
     * @return list of suggestions, never null
     */
    List<String> complete(TabCompletionContext context);
}
