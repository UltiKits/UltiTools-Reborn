package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.ExpiryReason;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.Failure;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.GameAccountResult;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.GameAuth;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkCompletion;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkCreation;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkExpiry;
import com.ultikits.ultitools.websocket.CloudPlayerLinks.LinkProof;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * End-to-end player-link behaviour over the real router, the real ledger contract and the frozen
 * fixtures: a module creates a link, receives its URL, and later its completion with a persisted
 * result.
 */
@DisplayName("Player-link channel: create, answer, completion and persisted result")
class PlayerLinkEndToEndTest {

    static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    static final String CREATE_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVX";
    static final String COMPLETED_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0CRT";
    static final long OFFSET = 5000L;

    private RecordingChannel recording;
    private TestLedger ledger;
    private ManualTimers timers;
    private UltiToolsPlugin plugin;
    private PlayerLinkChannel channel;
    private CloudPlayerLinks links;
    private AgentMessageRouter router;

    @BeforeEach
    void setUp() {
        recording = new RecordingChannel();
        ledger = new TestLedger(() -> Frames.count(recording, "player.link.completed.result"));
        timers = new ManualTimers();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        channel = new PlayerLinkChannel(() -> recording, ledger, () -> false, () -> OFFSET, timers.service(),
                Runnable::run);
        links = new CloudPlayerLinks(channel);
        router = new AgentMessageRouter(capability -> true, capability -> "not available");
        channel.register(router);
    }

    @Test
    @DisplayName("create records one schema-valid player.link.create and returns the id it used")
    void createSendsOneTrackedFrame() {
        CompletableFuture<LinkCreation> future = links.create(plugin, STEVE, "Steve",
                GameAuth.authPlugin(true, false), EnumSet.of(LinkProof.IN_GAME_SESSION, LinkProof.WEB_REGISTRATION));

        assertThat(future.isDone()).as("create must not wait for the answer").isFalse();
        JsonObject frame = Frames.last(recording, "player.link.create");
        assertThat(frame.get("replyTo")).as("player.link.create forbids replyTo").isNull();
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.getAsJsonObject("player").get("uuid").getAsString())
                .isEqualTo("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertThat(payload.getAsJsonObject("player").get("name").getAsString()).isEqualTo("Steve");
        assertThat(payload.get("serverOnlineMode").getAsBoolean()).as("the port's value").isFalse();
        JsonObject auth = payload.getAsJsonObject("gameAuth");
        assertThat(auth.get("provider").getAsString()).isEqualTo("auth_plugin");
        assertThat(auth.get("authenticated").getAsBoolean()).isTrue();
        assertThat(auth.get("accountExists").getAsBoolean()).isFalse();
        assertThat(Frames.texts(payload.getAsJsonArray("offeredProofs")))
                .containsExactly("in_game_session", "web_registration");
        assertThat(timers.pending()).as("a 30 s deadline is armed").isEqualTo(1);
    }

    @Test
    @DisplayName("a created answer completes the future with the URL, proofs and framework-clock expiry")
    void createdAnswerCompletesTheFuture() throws Exception {
        CompletableFuture<LinkCreation> future = createLink();
        String createId = Frames.last(recording, "player.link.create").get("id").getAsString();

        dispatch(Frames.fixture("fixtures/agent/player.link.created/valid/url.json", createId), recording);

        LinkCreation creation = future.get(1, TimeUnit.SECONDS);
        assertThat(creation.failure()).isNull();
        assertThat(creation.linkId()).isEqualTo(createId);
        assertThat(creation.url()).isEqualTo("https://panel.ultikits.com/link#ucl_3q2f9XbK");
        assertThat(creation.expiresAt()).isEqualTo(Instant.ofEpochMilli(1761275400000L - OFFSET));
        assertThat(creation.acceptedProofs()).containsExactly(LinkProof.IN_GAME_SESSION);
    }

