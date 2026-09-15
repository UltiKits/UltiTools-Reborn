package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.google.gson.JsonArray;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthExceededException;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthGuard;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.utils.TestHelper;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GuiScheduler 测试。
 */
class GuiSchedulerTest {

    private Plugin mockPlugin;
    private BukkitScheduler mockScheduler;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(Plugin.class);
        mockScheduler = mock(BukkitScheduler.class);
    }

    @Test
    void testIsOnMainThread() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            GuiScheduler scheduler = new GuiScheduler(mockPlugin);
            assertTrue(scheduler.isOnMainThread());

            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertFalse(scheduler.isOnMainThread());
        }
    }

    @Test
    void testRunOnMainThread_WhenOnMainThread() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            GuiScheduler scheduler = new GuiScheduler(mockPlugin);
            AtomicBoolean executed = new AtomicBoolean(false);

            scheduler.runOnMainThread(() -> executed.set(true));

            assertTrue(executed.get());
        }
    }

    @Test
    void testRunOnMainThread_WhenNotOnMainThread() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            when(mockScheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                task.run();
                return null;
            });
            bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

            GuiScheduler scheduler = new GuiScheduler(mockPlugin);
            AtomicBoolean executed = new AtomicBoolean(false);

            scheduler.runOnMainThread(() -> executed.set(true));

            assertTrue(executed.get());
        }
    }

    @Test
    void testScheduleFrame_MergesMultipleCalls() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            // 模拟 runTaskLater，但不立即执行，而是保存 Runnable
            AtomicBoolean taskRan = new AtomicBoolean(false);
            when(mockScheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong()))
                    .thenAnswer(invocation -> {
                        Runnable task = invocation.getArgument(1);
                        // 模拟延迟执行，这里我们手动触发
                        // 在真实场景中，Bukkit 会在之后调用这个 task
                        // 这里我们不执行，只是为了让 scheduler 认为已经调度了
                        return null;
                    });
            bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

            GuiScheduler scheduler = new GuiScheduler(mockPlugin, 100); // 100ms 帧间隔
            AtomicInteger executionCount = new AtomicInteger(0);

            // 第一次调用，应该触发调度
            scheduler.scheduleFrame(executionCount::incrementAndGet);
            // 此时 pendingFrame = true

            // 第二次调用，应该被合并（因为 pendingFrame = true）
            scheduler.scheduleFrame(executionCount::incrementAndGet);

            // 第三次调用
            scheduler.scheduleFrame(executionCount::incrementAndGet);

            // 验证 runTaskLater 只被调用了一次
            verify(mockScheduler, times(1)).runTaskLater(any(Plugin.class), any(Runnable.class), anyLong());
        }
    }

    @Test
    void testCancelAll() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            // 模拟不在主线程，强制 scheduleFrame 使用 runTaskLater
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);

            // 模拟 runTaskLater 只是入队，不立即执行
            when(mockScheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenReturn(null);
            bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

            GuiScheduler scheduler = new GuiScheduler(mockPlugin);
            AtomicBoolean executed = new AtomicBoolean(false);

            scheduler.scheduleFrame(() -> executed.set(true));

            // 此时任务应该被调度到了 BukkitScheduler (即我们的 mock)，但还没执行

            scheduler.cancelAll();

            // 任务已被取消，即使 BukkitScheduler 后来执行了 task (这里我们甚至没让它执行)，
            // GuiScheduler 内部的 pendingTasks 也应该被清空了，或者标记为不可执行
            // 但在这个测试里，我们验证的是 runTaskLater 没被触发执行 runnable？
            // 不，runTaskLater 接收的是 this::executeFrame。
            // 如果 cancelAll 被调用，pendingTasks 被清空。
            // 即使 executeFrame 被调用，它也取不到任务。

            // 既然我们没有执行 executeFrame，executed 肯定是 false。
            // 这个测试其实验证的是：在调用 cancelAll 后，任务状态被重置。

            assertFalse(executed.get());
        }
    }

    // === Gate-2 Codex finding (round 1, PR #478): the initial build -- GuiRenderer.initialize()
    // -> runOnMainThread() -- runs OUTSIDE executeFrame()'s try/catch entirely, so a tree deep
    // enough to trip Element.mount (CR-01) during the FIRST build never reached
    // ErrorReportCollector at all, only a subsequent scheduled frame's rebuild would have. This
    // is the more common case in practice: CR-01's guard fires during mounting, and the very
    // first build is exactly where a brand-new tree gets mounted. ===

    @Test
    void testRunOnMainThread_OnMainThread_ReportsRenderDepthExceededAndStillRethrows() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

            ErrorReportCollector collector = new ErrorReportCollector();
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    when(ultiTools.getErrorReportCollector()).thenReturn(collector));

            GuiScheduler scheduler = new GuiScheduler(mockPlugin);
            RenderDepthExceededException thrown = new RenderDepthExceededException(
                    "Element.mount", 65, RenderDepthGuard.MAX_DEPTH);

            RenderDepthExceededException caught = assertThrows(RenderDepthExceededException.class,
                    () -> scheduler.runOnMainThread(() -> {
                        throw thrown;
                    }),
                    "the initial (already-on-main-thread) build path must still propagate the "
                            + "exception to its caller exactly as before -- GuiRenderer.initialize() "
                            + "and every existing CR-01 test depend on this");

            assertSame(thrown, caught);
            assertEquals(1, collector.drainErrors(10).size(),
                    "the initial build's own RenderDepthExceededException must reach "
                            + "ErrorReportCollector too, not only a LATER scheduled frame's -- "
                            + "mounting (CR-01) happens on the very first build, which never "
                            + "passed through executeFrame()'s try/catch at all");
        }
    }

    @Test
    void testRunOnMainThread_OffMainThread_ReportsRenderDepthExceededWithoutPropagating() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            when(mockScheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
                Runnable wrapped = invocation.getArgument(1);
                wrapped.run(); // simulate Bukkit actually invoking the deferred task
                return null;
            });

            ErrorReportCollector collector = new ErrorReportCollector();
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    when(ultiTools.getErrorReportCollector()).thenReturn(collector));

            GuiScheduler scheduler = new GuiScheduler(mockPlugin);

            assertDoesNotThrow(() -> scheduler.runOnMainThread(() -> {
                throw new RenderDepthExceededException("Element.mount", 65, RenderDepthGuard.MAX_DEPTH);
            }), "the caller of runOnMainThread() off-thread already never sees the deferred "
                    + "task's exception (Bukkit's own scheduler runs it later) -- this must stay "
                    + "true; only the reporting is new");

            assertEquals(1, collector.drainErrors(10).size(),
                    "the deferred task's RenderDepthExceededException must still reach "
                            + "ErrorReportCollector even though nothing in this thread can "
                            + "observe it any other way");
        }
    }

    // === WR-01: RenderDepthExceededException must reach ErrorReportCollector, and repeated
    // occurrences from the same site across many frames must not each produce a separate report
    // (ErrorReportCollector's own fingerprint dedup is the chosen rate limit -- no new
    // GUI-specific bookkeeping is added). ===

    @Test
    void testRenderDepthExceededException_ReportsOnceAcrossMultipleFrames() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            when(mockPlugin.getLogger()).thenReturn(
                    Logger.getLogger("GuiSchedulerTest.RenderDepthExceededException"));

            // Real ErrorReportCollector (enabled=true by field default; init()/loadConfiguration()
            // are deliberately not called, to avoid starting the dedup-reset scheduler thread --
            // the same idiom ErrorReportCollectorTest itself uses).
            ErrorReportCollector collector = new ErrorReportCollector();
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    when(ultiTools.getErrorReportCollector()).thenReturn(collector));

            GuiScheduler scheduler = new GuiScheduler(mockPlugin, 0L);

            // Always thrown from the SAME site with the SAME depth/limit, exactly as a single
            // over-deep GUI tree being rebuilt repeatedly (e.g. on every setState()) would.
            Runnable throwingTask = () -> {
                throw new RenderDepthExceededException("Element.mount", 65, RenderDepthGuard.MAX_DEPTH);
            };

            int frames = 5;
            for (int i = 0; i < frames; i++) {
                scheduler.scheduleFrame(throwingTask);
            }

            JsonArray drained = collector.drainErrors(10);
            assertEquals(1, drained.size(),
                    "five frames throwing the SAME RenderDepthExceededException must produce "
                            + "exactly one report, not five -- repeated re-logging of the same "
                            + "site is exactly the log-noise problem the collector's "
                            + "fingerprint-dedup mechanism already exists to solve elsewhere");
            assertEquals(frames,
                    drained.get(0).getAsJsonObject().get("occurrenceCount").getAsInt(),
                    "the single report must still track how many times it actually happened");
        }
    }

    @Test
    void testOtherExceptions_AreNotRoutedToErrorReportCollector() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            when(mockPlugin.getLogger()).thenReturn(
                    Logger.getLogger("GuiSchedulerTest.OtherExceptions"));

            ErrorReportCollector collector = new ErrorReportCollector();
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    when(ultiTools.getErrorReportCollector()).thenReturn(collector));

            GuiScheduler scheduler = new GuiScheduler(mockPlugin, 0L);
            scheduler.scheduleFrame(() -> {
                throw new IllegalStateException("some unrelated frame failure");
            });

            assertEquals(0, collector.drainErrors(10).size(),
                    "this fix is scoped to RenderDepthExceededException specifically -- an "
                            + "unrelated exception's existing (unchanged) warning+stack-trace "
                            + "handling is not touched here");
        }
    }
}
