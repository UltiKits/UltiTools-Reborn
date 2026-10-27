package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.commands.CloudLoginCommand;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.websocket.CloudLimits;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Timeout(30)
class RemoteCommandEndToEndTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(spy(new ServerMock()));
        YamlConfiguration config = new YamlConfiguration();
        config.set(Capability.COMMANDS.getConfigPath(), true);
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
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

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("panelCloudCommandCases")
    void panelCloudCommandPolicyIsEnforcedBeforeDispatch(String description, String commandLine,
                                                        String matched, boolean blocked) throws Exception {
        JsonObject request = JsonParser.parseString(ContractFixtures.text(
                "fixtures/agent/command.execute/valid/say.json")).getAsJsonObject();
        request.addProperty("id", Ulid.next());
        request.getAsJsonObject("payload").addProperty("command", commandLine);
        String id = request.get("id").getAsString();
        List<JsonObject> sent = new ArrayList<>();
        Queue<Runnable> mainThread = new ArrayDeque<>();
        FramedChannel channel = new FramedChannel(frame -> {
            JsonObject json = JsonParser.parseString(frame).getAsJsonObject();
            ContractSchemas.assertFrameValid(json);
            sent.add(json);
            return true;
        }, () -> true, CloudLimits::defaults, Ulid::next, () -> 1000L);
        DedupeLedger ledger = mock(DedupeLedger.class);
        when(ledger.firstSeen(id)).thenReturn(true);
        CommandExecutionManager manager = new CommandExecutionManager();
        manager.setBlockedCommands(Collections.emptySet());
        AtomicInteger executions = new AtomicInteger();
        registerCloudTracingCommand(executions);
        AgentMessageRouter router = new AgentMessageRouter(capability -> Capability.COMMANDS.isEnabled(),
                capability -> Capability.COMMANDS.refusalMessage());
        router.register("command.execute", new RemoteCommandHandler(manager, ledger, mainThread::add, () -> false));

        FutureTask<Void> inbound = new FutureTask<>(() -> {
            router.dispatch(request.toString(), channel);
            return null;
        });
        new Thread(inbound, "panel-cloud-policy-receiver").start();
        inbound.get(5, TimeUnit.SECONDS);
        assertThat(executions.get()).as("%s: no dispatch on the receiver thread", description).isZero();
        int scheduled = mainThread.size();
        // This is the handler's own executor, not MockBukkit's scheduler. Drain it explicitly;
        // erroneously queued refused commands only reach the harmless tracing command below.
        while (!mainThread.isEmpty()) {
            mainThread.remove().run();
        }
        assertThat(sent).extracting(frame -> frame.get("type").getAsString())
                .containsExactly("command.ack", "command.result");
        assertThat(sent).allSatisfy(frame -> assertThat(frame.get("replyTo").getAsString()).isEqualTo(id));
        JsonObject payload = sent.get(1).getAsJsonObject("payload");
        if (blocked) {
            JsonObject fixture = JsonParser.parseString(ContractFixtures.text(
                    "fixtures/agent/command.result/valid/blocked.json")).getAsJsonObject().getAsJsonObject("payload");
            assertAll(description,
                    () -> assertThat(scheduled).as("fixed refusal must not enqueue a dispatch").isZero(),
                    () -> assertThat(executions.get()).as("fixed refusal must not execute").isZero(),
                    () -> assertThat(payload.keySet()).containsExactlyInAnyOrderElementsOf(fixture.keySet()),
                    () -> assertThat(payload.get("status")).isEqualTo(fixture.get("status")),
                    () -> assertThat(payload.get("code")).isEqualTo(fixture.get("code")),
                    () -> assertThat(payload.get("message")).as("English console-only refusal").isNotNull(),
                    () -> assertThat(payload.has("message") ? payload.get("message").getAsString() : "")
                            .containsIgnoringCase("server console"),
                    () -> assertThat(payload.getAsJsonObject("details")).as("matched command details").isNotNull(),
                    () -> {
                        if (payload.has("details")) {
                            JsonObject details = payload.getAsJsonObject("details");
                            assertThat(details.keySet()).containsExactly("matched");
                            assertThat(details.get("matched").getAsString()).isEqualTo(matched);
                        }
                    });
        } else {
            assertThat(scheduled).as("allowed command is enqueued on the owned main-thread executor").isEqualTo(1);
            assertThat(executions.get()).as("allowed command really reaches Bukkit dispatch").isEqualTo(1);
            assertThat(payload.get("status").getAsString()).isEqualTo("ok");
            assertThat(payload.getAsJsonObject("data").getAsJsonArray("output")).hasSize(1);
            assertThat(payload.getAsJsonObject("data").getAsJsonArray("output").get(0).getAsString())
                    .isEqualTo("UltiCloud tracing command dispatched.");
        }
    }

    private void registerCloudTracingCommand(AtomicInteger executions) {
        String[] aliases = CloudLoginCommand.class.getAnnotation(CmdExecutor.class).alias();
        Command tracing = new Command(aliases[0]) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                assertThat(Bukkit.isPrimaryThread()).isTrue();
                executions.incrementAndGet();
                sender.sendMessage(Component.text("UltiCloud tracing command dispatched."));
                return true;
            }
        };
        tracing.setAliases(Arrays.asList(aliases));
        // Use the actual command map for case/namespace lookup and argument splitting. The
        // tracing body deliberately makes no policy decision and never invokes authentication.
        server.getCommandMap().register("ultitools", tracing);
    }

    private static Stream<Arguments> panelCloudCommandCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String alias : CloudLoginCommand.class.getAnnotation(CmdExecutor.class).alias()) {
            for (String subcommand : Arrays.asList("login", "logout")) {
                addCloudCommandForms(cases, alias, subcommand, true);
                addCloudCommandCase(cases, alias, subcommand, true, "tab separator", alias + "\t" + subcommand);
                addCloudCommandCase(cases, alias, subcommand, true, "trailing argument",
                        alias + " " + subcommand + " foo");
                addCloudCommandCase(cases, alias, subcommand, true, "combined variants",
                        "\t /UlTiToOlS:" + alias.toUpperCase(Locale.ROOT) + "\t "
                                + subcommand.toUpperCase(Locale.ROOT) + " foo \t");
            }
            for (String subcommand : Arrays.asList("", "status", "reconnect", "help", "loginx", "logoutx")) {
                addCloudCommandForms(cases, alias, subcommand, false);
            }
        }
        return cases.stream();
    }

    private static void addCloudCommandForms(List<Arguments> cases, String alias, String subcommand, boolean blocked) {
        String suffix = subcommand.isEmpty() ? "" : " " + subcommand;
        String bare = alias + suffix;
        addCloudCommandCase(cases, alias, subcommand, blocked, "bare", bare);
        addCloudCommandCase(cases, alias, subcommand, blocked, "namespace", "ultitools:" + bare);
        addCloudCommandCase(cases, alias, subcommand, blocked, "slash", "/" + bare);
        addCloudCommandCase(cases, alias, subcommand, blocked, "namespaced slash", "/ultitools:" + bare);
        addCloudCommandCase(cases, alias, subcommand, blocked, "mixed case", bare.toUpperCase(Locale.ROOT));
        addCloudCommandCase(cases, alias, subcommand, blocked, "namespaced mixed case",
                "UlTiToOlS:" + bare.toUpperCase(Locale.ROOT));
        addCloudCommandCase(cases, alias, subcommand, blocked, "outer whitespace", "\t " + bare + " \t");
        addCloudCommandCase(cases, alias, subcommand, blocked, "extra spaces",
                alias + (subcommand.isEmpty() ? "   " : "   " + subcommand + "  "));
    }

    private static void addCloudCommandCase(List<Arguments> cases, String alias, String subcommand, boolean blocked,
                                           String variant, String commandLine) {
        String description = (blocked ? "refused " : "allowed ") + alias + " "
                + (subcommand.isEmpty() ? "root" : subcommand) + " (" + variant + ")";
        cases.add(Arguments.of(description, commandLine, alias.toLowerCase(Locale.ROOT), blocked));
    }

    @Test
    void fixtureIsPersistedAndAckedBeforeOneMainThreadExecutionWithRealFeedback() throws Exception {
        JsonObject request = JsonParser.parseString(ContractFixtures.text(
                "fixtures/agent/command.execute/valid/say.json")).getAsJsonObject();
        request.getAsJsonObject("payload").addProperty("command", "list");
        String id = request.get("id").getAsString();
        List<String> order = new ArrayList<>();
        List<JsonObject> sent = new ArrayList<>();
        Queue<Runnable> mainThread = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        DedupeLedger ledger = new DedupeLedger() {
            @Override public boolean firstSeen(String value) {
                order.add("firstSeen");
                return seen.add(value);
            }
            @Override public void persistNow() { order.add("persistNow"); }
            @Override public JsonObject receiptFor(String value) { return null; }
            @Override public void recordReceipt(String value, JsonObject receipt) { }
        };
        FramedChannel channel = new FramedChannel(frame -> {
            JsonObject json = JsonParser.parseString(frame).getAsJsonObject();
            ContractSchemas.assertFrameValid(json);
            sent.add(json);
            order.add(json.get("type").getAsString());
            return true;
        }, () -> true, CloudLimits::defaults, Ulid::next, () -> 1000L);
        AtomicInteger executions = new AtomicInteger();
        server.addPlayer("TracerPlayer");
        Command command = new Command("list") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                assertThat(Bukkit.isPrimaryThread()).isTrue();
                executions.incrementAndGet();
                order.add("execute");
                sender.sendMessage(Component.text("There are " + server.getOnlinePlayers().size() + " players online."));
                return true;
            }
        };
        server.getCommandMap().register("tracer", command);
        AgentMessageRouter router = new AgentMessageRouter(capability -> Capability.COMMANDS.isEnabled(),
                capability -> Capability.COMMANDS.refusalMessage());
        router.register("command.execute", new RemoteCommandHandler(new CommandExecutionManager(), ledger,
                mainThread::add, () -> false));

        Thread receiver = new Thread(() -> router.dispatch(request.toString(), channel), "command-receiver");
        receiver.start();
        receiver.join();
        assertThat(order).containsExactly("firstSeen", "persistNow", "command.ack");
        assertThat(executions.get()).isZero();
        assertThat(mainThread).hasSize(1);
        mainThread.remove().run();

        assertThat(order).containsExactly("firstSeen", "persistNow", "command.ack", "execute", "command.result");
        assertThat(sent).hasSize(2);
        JsonObject result = sent.get(1).getAsJsonObject("payload");
        assertThat(result.get("status").getAsString()).isEqualTo("ok");
        JsonObject data = result.getAsJsonObject("data");
        assertThat(data.getAsJsonArray("output").get(0).getAsString()).isEqualTo("There are 1 players online.");
        assertThat(data.get("truncated").getAsBoolean()).isFalse();
        assertThat(data.get("executionMs").getAsBigDecimal().longValueExact()).isNotNegative();

        router.dispatch(request.toString(), channel);
        assertThat(sent).hasSize(3);
        assertThat(sent.get(2).get("type").getAsString()).isEqualTo("command.ack");
        assertThat(sent).allSatisfy(frame -> assertThat(frame.get("replyTo").getAsString()).isEqualTo(id));
        assertThat(order).containsExactly("firstSeen", "persistNow", "command.ack", "execute", "command.result",
                "firstSeen", "command.ack");
        assertThat(mainThread).isEmpty();
        assertThat(executions.get()).isEqualTo(1);
    }
}
