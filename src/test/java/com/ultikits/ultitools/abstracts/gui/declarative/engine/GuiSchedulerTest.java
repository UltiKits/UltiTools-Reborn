package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.google.gson.JsonArray;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthExceededException;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthGuard;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.utils.TestHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Timeout(30)
class GuiSchedulerTest {
    private TaskContext context;
    private GuiScheduler scheduler;
    private Queue<Runnable> queued;
    private List<TaskHandle> handles;
    private boolean onThread;
    private Logger logger;

    @BeforeEach void setUp() {
        Scheduler facade = mock(Scheduler.class);
        Player viewer = mock(Player.class);
        context = mock(TaskContext.class);
        when(facade.entity(viewer)).thenReturn(context);
        when(context.isCurrentThread()).thenAnswer(call -> onThread);
        queued = new ArrayDeque<>();
        handles = new ArrayList<>();
        when(context.run(any(Runnable.class))).thenAnswer(call -> {
            TaskHandle handle = mock(TaskHandle.class);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            when(handle.completion()).thenReturn(completion.minimalCompletionStage());
            when(handle.cancel()).thenAnswer(cancel -> completion.cancel(false));
            Runnable task = call.getArgument(0);
            queued.add(() -> { task.run(); completion.complete(null); });
            handles.add(handle);
            return handle;
        });
        onThread = true;
        logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(instance -> when(instance.getLogger()).thenReturn(logger));
        scheduler = new GuiScheduler(facade, viewer);
    }

    private void runNext() { queued.remove().run(); }
    private static RenderDepthExceededException depth() {
        return new RenderDepthExceededException("Element.mount", 65, RenderDepthGuard.MAX_DEPTH);
    }
    private ErrorReportCollector collector() {
        ErrorReportCollector collector = new ErrorReportCollector();
        TestHelper.mockUltiToolsInstance(instance -> {
            when(instance.getLogger()).thenReturn(logger);
            when(instance.getErrorReportCollector()).thenReturn(collector);
        });
        return collector;
    }

