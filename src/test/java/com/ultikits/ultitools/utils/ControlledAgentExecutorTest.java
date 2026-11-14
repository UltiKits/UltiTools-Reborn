package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The virtual-clock executor double itself: one due task can cancel a sibling or stop the executor. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ControlledAgentExecutorTest {
    private final AtomicLong clock = new AtomicLong();
    private ControlledAgentExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private ControlledAgentExecutor agentExecutor() {
        executor = new ControlledAgentExecutor(clock, runnable -> {
            Thread thread = new Thread(runnable, "controlled-agent-executor-test");
            thread.setDaemon(true);
            return thread;
        });
        return executor;
    }

    @Test
    void aDueTaskCancelledByAnEarlierOneInTheSameSnapshotIsSkipped() throws Exception {
        ControlledAgentExecutor agentExecutor = agentExecutor();
        AtomicBoolean cancelledTaskRan = new AtomicBoolean();
        ScheduledFuture<?> victim = agentExecutor.schedule(
                () -> cancelledTaskRan.set(true), 100, TimeUnit.MILLISECONDS);
        // A block body keeps this lambda a Runnable: an expression body returning boolean binds to
        // schedule(Callable, ...), which runs on the real clock and outside this virtual one.
        agentExecutor.schedule(() -> { victim.cancel(false); }, 50, TimeUnit.MILLISECONDS);
        clock.addAndGet(100);

        assertThatCode(agentExecutor::runDue).as("a cancelled sibling is not an error").doesNotThrowAnyException();
        assertThat(cancelledTaskRan).isFalse();
    }

    @Test
    void aTaskThatShutsTheExecutorDownDoesNotRejectTheRestOfTheSnapshot() throws Exception {
        ControlledAgentExecutor agentExecutor = agentExecutor();
        AtomicBoolean laterTaskRan = new AtomicBoolean();
        agentExecutor.schedule(agentExecutor::shutdown, 50, TimeUnit.MILLISECONDS);
        agentExecutor.schedule(() -> laterTaskRan.set(true), 100, TimeUnit.MILLISECONDS);
        clock.addAndGet(100);

        assertThatCode(agentExecutor::runDue).as("no RejectedExecutionException").doesNotThrowAnyException();
        assertThat(laterTaskRan).as("production runs delayed tasks that survive shutdown").isTrue();
        assertThat(agentExecutor.pendingDelayedAtShutdown())
                .as("both were already due, so neither is left queued").isZero();
    }
}
