package com.ultikits.ultitools.manager;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Immutable context describing what triggered an error.
 * All player info is captured as strings at construction time
 * to be safe across async thread boundaries.
 *
 * @since 6.2.3
 */
public final class TriggerContext {

    /**
     * Trigger type enum.
     */
    public enum TriggerType {
        COMMAND,
        EVENT,
        SCHEDULED,
        AOP,
        UNCAUGHT
    }

    private final TriggerType triggerType;
    private final String triggerDetail;
    private final String playerName;
    private final String playerUUID;

    private TriggerContext(TriggerType triggerType, String triggerDetail,
                           String playerName, String playerUUID) {
        this.triggerType = triggerType;
        this.triggerDetail = triggerDetail;
        this.playerName = playerName;
        this.playerUUID = playerUUID;
    }

    /**
     * Create context for a command trigger.
     * Captures player info immediately (safe for async use).
     *
     * @param sender      the command sender
     * @param commandLine the full command line (e.g., "uc msg Hello")
     * @return trigger context
     */
    public static TriggerContext command(CommandSender sender, String commandLine) {
        String name = null;
        String uuid = null;
        if (sender instanceof Player) {
            name = ((Player) sender).getName();
            uuid = ((Player) sender).getUniqueId().toString();
        }
        return new TriggerContext(TriggerType.COMMAND, commandLine, name, uuid);
    }

    /**
     * Create context for an event trigger.
     *
     * @param eventClassName the simple name of the event class
     * @return trigger context
     */
    public static TriggerContext event(String eventClassName) {
        return new TriggerContext(TriggerType.EVENT, eventClassName, null, null);
    }

    /**
     * Create context for an AOP interceptor trigger.
     *
     * @param className  the simple name of the declaring class
     * @param methodName the method name
     * @return trigger context
     */
    public static TriggerContext aop(String className, String methodName) {
        return new TriggerContext(TriggerType.AOP,
                className + "." + methodName + "()", null, null);
    }

    /**
     * Create context for a scheduled task trigger.
     *
     * @param detail description of the scheduled task
     * @return trigger context
     */
    public static TriggerContext scheduled(String detail) {
        return new TriggerContext(TriggerType.SCHEDULED, detail, null, null);
    }

    /**
     * Create context for an uncaught exception.
     *
     * @param detail description of where the error occurred
     * @return trigger context
     */
    public static TriggerContext uncaught(String detail) {
        return new TriggerContext(TriggerType.UNCAUGHT, detail, null, null);
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public String getTriggerDetail() {
        return triggerDetail;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getPlayerUUID() {
        return playerUUID;
    }
}
