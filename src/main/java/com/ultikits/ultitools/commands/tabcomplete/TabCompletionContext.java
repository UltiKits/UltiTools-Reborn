package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.reflect.Method;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;

import lombok.Builder;
import lombok.Getter;

/**
 * Context object containing all information needed for tab completion.
 * Immutable and thread-safe.
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
     */
    private final Player player;
    
    /**
     * The command being completed.
     */
    private final Command command;
    
    /**
     * The current arguments typed by the player.
     */
    private final String[] args;
    
    /**
     * The index of the current argument being completed (0-based).
     */
    private final int currentArgIndex;
    
    /**
     * The partial text of the current argument (may be empty).
     */
    private final String partialArg;
    
    /**
     * The matched method for the current command format (may be null).
     */
    private final Method matchedMethod;
    
    /**
     * The parameter name being completed (from format like &lt;paramName&gt;).
     */
    private final String parameterName;
    
    /**
     * The command executor instance for invoking suggestion methods.
     */
    private final Object executorInstance;
    
    /**
     * Gets the current partial argument or empty string if none.
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
