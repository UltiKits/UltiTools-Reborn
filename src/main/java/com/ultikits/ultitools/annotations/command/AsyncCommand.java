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
 * Inject {@link com.ultikits.ultitools.scheduling.Scheduler} and use
 * {@code scheduler.global().run(...)} for global work or {@code scheduler.entity(player).run(...)}
 * for player-owned work. Command bodies themselves use the framework-owned async scheduler.
 * <p>
 * At unload the executor waits up to approximately ten seconds for its accepted async commands,
 * without interrupting them. Do not block awaiting a tick-thread handoff during that wait: the
 * unload thread owns the tick thread, and scheduling is rejected once the relevant owner retires.
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
 *     scheduler.entity(player).run(() -> {
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
     * Custom message key in {@code "<namespace>:<path>"} form, for example {@code "my-module:busy"}.
     * The module catalogue supplies its colors. Invalid syntax logs one warning per executor and
     * value and uses the framework's default processing message.
     *
     * @return the namespaced message key, or empty for the default message
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
     * or cancelled: the framework's async scheduler runs on a shared thread pool, and interrupting a
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
