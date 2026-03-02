package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GuiScheduler 负责确保所有 GUI 操作都在 Bukkit 主线程执行。
 * <p>
 * 它提供以下功能：
 * <ul>
 *   <li>检查当前是否在主线程</li>
 *   <li>将任务调度到主线程执行</li>
 *   <li>帧调度：合并短时间内的多次更新请求</li>
 *   <li>防止重复调度</li>
 * </ul>
 *
 * <p><strong>帧调度机制：</strong></p>
 * <pre>
 * 时间轴：
 * |----16ms----|----16ms----|----16ms----|
 *    ↑ ↑           ↑
 *   setState     实际执行重建
 *    ↑
 *   setState (合并到同一帧)
 * </pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class GuiScheduler {

    /**
     * 默认帧间隔（毫秒）。
     * Minecraft 默认 tick 是 50ms，但 GUI 更新可以更频繁。
     */
    private static final long DEFAULT_FRAME_INTERVAL_MS = 16; // ~60 FPS

    private final Plugin plugin;
    private final long frameIntervalMs;
    private final AtomicBoolean isScheduled = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    private long lastFrameTime = 0;

    /**
     * 使用默认插件实例创建 GuiScheduler。
     */
    public GuiScheduler() {
        this(UltiTools.getInstance());
    }

    /**
     * 创建 GuiScheduler。
     *
     * @param plugin 插件实例
     */
    public GuiScheduler(@NotNull Plugin plugin) {
        this(plugin, DEFAULT_FRAME_INTERVAL_MS);
    }

    /**
     * 创建 GuiScheduler，指定帧间隔。
     *
     * @param plugin          插件实例
     * @param frameIntervalMs 帧间隔（毫秒）
     */
    public GuiScheduler(@NotNull Plugin plugin, long frameIntervalMs) {
        this.plugin = plugin;
        this.frameIntervalMs = frameIntervalMs;
    }

    /**
     * 检查当前是否在主线程。
     *
     * @return 如果在主线程则返回 true
     */
    public boolean isOnMainThread() {
        return Bukkit.isPrimaryThread();
    }

    /**
     * 确保在主线程执行任务。
     * <p>
     * 如果当前在主线程，立即执行。
     * 否则，调度到主线程执行。
     *
     * @param task 要执行的任务
     */
    public void runOnMainThread(@NotNull Runnable task) {
        if (isOnMainThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 调度一个帧任务。
     * <p>
     * 这个方法实现了帧合并机制：
     * 在同一帧窗口期内的多次调用只会触发一次实际执行。
     *
     * @param frameTask 帧任务
     */
    public void scheduleFrame(@NotNull Runnable frameTask) {
        pendingTasks.offer(frameTask);
        
        if (isScheduled.compareAndSet(false, true)) {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastFrame = currentTime - lastFrameTime;
            long delay = Math.max(0, frameIntervalMs - timeSinceLastFrame);

            if (delay == 0 && isOnMainThread()) {
                // 可以直接执行
                executeFrame();
            } else {
                // 调度到下一帧
                Bukkit.getScheduler().runTaskLater(plugin, this::executeFrame, 
                        Math.max(1, delay / 50)); // 转换为 tick
            }
        }
    }

    /**
     * 执行所有挂起的帧任务。
     */
    private void executeFrame() {
        isScheduled.set(false);
        lastFrameTime = System.currentTimeMillis();

        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            try {
                if (isOnMainThread()) {
                    task.run();
                } else {
                    // 如果不在主线程，重新调度
                    Bukkit.getScheduler().runTask(plugin, task);
                    break; // 只处理一个，剩下的在下一次 tick 处理
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error executing GUI frame task: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 立即执行所有挂起的任务（阻塞直到完成）。
     * <p>
     * <b>注意：</b> 这个方法只能在主线程调用。
     *
     * @throws IllegalStateException 如果不在主线程
     */
    public void flush() {
        if (!isOnMainThread()) {
            throw new IllegalStateException("flush() must be called on main thread");
        }

        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            task.run();
        }
        isScheduled.set(false);
    }

    /**
     * 取消所有挂起的任务。
     */
    public void cancelAll() {
        pendingTasks.clear();
        isScheduled.set(false);
    }
}
