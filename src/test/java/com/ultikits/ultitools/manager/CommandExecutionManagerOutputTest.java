package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.entities.Capability;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(spy(new ServerMock()));
        config = new YamlConfiguration();
        config.set(Capability.COMMANDS.getConfigPath(), true);
        actionLog = mock(RemoteActionLog.class);
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
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
    }

    @AfterEach
    void tearDown() {
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
        verify(actionLog).record(any(RemoteActionLog.Entry.class));
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
    void theCapabilitySwitchRefusesBeforeAnythingIsDispatched() {
        config.set(Capability.COMMANDS.getConfigPath(), false);
        AtomicInteger dispatches = countDispatches("guarded");

        RecordingSink sink = execute("guarded");

        assertThat(dispatches.get()).isZero();
        assertThat(sink.completedCalls).isZero();
        assertThat(sink.failedCalls).isEqualTo(1);
        assertThat(sink.failedMessage).contains(Capability.COMMANDS.getConfigPath());
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
        }

        @Override
        public void failed(String message) {
            failedCalls++;
            failedMessage = message;
        }
    }
}
