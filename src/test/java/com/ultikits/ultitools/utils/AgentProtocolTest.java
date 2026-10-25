package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.websocket.CloudRequest;

/**
 * The welcome rules, the overflow report, inbound errors and the outbound capability gate of
 * {@link AgentProtocol} (D-13, D-15, contract answers 3, 4, 21, SEMANTICS 6, 8, 14, 16, S-51), on a
 * real {@link AgentConnection} driven by fake sockets and a manual clock.
 */
@DisplayName("AgentProtocol: welcome rules, overflow, inbound errors, outbound gate")
class AgentProtocolTest {

    private static final String SERVER_ID = HandshakeEndToEndTest.SERVER_ID;
    private static final String OTHER_SERVER_ID = "01JXXXXSERVER0000000000000";
    private static final double HIGH = Math.nextDown(1.0);

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AgentConnectionTest.ManualScheduler scheduler = new AgentConnectionTest.ManualScheduler(now);
    private final List<HandshakeEndToEndTest.FakeAttempt> attempts = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final List<CloudRequest> replies = new ArrayList<>();
    private final AtomicInteger mismatches = new AtomicInteger();
    private final CloudOutbox outbox = new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, now::get);
    private final CommandDedupeSet dedupe = new CommandDedupeSet(CommandDedupeSet.DEFAULT_DEDUPE_MAX,
            CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS, now::get);
    private List<String> capabilities = Arrays.asList("telemetry", "logs", "events:players", "events:commands");
    private AgentConnection connection;
    private AgentProtocol protocol;

    @BeforeEach
    void setUp() {
        AgentConnection.Listener listener = new AgentConnection.Listener() {
            @Override
            public void onOpened() {
                protocol = new AgentProtocol(connection, outbox, dedupe, SERVER_ID, () -> capabilities,
                        CloudSession::frameworkSettings, capability -> true, capability -> "refused", now::get,
                        (level, message) -> logs.add(level.getName() + " " + message));
                protocol.addReplyListener(replies::add);
                protocol.onOpen();
            }

            @Override
            public void onText(String text) {
                protocol.onText(text);
            }

            @Override
            public void onStateChanged(AgentConnection.State state) {
                if (state != AgentConnection.State.OPEN && protocol != null) {
                    protocol.onClose();
                }
            }

            @Override
            public void onServerIdMismatch() {
                mismatches.incrementAndGet();
            }
        };
        connection = new AgentConnection(URI.create("wss://api.example.test/v1/agent"), () -> "ucs_SECRET", "6.3.1",
                scheduler, listener, (uri, headers, events) -> {
                    HandshakeEndToEndTest.FakeAttempt attempt = new HandshakeEndToEndTest.FakeAttempt(events, scheduler);
                    attempts.add(attempt);
                    return attempt;
                }, new ReconnectPolicy(() -> HIGH), now::get, AgentConnection.Timing.defaults(),
                (level, message) -> logs.add(level.getName() + " " + message));
    }

    // ------------------------------------------------------------------ helpers

    private HandshakeEndToEndTest.FakeAttempt open() {
        connection.start();
        HandshakeEndToEndTest.FakeAttempt attempt = attempts.get(attempts.size() - 1);
        attempt.accept();
        return attempt;
    }

    private static String hello(HandshakeEndToEndTest.FakeAttempt attempt) {
        return attempt.sent.get(0);
    }

    private static JsonObject welcome(HandshakeEndToEndTest.FakeAttempt attempt, String serverId) {
        return HandshakeEndToEndTest.welcomeFor(hello(attempt), serverId);
    }

    private static JsonObject frame(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private List<JsonObject> sentFrames(HandshakeEndToEndTest.FakeAttempt attempt) {
        List<JsonObject> frames = new ArrayList<>();
        for (String text : attempt.sent) {
            if (!"ping".equals(text)) {
                frames.add(frame(text));
            }
        }
        return frames;
    }

    private long pendingDelay() {
        long at = connection.nextRetryAtMillis();
        return at == 0 ? -1 : at - now.get();
    }

    private long count(String prefix) {
        return logs.stream().filter(line -> line.startsWith(prefix)).count();
    }

    // ------------------------------------------------------------------ welcome rules

    @Test
    @DisplayName("welcome limits outboxMax 100 and commandDedupeMax 100 bound the outbox and the dedupe set")
    void welcomeLimitsApply() {
        for (int i = 0; i < 150; i++) {
            outbox.enqueue("player.join", HandshakeEndToEndTest.join("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Steve"));
        }
        for (int i = 0; i < 150; i++) {
            dedupe.firstSeen(Ulid.next());
        }
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject welcome = welcome(attempt, SERVER_ID);
        JsonObject limits = welcome.getAsJsonObject("payload").getAsJsonObject("limits");
        limits.addProperty("outboxMax", 100);
        limits.addProperty("commandDedupeMax", 100);
        attempt.text(welcome.toString());

        assertThat(protocol.isEstablished()).isTrue();
        assertThat(outbox.unacked()).hasSize(100);
        assertThat(dedupe.snapshot()).hasSize(100);
        assertThat(protocol.limits().outboxMax()).isEqualTo(100);
        assertThat(protocol.channel().limits().commandDedupeMax()).isEqualTo(100);
    }

    @Test
    @DisplayName("a welcome whose limits break the contract is answered invalid_message, closed 1000, normal backoff")
    void outOfBoundsWelcomeIsRefused() {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject welcome = welcome(attempt, SERVER_ID);
        JsonObject bad = ContractFixtures.invalidFrames("welcome").stream()
                .filter(f -> f.path().endsWith("batch-too-large.json")).findFirst().get().frame();
        welcome.getAsJsonObject("payload").add("limits", bad.getAsJsonObject("payload").getAsJsonObject("limits"));
        attempt.text(welcome.toString());
        assertRefused(attempt, welcome.get("id").getAsString());
    }

    @Test
    @DisplayName("a welcome that does not answer the hello is refused the same way")
    void welcomeForAnotherHelloIsRefused() {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject welcome = welcome(attempt, SERVER_ID);
        welcome.addProperty("replyTo", Ulid.next());
        attempt.text(welcome.toString());
        assertRefused(attempt, welcome.get("id").getAsString());
    }

    static Stream<Arguments> invalidWelcomeFixtures() {
        return ContractFixtures.invalidFrames("welcome").stream()
                .map(fixture -> Arguments.of(fixture.path().substring(fixture.path().lastIndexOf('/') + 1), fixture));
    }

    @Test
    @DisplayName("the vendored contract carries both invalid welcome fixtures the parameterised test reads")
    void invalidWelcomeFixturesArePresent() {
        assertThat(ContractFixtures.invalidFrames("welcome")).extracting(ContractFixtures.Fixture::path)
                .anyMatch(path -> path.endsWith("/missing-replyTo.json"))
                .anyMatch(path -> path.endsWith("/batch-too-large.json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWelcomeFixtures")
    @DisplayName("every vendored invalid welcome is answered invalid_message and ends the attempt at once")
    void vendoredInvalidWelcomeIsRefusedAtOnce(String name, ContractFixtures.Fixture fixture) {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject frame = fixture.frame();
        assertThat(fixture.expect()).isEqualTo("invalid_message");
        attempt.text(frame.toString());
        // No scheduler advance: the refusal must not wait for the 15 s welcome deadline.
        assertRefused(attempt, frame.get("id").getAsString());
    }

    @Test
    @DisplayName("an unreadable frame before welcome is answered invalid_message without replyTo and ends the attempt")
    void unreadableFrameBeforeWelcomeIsRefused() {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        attempt.text("{not json");
        assertRefused(attempt, null);
    }

    private void assertRefused(HandshakeEndToEndTest.FakeAttempt attempt, String welcomeId) {
        List<JsonObject> frames = sentFrames(attempt);
        assertThat(frames).hasSize(2);
        JsonObject error = frames.get(1);
        ContractSchemas.assertFrameValid(error);
        assertThat(error.get("type").getAsString()).isEqualTo("error");
        if (welcomeId == null) {
            assertThat(error.has("replyTo")).as("no readable id to answer").isFalse();
        } else {
            assertThat(error.get("replyTo").getAsString()).isEqualTo(welcomeId);
        }
        assertThat(error.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
        assertThat(attempt.closedWith).isEqualTo(1000);
        assertThat(protocol.isEstablished()).isFalse();
        assertThat(protocol.channel().isOpen()).isFalse();
        assertThat(connection.state()).isEqualTo(AgentConnection.State.BACKOFF);
        assertThat(pendingDelay()).as("normal backoff, attempt 0").isEqualTo(1000);
        assertThat(mismatches.get()).isZero();
    }

    @Test
    @DisplayName("a welcome for another server id stops for good, closed 1000, never retried, reconnect cannot leave it")
    void serverIdMismatchStops() {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        attempt.text(welcome(attempt, OTHER_SERVER_ID).toString());

        assertThat(attempt.closedWith).isEqualTo(1000);
        assertThat(connection.state()).isEqualTo(AgentConnection.State.SERVER_ID_MISMATCH);
        assertThat(mismatches.get()).isEqualTo(1);
        assertThat(protocol.isEstablished()).isFalse();
        assertThat(sentFrames(attempt)).as("nothing but the hello").hasSize(1);
        scheduler.advance(10 * 60_000L);
        assertThat(attempts).as("no further attempt over ten times the maximum retry delay").hasSize(1);
        assertThat(connection.reconnectNow()).isEqualTo(AgentConnection.ReconnectOutcome.NOT_RUNNING);
        scheduler.runDue();
        assertThat(attempts).hasSize(1);
    }

    // ------------------------------------------------------------------ overflow

    @Test
    @DisplayName("a dropped count of 3 is reported once as outbox.overflow after the resend and cleared")
    void overflowIsReportedOnceAfterResend() {
        outbox.setCapacity(2);
        for (int i = 0; i < 5; i++) {
            outbox.enqueue("player.join", HandshakeEndToEndTest.join("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Steve"));
        }
        assertThat(outbox.droppedSinceLastReport()).isEqualTo(3);
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject welcome = welcome(attempt, SERVER_ID);
        welcome.getAsJsonObject("payload").getAsJsonObject("limits").addProperty("outboxMax", 2);
        attempt.text(welcome.toString());

        List<JsonObject> frames = sentFrames(attempt);
        assertThat(frames).extracting(f -> f.get("type").getAsString())
                .containsExactly("hello", "player.join", "player.join", "outbox.overflow");
        JsonObject overflow = frames.get(3);
        ContractSchemas.assertFrameValid(overflow);
        assertThat(overflow.getAsJsonObject("payload").get("dropped").getAsLong()).isEqualTo(3);
        assertThat(outbox.droppedSinceLastReport()).isZero();
    }

    @Test
    @DisplayName("when the overflow report cannot be handed to the socket the count stays for the next session")
    void overflowCountSurvivesASocketFailure() {
        outbox.setCapacity(1);
        for (int i = 0; i < 4; i++) {
            outbox.enqueue("player.join", HandshakeEndToEndTest.join("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Steve"));
        }
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject welcome = welcome(attempt, SERVER_ID);
        welcome.getAsJsonObject("payload").getAsJsonObject("limits").addProperty("outboxMax", 1);
        attempt.open = false;
        attempt.text(welcome.toString());
        assertThat(outbox.droppedSinceLastReport()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ inbound errors

    @Test
    @DisplayName("an error with replyTo reaches the reply listeners and logs one WARNING per code per minute")
    void replyErrorReachesListeners() {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        attempt.text(welcome(attempt, SERVER_ID).toString());
        String reply = ContractFixtures.text("fixtures/agent/error/valid/reply-invalid-message.json");
        attempt.text(reply);
        attempt.text(reply);

        assertThat(replies).hasSize(2);
        assertThat(replies.get(0).replyTo()).isEqualTo(frame(reply).get("replyTo").getAsString());
        assertThat(replies.get(0).payload().get("code").getAsString()).isEqualTo("invalid_message");
        String warning = "WARNING UltiCloud: the gateway answered a frame with error invalid_message.";
        assertThat(logs.stream().filter(warning::equals)).hasSize(1);
        now.addAndGet(60_000L);
        attempt.text(reply);
        assertThat(logs.stream().filter(warning::equals)).hasSize(2);
        assertThat(sentFrames(attempt)).as("an error is never answered").extracting(f -> f.get("type").getAsString())
                .doesNotContain("error");
        assertThat(logs).noneMatch(line -> line.contains("unrecognized key"));
    }

    @Test
    @DisplayName("an unsolicited rate_limited is a FINE notice, at most one INFO per minute")
    void unsolicitedRateLimitedIsQuiet() {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        attempt.text(welcome(attempt, SERVER_ID).toString());
        String notice = ContractFixtures.text("fixtures/agent/error/valid/unsolicited-rate-limited.json");
        attempt.text(notice);
        attempt.text(notice);
        attempt.text(notice);
        assertThat(count("INFO UltiCloud: the gateway is limiting")).isEqualTo(1);
        assertThat(count("FINE UltiCloud: the gateway is limiting")).isEqualTo(2);
        assertThat(count("WARNING")).isZero();
        assertThat(replies).isEmpty();
        now.addAndGet(60_000L);
        attempt.text(notice);
        assertThat(count("INFO UltiCloud: the gateway is limiting")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ outbound gate

    @Test
    @DisplayName("an up frame whose capability the welcome did not accept is not sent")
    void outboundCapabilityGate() {
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject welcome = welcome(attempt, SERVER_ID);
        com.google.gson.JsonArray accepted = new com.google.gson.JsonArray();
        accepted.add("telemetry");
        accepted.add("logs");
        welcome.getAsJsonObject("payload").add("accepted", accepted);
        attempt.text(welcome.toString());
        int before = attempt.sent.size();

        JsonObject command = new JsonObject();
        command.addProperty("command", "login");
        assertThat(protocol.channel().send("player.command", command)).isFalse();
        assertThat(protocol.channel().sendTracked("player.command", command)).isNull();
        assertThat(attempt.sent).hasSize(before);
        // Control: an accepted capability goes out.
        assertThat(protocol.channel().send("log.lines", new JsonObject())).isTrue();
        assertThat(attempt.sent).hasSize(before + 1);
        assertThat(protocol.acceptedCapabilities()).containsExactly("telemetry", "logs");
    }

    @Test
    @DisplayName("a gateway cannot accept a capability the hello did not declare")
    void acceptedIsIntersectedWithTheHello() {
        capabilities = Arrays.asList("telemetry");
        HandshakeEndToEndTest.FakeAttempt attempt = open();
        JsonObject welcome = welcome(attempt, SERVER_ID);
        welcome.getAsJsonObject("payload").getAsJsonArray("accepted").add("commands");
        attempt.text(welcome.toString());
        assertThat(protocol.acceptedCapabilities()).containsExactly("telemetry");
        assertThat(logs).noneMatch(line -> line.startsWith(Level.SEVERE.getName()));
    }
}
