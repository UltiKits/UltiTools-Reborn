package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.reflect.Method;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
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
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class TabCompletionContext {

    /**
     * The requesting sender. Legacy player-only builders may leave this unset.
     */
    private final CommandSender sender;

    /**
     * The player supplied by legacy callers; null for non-player requests.
     */
    private final Player player;

    /**
     * Returns the requesting sender, including contexts built with the legacy player setter.
     * An explicit sender takes precedence if both builder fields are supplied.
     *
     * @return the sender, or null if neither sender nor player was supplied
     */
    public CommandSender getSender() {
        return sender == null ? player : sender;
    }

    /**
     * Returns the requesting player, or null for a non-player sender.
     *
     * @return the requesting player, when applicable
     */
    public Player getPlayer() {
        CommandSender requester = getSender();
        return requester instanceof Player ? (Player) requester : null;
    }

    // Preserve the constructor descriptor used by existing package-local callers.
    TabCompletionContext(Player player, Command command, String[] args, int currentArgIndex,
            String partialArg, Method matchedMethod, String parameterName, Object executorInstance) {
        this(null, player, command, args, currentArgIndex, partialArg, matchedMethod, parameterName,
                executorInstance);
    }
    
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
        return of((CommandSender) player, command, args);
    }

    /**
     * Creates a completion context for any Bukkit command sender.
     *
     * @param sender the requesting sender
     * @param command the command
     * @param args the arguments
     * @return a new sender-aware context
     */
    public static TabCompletionContext of(CommandSender sender, Command command, String[] args) {
        int currentIndex = args.length > 0 ? args.length - 1 : 0;
        String partial = args.length > 0 ? args[args.length - 1] : "";
        
        return TabCompletionContext.builder()
                .sender(sender)
                .command(command)
                .args(args)
                .currentArgIndex(currentIndex)
                .partialArg(partial)
                .build();
    }
}
