package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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

    /**
     * Command-map resolution is needed for aliases of non-framework commands. This synthetic
     * registration deliberately exposes both bare and namespaced aliases; Paper 1.21.11 itself
     * installs ReloadCommand only as bukkit:reload and bukkit:rl, not as bare reload or rl.
     */
    @ParameterizedTest
    @ValueSource(strings = {"rl confirm", "bukkit:rl confirm", "reload confirm", "bukkit:reload confirm"})
    void builtInAliasOfABlocklistedCommandIsRefused(String line) {
        AtomicInteger reloads = new AtomicInteger();
        Command reload = new Command("reload") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                reloads.incrementAndGet();
                return true;
            }
        };
        reload.setAliases(Collections.singletonList("rl"));
        server.getCommandMap().register("bukkit", reload);

        accept(line);
        // The direct spellings are refused at admission, so nothing reaches the server thread.
        assertThat(mainThread).as("the alias form is only refused on the server thread")
                .hasSize("rl confirm".equals(line) || "bukkit:rl confirm".equals(line) ? 1 : 0);
        if (!mainThread.isEmpty()) {
            mainThread.remove().run();
        }

        assertThat(reloads.get()).as("the alias must never reach the blocklisted command").isZero();
        assertThat(channel.frames()).hasSize(2);
        JsonObject result = channel.frames().get(1);
        assertThat(result.get("type").getAsString()).isEqualTo("command.result");
        JsonObject payload = result.getAsJsonObject("payload");
        assertThat(payload.get("code").getAsString()).isEqualTo("command_blocked");
        assertThat(payload.getAsJsonObject("details").get("matched").getAsString()).isEqualTo("reload");
        assertThat(payload.get("message").getAsString()).contains("ultipanel.commands.blocklist");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    /**
     * The control for the check above: resolving the label must not turn an ordinary alias into a
     * refusal. Without it, refusing everything that resolves through the command map would pass.
     */
    @Test
    void unblockedCommandStillRunsThroughItsAlias() {
        AtomicInteger calls = new AtomicInteger();
        Command friendly = new Command("friendly") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                calls.incrementAndGet();
                return true;
            }
        };
        friendly.setAliases(Collections.singletonList("fr"));
        server.getCommandMap().register("test", friendly);

        accept("fr hello");
        mainThread.remove().run();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(channel.frames()).hasSize(2);
        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").has("output")).isTrue();
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ul help", "ulti help", "ultitools help", "ultitools:ul help"})
    void siblingAliasEntryBlocksEveryOuterSpelling(String line) {
        UlCommand executor = registerUl();
        manager.setBlockedCommands(Collections.singleton("ultitools"));

        accept(line);
        if (!mainThread.isEmpty()) {
            mainThread.remove().run();
        }

        assertSiblingRefusal(executor);
        verify(UltiTools.getInstance().getLogger()).log(eq(Level.WARNING), endsWith(": " + line));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UlTiToOlS", "test:ULTITOOLS"})
    void siblingAliasEntriesAreCaseInsensitiveAndNamespaceFree(String entry) {
        UlCommand executor = registerUl();
        manager.setBlockedCommands(Collections.singleton(entry));

        accept("ul help");
        mainThread.remove().run();

        assertSiblingRefusal(executor);
        assertThat(manager.isCommandAllowed("ultitools help").isAllowed()).isFalse();
    }

    @Test
    void siblingAliasAddedAfterAdmissionIsRefusedBeforeDispatch() {
        UlCommand executor = registerUl();
        accept("ul help");
        assertThat(channel.frames()).hasSize(1);
        manager.setBlockedCommands(Collections.singleton("ultitools"));

        mainThread.remove().run();

        assertSiblingRefusal(executor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ul help", "ulti help", "ultitools:ul help"})
    void synchronousNestedCommandChecksSiblingAliases(String nested) {
        UlCommand executor = registerUl();
        // A synchronous forwarding fixture, not a simulation of Paper's Brigadier parser.
        register("execute", (sender, args) -> Bukkit.dispatchCommand(sender, nested));
        manager.setBlockedCommands(Collections.singleton("ultitools"));

        accept("execute run " + nested);
        mainThread.remove().run();

        assertThat(executor.calls.get()).isZero();
        assertThat(channel.frames()).hasSize(2);
        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").getAsJsonArray("output").toString())
                .contains("command 'ultitools' is on the remote command blocklist")
                .contains("ultipanel.commands.blocklist").doesNotContain("ul help body");
        verify(UltiTools.getInstance().getLogger()).log(Level.WARNING,
                "[Remote command] Blocked a nested panel command: ul");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
        Bukkit.dispatchCommand(server.getConsoleSender(), "ul help");
        assertThat(executor.calls.get()).as("the local console is not subject to the remote policy").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ul help", "ulti help", "ultitools:ul help", "execute run ul help"})
    void unrelatedEntryDoesNotBlockUlOrItsNestedPath(String line) {
        UlCommand executor = registerUl();
        register("execute", (sender, args) -> Bukkit.dispatchCommand(sender, "ul help"));
        manager.setBlockedCommands(Collections.singleton("unrelated"));

        accept(line);
        mainThread.remove().run();

        assertThat(executor.calls.get()).isEqualTo(1);
        assertThat(channel.frames()).hasSize(2);
        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").getAsJsonArray("output").toString()).contains("ul help body");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    /** Paper 1.21.11 uses distinct wrappers; only the primary lists its aliases. */
    @ParameterizedTest
    @CsvSource({
            "plugins, pl", "plugins, bukkit:pl", "BuKkIt:PLUGINS, BuKkIt:PL",
            "version, ver", "version, about", "version, bukkit:ver", "version, bukkit:about",
            "pl, plugins", "bukkit:PL, bukkit:plugins"
    })
    void splitPaperWrappersMatchAliasesInEitherDirection(String entry, String line) {
        AtomicInteger calls = registerPaperSplitWrappers(null);
        manager.setBlockedCommands(Collections.singleton(entry));
        assertThat(server.getCommandMap().getCommand("pl"))
                .isNotSameAs(server.getCommandMap().getCommand("plugins"));
        assertThat(server.getCommandMap().getCommand("pl").getAliases()).isEmpty();
        assertThat(server.getCommandMap().getCommand("plugins").getAliases())
                .containsExactly("pl", "bukkit:pl");
        clearInvocations(server);

        accept(line);

        verify(server, never()).getCommandMap();
        assertThat(mainThread).as("admission is text-only; metadata is checked on the server thread").hasSize(1);
        mainThread.remove().run();

        assertThat(calls.get()).isZero();
        assertThat(channel.frames()).hasSize(2);
        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("code").getAsString()).isEqualTo("command_blocked");
        assertThat(payload.getAsJsonObject("details").get("matched").getAsString())
                .isEqualTo(entry.substring(entry.indexOf(':') + 1).toLowerCase(java.util.Locale.ROOT));
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"plugins, pl help", "plugins, bukkit:pl help", "version, ver help",
            "version, about help", "pl, plugins help"})
    void nestedFrameworkCheckAlsoMatchesSplitPaperWrappers(String entry, String nested) {
        UlCommand executor = new UlCommand();
        registerPaperSplitWrappers(executor);
        register("execute", (sender, args) -> Bukkit.dispatchCommand(sender, nested));
        manager.setBlockedCommands(Collections.singleton(entry));

        accept("execute run " + nested);
        mainThread.remove().run();

        assertThat(executor.calls.get()).isZero();
        assertThat(channel.frames()).hasSize(2);
        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").getAsJsonArray("output").toString())
                .contains("command '" + entry + "' is on the remote command blocklist")
                .doesNotContain("ul help body");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pl", "bukkit:pl", "ver", "about"})
    void unrelatedEntryDoesNotRefuseSplitPaperWrappers(String line) {
        AtomicInteger calls = registerPaperSplitWrappers(null);
        manager.setBlockedCommands(Collections.singleton("unrelated"));

        accept(line);
        mainThread.remove().run();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(channel.frames()).hasSize(2);
        assertThat(channel.frames().get(1).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
    }

    @Test
    void splitSiblingWrappersWithoutMetadataDoNotInventATransitiveAliasRelation() {
        AtomicInteger calls = registerPaperSplitWrappers(null);
        manager.setBlockedCommands(Collections.singleton("ver"));

        accept("about");
        mainThread.remove().run();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(channel.frames().get(1).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pl", "bukkit:pl"})
    void nullAliasesOnTypedWrapperStillAllowReverseMatching(String line) {
        AtomicInteger calls = registerPaperSplitWrappers(null);
        Command alias = spy(server.getCommandMap().getCommand("pl"));
        doReturn(null).when(alias).getAliases();
        server.getCommandMap().getKnownCommands().put("pl", alias);
        server.getCommandMap().getKnownCommands().put("bukkit:pl", alias);
        manager.setBlockedCommands(Collections.singleton("plugins"));

        accept(line);
        mainThread.remove().run();

        assertThat(calls.get()).isZero();
        assertThat(channel.frames().get(1).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("command_blocked");
    }

    @Test
    void nullAliasesOnBlocklistEntryDoNotRefuseAnUnrelatedCommand() {
        AtomicInteger calls = registerPaperSplitWrappers(null);
        Command primary = spy(server.getCommandMap().getCommand("plugins"));
        doReturn(null).when(primary).getAliases();
        server.getCommandMap().getKnownCommands().put("plugins", primary);
        server.getCommandMap().getKnownCommands().put("bukkit:plugins", primary);
        manager.setBlockedCommands(Collections.singleton("plugins"));

        accept("ver");
        mainThread.remove().run();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(channel.frames().get(1).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
    }

    @Test
    void nestedAuthenticationExecutorUsesPolicyTextBeforeItsBody() {
        CloudLoginCommand authentication = new CloudLoginCommand();
        server.getCommandMap().register("ultitools", new Command("ulticloud") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return authentication.onCommand(sender, this, label, args);
            }
        });
        register("execute", (sender, args) -> Bukkit.dispatchCommand(sender, "ulticloud logout"));
        manager.setBlockedCommands(Collections.emptySet());

        accept("execute run ulticloud logout");
        mainThread.remove().run();

        assertThat(channel.frames()).hasSize(2);
        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").getAsJsonArray("output").get(0).getAsString())
                .isEqualTo("Blocked — UltiCloud login/logout must be run from the server console. "
                        + "This restriction cannot be changed through configuration.");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    private AtomicInteger registerPaperSplitWrappers(UlCommand executor) {
        AtomicInteger calls = new AtomicInteger();
        installPaperWrapper("plugins", Arrays.asList("pl", "bukkit:pl"), executor, calls);
        installPaperWrapper("pl", Collections.emptyList(), executor, calls);
        installPaperWrapper("version", Arrays.asList("ver", "bukkit:ver", "about", "bukkit:about"), executor, calls);
        installPaperWrapper("ver", Collections.emptyList(), executor, calls);
        installPaperWrapper("about", Collections.emptyList(), executor, calls);
        return calls;
    }

    private void installPaperWrapper(String name, List<String> aliases, UlCommand executor, AtomicInteger calls) {
        Command wrapper = new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (executor != null) {
                    return executor.onCommand(sender, this, label, args);
                }
                calls.incrementAndGet();
                sender.sendMessage("split wrapper body");
                return true;
            }
        };
        wrapper.setAliases(aliases);
        // Replace lookup nodes directly: SimpleCommandMap.register would reuse the primary object
        // for aliases, whereas Paper's VanillaCommandWrapper nodes are separate objects.
        Map<String, Command> commands = server.getCommandMap().getKnownCommands();
        commands.put(name, wrapper);
        commands.put("bukkit:" + name, wrapper);
    }

    private void assertSiblingRefusal(UlCommand executor) {
        assertThat(executor.calls.get()).isZero();
        assertThat(channel.frames()).hasSize(2);
        JsonObject result = channel.frames().get(1);
        assertThat(result.get("type").getAsString()).isEqualTo("command.result");
        JsonObject payload = result.getAsJsonObject("payload");
        assertThat(payload.get("code").getAsString()).isEqualTo("command_blocked");
        assertThat(payload.getAsJsonObject("details").get("matched").getAsString()).isEqualTo("ultitools");
        assertThat(payload.get("message").getAsString()).contains("ultipanel.commands.blocklist");
        assertThat(CommandExecutionManager.isPanelDispatch()).isFalse();
    }

    private UlCommand registerUl() {
        UlCommand executor = new UlCommand();
        Command command = new Command("ul") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return executor.onCommand(sender, this, label, args);
            }
        };
        command.setAliases(Arrays.asList("ulti", "ultitools"));
        server.getCommandMap().register("ultitools", command);
        return executor;
    }

    @CmdExecutor(alias = {"ul", "ulti", "ultitools"})
    public static class UlCommand extends BaseCommandExecutor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        protected void handleHelp(CommandSender sender) {
            calls.incrementAndGet();
            sender.sendMessage("ul help body");
        }
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
