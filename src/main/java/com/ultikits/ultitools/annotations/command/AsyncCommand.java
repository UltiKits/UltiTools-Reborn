package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.*;

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
 * <h3>Example / 示例:</h3>
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
     * Timeout in seconds. If the async operation takes longer, it will be cancelled.
     * Set to 0 for no timeout.
     * <p>
     * 超时时间（秒）。如果异步操作超时，将被取消。
     * 设为 0 表示无超时。
     *
     * @return timeout in seconds
     */
    int timeout() default 30;
}
