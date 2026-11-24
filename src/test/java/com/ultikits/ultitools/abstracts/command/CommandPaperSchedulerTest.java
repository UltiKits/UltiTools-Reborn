package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.ui.RecordingScheduler;
import com.ultikits.ultitools.utils.FrameworkSchedulerTestSession;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(15)
class CommandPaperSchedulerTest {
    private final AtomicReference<TaskHandle> body = new AtomicReference<>();
    private final AtomicReference<TaskHandle> watchdog = new AtomicReference<>();
    private ServerMock server;
    private FrameworkSchedulerTestSession framework;
    private AutoCloseable runtime;
    private PlayerMock player;
    private InMemoryMessages messages;
    private Fixture executor;
    private final AtomicReference<RecordingScheduler.Scope> delivery = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        framework = new FrameworkSchedulerTestSession();
        var scheduler = spy(new RecordingScheduler(framework.scheduler()));
        TaskContext async = spy(scheduler.async());
        TaskContext entity = spy(scheduler.entity(player));
        doReturn(async).when(scheduler).async();
        doReturn(entity).when(scheduler).entity(player);
        doAnswer(call -> {
            TaskHandle handle = (TaskHandle) call.callRealMethod();
            body.set(handle);
            return handle;
        }).when(async).run(any(Runnable.class));
        doAnswer(call -> {
            TaskHandle handle = (TaskHandle) call.callRealMethod();
            watchdog.set(handle);
            return handle;
        }).when(entity).runLater(any(Duration.class), any(Runnable.class));
        Logger logger = Logger.getAnonymousLogger();
        messages = spy(new InMemoryMessages("ultitools", new ConfiguredLocaleResolver(() -> "auto", () -> "en", logger)));
        messages.put(CommandKeys.TIMEOUT, Locale.ENGLISH, "<red>timeout");
        doAnswer(call -> {
            delivery.set(RecordingScheduler.current().orElse(null));
            return call.callRealMethod();
        }).when(messages).send(eq(player), any(Text.class));
        runtime = CommandRuntime.override(messages, scheduler);
        executor = new Fixture();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            executor.release.countDown();
            if (body.get() != null) {
                server.getScheduler().performOneTick();
                body.get().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        } finally {
            runtime.close();
            framework.close();
            MockBukkit.unmock();
        }
    }

    @Test
    void realEngineReportsTimeoutOnceInTheSendersEntityContextWithoutInterruptingTheBody() throws Exception {
        assertThat(CommandTestSupport.dispatch(executor, player, null, "probe", new String[] {"slow"})).isTrue();
        server.getScheduler().performOneTick();
        assertThat(executor.started.await(5, TimeUnit.SECONDS)).isTrue();
        server.getScheduler().performTicks(20);
        verify(messages, times(1)).send(player, Text.of(CommandKeys.TIMEOUT));
        assertThat(delivery.get()).isEqualTo(new RecordingScheduler.Scope(RecordingScheduler.Scope.Kind.ENTITY, player.getUniqueId()));
        assertThat(executor.finished.getCount()).isEqualTo(1);
        executor.release.countDown();
        body.get().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        server.getScheduler().performTicks(20);
        verify(messages, times(1)).send(player, Text.of(CommandKeys.TIMEOUT));
        assertThat(executor.interrupted).isFalse();
        assertThat(executor.onTickThread).isFalse();
        assertThat(executor.finished.getCount()).isZero();
    }

    @Test
    void realEngineTimelyCompletionCancelsTheTimeoutTask() throws Exception {
        assertThat(CommandTestSupport.dispatch(executor, player, null, "probe", new String[] {"fast"})).isTrue();
        server.getScheduler().performOneTick();
        body.get().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(watchdog.get().state()).isEqualTo(TaskState.CANCELLED);
        server.getScheduler().performTicks(20);
        verify(messages, never()).send(player, Text.of(CommandKeys.TIMEOUT));
        assertThat(executor.finished.getCount()).isZero();
    }

    static class Fixture extends BaseCommandExecutor {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean interrupted;
        volatile boolean onTickThread;
        Fixture() { super(ValidatorChain.builder().build()); }
        @AsyncCommand(showProcessing = false, timeout = 1)
        @CmdMapping(format = "slow")
        public void slow() throws InterruptedException {
            onTickThread = CommandRuntime.scheduler().global().isCurrentThread();
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the command body");
                }
            } catch (InterruptedException failure) {
                interrupted = true;
                throw failure;
            } finally {
                finished.countDown();
            }
        }
        @AsyncCommand(showProcessing = false, timeout = 1)
        @CmdMapping(format = "fast")
        public void fast() { finished.countDown(); }
        @Override protected void handleHelp(CommandSender sender) { /* Not exercised. */ }
    }
}
