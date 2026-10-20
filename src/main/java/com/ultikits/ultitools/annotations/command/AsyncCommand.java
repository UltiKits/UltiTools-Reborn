package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a command method as async execution.
 * When applied, the command will be executed in an async thread.
 * <p>
 * <b>Warning:</b> Async commands should not directly access Bukkit APIs that must run on main thread.
 * Use {@link org.bukkit.scheduler.BukkitScheduler#runTask(org.bukkit.plugin.Plugin, Runnable)}
 * to sync back to main thread when needed.
 *
 * <p><strong>Example:</strong></p>
 * <pre>{@code
 * @CmdMapping(format = "backup")
 * @AsyncCommand
 * public void backupWorld(@CmdSender Player player) {
 *     // This runs async - safe for I/O operations
 *     performBackup();
 *     
 *     // Sync back to main thread for Bukkit operations
 *     Bukkit.getScheduler().runTask(plugin, () -> {
 *         player.sendMessage("Backup completed!");
 *     });
 * }
 * }</pre>
 *
 * @author wisdomme
 * @since 6.2.0
 * @see com.ultikits.ultitools.abstracts.command.BaseCommandExecutor
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AsyncCommand {
    
    /**
     * Whether to show "Processing..." message to sender while executing.
     *
     * @return true to show processing message
     */
    boolean showProcessing() default true;

    /**
     * Custom processing message key for i18n.
     *
     * @return the i18n key, or empty for default message
     */
    String processingMessageKey() default "";
    
    /**
     * The number of seconds the framework waits for the command body before reporting a
     * timeout to the sender.
     * <p>
     * <b>This is a deadline on how long the framework waits, not a cancellation of the
     * command body.</b> (D-13, matching
     * {@link com.ultikits.ultitools.annotations.Transactional#timeout()}'s register for the
     * structurally identical case, D-10.) When the configured duration elapses with the body
     * still running, the framework stops waiting and sends the sender a timeout message --
     * exactly once. The command body is <em>never</em> interrupted
     * or cancelled: Bukkit's async scheduler runs on a shared thread pool, and interrupting a
     * pooled thread is unsafe, since it can affect unrelated tasks sharing that pool. The body
     * keeps running to completion on its own; whatever it does after the deadline -- return,
     * throw, send further messages of its own -- happens exactly as if no timeout had fired,
     * and none of it is surfaced through the timeout report.
     * <p>
     * Defaults to 30 seconds. A value of 0 disables the watcher entirely: the framework then
     * waits indefinitely and never reports a timeout, regardless of how long the body runs.
     *
     * @return timeout in seconds
     */
    int timeout() default 30;
}
