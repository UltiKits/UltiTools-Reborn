package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.Ticks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class TaskRecordRaceTest {
    @Test
    void cancellationAndDelivery_haveExactlyOneTerminalOutcome() throws Exception {
        QueuedBackend backend = new QueuedBackend();
        PaperSchedulerEngine engine = engine(backend.backend);
        Scheduler scheduler = engine.newOwner("race");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 1_000; i++) {
                AtomicInteger bodies = new AtomicInteger();
                AtomicInteger completions = new AtomicInteger();
                TaskHandle task = scheduler.global().run(bodies::incrementAndGet);
                task.completion().whenComplete((value, failure) -> completions.incrementAndGet());
                race(workers, task::cancel, backend.queue.remove());
                assertThat(task.state()).isIn(TaskState.COMPLETED, TaskState.CANCELLED);
                assertThat(completions).hasValue(1);
                assertThat(bodies.get()).isEqualTo(task.state() == TaskState.COMPLETED ? 1 : 0);
                assertThat(((OwnedScheduler) scheduler).live).isEmpty();
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unloadAndSubmission_neverLeaveWorkAfterRetirement() throws Exception {
        QueuedBackend backend = new QueuedBackend();
        PaperSchedulerEngine engine = engine(backend.backend);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 1_000; i++) {
                Scheduler scheduler = engine.newOwner("same-name");
                AtomicInteger bodies = new AtomicInteger();
                List<TaskHandle> accepted = new ArrayList<>();
                AtomicInteger rejected = new AtomicInteger();
                race(workers, () -> {
                    try {
                        accepted.add(scheduler.global().run(bodies::incrementAndGet));
                    } catch (RejectedExecutionException expected) {
                        rejected.incrementAndGet();
                    }
                }, () -> engine.retire(scheduler));
                backend.drain();
                assertThat(accepted.size() + rejected.get()).isEqualTo(1);
                accepted.forEach(task -> assertThat(task.state()).isEqualTo(TaskState.CANCELLED));
                assertThat(bodies).hasValue(0);
                assertThat(((OwnedScheduler) scheduler).live).isEmpty();
                assertThatThrownBy(() -> scheduler.async().run(bodies::incrementAndGet))
                        .isInstanceOf(RejectedExecutionException.class);
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void retireCompletionCanSubmitToOtherOwner_andCannotResurrectItsOwnOwner() {
        QueuedBackend backend = new QueuedBackend();
        PaperSchedulerEngine engine = engine(backend.backend);
        Scheduler first = engine.newOwner("same-name");
        Scheduler second = engine.newOwner("same-name");
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger bodies = new AtomicInteger();
        TaskHandle pending = first.global().run(bodies::incrementAndGet);
        pending.completion().whenComplete((value, failure) -> {
            assertThatThrownBy(() -> first.global().run(bodies::incrementAndGet))
                    .isInstanceOf(RejectedExecutionException.class);
            second.global().run(bodies::incrementAndGet);
            completions.incrementAndGet();
        });
        engine.retire(first);
        backend.drain();
        assertThat(completions).hasValue(1);
        assertThat(bodies).hasValue(1);
        assertThat(pending.completion().toCompletableFuture()).isCompletedExceptionally();
    }

    @Test
    void backendCancelFailure_doesNotStrandCompletionOrOtherTasks() {
        QueuedBackend backend = new QueuedBackend();
        when(backend.paper.cancel()).thenThrow(new IllegalStateException("backend cleanup failed"));
        PaperSchedulerEngine engine = engine(backend.backend);
        Scheduler scheduler = engine.newOwner("cleanup");
        AtomicInteger bodies = new AtomicInteger();
        TaskHandle first = scheduler.global().run(bodies::incrementAndGet);
        TaskHandle second = scheduler.global().run(bodies::incrementAndGet);
        engine.retire(scheduler);
        backend.drain();
        assertThat(first.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(second.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(first.completion().toCompletableFuture()).isCompletedExceptionally();
        assertThat(bodies).hasValue(0);
    }

    @Test
    void asyncCallbackBeforeSubmissionReturns_doesNotLoseSuccessorHandle() {
        QueuedBackend backend = new QueuedBackend();
        AtomicInteger submissions = new AtomicInteger();
        when(backend.backend.async(any(), anyLong())).thenAnswer(invocation -> {
            Consumer<ScheduledTask> callback = invocation.getArgument(0);
            ScheduledTask paper = mock(ScheduledTask.class);
            if (submissions.incrementAndGet() == 1) {
                // A different thread may finish a delivery while its submission is still returning.
                Thread worker = new Thread(() -> callback.accept(paper), "early-delivery");
                worker.start();
                worker.join(10_000);
                assertThat(worker.isAlive()).isFalse();
            } else {
                backend.queue.add(() -> callback.accept(paper));
            }
            return paper;
        });
        Scheduler scheduler = engine(backend.backend).newOwner("early");
        AtomicInteger runs = new AtomicInteger();
        TaskHandle repeating = scheduler.async().runRepeating(Duration.ZERO, Ticks.TICK,
                ignored -> runs.incrementAndGet());
        assertThat(runs).hasValue(1);
        assertThat(repeating.cancel()).isTrue();
        backend.drain();
        assertThat(runs).hasValue(1);
        assertThat(repeating.state()).isEqualTo(TaskState.CANCELLED);
    }

    @Test
    void supplierCancellationBeforeDelivery_completesWithoutRetainingLiveRecord() {
        QueuedBackend backend = new QueuedBackend();
        Scheduler scheduler = engine(backend.backend).newOwner("supplier");
        AtomicInteger runs = new AtomicInteger();
        assertThat(scheduler.global().supply(runs::incrementAndGet).cancel(false)).isTrue();
        backend.drain();
        assertThat(runs).hasValue(0);
        assertThat(((OwnedScheduler) scheduler).live).isEmpty();
    }

    private static PaperSchedulerEngine engine(PaperBackend backend) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new PaperSchedulerEngine(mock(Plugin.class), logger, backend);
    }

    private static void race(ExecutorService workers, Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<?> a = workers.submit(() -> { await(start); first.run(); });
        Future<?> b = workers.submit(() -> { await(start); second.run(); });
        start.countDown();
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    static final class QueuedBackend {
        final PaperBackend backend = mock(PaperBackend.class);
        final ScheduledTask paper = mock(ScheduledTask.class);
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

        QueuedBackend() {
            doAnswer(invocation -> {
                Consumer<ScheduledTask> callback = invocation.getArgument(0);
                queue.add(() -> callback.accept(paper));
                return paper;
            }).when(backend).global(any(), anyLong());
        }

        void drain() {
            Runnable next;
            while ((next = queue.poll()) != null) {
                next.run();
            }
        }
    }
}
