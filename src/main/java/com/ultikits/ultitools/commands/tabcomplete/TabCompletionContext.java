package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** Immutable input metadata for a parameter's completion provider. */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class TabCompletionContext {
    private final CommandSender sender;
    private final Player player;
    private final String commandName;
    private final String[] args;
    private final int currentArgIndex;
    private final String partialArg;
    private final Method matchedMethod;
    private final String parameterName;
    private final Object executorInstance;

    /**
     * Returns the requesting sender, including contexts built with the player setter.
     * @return the sender, or null when none was supplied
     */
    public CommandSender getSender() { return sender == null ? player : sender; }

    /**
     * Returns the player when this completion originated from one.
     * @return the player, or null for another sender kind
     */
    public Player getPlayer() { return getSender() instanceof Player requesting ? requesting : null; }

    /**
     * Gets the current partial token.
     * @return the partial token, or an empty string
     */
    public String getCurrentInput() {
        if (args == null || currentArgIndex < 0 || currentArgIndex >= args.length) return "";
        return args[currentArgIndex];
    }

    /**
     * Checks the current token's prefix without case sensitivity.
     * @param prefix the candidate prefix
     * @return whether the token starts with that prefix
     */
    public boolean inputStartsWith(String prefix) {
        return getCurrentInput().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    /**
     * Creates a completion context without a Bukkit command object.
     * @param sender the requesting sender
     * @param commandName the primary root label
     * @param args input tokens including the partial final token
     * @return a new context
     */
    public static TabCompletionContext of(CommandSender sender, String commandName, String[] args) {
        int currentIndex = Math.max(0, args.length - 1);
        return builder().sender(sender).commandName(commandName).args(args).currentArgIndex(currentIndex)
                .partialArg(args.length == 0 ? "" : args[args.length - 1]).build();
    }
}
