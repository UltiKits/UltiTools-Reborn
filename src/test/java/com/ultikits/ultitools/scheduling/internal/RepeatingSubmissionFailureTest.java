package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskRetiredException;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.Ticks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;

class RepeatingSubmissionFailureTest {
    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(false, new IllegalPluginAccessException("host disabled"), TaskState.RETIRED),
                Arguments.of(true, new IllegalPluginAccessException("host disabled"), TaskState.RETIRED),
                Arguments.of(false, new RejectedExecutionException("scheduler stopped"), TaskState.RETIRED),
                Arguments.of(true, new RejectedExecutionException("scheduler stopped"), TaskState.RETIRED),
                Arguments.of(false, new IllegalStateException("backend failed"), TaskState.FAILED));
    }

    @Test
    void entityRetiredBeforeNextSubmission_nullHandleFinishesTheRunningRepeat() {
        PaperBackend backend = mock(PaperBackend.class);
        ScheduledTask paper = mock(ScheduledTask.class);
        AtomicReference<Consumer<ScheduledTask>> delivery = new AtomicReference<>();
        AtomicInteger submissions = new AtomicInteger();
        doAnswer(invocation -> {
            if (submissions.incrementAndGet() > 1) {
                return null;
            }
            delivery.set(invocation.getArgument(1));
            return paper;
        }).when(backend).entity(any(), any(), any(), anyLong());
        OwnedScheduler scheduler = (OwnedScheduler) new PaperSchedulerEngine(
                mock(Plugin.class), Logger.getAnonymousLogger(), backend).newOwner("removed");
        AtomicInteger runs = new AtomicInteger();
        TaskHandle task = scheduler.entity(mock(Entity.class))
                .runRepeating(Duration.ZERO, Ticks.TICK, ignored -> runs.incrementAndGet());
        delivery.get().accept(paper);
        assertThat(task.state()).isEqualTo(TaskState.RETIRED);
        assertThat(task.completion().toCompletableFuture()).isCompletedExceptionally()
                .hasFailedWithThrowableThat().isInstanceOf(TaskRetiredException.class);
        assertThat(scheduler.live).isEmpty();
        assertThat(runs).hasValue(0);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void nextDeliveryFailure_finishesWithoutMisreportingABodyFailure(
            boolean async, RuntimeException failure, TaskState expected) {
        PaperBackend backend = mock(PaperBackend.class);
        Logger logger = mock(Logger.class);
        AtomicReference<Consumer<ScheduledTask>> delivery = new AtomicReference<>();
        AtomicInteger submissions = new AtomicInteger();
        ScheduledTask paper = mock(ScheduledTask.class);
        Answer<ScheduledTask> enqueue = invocation -> {
            if (submissions.incrementAndGet() > 1) {
                throw failure;
            }
            delivery.set(invocation.getArgument(0));
            return paper;
        };
        if (async) {
            doAnswer(enqueue).when(backend).async(any(), anyLong());
        } else {
            doAnswer(enqueue).when(backend).global(any(), anyLong());
        }
        OwnedScheduler scheduler = (OwnedScheduler) new PaperSchedulerEngine(mock(Plugin.class), logger, backend)
                .newOwner("stopping");
        AtomicInteger runs = new AtomicInteger();
        TaskHandle task = (async ? scheduler.async() : scheduler.global())
                .runRepeating(Duration.ZERO, Ticks.TICK, ignored -> runs.incrementAndGet());
        delivery.get().accept(paper);
        assertThat(task.state()).isEqualTo(expected);
        assertThat(task.completion().toCompletableFuture()).isCompletedExceptionally()
                .hasFailedWithThrowableThat().isSameAs(failure);
        assertThat(scheduler.live).isEmpty();
        assertThat(runs).hasValue(0);
        assertThat(submissions).hasValue(2);
        verifyNoInteractions(logger);
    }
}
