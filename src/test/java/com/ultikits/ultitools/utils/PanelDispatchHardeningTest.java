package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.commands.CloudLoginCommand;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.websocket.CloudRequest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Timeout(30)
class PanelDispatchHardeningTest {
    private ServerMock server;
    private CommandExecutionManager manager;
    private RecordingChannel channel;
    private final Queue<Runnable> mainThread = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(spy(new ServerMock()));
        YamlConfiguration config = new YamlConfiguration();
        config.set(Capability.COMMANDS.getConfigPath(), true);
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(plugin.i18n(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        });
        doAnswer(invocation -> {
            Consumer<? super Component> feedback = invocation.getArgument(0);
            CommandSender sender = mock(CommandSender.class);
            when(sender.isOp()).thenReturn(true);
            doAnswer(message -> {
                feedback.accept(message.getArgument(0));
                return null;
            }).when(sender).sendMessage(any(Component.class));
            doAnswer(message -> {
                feedback.accept(Component.text(message.getArgument(0, String.class)));
                return null;
            }).when(sender).sendMessage(any(String.class));
            return sender;
        }).when(server).createCommandSender(any());
        manager = new CommandExecutionManager();
        channel = new RecordingChannel();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @ParameterizedTest
    @ValueSource(strings = {"forward", "shortcut", "test:shortcut"})
    void nestedOrAliasedFrameworkCommandChecksItsResolvedName(String line) {
        GuardedCommand executor = new GuardedCommand();
        registerGuarded(executor);
        register("forward", (sender, args) -> Bukkit.dispatchCommand(sender, "test:guarded"));
        manager.setBlockedCommands(Collections.singleton("guarded"));

        accept(line);
        mainThread.remove().run();

        assertThat(executor.calls.get()).isZero();
        assertThat(channel.frames()).hasSize(2);
        assertThat(channel.frames().get(1).toString()).contains("ultipanel.commands.blocklist");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
        Bukkit.dispatchCommand(server.getConsoleSender(), "guarded");
        assertThat(executor.calls.get()).as("local console commands keep their existing authority").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "repair", "logout"})
    void synchronousWrappersCannotRunAuthenticationBodies(String action) {
        CloudLoginCommand authentication = new CloudLoginCommand();
        register("forward", (sender, args) -> {
            if ("logout".equals(action)) {
                authentication.logout(sender);
            } else if ("repair".equals(action)) {
                authentication.login(sender, "not-a-code");
            } else {
                authentication.login(sender);
            }
            return true;
        });

        accept("forward");
        mainThread.remove().run();

        assertThat(channel.frames()).hasSize(2);
        assertThat(channel.frames().get(1).toString()).contains("not the panel");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    @Test
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
    void commandErrorStillAnswersAfterAckAndRestoresOriginBeforeRethrowing() {
        AssertionError failure = new AssertionError("synthetic command failure");
        register("broken", (sender, args) -> { throw failure; });
        accept("broken");
        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).get("type").getAsString()).isEqualTo("command.ack");

        assertThatThrownBy(() -> mainThread.remove().run()).isSameAs(failure);

        assertThat(channel.frames()).hasSize(2);
        JsonObject result = channel.frames().get(1);
        assertThat(result.get("type").getAsString()).isEqualTo("command.result");
        assertThat(result.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("command_failed");
        assertThat(result.toString()).doesNotContain("AssertionError").doesNotContain("\\tat ");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    @Test
    void commandLinkageFailureIsAnsweredWithoutKillingDispatch() {
        register("broken", (sender, args) -> { throw new NoClassDefFoundError("synthetic dependency"); });
        accept("broken");

        mainThread.remove().run();

        assertThat(channel.frames()).hasSize(2);
        assertThat(channel.frames().get(1).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("command_failed");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    private void accept(String line) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", line);
        new RemoteCommandHandler(manager, new PlayerLinkEndToEndTest.TestLedger(() -> 0), mainThread::add, () -> false)
                .handle(new CloudRequest(Ulid.next(), "command.execute", 1L, null, payload), channel);
    }

    private void registerGuarded(GuardedCommand executor) {
        Command command = new Command("guarded") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return executor.onCommand(sender, this, label, args);
            }
        };
        command.setAliases(Arrays.asList("guarded", "shortcut"));
        server.getCommandMap().register("test", command);
    }

    private void register(String name, BiFunction<CommandSender, String[], Boolean> body) {
        server.getCommandMap().register("test", new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return body.apply(sender, args);
            }
        });
    }

    @CmdExecutor(alias = {"guarded", "shortcut"})
    public static class GuardedCommand extends BaseCommandExecutor {
        private final AtomicInteger calls = new AtomicInteger();

        @CmdMapping(format = "")
        public void run() {
            calls.incrementAndGet();
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("guarded help");
        }
    }
}
