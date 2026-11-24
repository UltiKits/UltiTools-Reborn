package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.scheduling.TaskHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@Timeout(10)
@ExtendWith(CommandTestExtension.class)
class CommandUnloadWaitTest {
    @Test
    void containerCloseWaitsForTheAsyncBodyAndItsCompletionHooks() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        var executor = new Fixture();
        AtomicInteger completions = new AtomicInteger();
        executor.addValidator(completionCounter(completions));
        Future<?>[] submitted = new Future<?>[1];
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenAnswer(call -> {
            submitted[0] = worker.submit(call.getArgument(0, Runnable.class));
            return mock(TaskHandle.class);
        });
        var container = new SimpleContainer();
        try {
            container.registerSingleton("executor", executor);
            assertThat(dispatch(executor, "slow")).isTrue();
            assertThat(executor.started.await(5, TimeUnit.SECONDS)).isTrue();
            timer.schedule(executor.release::countDown, 50, TimeUnit.MILLISECONDS);
            container.close();
            assertThat(completions.get()).isEqualTo(1);
            assertThat(executor.finished.getCount()).isZero();
        } finally {
            executor.release.countDown();
            if (submitted[0] != null) {
                submitted[0].get(5, TimeUnit.SECONDS);
            }
            container.close();
            worker.shutdownNow();
            timer.shutdownNow();
        }
    }

    @Test
    void deadlineWarnsOnceAndDoesNotInterruptTheBodyOrRepeatTheBudget() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        var executor = new Fixture();
        executor.asyncUnloadWait = Duration.ofMillis(200);
        Future<?>[] submitted = new Future<?>[1];
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenAnswer(call -> {
            submitted[0] = worker.submit(call.getArgument(0, Runnable.class));
            return mock(TaskHandle.class);
        });
        try (var warnings = new Warnings()) {
            assertThat(dispatch(executor, "slow")).isTrue();
            assertThat(executor.started.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            executor.awaitAsyncCommands();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(150), Duration.ofSeconds(2));
            assertThat(executor.finished.getCount()).isEqualTo(1);
            assertThat(executor.interrupted).isFalse();
            assertThat(warnings.records).hasSize(1);
            assertThat(warnings.records.getFirst().getMessage()).contains("continues in the background");
            assertThat(warnings.records.getFirst().getParameters()).containsExactly(Fixture.class.getName(), 1);
            start = System.nanoTime();
            executor.awaitAsyncCommands();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(150));
            assertThat(warnings.records).hasSize(1);
            executor.release.countDown();
            submitted[0].get(5, TimeUnit.SECONDS);
            assertThat(executor.interrupted).isFalse();
            assertThat(executor.finished.getCount()).isZero();
        } finally {
            executor.release.countDown();
            if (submitted[0] != null) {
                submitted[0].get(5, TimeUnit.SECONDS);
            }
            worker.shutdownNow();
        }
    }

    @Test
    void refusalRollsBackTheReservationAndNotifiesPassedValidators() {
        var executor = new Fixture();
        executor.asyncUnloadWait = Duration.ofMillis(20);
        AtomicInteger refusals = new AtomicInteger();
        executor.addValidator(refusalCounter(refusals));
        var rejection = new RejectedExecutionException("closed scheduler");
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenThrow(rejection);
        try (var warnings = new Warnings()) {
            assertThatThrownBy(() -> dispatch(executor, "fast")).isSameAs(rejection);
            executor.awaitAsyncCommands();
            assertThat(refusals.get()).isEqualTo(1);
            assertThat(warnings.records).isEmpty();
        }
    }

    @Test
    void completionBeforeSubmissionReturnsDoesNotLeaveAPhantomInvocation() {
        var executor = new Fixture();
        executor.asyncUnloadWait = Duration.ofMillis(20);
        AtomicInteger completions = new AtomicInteger();
        executor.addValidator(completionCounter(completions));
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return mock(TaskHandle.class);
        });
        try (var warnings = new Warnings()) {
            assertThat(dispatch(executor, "fast")).isTrue();
            executor.awaitAsyncCommands();
            assertThat(completions.get()).isEqualTo(1);
            assertThat(warnings.records).isEmpty();
        }
    }

    @Test
    void unloadingRefusesNewAsyncBodiesAndStillNotifiesValidators() {
        var executor = new Fixture();
        AtomicInteger refusals = new AtomicInteger();
        executor.addValidator(refusalCounter(refusals));
        executor.awaitAsyncCommands();
        assertThatThrownBy(() -> dispatch(executor, "fast")).isInstanceOf(RejectedExecutionException.class);
        assertThat(refusals.get()).isEqualTo(1);
        assertThat(executor.finished.getCount()).isEqualTo(1);
    }

    @Test
    void runtimeDrainsSingletonAliasesOnlyOnce() {
        BaseCommandExecutor first = mock(BaseCommandExecutor.class);
        BaseCommandExecutor second = mock(BaseCommandExecutor.class);
        CommandRuntime.awaitAsyncCommands(List.of(first, "not an executor", second, first));
        verify(first, times(1)).awaitAsyncCommands();
        verify(second, times(1)).awaitAsyncCommands();
    }

    private static boolean dispatch(Fixture executor, String command) {
        return CommandTestSupport.dispatch(executor, mock(CommandSender.class), null, "probe", new String[] {command});
    }

    private static CommandValidator completionCounter(AtomicInteger completions) {
        return new CommandValidator() {
            @Override
            public ValidationResult validate(CommandContext context) {
                return ValidationResult.success();
            }
            @Override
            public void onComplete(CommandContext context, boolean succeeded) {
                completions.incrementAndGet();
            }
        };
    }

    private static CommandValidator refusalCounter(AtomicInteger refusals) {
        return new CommandValidator() {
            @Override
            public ValidationResult validate(CommandContext context) {
                return ValidationResult.success();
            }
            @Override
            public void onRefused(CommandContext context) {
                refusals.incrementAndGet();
            }
        };
    }

    private static final class Warnings extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(BaseCommandExecutor.class.getName());
        final List<LogRecord> records = new ArrayList<>();
        Warnings() {
            logger.addHandler(this);
        }
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
                records.add(record);
            }
        }
        @Override
        public void flush() { /* No buffered output. */ }
        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }

    static class Fixture extends BaseCommandExecutor {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean interrupted;
        @RunAsync
        @CmdMapping(format = "slow")
        public void slow() throws InterruptedException {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the command body");
                }
            } catch (InterruptedException failure) {
                interrupted = true;
                throw failure;
            } finally {
                finished.countDown();
            }
        }
        @RunAsync
        @CmdMapping(format = "fast")
        public void fast() {
            finished.countDown();
        }
        @Override
        protected void handleHelp(CommandSender sender) { /* Not exercised. */ }
    }
}