    @Test
    @DisplayName("a completion reaches the owner's handler and its result is persisted before it is sent")
    void completionReachesTheHandlerAndIsRecorded() throws Exception {
        CompletableFuture<LinkCreation> future = createLink();
        String createId = Frames.last(recording, "player.link.create").get("id").getAsString();
        dispatch(Frames.fixture("fixtures/agent/player.link.created/valid/url.json", createId), recording);
        future.get(1, TimeUnit.SECONDS);

        final AtomicReference<LinkCompletion> received = new AtomicReference<>();
        links.setHandler(plugin, new RecordingHandler(received, GameAccountResult.CREATED));

        dispatch(Frames.fixtureWithId("fixtures/agent/player.link.completed/valid/web-registration.json",
                COMPLETED_ID, createId), recording);

        LinkCompletion completion = received.get();
        assertThat(completion).isNotNull();
        assertThat(completion.completionId()).isEqualTo(COMPLETED_ID);
        assertThat(completion.linkId()).isEqualTo(createId);
        assertThat(completion.proof()).isEqualTo(LinkProof.WEB_REGISTRATION);
        assertThat(completion.playerUuid()).isEqualTo(STEVE);
        assertThat(completion.playerName()).isEqualTo("Steve");
        assertThat(completion.provisionGameAccount()).isTrue();
        assertThat(completion.webUsername()).isEqualTo("steve_web");

        JsonObject result = Frames.last(recording, "player.link.completed.result");
        assertThat(result.get("replyTo").getAsString()).isEqualTo(COMPLETED_ID);
        JsonObject payload = result.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").get("gameAccount").getAsString()).isEqualTo("created");

        assertThat(ledger.receiptFor(COMPLETED_ID)).isNotNull();
        assertThat(ledger.receiptFor(COMPLETED_ID).getAsJsonObject("data").get("gameAccount").getAsString())
                .isEqualTo("created");
        assertThat(ledger.resultsAtPersist).as("result frames already sent when the receipt was persisted")
                .containsExactly(0);
    }

    @Test
    @DisplayName("with no connection the future completes NOT_CONNECTED and nothing is sent")
    void notConnectedFailsAtOnce() {
        recording.setOpen(false);

        CompletableFuture<LinkCreation> future = links.create(plugin, STEVE, "Steve",
                GameAuth.none(), EnumSet.of(LinkProof.IN_GAME_SESSION));

        assertThat(future.isDone()).isTrue();
        assertThat(future.join().failure()).isEqualTo(Failure.NOT_CONNECTED);
        assertThat(recording.frames()).isEmpty();
        assertThat(ledger.receipts).isEmpty();
    }

    /** Starts one create the way a module would and leaves it waiting. */
    private CompletableFuture<LinkCreation> createLink() {
        return links.create(plugin, STEVE, "Steve", GameAuth.authPlugin(true, false),
                EnumSet.of(LinkProof.IN_GAME_SESSION));
    }

    private void dispatch(JsonObject frame, CloudChannel connection) {
        router.dispatch(frame.toString(), connection);
    }

    /** A handler that records the completion it saw and answers with a fixed result. */
    static final class RecordingHandler implements CloudPlayerLinks.LinkEventHandler {
        private final AtomicReference<LinkCompletion> received;
        private final GameAccountResult result;
        private final AtomicReference<LinkExpiry> expiry = new AtomicReference<>();
        private final List<String> calls = new CopyOnWriteArrayList<>();

        RecordingHandler(AtomicReference<LinkCompletion> received, GameAccountResult result) {
            this.received = received;
            this.result = result;
        }

        @Override
        public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
            received.set(completion);
            calls.add("completed");
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void onExpired(LinkExpiry seen) {
            expiry.set(seen);
            calls.add("expired");
        }

        AtomicReference<LinkExpiry> expiry() {
            return expiry;
        }

        List<String> calls() {
            return calls;
        }
    }





    /**
     * In-memory {@link DedupeLedger} that also records, for every persisted receipt, how many
     * completion-result frames had already been sent when the persistence happened.
     */
    static final class TestLedger implements DedupeLedger {
        final Map<String, JsonObject> receipts = new ConcurrentHashMap<>();
        final List<Integer> resultsAtPersist = new CopyOnWriteArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final IntSupplier resultFrames;

