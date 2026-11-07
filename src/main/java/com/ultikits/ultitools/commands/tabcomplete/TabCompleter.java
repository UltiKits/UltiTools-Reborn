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
     * The requesting sender may be a player, the console, or another command sender.
     * Use {@link TabCompletionContext#getSender()} for the sender; player-specific
     * implementations must check {@link TabCompletionContext#getPlayer()} for null
     * and return an empty list for non-player requests.
     *
     * @param context the tab completion context containing command state
     * @return list of suggestions, never null
     */
    List<String> complete(TabCompletionContext context);
}
