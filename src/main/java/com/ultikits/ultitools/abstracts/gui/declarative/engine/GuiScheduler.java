package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GuiScheduler is responsible for ensuring every GUI operation runs on the Bukkit main thread.
 * <p>
 * It provides:
 * <ul>
 *   <li>a check for whether the current thread is the main thread</li>
 *   <li>scheduling a task to run on the main thread</li>
 *   <li>frame scheduling: coalescing multiple update requests within a short window</li>
 *   <li>prevention of duplicate scheduling</li>
 * </ul>
 * <p>
 * <b>Thread-safety contract:</b> {@code State.setState(...)} and {@code Element.markNeedsBuild()}
 * are safe to call from any thread -- a call made off the main thread is marshalled onto it, and
 * multiple calls that land within the same frame window are coalesced via this scheduler's
 * {@link java.util.concurrent.atomic.AtomicBoolean} compare-and-set guard, at a nominal 16ms frame
 * interval (floored to one Bukkit tick when scheduling has to wait for one). {@link #flush()}, by
 * contrast, throws {@link IllegalStateException} when called off the main thread -- it runs
 * pending work synchronously rather than marshalling it.
 *
 * <p><strong>Frame scheduling:</strong></p>
 * <pre>
 * Timeline:
 * |----16ms----|----16ms----|----16ms----|
 *    ↑ ↑           ↑
 *   setState     rebuild actually runs
 *    ↑
 *   setState (coalesced into the same frame)
 * </pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class GuiScheduler {

    /**
     * The default frame interval, in milliseconds.
     * Minecraft's default tick is 50ms, but GUI updates can run more frequently.
     */
    private static final long DEFAULT_FRAME_INTERVAL_MS = 16; // ~60 FPS

    private final Plugin plugin;
    private final long frameIntervalMs;
    private final AtomicBoolean isScheduled = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    private long lastFrameTime = 0;

    /**
     * Creates a GuiScheduler using the default plugin instance.
     */
    public GuiScheduler() {
        this(UltiTools.getInstance());
    }

    /**
     * Creates a GuiScheduler.
     *
     * @param plugin the plugin instance
     */
    public GuiScheduler(@NotNull Plugin plugin) {
        this(plugin, DEFAULT_FRAME_INTERVAL_MS);
    }

    /**
     * Creates a GuiScheduler with the given frame interval.
     *
     * @param plugin          the plugin instance
     * @param frameIntervalMs the frame interval, in milliseconds
     */
    public GuiScheduler(@NotNull Plugin plugin, long frameIntervalMs) {
        this.plugin = plugin;
        this.frameIntervalMs = frameIntervalMs;
    }

    /**
     * Checks whether the current thread is the main thread.
     *
     * @return true if on the main thread
     */
    public boolean isOnMainThread() {
        return Bukkit.isPrimaryThread();
    }

    /**
     * Ensures the task runs on the main thread.
     * <p>
     * If already on the main thread, runs immediately.
     * Otherwise, schedules it to run on the main thread.
     *
     * @param task the task to run
     */
    public void runOnMainThread(@NotNull Runnable task) {
        if (isOnMainThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Schedules a frame task.
     * <p>
     * This method implements the frame-coalescing mechanism: multiple calls within the same
     * frame window trigger only one actual execution.
     *
     * @param frameTask the frame task
     */
    public void scheduleFrame(@NotNull Runnable frameTask) {
        pendingTasks.offer(frameTask);

        if (isScheduled.compareAndSet(false, true)) {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastFrame = currentTime - lastFrameTime;
            long delay = Math.max(0, frameIntervalMs - timeSinceLastFrame);

            if (delay == 0 && isOnMainThread()) {
                // Can run immediately
                executeFrame();
            } else {
                // Schedule for the next frame
                Bukkit.getScheduler().runTaskLater(plugin, this::executeFrame, 
                        Math.max(1, delay / 50)); // 转换为 tick
            }
        }
    }

    /**
     * Runs every pending frame task.
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
                    // Not on the main thread -- reschedule
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
     * Runs every pending task immediately (blocking until complete).
     * <p>
     * <b>Note:</b> this method may only be called on the main thread.
     *
     * @throws IllegalStateException if not on the main thread
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
     * Cancels every pending task.
     */
    public void cancelAll() {
        pendingTasks.clear();
        isScheduled.set(false);
    }
}
