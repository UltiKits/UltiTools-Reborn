package com.ultikits.ultitools.manager;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A command a module registered as sensitive, run from the panel's remote console, must not leave
 * its arguments in the framework's own records (6.4.0): the echo line (which the server log, and so
 * {@code latest.log}, receives), the refusal and failure lines, the remote-action log entry, and the
 * panel's log stream, which carries those log lines after {@link SensitiveCommandRegistry#redactText}.
 * The command itself must still be dispatched with its arguments.
 */
@Timeout(30)
class CommandExecutionManagerSensitiveTest {

    private static final String SECRET = "hunter2-zq81";

    private ServerMock server;
    private YamlConfiguration config;
    private RemoteActionLog actionLog;
    private Logger logger;
    private SensitiveCommandRegistry registry;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(spy(new ServerMock()));
        // MockBukkit does not implement Paper's capturing sender; the v2 path needs one.
        doAnswer(invocation -> {
            Consumer<? super Component> feedback = invocation.getArgument(0);
            CommandSender sender = mock(CommandSender.class);
            doAnswer(message -> {
                feedback.accept(message.getArgument(0));
                return null;
            }).when(sender).sendMessage(any(Component.class));
            return sender;
        }).when(server).createCommandSender(any());
        config = new YamlConfiguration();
        config.set(Capability.COMMANDS.getConfigPath(), true);
        actionLog = mock(RemoteActionLog.class);
        logger = mock(Logger.class);
        registry = new SensitiveCommandRegistry();
        registry.register("logadmin", "stop");
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getRemoteActionLog()).thenReturn(actionLog);
            when(plugin.getSensitiveCommandRegistry()).thenReturn(registry);
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "logadmin reset Steve " + SECRET + " | logadmin ***",
            "/logadmin reset Steve " + SECRET + " | logadmin ***",
            "/ultilogin:LogAdmin reset Steve " + SECRET + " | ultilogin:LogAdmin ***",
            "/ logadmin reset Steve " + SECRET + " | logadmin ***",
            "logadmin\u00a0reset Steve " + SECRET + " | logadmin ***",
    })
    void theEchoLineShowsOnlyTheCommandName(String commandLine, String shown) {
        execute(commandLine);

        assertThat(loggedLines()).noneMatch(line -> line.contains(SECRET));
        assertThat(loggedLines()).contains(FrameworkText.format("[远程命令] > %s", shown));
    }

    @Test
    void theCommandIsStillDispatchedWithItsArguments() {
        AtomicReference<String[]> seen = new AtomicReference<>();
        register("logadmin", (sender, args) -> {
            seen.set(args);
            return true;
        });

        execute("logadmin reset Steve " + SECRET);

        assertThat(seen.get()).containsExactly("reset", "Steve", SECRET);
    }

    @Test
    void theRemoteActionLogEntryShowsOnlyTheCommandName() {
        execute("/logadmin reset Steve " + SECRET);

        ArgumentCaptor<RemoteActionLog.Entry> entry = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(actionLog).record(entry.capture());
        assertThat(entry.getValue().getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        assertThat(entry.getValue().getTarget()).isEqualTo("logadmin ***");
    }

    @Test
    void aBlockedSensitiveCommandIsLoggedAndRecordedWithoutItsArguments() {
        // "stop" is on the shipped blocklist and registered as sensitive above.
        execute("/stop now " + SECRET);

        assertThat(loggedLines()).noneMatch(line -> line.contains(SECRET));
        assertThat(loggedLines()).contains(FrameworkText.format("[远程命令] 已拦截: %s", "stop ***"));
        ArgumentCaptor<RemoteActionLog.Entry> entry = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(actionLog).record(entry.capture());
        assertThat(entry.getValue().getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(entry.getValue().getTarget()).isEqualTo("stop ***");
    }

    @Test
    void aRefusalByTheCapabilitySwitchIsRecordedWithoutTheArguments() {
        config.set(Capability.COMMANDS.getConfigPath(), false);

        execute("logadmin reset Steve " + SECRET);

        ArgumentCaptor<RemoteActionLog.Entry> entry = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(actionLog).record(entry.capture());
        assertThat(entry.getValue().getTarget()).isEqualTo("logadmin ***");
    }

    @Test
    void thePanelLogStreamCarriesTheEchoWithoutTheArguments() {
        execute("logadmin reset Steve " + SECRET);

        // The log stream sends each server log line through SensitiveCommandRegistry#redactText
        // (UltiPanelLogTransmitter#buildLogData). That only finds a command after a "/", and the
        // echo has none, so the echo itself has to be clean.
        List<String> streamed = new ArrayList<>();
        for (String line : loggedLines()) {
            streamed.add(registry.redactText(line));
        }
        assertThat(streamed).isNotEmpty().noneMatch(line -> line.contains(SECRET));
    }

    @Test
    void anUnregisteredCommandIsLoggedAndRecordedAsReceived() {
        execute("/say hello " + SECRET);

        assertThat(loggedLines()).contains(FrameworkText.format("[远程命令] > %s", "/say hello " + SECRET));
        ArgumentCaptor<RemoteActionLog.Entry> entry = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(actionLog).record(entry.capture());
        assertThat(entry.getValue().getTarget()).isEqualTo("/say hello " + SECRET);
    }

    @Test
    void withoutARegistryTheLineIsLoggedAsReceived() {
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getRemoteActionLog()).thenReturn(actionLog);
        });

        execute("logadmin reset Steve " + SECRET);

        assertThat(loggedLines()).contains(FrameworkText.format("[远程命令] > %s", "logadmin reset Steve " + SECRET));
    }

    @Test
    void theV1HandlerLogsAndRecordsTheCommandWithoutItsArguments() {
        CommandExecutionManager manager = new CommandExecutionManager();
        UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);
        manager.setWebSocketClient(client);
        JsonObject data = new JsonObject();
        data.addProperty("command", "/logadmin reset Steve " + SECRET);
        data.addProperty("commandId", "v1-sensitive");

        manager.executeCommand(data);
        server.getScheduler().performOneTick();

        assertThat(loggedLines()).isNotEmpty().noneMatch(line -> line.contains(SECRET));
        assertThat(loggedLines()).contains(FrameworkText.format("[远程命令] > %s", "logadmin ***"));
        ArgumentCaptor<RemoteActionLog.Entry> entry = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(actionLog, atLeastOnce()).record(entry.capture());
        assertThat(entry.getAllValues()).extracting(RemoteActionLog.Entry::getTarget).containsOnly("logadmin ***");
    }

    /** Every string message the framework logger received, at any level. */
    private List<String> loggedLines() {
        List<String> lines = new ArrayList<>();
        for (Invocation invocation : mockingDetails(logger).getInvocations()) {
            for (Object argument : invocation.getArguments()) {
                if (argument instanceof String) {
                    lines.add((String) argument);
                }
            }
        }
        return Collections.unmodifiableList(lines);
    }

    private void execute(String commandLine) {
        new CommandExecutionManager().execute(commandLine, null, Runnable::run,
                new CommandExecutionManager.CommandResultSink() {
                    @Override
                    public void blocked(String matched, String message) {
                        // The outcome is not what these tests check; the log lines and records are.
                    }

                    @Override
                    public void completed(List<String> lines, boolean truncated, long ms) {
                        // As above: only the framework's own records are asserted.
                    }

                    @Override
                    public void failed(String message) {
                        // As above: only the framework's own records are asserted.
                    }
                });
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
}