    @Test void testIsOnMainThread() {
        assertThat(scheduler.isOnMainThread()).isTrue();
        onThread = false;
        assertThat(scheduler.isOnMainThread()).isFalse();
    }
    @Test void testRunOnMainThread_WhenOnMainThread() {
        AtomicInteger runs = new AtomicInteger();
        scheduler.runOnMainThread(runs::incrementAndGet);
        assertThat(runs).hasValue(1);
        verify(context, never()).run(any(Runnable.class));
    }
    @Test void testRunOnMainThread_WhenNotOnMainThread() {
        AtomicInteger runs = new AtomicInteger();
        onThread = false;
        scheduler.runOnMainThread(runs::incrementAndGet);
        assertThat(runs).hasValue(0);
        onThread = true; runNext();
        assertThat(runs).hasValue(1);
    }
    @Test void sameTickRequestsRunOneFrame() {
        AtomicInteger frames = new AtomicInteger();
        Runnable frame = frames::incrementAndGet;
        scheduler.scheduleFrame(frame); scheduler.scheduleFrame(frame);
        assertThat(frames).hasValue(0);
        assertThat(queued).hasSize(1);
        runNext();
        assertThat(frames).hasValue(1);
    }
    @Test void testScheduleFrame_MergesMultipleCalls() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        scheduler.scheduleFrame(a::incrementAndGet); scheduler.scheduleFrame(b::incrementAndGet);
        assertThat(queued).hasSize(1);
        runNext();
        assertThat(a).hasValue(1); assertThat(b).hasValue(1);
    }
    @Test void testCancelAll() {
        AtomicInteger runs = new AtomicInteger();
        scheduler.scheduleFrame(runs::incrementAndGet);
        onThread = false; scheduler.runOnMainThread(runs::incrementAndGet);
        scheduler.cancelAll();
        handles.forEach(handle -> verify(handle).cancel());
        onThread = true;
        while (!queued.isEmpty()) { runNext(); }
        assertThat(runs).hasValue(0);
        scheduler.scheduleFrame(runs::incrementAndGet); runNext();
        assertThat(runs).hasValue(1);
    }
    @Test void testRunOnMainThread_OnMainThread_ReportsRenderDepthExceededAndStillRethrows() {
        ErrorReportCollector collector = collector();
        RenderDepthExceededException failure = depth();
        assertThat(assertThrows(RenderDepthExceededException.class,
                () -> scheduler.runOnMainThread(() -> { throw failure; }))).isSameAs(failure);
        assertThat(collector.drainErrors(10)).hasSize(1);
    }
    @Test void testRunOnMainThread_OffMainThread_ReportsRenderDepthExceededWithoutPropagating() {
        ErrorReportCollector collector = collector();
        RenderDepthExceededException failure = depth();
        onThread = false;
        scheduler.runOnMainThread(() -> { throw failure; });
        onThread = true;
        assertThatCode(this::runNext).doesNotThrowAnyException();
        assertThat(collector.drainErrors(10)).hasSize(1);
        verify(logger).log(eq(Level.WARNING), contains("Error executing GUI frame task"), same(failure));
    }
    @Test void testFlush_ReportsRenderDepthExceededAndStillRethrows() {
        ErrorReportCollector collector = collector();
        RenderDepthExceededException failure = depth();
        scheduler.scheduleFrame(() -> { throw failure; });
        assertThat(assertThrows(RenderDepthExceededException.class, scheduler::flush)).isSameAs(failure);
        assertThat(collector.drainErrors(10)).hasSize(1);
    }
    @Test void testExecuteFrame_RescheduledTask_ReportsRenderDepthExceededWhenRunLater() {
        ErrorReportCollector collector = collector();
        RenderDepthExceededException failure = depth();
        scheduler.scheduleFrame(() -> { throw failure; });
        onThread = false; runNext();
        assertThat(queued).hasSize(1);
        onThread = true; runNext();
        assertThat(collector.drainErrors(10)).hasSize(1);
        verify(logger).log(eq(Level.WARNING), contains("Error executing GUI frame task"), same(failure));
    }
    @Test void testRenderDepthExceededException_ReportsOnceAcrossMultipleFrames() {
        ErrorReportCollector collector = collector();
        Runnable failure = () -> { throw depth(); };
        for (int i = 0; i < 5; i++) { scheduler.scheduleFrame(failure); runNext(); }
        JsonArray errors = collector.drainErrors(10);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getAsJsonObject().get("occurrenceCount").getAsInt()).isEqualTo(5);
    }
    @Test void frameFailureIsOneWarningWithOriginalThrowableAndNoStderrTrace() {
        IllegalStateException failure = spy(new IllegalStateException("frame failed"));
        scheduler.scheduleFrame(() -> { throw failure; }); runNext();
        verify(logger).log(Level.WARNING, "Error executing GUI frame task: frame failed", failure);
        verifyNoMoreInteractions(logger);
        verify(failure, never()).printStackTrace();
        assertThat(queued).isEmpty();
    }
    @Test void deferredErrorIsLoggedOnceAtSevereAndRethrownUnchanged() {
        AssertionError failure = new AssertionError("fatal frame");
        scheduler.scheduleFrame(() -> { throw failure; });
        assertThatThrownBy(this::runNext).isSameAs(failure);
        verify(logger).log(Level.SEVERE, "Error executing GUI frame task: fatal frame", failure);
        verifyNoMoreInteractions(logger);
    }
    @Test void frameFailureIsSafeWhenLoggerIsMissing() {
        TestHelper.mockUltiToolsInstance(instance -> when(instance.getLogger()).thenReturn(null));
        assertFrameFailureDoesNotStopLaterTask();
    }
    @Test void frameFailureIsSafeWhenLoggerLookupThrows() {
        TestHelper.mockUltiToolsInstance(instance -> when(instance.getLogger()).thenThrow(new IllegalStateException("no logger")));
        assertFrameFailureDoesNotStopLaterTask();
    }
    @Test void frameFailureIsSafeWhenLoggerHandlerThrows() {
        doThrow(new IllegalStateException("broken handler")).when(logger).log(any(Level.class), anyString(), any(Throwable.class));
        assertFrameFailureDoesNotStopLaterTask();
    }
    private void assertFrameFailureDoesNotStopLaterTask() {
        scheduler.scheduleFrame(() -> { throw new IllegalStateException("frame failed"); });
        AtomicInteger runs = new AtomicInteger();
        scheduler.scheduleFrame(runs::incrementAndGet); runNext();
        assertThat(runs).hasValue(1);
    }
    @Test void failedLoggingDoesNotPreventRenderDepthReporting() {
        ErrorReportCollector collector = collector();
        doThrow(new IllegalStateException("broken handler")).when(logger).log(any(Level.class), anyString(), any(Throwable.class));
        scheduler.scheduleFrame(() -> { throw depth(); }); runNext();
        assertThat(collector.drainErrors(10)).hasSize(1);
    }
    @Test void testOtherExceptions_AreNotRoutedToErrorReportCollector() {
        ErrorReportCollector collector = collector();
        scheduler.scheduleFrame(() -> { throw new IllegalStateException("other failure"); }); runNext();
        assertThat(collector.drainErrors(10)).isEmpty();
    }
    @Test void flushRequiresOwningThreadAndDrainsFollowUpWork() {
        onThread = false;
        assertThatThrownBy(scheduler::flush).isInstanceOf(IllegalStateException.class);
        onThread = true;
        AtomicInteger runs = new AtomicInteger();
        scheduler.scheduleFrame(() -> { runs.incrementAndGet(); scheduler.scheduleFrame(runs::incrementAndGet); });
        scheduler.flush();
        assertThat(runs).hasValue(2);
        while (!queued.isEmpty()) { runNext(); }
        assertThat(runs).hasValue(2);
    }
    @Test void rejectedSubmissionCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger();
        TaskHandle handle = mock(TaskHandle.class);
        when(handle.completion()).thenReturn(new CompletableFuture<Void>().minimalCompletionStage());
        when(context.run(any(Runnable.class))).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) { throw new RejectedExecutionException("rejected"); }
            queued.add(call.getArgument(0));
            return handle;
        });
        AtomicInteger runs = new AtomicInteger();
        Runnable frame = runs::incrementAndGet;
        assertThatThrownBy(() -> scheduler.scheduleFrame(frame)).isInstanceOf(RejectedExecutionException.class);
        scheduler.scheduleFrame(frame); runNext();
        assertThat(attempts).hasValue(2);
        assertThat(runs).hasValue(1);
    }
    @Test void cancellingInsideAFrameStopsItsRemainingBudget() {
        AtomicInteger skipped = new AtomicInteger();
        scheduler.scheduleFrame(scheduler::cancelAll); scheduler.scheduleFrame(skipped::incrementAndGet);
        runNext();
        assertThat(skipped).hasValue(0);
    }
}
