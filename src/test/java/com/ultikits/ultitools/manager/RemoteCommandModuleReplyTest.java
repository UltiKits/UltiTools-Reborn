package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

import net.kyori.adventure.text.Component;

/**
 * #541: a module command's body runs inside the dispatch the panel's remote command makes on the
 * primary thread.
 * <p>
 * Re-pointed in 6.4.0, when the protocol v1 {@code command_result} sender was removed: the panel's
 * command now goes through {@link CommandExecutionManager#execute}, which dispatches with Paper's
 * feedback-capturing sender, so a body that runs inside the dispatch is proven by its reply being
 * part of the {@code command.result} lines. The capturing sender is stubbed as in
 * {@code CommandExecutionManagerOutputTest}.
 */
@DisplayName("The panel's remote command runs a module command's body inside the dispatch (#541)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RemoteCommandModuleReplyTest {

    private ServerMock server;
    private CommandExecutionManager manager;

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"moduleprobe"})
    static class ModuleProbeExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdMapping(format = "")
        public void reply(CommandSender sender) {
            sender.sendMessage("module reply 541");
        }
    }

    /** Records the lines a completed command reports. */
    private static final class LinesSink implements CommandExecutionManager.CommandResultSink {
        List<String> lines;
        String failure;

        @Override
        public void blocked(String matched, String message) {
            failure = message;
        }

        @Override
        public void completed(List<String> captured, boolean truncated, long executionMs) {
            lines = new ArrayList<>(captured);
        }

        @Override
        public void failed(String message) {
            failure = message;
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(spy(new ServerMock()));
        MockBukkit.createMockPlugin();
        Logger logger = mock(Logger.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set(Capability.COMMANDS.getConfigPath(), true);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(logger);
            when(ultiTools.getConfig()).thenReturn(config);
        });
        doAnswer(invocation -> {
            Consumer<? super Component> feedback = invocation.getArgument(0);
            CommandSender sender = mock(CommandSender.class);
            doAnswer(message -> {
                feedback.accept(message.getArgument(0));
                return null;
            }).when(sender).sendMessage(any(Component.class));
            doAnswer(message -> {
                feedback.accept(Component.text((String) message.getArgument(0)));
                return null;
            }).when(sender).sendMessage(any(String.class));
            return sender;
        }).when(server).createCommandSender(any());
        manager = new CommandExecutionManager();

        ModuleProbeExecutor executor = new ModuleProbeExecutor();
        server.getCommandMap().register("moduleprobe", new Command("moduleprobe") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return executor.onCommand(sender, this, label, args);
            }
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("the module body replies inside the dispatch: its reply is in the command result")
    void remoteCommandRunsTheModuleBodyInsideTheDispatch() {
        LinesSink sink = new LinesSink();

        // The executor runs the dispatch at once on this (main) thread; no further tick runs.
        manager.execute("moduleprobe", null, Runnable::run, sink);

        assertThat(sink.failure).isNull();
        assertThat(sink.lines).containsExactly("module reply 541");
    }

    @Test
    @DisplayName("control: a command that replies inside its own dispatch has its reply in the result too")
    void controlAnInlineReplyingCommandRepliesToTheConsole() {
        server.getCommandMap().register("controlprobe", new Command("controlprobe") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                sender.sendMessage("control reply");
                return true;
            }
        });
        LinesSink sink = new LinesSink();

        manager.execute("controlprobe", null, Runnable::run, sink);

        assertThat(sink.failure).isNull();
        assertThat(sink.lines).containsExactly("control reply");
    }
}
