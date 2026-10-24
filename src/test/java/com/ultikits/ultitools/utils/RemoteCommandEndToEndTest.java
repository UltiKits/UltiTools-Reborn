package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
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
