package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the v2 result shape produced by {@link CommandExecutionManager#execute}: the 6.3.0 guards,
 * the failure messages a {@code command.result} may carry, and the output caps the schema imposes.
 */
@Timeout(30)
class CommandExecutionManagerOutputTest {
    private ServerMock server;
    private YamlConfiguration config;
    private RemoteActionLog actionLog;
    private Logger logger;
    private ConsoleCapture console;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(spy(new ServerMock()));
        config = new YamlConfiguration();
        config.set(Capability.COMMANDS.getConfigPath(), true);
        actionLog = mock(RemoteActionLog.class);
        logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getRemoteActionLog()).thenReturn(actionLog);
        });
        doAnswer(invocation -> {
            Consumer<? super Component> feedback = invocation.getArgument(0);
            CommandSender sender = mock(CommandSender.class);
            doAnswer(message -> {
                feedback.accept(message.getArgument(0));
                return null;
            }).when(sender).sendMessage(any(Component.class));
            return sender;
        }).when(server).createCommandSender(any());
        console = new ConsoleCapture();
        server.getLogger().addHandler(console);
    }

    @AfterEach
    void tearDown() {
        server.getLogger().removeHandler(console);
        MockBukkitHelper.safeUnmock();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "/STOP", "minecraft:stop"})
    void blocklistedCommandIsReportedWithThePolicyMessageAndNeverDispatched(String command) {
        AtomicInteger dispatches = countDispatches("stop");

        RecordingSink sink = execute(command);

        assertThat(dispatches.get()).isZero();
        assertThat(sink.blockedCalls).isEqualTo(1);
        assertThat(sink.blockedMatched).isEqualTo("stop");
        assertThat(sink.blockedMessage).contains("ultipanel.commands.blocklist");
        assertThat(sink.failedCalls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "/STOP", "minecraft:stop"})
    void aBlockedCommandWritesTheDeniedActionLogEntry(String command) {
        execute(command);

        ArgumentCaptor<RemoteActionLog.Entry> entry = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(actionLog).record(entry.capture());
        assertThat(entry.getValue().getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(entry.getValue().getCapability()).isEqualTo("COMMANDS");
        assertThat(entry.getValue().getAction()).isEqualTo("execute_command");
        assertThat(entry.getValue().getTarget()).isEqualTo(command);
        assertThat(entry.getValue().getActor()).isEqualTo("panel");
        assertThat(entry.getValue().getReason()).contains("ultipanel.commands.blocklist");
    }

    @Test
    void aBlockedCommandIsAlsoLoggedAsAWarning() {
        AtomicInteger dispatches = countDispatches("stop");

        execute("/STOP");

        assertThat(dispatches.get()).isZero();
        // The 6.3.0 interception line, kept as the control that proves the refusal was observed.
        String expected = FrameworkText.format("[远程命令] 已拦截: %s", "/STOP");
        verify(logger).log(eq(Level.WARNING), eq(expected));
    }

    @Test
    void emptyFeedbackIsReportedAsOkWithAnEmptyArray() {
        register("silent", (sender, args) -> true);

        RecordingSink sink = execute("silent");

        assertThat(sink.completedCalls).isEqualTo(1);
        assertThat(sink.lines).isEmpty();
        assertThat(sink.truncated).isFalse();
        assertThat(sink.executionMs).isNotNegative();
    }

    @Test
    void moreThanOneThousandLinesKeepTheFirstThousandAndFlagTruncation() {
        register("flood", (sender, args) -> {
            for (int i = 1; i <= 1200; i++) {
                sender.sendMessage(Component.text("line-" + i));
            }
            return true;
        });

        RecordingSink sink = execute("flood");

        assertThat(sink.completedCalls).isEqualTo(1);
        assertThat(sink.lines).hasSize(1000);
        assertThat(sink.lines.get(0)).isEqualTo("line-1");
        assertThat(sink.lines.get(999)).isEqualTo("line-1000");
        assertThat(sink.truncated).isTrue();
    }

    @Test
    void oneOversizedLineIsCutToTheSchemaMaximumAndFlagged() {
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longLine.append('x');
        }
        register("loud", (sender, args) -> {
            sender.sendMessage(Component.text(longLine.toString()));
            return true;
        });

        RecordingSink sink = execute("loud");

        assertThat(sink.lines).hasSize(1);
        assertThat(sink.lines.get(0)).hasSize(4096);
        assertThat(sink.truncated).isTrue();
    }

    @Test
    void aRejectedDispatchCarriesTheReplyTheServerPrinted() {
        register("usagefail", (sender, args) -> {
            sender.sendMessage(Component.text("Usage: /usagefail <player>"));
            return false;
        });

        RecordingSink sink = execute("usagefail");

        assertThat(sink.completedCalls).isZero();
        assertThat(sink.failedCalls).isEqualTo(1);
        assertThat(sink.failedMessage).contains("Usage: /usagefail <player>")
                .doesNotContain("server log stream");
    }

    @Test
    void aRejectedDispatchCutsItsCapturedReplyToTheMessageBound() {
        StringBuilder reply = new StringBuilder();
        while (reply.length() < 900) {
            reply.append("-detail");
        }
        register("loudfail", (sender, args) -> {
            sender.sendMessage(Component.text(reply.toString()));
            return false;
        });

        RecordingSink sink = execute("loudfail");

        assertThat(sink.failedCalls).isEqualTo(1);
        assertThat(sink.failedMessage).hasSize(500).isEqualTo(reply.substring(0, 500));
    }

    @Test
    void aRejectedDispatchWithoutAnyReplySaysOnlyThatItWasNotAccepted() {
        register("rejected", (sender, args) -> false);

        RecordingSink sink = execute("rejected");

        assertThat(sink.completedCalls).isZero();
        assertThat(sink.failedCalls).isEqualTo(1);
        assertThat(sink.failedMessage).isEqualTo(CommandExecutionManager.NOT_ACCEPTED_WITHOUT_REPLY);
    }

    @Test
    void aDispatchExceptionIsAFailureWithoutStackTraceAndCutToFiveHundredCharacters() {
        StringBuilder loud = new StringBuilder("boom");
        while (loud.length() < 900) {
            loud.append("-detail");
        }
        register("crash", (sender, args) -> {
            throw new IllegalStateException(loud.toString());
        });

        RecordingSink sink = execute("crash");

        assertThat(sink.failedCalls).isEqualTo(1);
        assertThat(sink.failedMessage).hasSize(500);
        assertThat(sink.failedMessage).isEqualTo(loud.substring(0, 500));
        assertThat(sink.failedMessage).doesNotContain("IllegalStateException").doesNotContain("\tat ");
    }

    @Test
    void aDispatchExceptionWithoutMessageStillProducesANonEmptyMessage() {
        register("crash", (sender, args) -> {
            throw new IllegalStateException();
        });

        RecordingSink sink = execute("crash");

        assertThat(sink.failedCalls).isEqualTo(1);
        assertThat(sink.failedMessage).isNotBlank();
    }

    @Test
    void aDispatchExceptionIsLoggedWithTheCommandNameAndTheStackTrace() {
        register("crash", (sender, args) -> {
            throw new IllegalStateException("boom");
        });

        RecordingSink sink = execute("crash secret-argument");

        assertThat(sink.failedCalls).isEqualTo(1);
        // The command name only — an argument may carry a secret — and with the throwable, so the
        // operator keeps the stack trace the panel never receives.
        verify(logger).log(eq(Level.WARNING),
                argThat(line -> line.contains("crash") && !line.contains("secret-argument")),
                any(IllegalStateException.class));
    }

    @Test
    void theCapabilitySwitchRefusesBeforeAnythingIsDispatched() {
        config.set(Capability.COMMANDS.getConfigPath(), false);
        AtomicInteger dispatches = countDispatches("guarded");

        RecordingSink sink = execute("guarded");

        assertThat(dispatches.get()).isZero();
        assertThat(sink.completedCalls).isZero();
        assertThat(sink.failedCalls).isEqualTo(1);
        assertThat(sink.failedMessage).contains(Capability.COMMANDS.getConfigPath());
    }

    @Test
    void aLinePrintedAfterDispatchReturnsReachesTheServerConsoleOnceAndStaysOutOfTheResult() throws Exception {
        CountDownLatch resultTaken = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        register("later", (sender, args) -> {
            sender.sendMessage(Component.text("now"));
            Thread thread = new Thread(() -> {
                awaitQuietly(resultTaken);
                sender.sendMessage(Component.text("later"));
            });
            worker.set(thread);
            thread.start();
            return true;
        });

        RecordingSink sink = new RecordingSink();
        sink.onCompleted = resultTaken::countDown;
        new CommandExecutionManager().execute("later", null, Runnable::run, sink);

        assertThat(sink.completedCalls).isEqualTo(1);
        assertThat(sink.lines).containsExactly("now");
        assertThat(console.awaitMessages(1, 10_000L)).containsExactly("later");
        worker.get().join(10_000L);
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(console.records()).hasSize(1);
        assertThat(console.records().get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(console.records().get(0).getLoggerName()).isEqualTo(server.getLogger().getName());
    }

    @Test
    void aLateLineAfterARefusedDispatchIsForwardedToTheConsoleToo() throws Exception {
        CountDownLatch resultTaken = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        register("refusedlater", (sender, args) -> {
            Thread thread = new Thread(() -> {
                awaitQuietly(resultTaken);
                sender.sendMessage(Component.text("finished elsewhere"));
            });
            worker.set(thread);
            thread.start();
            return false;
        });

        RecordingSink sink = new RecordingSink();
        sink.onFailed = resultTaken::countDown;
        new CommandExecutionManager().execute("refusedlater", null, Runnable::run, sink);

        assertThat(sink.failedMessage).isEqualTo(CommandExecutionManager.NOT_ACCEPTED_WITHOUT_REPLY);
        assertThat(console.awaitMessages(1, 10_000L)).containsExactly("finished elsewhere");
        worker.get().join(10_000L);
    }

    @Test
    void linesRacingTheEndOfTheDispatchLandExactlyOnceInTheResultOrOnTheConsole() throws Exception {
        int count = 2000;
        AtomicReference<Thread> worker = new AtomicReference<>();
        register("racing", (sender, args) -> {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    sender.sendMessage(Component.text("late-" + i));
                }
            });
            worker.set(thread);
            thread.start();
            sender.sendMessage(Component.text("sync"));
            return true;
        });

        RecordingSink sink = execute("racing");
        worker.get().join(10_000L);
        assertThat(worker.get().isAlive()).isFalse();

        List<String> inResult = new ArrayList<>(sink.lines);
        assertThat(inResult).contains("sync");
        inResult.remove("sync");
        int forwardedExpected = count - inResult.size();
        List<String> onConsole = console.awaitMessages(forwardedExpected, 10_000L);
        Set<String> seen = new HashSet<>(inResult);
        for (String line : onConsole) {
            assertThat(seen.add(line)).as("printed once: %s", line).isTrue();
        }
        assertThat(seen).hasSize(count);
        assertThat(onConsole).doesNotContain("sync");
    }

    @Test
    void afterTheForwardingWindowLateLinesAreDroppedWithOneWarningNamingOnlyTheCommand() {
        AtomicLong now = new AtomicLong(0L);
        List<String> forwarded = new ArrayList<>();
        CommandExecutionManager.CapturedFeedback feedback = new CommandExecutionManager.CapturedFeedback(
                "login", TimeUnit.SECONDS.toNanos(60), now::get, forwarded::add);
        feedback.accept(Component.text("before"));

        assertThat(feedback.take()).containsExactly("before");
        now.set(TimeUnit.SECONDS.toNanos(60));
        feedback.accept(Component.text("inside\nthe window"));
        now.set(TimeUnit.SECONDS.toNanos(61));
        feedback.accept(Component.text("too late hunter2"));
        feedback.accept(Component.text("too late again"));

        assertThat(forwarded).containsExactly("inside", "the window");
        verify(logger).log(eq(Level.WARNING),
                argThat((String line) -> line.contains("'login'") && !line.contains("hunter2")));
        assertThat(feedback.take()).isEmpty();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private RecordingSink execute(String command) {
        RecordingSink sink = new RecordingSink();
        Executor mainThread = Runnable::run;
        new CommandExecutionManager().execute(command, null, mainThread, sink);
        return sink;
    }

    private AtomicInteger countDispatches(String name) {
        AtomicInteger count = new AtomicInteger();
        register(name, (sender, args) -> {
            count.incrementAndGet();
            return true;
        });
        return count;
    }

    private void register(String name, Body body) {
        server.getCommandMap().register("ulticmd", new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return body.run(sender, args);
            }
        });
    }

    @FunctionalInterface
    private interface Body {
        boolean run(CommandSender sender, String[] args);
    }

    private static final class RecordingSink implements CommandExecutionManager.CommandResultSink {
        private int blockedCalls;
        private String blockedMatched;
        private String blockedMessage;
        private int completedCalls;
        private List<String> lines = new ArrayList<>();
        private boolean truncated;
        private long executionMs = -1L;
        private int failedCalls;
        private String failedMessage;
        private Runnable onCompleted = () -> { };
        private Runnable onFailed = () -> { };

        @Override
        public void blocked(String matched, String message) {
            blockedCalls++;
            blockedMatched = matched;
            blockedMessage = message;
        }

        @Override
        public void completed(List<String> captured, boolean wasTruncated, long elapsed) {
            completedCalls++;
            lines = new ArrayList<>(captured);
            truncated = wasTruncated;
            executionMs = elapsed;
            onCompleted.run();
        }

        @Override
        public void failed(String message) {
            failedCalls++;
            failedMessage = message;
            onFailed.run();
        }
    }

    /** Stands for the server console: a handler on the server's logger, recording what it is given. */
    private static final class ConsoleCapture extends Handler {
        private final List<LogRecord> received = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            received.add(record);
            notifyAll();
        }

        synchronized List<LogRecord> records() {
            return new ArrayList<>(received);
        }

        /** Waits, at most {@code timeoutMs}, until {@code expected} records arrived; returns their messages. */
        synchronized List<String> awaitMessages(int expected, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (received.size() < expected) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                wait(left);
            }
            List<String> messages = new ArrayList<>();
            for (LogRecord record : received) {
                messages.add(record.getMessage());
            }
            return messages;
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