        TestLedger(IntSupplier resultFrames) {
            this.resultFrames = resultFrames;
        }

        @Override
        public synchronized boolean firstSeen(String id) {
            return seen.add(id);
        }

        @Override
        public synchronized void persistNow() throws IOException {
            resultsAtPersist.add(resultFrames.getAsInt());
        }

        @Override
        public JsonObject receiptFor(String id) {
            JsonObject receipt = receipts.get(id);
            return receipt == null ? null : receipt.deepCopy();
        }

        @Override
        public void recordReceipt(String id, JsonObject receipt) {
            receipts.put(id, receipt.deepCopy());
        }
    }

    /** A timer port whose scheduled work runs only when a test asks for it. */
    static final class ManualTimers {
        private final ScheduledExecutorService service = Mockito.mock(ScheduledExecutorService.class);
        private final List<Task> tasks = new CopyOnWriteArrayList<>();

        ManualTimers() {
            Mockito.when(service.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(new Answer<ScheduledFuture<?>>() {
                        @Override
                        public ScheduledFuture<?> answer(InvocationOnMock invocation) {
                            tasks.add(new Task((Runnable) invocation.getArguments()[0],
                                    (Long) invocation.getArguments()[1]));
                            return Mockito.mock(ScheduledFuture.class);
                        }
                    });
        }

        ScheduledExecutorService service() {
            return service;
        }

        int pending() {
            return tasks.size();
        }

        /** Runs every task scheduled with {@code delayMillis}, in scheduling order. */
        void runAll(long delayMillis) {
            for (Task task : new ArrayList<>(tasks)) {
                if (task.delayMs == delayMillis && tasks.remove(task)) {
                    task.runnable.run();
                }
            }
        }

        private static final class Task {
            private final Runnable runnable;
            private final long delayMs;

            private Task(Runnable runnable, long delayMs) {
                this.runnable = runnable;
                this.delayMs = delayMs;
            }
        }
    }

    /** Frame construction and lookup over the frozen fixtures. */
    static final class Frames {
        private Frames() {
        }

        static JsonObject fixture(String path, String replyTo) {
            JsonObject frame = ContractFixtures.object(path);
            frame.addProperty("replyTo", replyTo);
            return frame;
        }

        static JsonObject fixtureWithId(String path, String id, String replyTo) {
            JsonObject frame = fixture(path, replyTo);
            frame.addProperty("id", id);
            return frame;
        }

        static JsonObject parse(String text) {
            return JsonParser.parseString(text).getAsJsonObject();
        }

        static List<JsonObject> ofType(RecordingChannel recording, String type) {
            List<JsonObject> matches = new ArrayList<>();
            for (JsonObject frame : recording.frames()) {
                if (type.equals(frame.get("type").getAsString())) {
                    matches.add(frame);
                }
            }
            return matches;
        }

        static JsonObject last(RecordingChannel recording, String type) {
            List<JsonObject> matches = ofType(recording, type);
            assertThat(matches).as("frames of type " + type).isNotEmpty();
            return matches.get(matches.size() - 1);
        }

        static int count(RecordingChannel recording, String type) {
            return ofType(recording, type).size();
        }

        static List<String> texts(JsonArray array) {
            List<String> values = new ArrayList<>();
            for (JsonElement element : array) {
                values.add(element.getAsString());
            }
            return Collections.unmodifiableList(values);
        }
    }

    @Test
    @DisplayName("argument validation refuses an empty name and an unusable proof set")
    void argumentsAreValidatedEagerly() {
        assertThatThrownBy(() -> links.create(plugin, STEVE, "", GameAuth.none(),
                EnumSet.of(LinkProof.IN_GAME_SESSION))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> links.create(plugin, STEVE, "Steve", GameAuth.none(),
                Collections.<LinkProof>emptySet())).isInstanceOf(IllegalArgumentException.class);
    }
}
