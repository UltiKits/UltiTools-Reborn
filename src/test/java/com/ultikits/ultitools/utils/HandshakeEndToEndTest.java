package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;

/**
 * The tracer of plan 22-20 (D-11, D-12, D-13, S-11, contract SEMANTICS 10): a linked server opens a
 * real socket to a loopback gateway, sends a schema-valid {@code hello} as its first and only frame
 * until {@code welcome}, counts as connected only after that welcome, resends both unacknowledged
 * outbox events with their own envelope ids, and drops them once the gateway acks them.
 */
@DisplayName("Handshake end to end: hello, welcome, resend, ack")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HandshakeEndToEndTest {

    /** A 26-character Crockford id, the shape of a real {@code serverId}. */
    static final String SERVER_ID = "01JTESTSERVER0000000000000";
    private static final double HIGH = Math.nextDown(1.0);

    @TempDir
    Path tempDir;

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private Gateway gateway;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("HandshakeEndToEndTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
        });
        CloudTestAccess.useTemporaryCredentials(tempDir);
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the repository's test idiom for the plugin singleton
    void tearDown() throws Exception {
        CloudSession.current().invalidate();
        if (gateway != null) {
            gateway.stop(500);
        }
        CloudTestAccess.reset();
        Field field = UltiTools.class.getDeclaredField("ultiTools");
        field.setAccessible(true);
        field.set(null, null);
    }

    // ------------------------------------------------------------------ real socket

    @Test
    @DisplayName("real socket: hello alone until welcome, connected only after it, two events resent, ack drains")
    void handshakeResendsAndAckDrainsTheOutbox() throws Exception {
        gateway = new Gateway();
        gateway.start();
        assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();
        CloudSession.setAgentEndpointOverrideForTesting(
                new URI("ws://127.0.0.1:" + gateway.getPort() + "/v1/agent"));
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();

        CloudSession session = CloudSession.current();
        session.offerReliableEvent("player.join", join("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Steve"));
        session.offerReliableEvent("player.join", join("853c80ef-3c37-49fd-aa49-938b674adae6", "Alex"));
        List<String> outboxIds = new ArrayList<>();
        for (CloudOutbox.Entry entry : session.cloudOutbox().unacked()) {
            outboxIds.add(entry.id());
        }
        assertThat(outboxIds).hasSize(2);
        session.flushCloudState();
        assertThat(persistedEvents()).hasSize(2);

        PluginInitiationUtils.initWebsocket();
        await(() -> gateway.hello != null, "hello");
        // Nothing else may arrive before welcome: give the client time to send anything it would.
        Thread.sleep(500);
        assertThat(gateway.frames).hasSize(1);
        assertThat(CloudAuthManager.status().isConnected()).as("open but not welcomed").isFalse();
        assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo("connecting");

        JsonObject hello = gateway.hello;
        assertThat(hello.get("type").getAsString()).isEqualTo("hello");
        assertThat(hello.get("v").getAsInt()).isEqualTo(2);
        JsonObject helloPayload = hello.getAsJsonObject("payload");
        assertThat(helloPayload.get("protocol").getAsInt()).isEqualTo(2);
        assertThat(helloPayload.get("framework").getAsString()).isEqualTo("6.3.1-SNAPSHOT");
        assertThat(helloPayload.getAsJsonArray("capabilities")).isNotEmpty();
        assertThat(helloPayload.getAsJsonObject("settings").get("powerActionsEnabled").getAsJsonPrimitive().isBoolean())
                .isTrue();

        gateway.sendWelcome(SERVER_ID);
        await(() -> CloudAuthManager.status().isConnected(), "connected after welcome");
        await(() -> gateway.eventIds.size() == 2, "both events resent");
        assertThat(gateway.eventIds).containsExactlyElementsOf(outboxIds);

        gateway.ack(outboxIds);
        await(() -> session.cloudOutbox().unacked().isEmpty(), "ack removes the events");
        // The 30 s cadence runs flushCloudState's write; the removal reaches the file through it.
        session.flushCloudState();
        assertThat(persistedEvents()).isEmpty();
        assertThat(gateway.invalidFrames).isEmpty();
        assertThat(records).allSatisfy(r -> assertThat(String.valueOf(r.getMessage())).doesNotContain("ucs_TESTSECRET"));
    }

    // ------------------------------------------------------------------ fake socket, manual clock

    @Test
    @DisplayName("established at welcome: counters keep growing across opens, reset at welcome; heartbeat starts there")
    void establishedOnlyAtWelcome() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        List<AgentConnectionTest.ManualScheduler> schedulers = new ArrayList<>();
        List<FakeAttempt> attempts = new ArrayList<>();
        CloudSession.setAgentEndpointOverrideForTesting(URI.create("ws://cloud.example.test/v1/agent"));
        CloudSession.setAgentTimingOverrideForTesting(new ReconnectPolicy(() -> HIGH),
                AgentConnection.Timing.defaults());
        CloudSession.setAgentRuntimeForTesting(() -> {
            AgentConnectionTest.ManualScheduler scheduler = new AgentConnectionTest.ManualScheduler(clock);
            schedulers.add(scheduler);
            return scheduler;
        }, (uri, headers, events) -> {
            FakeAttempt attempt = new FakeAttempt(events, schedulers.get(schedulers.size() - 1));
            attempts.add(attempt);
            return attempt;
        }, clock::get);
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        try {
            PluginInitiationUtils.initWebsocket();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        AgentConnectionTest.ManualScheduler scheduler = schedulers.get(0);
        scheduler.runDue();

        FakeAttempt first = attempts.get(0);
        first.accept();
        assertThat(first.sent).hasSize(1);
        assertThat(JsonParser.parseString(first.sent.get(0)).getAsJsonObject().get("type").getAsString())
                .isEqualTo("hello");
        assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo("connecting");
        first.close(1011);
        assertThat(CloudAuthManager.status().getConnectionDescription()).startsWith("reconnecting in 1 s");

        scheduler.advance(1000);
        FakeAttempt second = attempts.get(1);
        second.accept();
        second.close(1011);
        assertThat(CloudAuthManager.status().getConnectionDescription())
                .as("an open without welcome does not reset the counter").startsWith("reconnecting in 2 s");

        scheduler.advance(2000);
        FakeAttempt third = attempts.get(2);
        third.accept();
        scheduler.advance(14_000);
        assertThat(third.sent).as("no heartbeat before welcome").hasSize(1);
        third.text(welcomeFor(third.sent.get(0), SERVER_ID).toString());
        assertThat(CloudAuthManager.status().isConnected()).isTrue();
        scheduler.advance(30_000);
        assertThat(third.sent).contains("ping");
        third.close(1011);
        assertThat(CloudAuthManager.status().getConnectionDescription())
                .as("welcome reset the counter").startsWith("reconnecting in 1 s");
    }

    // ------------------------------------------------------------------ helpers

    static JsonObject join(String uuid, String name) {
        JsonObject payload = new JsonObject();
        payload.addProperty("playerUuid", uuid);
        payload.addProperty("playerName", name);
        return payload;
    }

    /**
     * The vendored {@code welcome/valid/default-limits.json}, answering {@code helloText}: replyTo is
     * the hello's id, serverId the given one, and accepted the hello's capabilities (the
     * intersection with a gateway that supports everything).
     */
    static JsonObject welcomeFor(String helloText, String serverId) {
        JsonObject hello = JsonParser.parseString(helloText).getAsJsonObject();
        JsonObject welcome = ContractFixtures.object("fixtures/agent/welcome/valid/default-limits.json");
        welcome.addProperty("id", Ulid.next());
        welcome.addProperty("replyTo", hello.get("id").getAsString());
        JsonObject payload = welcome.getAsJsonObject("payload");
        payload.addProperty("serverId", serverId);
        payload.add("accepted", hello.getAsJsonObject("payload").getAsJsonArray("capabilities").deepCopy());
        return welcome;
    }

    /**
     * Lets a loopback gateway of another test answer the hello the way the contract requires
     * (plan 22-20: a connection counts as connected only after welcome).
     *
     * @param peer the gateway's side of the socket
     * @param text a frame the client sent
     */
    static void answerHello(WebSocket peer, String text) {
        try {
            JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
            if ("hello".equals(frame.get("type").getAsString())) {
                peer.send(welcomeFor(text, SERVER_ID).toString());
            }
        } catch (RuntimeException notAFrame) {
            // Not an envelope (for example the heartbeat): nothing to answer.
        }
    }

    static JsonObject ackFor(List<String> ids) {
        JsonObject ack = ContractFixtures.object("fixtures/agent/ack/valid/two-ids.json");
        ack.addProperty("id", Ulid.next());
        JsonArray array = new JsonArray();
        for (String id : ids) {
            array.add(id);
        }
        ack.getAsJsonObject("payload").add("ids", array);
        return ack;
    }

    private JsonArray persistedEvents() throws java.io.IOException {
        Path file = tempDir.resolve("cloud-outbox.json");
        JsonObject state = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .getAsJsonObject();
        return state.getAsJsonArray("events");
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        AgentConnectionLoopbackTest.await(condition, what);
    }

    /** One fake attempt: records what was sent and lets the test fire socket events. */
    static final class FakeAttempt implements AgentConnection.Handle {
        final AgentConnection.SocketEvents events;
        final AgentConnectionTest.ManualScheduler scheduler;
        final List<String> sent = new ArrayList<>();
        int closedWith;
        boolean open;

        FakeAttempt(AgentConnection.SocketEvents events, AgentConnectionTest.ManualScheduler scheduler) {
            this.events = events;
            this.scheduler = scheduler;
        }

        @Override
        public void closeAttempt(int code, String reason) {
            closedWith = code;
            open = false;
        }

        @Override
        public boolean sendText(String text) {
            if (!open) {
                return false;
            }
            sent.add(text);
            return true;
        }

        void accept() {
            open = true;
            events.onOpen();
            scheduler.runDue();
        }

        void close(int code) {
            open = false;
            events.onClosed(code, "", true);
            scheduler.runDue();
        }

        void text(String text) {
            events.onText(text);
            scheduler.runDue();
        }
    }

    /**
     * A loopback gateway that validates every frame against the frozen schemas, answers the hello
     * only when the test says so, and records the reliable events it receives.
     */
    private static final class Gateway extends WebSocketServer {
        final CountDownLatch started = new CountDownLatch(1);
        final List<JsonObject> frames = new CopyOnWriteArrayList<>();
        final List<String> eventIds = new CopyOnWriteArrayList<>();
        final List<String> invalidFrames = new CopyOnWriteArrayList<>();
        volatile JsonObject hello;
        volatile WebSocket peer;

        Gateway() {
            super(new InetSocketAddress("127.0.0.1", 0), 1);
            setReuseAddr(true);
        }

        void sendWelcome(String serverId) {
            peer.send(welcomeFor(hello.toString(), serverId).toString());
        }

        void ack(List<String> ids) {
            peer.send(ackFor(ids).toString());
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            peer = conn;
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            if ("ping".equals(message)) {
                conn.send("pong");
                return;
            }
            JsonObject frame = JsonParser.parseString(message).getAsJsonObject();
            frames.add(frame);
            if (!ContractSchemas.isValid(frame)) {
                invalidFrames.add(message);
            }
            String type = frame.get("type").getAsString();
            if ("hello".equals(type) && hello == null) {
                hello = frame;
            } else if ("player.join".equals(type) || "player.leave".equals(type)) {
                eventIds.add(frame.get("id").getAsString());
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            // nothing to record
        }

        @Override
        public void onError(WebSocket conn, Exception error) {
            // a client going away mid-test
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }
}
