package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        var waits = new FakeWaits();
        var executor = new Fixture();
        AtomicInteger completions = new AtomicInteger();
        executor.addValidator(completionCounter(completions));
        Future<?>[] submitted = new Future<?>[1];
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenAnswer(call -> {
            submitted[0] = worker.submit(call.getArgument(0, Runnable.class));
            return mock(TaskHandle.class);
        });
        waits.action = (monitor, nanos) -> {
            executor.release.countDown();
            TimeUnit.SECONDS.timedWait(monitor, 5);
        };
        var container = new SimpleContainer();
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget)) {
            container.registerSingleton("executor", executor);
            assertThat(dispatch(executor, "slow")).isTrue();
            assertThat(executor.started.await(5, TimeUnit.SECONDS)).isTrue();
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
        }
    }

    @Test
    void deadlineWarnsOnceAndDoesNotInterruptTheBodyOrRepeatTheBudget() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        var executor = new Fixture();
        var waits = new FakeWaits();
        Future<?>[] submitted = new Future<?>[1];
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenAnswer(call -> {
            submitted[0] = worker.submit(call.getArgument(0, Runnable.class));
            return mock(TaskHandle.class);
        });
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget); var warnings = new Warnings()) {
            assertThat(dispatch(executor, "slow")).isTrue();
            assertThat(executor.started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.awaitAsyncCommands();
            assertThat(waits.allowances).containsExactly(seconds(10));
            assertThat(waits.now.get()).isEqualTo(seconds(10));
            assertThat(executor.finished.getCount()).isEqualTo(1);
            assertThat(executor.interrupted).isFalse();
            assertThat(warnings.records).hasSize(1);
            assertThat(warnings.records.getFirst().getMessage()).contains("continues in the background");
            assertThat(warnings.records.getFirst().getParameters()).containsExactly(Fixture.class.getName(), 1);
            executor.awaitAsyncCommands();
            assertThat(waits.allowances).hasSize(1);
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
    void serverStopSharesTenSecondsAcrossTwoStuckExecutors() throws Exception {
        var waits = new FakeWaits();
        List<Runnable> bodies = captureBodies();
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget)) {
            Fixture first = pending();
            Fixture second = pending();
            CommandRuntime.beginShutdown();
            CommandRuntime.awaitAsyncCommands(List.of(first));
            CommandRuntime.awaitAsyncCommands(List.of(second));
            assertThat(waits.now.get()).isEqualTo(seconds(10));
            assertThat(waits.allowances).containsExactly(seconds(10));
            assertThat(first.finished.getCount()).isEqualTo(1);
            assertThat(second.finished.getCount()).isEqualTo(1);
        } finally {
            bodies.forEach(Runnable::run);
        }
    }

    @Test
    void quickCompletionLeavesAlmostAllTheBudgetForTheNextExecutor() throws Exception {
        var waits = new FakeWaits();
        List<Runnable> bodies = captureBodies();
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget)) {
            Fixture first = pending();
            Fixture second = pending();
            waits.action = (monitor, nanos) -> {
                if (waits.allowances.size() == 1) {
                    waits.now.addAndGet(Duration.ofMillis(100).toNanos());
                    bodies.set(0, () -> { }).run();
                } else {
                    waits.now.addAndGet(nanos);
                }
            };
            CommandRuntime.beginShutdown();
            CommandRuntime.awaitAsyncCommands(List.of(first, second));
            assertThat(waits.allowances).containsExactly(seconds(10), Duration.ofMillis(9900).toNanos());
            assertThat(first.finished.getCount()).isZero();
            assertThat(second.finished.getCount()).isEqualTo(1);
        } finally {
            bodies.forEach(Runnable::run);
        }
    }

    @Test
    void repeatedShutdownStartDoesNotRestoreTheBudget() throws Exception {
        var waits = new FakeWaits();
        List<Runnable> bodies = captureBodies();
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget)) {
            Fixture first = pending();
            Fixture second = pending();
            CommandRuntime.beginShutdown();
            CommandRuntime.awaitAsyncCommands(List.of(first));
            CommandRuntime.beginShutdown();
            CommandRuntime.awaitAsyncCommands(List.of(second));
            assertThat(waits.allowances).containsExactly(seconds(10));
            assertThat(waits.now.get()).isEqualTo(seconds(10));
        } finally {
            bodies.forEach(Runnable::run);
        }
    }

    @Test
    void oneModuleSharesOneDeadlineAcrossItsExecutors() throws Exception {
        var waits = new FakeWaits();
        List<Runnable> bodies = captureBodies();
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget)) {
            CommandRuntime.awaitAsyncCommands(List.of(pending(), pending()));
            assertThat(waits.allowances).containsExactly(seconds(10));
            assertThat(waits.now.get()).isEqualTo(seconds(10));
        } finally {
            bodies.forEach(Runnable::run);
        }
    }

    @Test
    void independentModuleUnloadsDoNotConsumeEachOthersBudget() throws Exception {
        var waits = new FakeWaits();
        List<Runnable> bodies = captureBodies();
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget)) {
            CommandRuntime.awaitAsyncCommands(List.of(pending()));
            CommandRuntime.awaitAsyncCommands(List.of(pending()));
            assertThat(waits.allowances).containsExactly(seconds(10), seconds(10));
            assertThat(waits.now.get()).isEqualTo(seconds(20));
        } finally {
            bodies.forEach(Runnable::run);
        }
    }

    @Test
    void serviceCleanupTimeIsNotChargedToTheShutdownWait() throws Exception {
        var waits = new FakeWaits();
        List<Runnable> bodies = captureBodies();
        try (var timing = CommandRuntime.overrideWaitBudgets(waits::budget)) {
            Fixture first = pending();
            Fixture second = pending();
            waits.action = (monitor, nanos) -> {
                waits.now.addAndGet(Duration.ofMillis(100).toNanos());
                bodies.set(waits.allowances.size() - 1, () -> { }).run();
            };
            CommandRuntime.beginShutdown();
            CommandRuntime.awaitAsyncCommands(List.of(first));
            waits.now.addAndGet(seconds(30));
            CommandRuntime.awaitAsyncCommands(List.of(second));
            assertThat(waits.allowances).containsExactly(seconds(10), Duration.ofMillis(9900).toNanos());
        } finally {
            bodies.forEach(Runnable::run);
        }
    }

    @Test
    void refusalRollsBackTheReservationAndNotifiesPassedValidators() {
        var executor = new Fixture();
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
        assertThat(first).isNotSameAs(second);
    }

    private static long seconds(long value) {
        return Duration.ofSeconds(value).toNanos();
    }

    private static Fixture pending() {
        var executor = new Fixture();
        assertThat(dispatch(executor, "fast")).isTrue();
        return executor;
    }

    private static List<Runnable> captureBodies() {
        List<Runnable> bodies = new ArrayList<>();
        var async = CommandRuntime.scheduler().async();
        doAnswer(call -> {
            bodies.add(call.getArgument(0));
            return mock(TaskHandle.class);
        }).when(async).run(any(Runnable.class));
        return bodies;
    }

    private static boolean dispatch(Fixture executor, String command) {
        return CommandTestSupport.dispatch(executor, mock(CommandSender.class), null, "probe", new String[] {command});
    }

    private static CommandValidator completionCounter(AtomicInteger completions) {
        return new CommandValidator() {
            @Override public ValidationResult validate(CommandContext context) { return ValidationResult.success(); }
            @Override public void onComplete(CommandContext context, boolean succeeded) { completions.incrementAndGet(); }
        };
    }

    private static CommandValidator refusalCounter(AtomicInteger refusals) {
        return new CommandValidator() {
            @Override public ValidationResult validate(CommandContext context) { return ValidationResult.success(); }
            @Override public void onRefused(CommandContext context) { refusals.incrementAndGet(); }
        };
    }

    private static final class FakeWaits {
        final AtomicLong now = new AtomicLong();
        final List<Long> allowances = new ArrayList<>();
        CommandWaitBudget.Awaiter action = (monitor, nanos) -> now.addAndGet(nanos);

        CommandWaitBudget budget() {
            return new CommandWaitBudget(Duration.ofSeconds(10), now::get, (monitor, nanos) -> {
                allowances.add(nanos);
                action.await(monitor, nanos);
            });
        }
    }

    private static final class Warnings extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(BaseCommandExecutor.class.getName());
        final List<LogRecord> records = new ArrayList<>();
        Warnings() { logger.addHandler(this); }
        @Override public void publish(LogRecord record) {
            if (Level.WARNING.equals(record.getLevel())) {
                records.add(record);
            }
        }
        @Override public void flush() { /* No buffered output. */ }
        @Override public void close() { logger.removeHandler(this); }
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
        public void fast() { finished.countDown(); }
        @Override protected void handleHelp(CommandSender sender) { /* Not exercised. */ }
    }
}
