package com.ultikits.ultitools.commands.tabcomplete;

import java.util.List;

/**
 * Strategy interface for providing tab completion suggestions.
 * Implementations can provide different types of suggestions based on context.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
@FunctionalInterface
public interface TabCompleter {
    
    /**
     * Generates completion suggestions based on the given context.
     *
     * @param context the tab completion context containing command state
     * @return list of suggestions, never null
     */
    List<String> complete(TabCompletionContext context);
}
