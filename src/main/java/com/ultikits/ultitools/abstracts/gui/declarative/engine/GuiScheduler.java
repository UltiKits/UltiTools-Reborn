package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthExceededException;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.TriggerContext;
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
     * <p>
     * <b>Gate-2 Codex finding, PR #478 round 1:</b> this is the path {@link GuiRenderer#initialize}
     * drives the FIRST build through, and {@link GuiRenderer#performBuild}'s own off-main-thread
     * re-entrant guard -- neither passes through {@link #executeFrame()}'s try/catch, which is
     * where {@link RenderDepthExceededException} reporting (WR-01) lived until now. Since
     * mounting is where the depth guard (CR-01) actually fires, and mounting happens on the very
     * first build, that build's own trip is the MORE common case to miss, not an edge case. Both
     * branches below now report it; only the already-on-main-thread branch re-throws afterward
     * (preserving the existing "the caller of {@code runOnMainThread()} sees the exception
     * synchronously" contract {@link GuiRenderer#initialize} and every CR-01 test depend on) --
     * the off-thread branch's caller could never observe the deferred task's exception anyway
     * (Bukkit's own scheduler runs it later), so there is nothing to preserve there beyond adding
     * the report.
     *
     * @param task the task to run
     */
    public void runOnMainThread(@NotNull Runnable task) {
        if (isOnMainThread()) {
            try {
                task.run();
            } catch (RenderDepthExceededException e) {
                reportRenderDepthExceeded(e);
                throw e;
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    task.run();
                } catch (RenderDepthExceededException e) {
                    // Gate-2 Codex finding (round 2, PR #478): before catching this here to
                    // report it, the exception escaped this deferred Runnable and Bukkit's OWN
                    // scheduler logged it to console automatically -- catching it silently would
                    // have made the failure invisible on console whenever the collector is
                    // unavailable or disabled. logFrameTaskError() keeps that console diagnostic.
                    logFrameTaskError(e);
                    reportRenderDepthExceeded(e);
                    // Deliberately not re-thrown: this runs on Bukkit's own scheduler thread,
                    // asynchronously from whoever called runOnMainThread() -- there is no
                    // caller left to propagate to, exactly as before this fix.
                }
            });
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
                        Math.max(1, delay / 50)); // Convert to ticks
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
                    break; // Handle only one; the rest are handled on the next tick
                }
            } catch (RenderDepthExceededException e) {
                // WR-01: route a depth-guard trip into ErrorReportCollector so it reaches the
                // panel's error dashboard, not only the server console -- consistent with every
                // other exception-reporting path this framework documents. A dedicated catch
                // clause (rather than an instanceof check inside the generic one below) also
                // keeps this scoped to exactly this one exception type: a blanket "report every
                // GUI frame exception" change is a wider behaviour change than this fix's own
                // finding asked for.
                logFrameTaskError(e);
                reportRenderDepthExceeded(e);
            } catch (Exception e) {
                logFrameTaskError(e);
            }
        }
    }

    /**
     * The console-visible half of a GUI frame task's failure -- a warning naming the task plus
     * a full stack trace. Shared by every catch clause in {@link #executeFrame()} so the two log
     * lines cannot drift between the exception types that also do additional reporting
     * ({@link RenderDepthExceededException}) and those that do not.
     *
     * @param e the failure to log
     */
    private void logFrameTaskError(Exception e) {
        plugin.getLogger().warning("Error executing GUI frame task: " + e.getMessage());
        e.printStackTrace();
    }

    /**
     * Reports a {@link RenderDepthExceededException} to {@link ErrorReportCollector}.
     * <p>
     * No new per-GUI "failed/closed" bookkeeping is added here: the exception is always thrown
     * from the same guard site with a stable top stack frame (the guard name and depth vary in
     * the message, not in the class/method/line the stack trace records), so
     * {@code ErrorReportCollector}'s own fingerprint-based dedup already coalesces repeated
     * occurrences -- e.g. the same over-deep tree being rebuilt on every {@code setState()} call
     * -- into ONE report with an incrementing count, rather than one report per frame.
     *
     * @param e the depth-guard trip to report
     */
    private void reportRenderDepthExceeded(RenderDepthExceededException e) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return;
            }
            ErrorReportCollector erc = instance.getErrorReportCollector();
            if (erc == null) {
                return;
            }
            erc.reportError(e, null, TriggerContext.uncaught("GUI render frame: " + e.getMessage()));
        } catch (Exception ignored) {
            // Never re-enter logging from error reporting.
        }
    }

    /**
     * Runs every pending task immediately (blocking until complete).
     * <p>
     * <b>Note:</b> this method may only be called on the main thread.
     * <p>
     * <b>Gate-2 Codex finding, PR #478 round 3:</b> a {@link RenderDepthExceededException} from a
     * queued task now reaches {@link ErrorReportCollector} here too, matching
     * {@link #executeFrame()} and {@link #runOnMainThread}'s on-main-thread branch -- neither of
     * those catches ran for a task drained through this method. Still re-thrown immediately
     * afterward, preserving this method's own documented synchronous-drain contract (and,
     * unchanged from before this fix, still skipping the {@code isScheduled.set(false)} below on
     * that path -- not a new gap this fix introduces or is scoped to close).
     *
     * @throws IllegalStateException if not on the main thread
     */
    public void flush() {
        if (!isOnMainThread()) {
            throw new IllegalStateException("flush() must be called on main thread");
        }

        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            try {
                task.run();
            } catch (RenderDepthExceededException e) {
                reportRenderDepthExceeded(e);
                throw e;
            }
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
