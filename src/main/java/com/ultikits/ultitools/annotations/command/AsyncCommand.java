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
 * 标记命令方法为异步执行的注解。
 * 应用后，命令将在异步线程中执行。
 * <p>
 * <b>Warning:</b> Async commands should not directly access Bukkit APIs that must run on main thread.
 * Use {@link org.bukkit.scheduler.BukkitScheduler#runTask(org.bukkit.plugin.Plugin, Runnable)} 
 * to sync back to main thread when needed.
 * <p>
 * <b>警告：</b>异步命令不应直接访问必须在主线程运行的 Bukkit API。
 * 需要时使用 BukkitScheduler.runTask() 同步回主线程。
 *
 * <p><strong>Example / 示例:</strong></p>
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
     * 是否在执行时向发送者显示"处理中..."消息。
     *
     * @return true to show processing message
     */
    boolean showProcessing() default true;
    
    /**
     * Custom processing message key for i18n.
     * 自定义处理中消息的 i18n 键。
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
     * <p>
     * 框架等待命令体的最长秒数，超过后向发送者报告超时。
     * <p>
     * <b>这是框架"等待多久"的截止时间，不是对命令体的取消。</b>（D-13，与结构相同的
     * {@link com.ultikits.ultitools.annotations.Transactional#timeout()}（D-10）保持同一措辞
     * 口径。）当配置的时长耗尽而命令体仍在运行时，框架停止等待并向发送者发送一次超时消息。
     * 命令体<em>永远不会</em>被中断或取消：Bukkit 的异步调度器运行在共享线程池上，中断池中的
     * 线程是不安全的，可能影响共享该线程池的其他任务。命令体会自行运行至完成；截止时间之后它
     * 所做的一切——返回、抛出异常、自行发送消息——都如同没有发生超时一样，不会通过超时报告体现
     * 出来。
     * <p>
     * 默认值为 30 秒。设为 0 将完全禁用该监视器：此时框架会无限期等待，无论命令体运行多久都
     * 不会报告超时。
     *
     * @return timeout in seconds
     */
    int timeout() default 30;
}
