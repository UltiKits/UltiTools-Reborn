package com.ultikits.ultitools.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code command.result} shapes {@link RemoteCommandHandler} produces: the blocklist
 * refusal, the two failure codes, negotiated output-byte truncation and the disabling window
 * between acknowledgement and execution.
 */
@Timeout(30)
class RemoteCommandHandlerTest {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ServerMock server;
    private RecordingChannel channel;
    private Ledger ledger;
    private Queue<Runnable> mainThread;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(spy(new ServerMock()));
        YamlConfiguration config = new YamlConfiguration();
        config.set(Capability.COMMANDS.getConfigPath(), true);
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(plugin.getRemoteActionLog()).thenReturn(mock(RemoteActionLog.class));
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
        channel = new RecordingChannel();
        ledger = new Ledger();
        mainThread = new ArrayDeque<>();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void blockedCommandIsAckedThenAnsweredWithTheMatchedEntryAndNotExecuted() {
        AtomicInteger dispatches = countDispatches("stop");
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> false);

        handler.handle(request("minecraft:stop"), channel);

        assertThat(dispatches.get()).isZero();
        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).get("type").getAsString()).isEqualTo("command.ack");
        JsonObject payload = frames.get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("command_blocked");
        assertThat(payload.get("message").getAsString()).contains("ultipanel.commands.blocklist");
        assertThat(payload.getAsJsonObject("details").get("matched").getAsString()).isEqualTo("stop");
    }

    @Test
    void aRejectedDispatchIsAnsweredAsCommandFailed() {
        register("rejected", (sender, args) -> false);
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> false);

        handler.handle(request("rejected"), channel);
        drainMainThread();

        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("command_failed");
        // Mirrors CommandExecutionManager.NOT_ACCEPTED_OUTPUT, which is package-private to the
        // manager package; the wording itself is pinned by CommandExecutionManagerTest.
        assertThat(payload.get("message").getAsString()).startsWith("The server console did not accept the command.");
    }

    @Test
    void aDispatchExceptionIsAnsweredWithoutStackTraceAndAtMostFiveHundredCharacters() {
        StringBuilder loud = new StringBuilder("boom");
        while (loud.length() < 900) {
            loud.append("-detail");
        }
        register("crash", (sender, args) -> {
            throw new IllegalStateException(loud.toString());
        });
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> false);

        handler.handle(request("crash"), channel);
        drainMainThread();

        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("code").getAsString()).isEqualTo("command_failed");
        String message = payload.get("message").getAsString();
        assertThat(message).hasSize(500).doesNotContain("IllegalStateException").doesNotContain("\tat ");
    }

    @Test
    void aFrameworkFailureIsAnsweredAsInternalErrorAfterTheAck() {
        CommandExecutionManager failing = mock(CommandExecutionManager.class);
        doThrow(new IllegalStateException("framework")).when(failing).execute(any(), any(), any(), any());
        RemoteCommandHandler handler = handler(failing, () -> false);

        handler.handle(request("list"), channel);

        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).get("type").getAsString()).isEqualTo("command.ack");
        JsonObject payload = frames.get(1).getAsJsonObject("payload");
        assertThat(payload.get("code").getAsString()).isEqualTo("internal_error");
        assertThat(payload.get("message").getAsString()).doesNotContain("framework");
    }

    @Test
    void outputBeyondTheNegotiatedByteCapDropsTrailingLinesAndFlagsTruncation() {
        register("flood", (sender, args) -> {
            for (int i = 1; i <= 40; i++) {
                sender.sendMessage(Component.text("line-" + i + "-" + padding(50)));
            }
            return true;
        });
        channel.setLimits(limitsWithOutputCap(1024));
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> false);

        handler.handle(request("flood"), channel);
        drainMainThread();

        JsonObject data = channel.frames().get(1).getAsJsonObject("payload").getAsJsonObject("data");
        JsonArray output = data.getAsJsonArray("output");
        assertThat(output.size()).isLessThan(40);
        assertThat(output.size()).isGreaterThan(0);
        assertThat(output.get(0).getAsString()).startsWith("line-1-");
        assertThat(data.get("truncated").getAsBoolean()).isTrue();
        assertThat(utf8Bytes(output)).isLessThanOrEqualTo(1024);
    }

    @Test
    void outputWithinTheNegotiatedByteCapIsNotFlaggedAsTruncated() {
        register("quiet", (sender, args) -> {
            sender.sendMessage(Component.text("one line"));
            return true;
        });
        channel.setLimits(limitsWithOutputCap(1024));
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> false);

        handler.handle(request("quiet"), channel);
        drainMainThread();

        JsonObject data = channel.frames().get(1).getAsJsonObject("payload").getAsJsonObject("data");
        assertThat(data.getAsJsonArray("output")).hasSize(1);
        assertThat(data.get("truncated").getAsBoolean()).isFalse();
    }

    @Test
    void disablingBetweenAckAndExecutionSendsNoResultAndLogsOnceAtFine() {
        AtomicInteger dispatches = countDispatches("list");
        List<LogRecord> fine = captureRecords(RemoteCommandHandler.class.getName());
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> true);

        handler.handle(request("list"), channel);
        assertThat(channel.frames()).hasSize(1);
        drainMainThread();

        assertThat(channel.frames()).hasSize(1);
        assertThat(dispatches.get()).isZero();
        assertThat(fine).anySatisfy(record -> assertThat(record.getMessage()).contains("disabling"));
    }

    @Test
    void aRedeliveredIdIsReAckedAndNotExecutedAgainAfterARestart() {
        AtomicInteger dispatches = countDispatches("list");
        CloudRequest request = request("list");

        handler(new CommandExecutionManager(), () -> false).handle(request, channel);
        drainMainThread();
        // A restart keeps the persisted ledger: a new handler over the same port.
        handler(new CommandExecutionManager(), () -> false).handle(request, channel);
        drainMainThread();

        assertThat(dispatches.get()).isEqualTo(1);
        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).get("type").getAsString()).isEqualTo("command.ack");
        assertThat(frames.get(1).get("type").getAsString()).isEqualTo("command.result");
        assertThat(frames.get(2).get("type").getAsString()).isEqualTo("command.ack");
    }

    @Test
    void aPersistenceFailureWarnsWithTheIdOnlyAndStillAcksAndExecutes() {
        ledger.persistFails = true;
        AtomicInteger dispatches = countDispatches("persistprobe");
        List<LogRecord> warnings = captureRecords(AckFirstGate.class.getName());
        CloudRequest request = request("persistprobe");
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> false);

        handler.handle(request, channel);
        drainMainThread();

        assertThat(dispatches.get()).isEqualTo(1);
        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).get("type").getAsString()).isEqualTo("command.ack");
        assertThat(frames.get(1).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        List<String> warned = new ArrayList<>();
        for (LogRecord record : warnings) {
            if (record.getLevel() == Level.WARNING) {
                warned.add(record.getMessage());
            }
        }
        assertThat(warned).hasSize(1);
        assertThat(warned.get(0)).contains(request.id()).doesNotContain("persistprobe");
    }

    @Test
    void twoIdsAreAckedImmediatelyAndExecuteInArrivalOrder() {
        List<String> executed = new ArrayList<>();
        register("firstcmd", (sender, args) -> {
            executed.add("first");
            return true;
        });
        register("secondcmd", (sender, args) -> {
            executed.add("second");
            return true;
        });
        RemoteCommandHandler handler = handler(new CommandExecutionManager(), () -> false);

        handler.handle(request("firstcmd"), channel);
        handler.handle(request("secondcmd"), channel);

        assertThat(channel.frames()).extracting(frame -> frame.get("type").getAsString())
                .containsExactly("command.ack", "command.ack");
        drainMainThread();

        assertThat(executed).containsExactly("first", "second");
        assertThat(channel.frames()).hasSize(4);
    }

    private RemoteCommandHandler handler(CommandExecutionManager manager, java.util.function.BooleanSupplier disabling) {
        return new RemoteCommandHandler(manager, ledger, mainThread::add, disabling);
    }

    private CloudRequest request(String command) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", command);
        return new CloudRequest(Ulid.next(), "command.execute", 1761274800000L, null, payload);
    }

    private void drainMainThread() {
        while (!mainThread.isEmpty()) {
            mainThread.remove().run();
        }
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

    private static String padding(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append('p');
        }
        return text.toString();
    }

    private static int utf8Bytes(JsonElement element) {
        return GSON.toJson(element).getBytes(StandardCharsets.UTF_8).length;
    }

    private static CloudLimits limitsWithOutputCap(int outputBytes) {
        JsonObject limits = new JsonObject();
        limits.addProperty("maxFrameBytes", 1048576);
        JsonObject log = new JsonObject();
        log.addProperty("ratePerSec", 50);
        log.addProperty("burst", 200);
        log.addProperty("lineMaxBytes", 8192);
        log.addProperty("batchMaxLines", 100);
        limits.add("log", log);
        limits.addProperty("outboxMax", 1000);
        limits.addProperty("commandDedupeMax", 1000);
        limits.addProperty("commandDedupeTtlSeconds", 86400);
        limits.addProperty("commandOutputMaxBytes", outputBytes);
        limits.addProperty("fileMaxBytes", 393216);
        return CloudLimits.fromWelcome(limits);
    }

    private static List<LogRecord> captureRecords(String loggerName) {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(loggerName);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return records;
    }

    @FunctionalInterface
    private interface Body {
        boolean run(CommandSender sender, String[] args);
    }

    /** In-memory ledger whose state outlives a handler restart. */
    private static final class Ledger implements DedupeLedger {
        private final Set<String> ids = new HashSet<>();
        private final Map<String, JsonObject> receipts = new HashMap<>();
        private boolean persistFails;

        @Override
        public boolean firstSeen(String id) {
            return ids.add(id);
        }

        @Override
        public void persistNow() throws java.io.IOException {
            if (persistFails) {
                throw new java.io.IOException("disk full");
            }
        }

        @Override
        public JsonObject receiptFor(String id) {
            JsonObject receipt = receipts.get(id);
            return receipt == null ? null : JsonParser.parseString(receipt.toString()).getAsJsonObject();
        }

        @Override
        public void recordReceipt(String id, JsonObject receipt) {
            receipts.put(id, receipt.deepCopy());
        }
    }
}
