package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.RecordingScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@Timeout(10)
@ExtendWith(CommandTestExtension.class)
class CommandSchedulerMigrationTest {
    @Test
    void runAsyncUsesTheFrameworkAsyncContextAndNotTheDispatchThread() throws Exception {
        try (var engine = new ManualScheduler.Engine()) {
            var scheduler = new RecordingScheduler(engine.scheduler("UltiTools"));
            try (var runtime = CommandRuntime.override(CommandRuntime.messages(), scheduler)) {
                var executor = new Fixture();
                assertThat(CommandTestSupport.dispatch(executor, mock(CommandSender.class), null, "probe", new String[] {"async"})).isTrue();
                assertThat(executor.calls).isZero();
                engine.advanceTicks(0);
                assertThat(executor.calls).isEqualTo(1);
                assertThat(executor.scope).isEqualTo(RecordingScheduler.Scope.Kind.ASYNC);
                assertThat(executor.onTickThread).isFalse();
            }
        }
    }

    @Test
    void offThreadSyncBodyUsesGlobalEvenForAPlayerSender() throws Exception {
        try (var engine = new ManualScheduler.Engine()) {
            var scheduler = new RecordingScheduler(engine.scheduler("UltiTools"));
            try (var runtime = CommandRuntime.override(CommandRuntime.messages(), scheduler)) {
                var executor = new Fixture();
                Player player = player();
                CompletableFuture.runAsync(() -> CommandTestSupport.dispatch(executor, player, null, "probe", new String[] {"sync"}))
                        .get(5, TimeUnit.SECONDS);
                assertThat(executor.calls).isZero();
                engine.advanceTicks(1);
                assertThat(executor.calls).isEqualTo(1);
                assertThat(executor.scope).isEqualTo(RecordingScheduler.Scope.Kind.GLOBAL);
            }
        }
    }

    @Test
    void timelyCompletionCancelsTheWatchdogHandleAndNeverReportsTimeout() throws Exception {
        try (var engine = new ManualScheduler.Engine()) {
            var scheduler = spy(new RecordingScheduler(engine.scheduler("UltiTools")));
            var player = player();
            TaskContext replies = spy(scheduler.entity(player));
            doReturn(replies).when(scheduler).entity(player);
            var watcher = new AtomicReference<TaskHandle>();
            doAnswer(call -> {
                TaskHandle handle = (TaskHandle) call.callRealMethod();
                watcher.set(handle);
                return handle;
            }).when(replies).runLater(any(Duration.class), any(Runnable.class));
            try (var runtime = CommandRuntime.override(CommandRuntime.messages(), scheduler)) {
                var executor = new Fixture();
                assertThat(CommandTestSupport.dispatch(executor, player, null, "probe", new String[] {"timed"})).isTrue();
                engine.advanceTicks(0);
                assertThat(executor.calls).isEqualTo(1);
                assertThat(watcher.get().state()).isEqualTo(TaskState.CANCELLED);
                engine.advanceTicks(20);
                verify(CommandRuntime.messages(), never()).send(player, Text.of(CommandKeys.TIMEOUT));
            }
        }
    }

    @Test
    void anEntityRetiredBeforeTheDeadlineReceivesNoTimeoutAndTheBodyStillRuns() throws Exception {
        try (var engine = new ManualScheduler.Engine()) {
            var scheduler = spy(new RecordingScheduler(engine.scheduler("UltiTools")));
            TaskContext async = mock(TaskContext.class);
            doReturn(async).when(scheduler).async();
            var pending = new AtomicReference<Runnable>();
            when(async.run(any(Runnable.class))).thenAnswer(call -> {
                pending.set(call.getArgument(0));
                return mock(TaskHandle.class);
            });
            var player = player();
            try (var runtime = CommandRuntime.override(CommandRuntime.messages(), scheduler)) {
                var executor = new Fixture();
                assertThat(CommandTestSupport.dispatch(executor, player, null, "probe", new String[] {"timed"})).isTrue();
                engine.retire(player);
                engine.advanceTicks(20);
                verify(CommandRuntime.messages(), never()).send(player, Text.of(CommandKeys.TIMEOUT));
                pending.get().run();
                assertThat(executor.calls).isEqualTo(1);
            }
        }
    }

    @Test
    void schedulerRefusalNotifiesPassedValidatorsBeforePropagating() {
        List<String> events = new ArrayList<>();
        var executor = new Fixture(ValidatorChain.builder().add(new CommandValidator() {
            @Override public ValidationResult validate(CommandContext context) { return ValidationResult.success(); }
            @Override public void onRefused(CommandContext context) { events.add("refused"); }
            @Override public void onComplete(CommandContext context, boolean succeeded) { events.add("complete"); }
        }).build());
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenThrow(new RejectedExecutionException("disabled"));
        assertThatThrownBy(() -> CommandTestSupport.dispatch(executor, mock(CommandSender.class), null, "probe", new String[] {"async"}))
                .isInstanceOf(RejectedExecutionException.class).hasMessage("disabled");
        assertThat(events).containsExactly("refused");
        assertThat(executor.calls).isZero();
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(new UUID(0, 456));
        return player;
    }

    static class Fixture extends BaseCommandExecutor {
        volatile int calls;
        volatile RecordingScheduler.Scope.Kind scope;
        volatile boolean onTickThread;
        Fixture() { super(ValidatorChain.builder().build()); }
        Fixture(ValidatorChain chain) { super(chain); }
        private void record() {
            calls++;
            scope = RecordingScheduler.current().map(RecordingScheduler.Scope::kind).orElse(null);
            onTickThread = CommandRuntime.scheduler().global().isCurrentThread();
        }
        @RunAsync @CmdMapping(format = "async") public void async() { record(); }
        @CmdMapping(format = "sync") public void sync() { record(); }
        @AsyncCommand(showProcessing = false, timeout = 1)
        @CmdMapping(format = "timed") public void timed() { record(); }
        @Override protected void handleHelp(CommandSender sender) { /* Not exercised. */ }
    }
}
