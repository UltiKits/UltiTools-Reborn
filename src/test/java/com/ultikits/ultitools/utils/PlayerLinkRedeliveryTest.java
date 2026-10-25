package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redelivery, restart, timeout, expiry and failure behaviour of the player-link channel.
 */
@DisplayName("Player-link channel: redelivery, ordering, timeouts and every contract failure")
class PlayerLinkRedeliveryTest {

    private static final String COMPLETED = "01JB2Z8Y3K4M5N6P7Q8R9S0CC1";
    private static final String HELD = "01JB2Z8Y3K4M5N6P7Q8R9S0CC2";
    private static final String NO_PROVISION = "01JB2Z8Y3K4M5N6P7Q8R9S0CC3";
    private static final String CANCELLED_EXPIRY_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";

    private RecordingChannel recording;
    private PlayerLinkEndToEndTest.TestLedger ledger;
    private PlayerLinkEndToEndTest.ManualTimers timers;
    private UltiToolsPlugin plugin;
    private UltiToolsPlugin other;
    private PlayerLinkChannel channel;
    private CloudPlayerLinks links;
    private AgentMessageRouter router;

    @BeforeEach
    void setUp() {
        recording = new RecordingChannel();
        ledger = new PlayerLinkEndToEndTest.TestLedger(() -> 0);
        timers = new PlayerLinkEndToEndTest.ManualTimers();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        other = Mockito.mock(UltiToolsPlugin.class);
        restart();
    }

    /** Rebuilds the channel and the router over the same recorded connection and ledger. */
    private void restart() {
        channel = new PlayerLinkChannel(() -> recording, ledger, () -> false, () -> 0L, timers.service(), Runnable::run);
        links = new CloudPlayerLinks(channel);
        router = new AgentMessageRouter(capability -> true, capability -> "not available");
        channel.register(router);
    }

    private JsonObject completedFrame(String id, String createId) {
        return PlayerLinkEndToEndTest.Frames.fixtureWithId(
                "fixtures/agent/player.link.completed/valid/web-registration.json", id, createId);
    }

    private JsonObject inGameFrame(String id, String createId) {
        return PlayerLinkEndToEndTest.Frames.fixtureWithId(
                "fixtures/agent/player.link.completed/valid/in-game-session.json", id, createId);
    }

    private void dispatch(JsonObject frame) {
        router.dispatch(frame.toString(), recording);
    }

    private List<JsonObject> results() {
        return PlayerLinkEndToEndTest.Frames.ofType(recording, "player.link.completed.result");
    }

    @Test
    @DisplayName("after a restart a completion reaches the handler, and its redelivery only re-sends the recorded result")
    void restartDeliversAndReplays() {
        AtomicReference<LinkCompletion> received = new AtomicReference<>();
        links.setHandler(plugin, new FixedHandler(received, GameAccountResult.CREATED));
        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(received.get()).isNotNull();
        assertThat(results()).hasSize(1);

        restart();
        AtomicReference<LinkCompletion> afterRestart = new AtomicReference<>();
        links.setHandler(plugin, new FixedHandler(afterRestart, GameAccountResult.CREATED));

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));

        assertThat(afterRestart.get()).as("a redelivered completed must not reach the handler").isNull();
        assertThat(results()).as("the recorded result is re-sent").hasSize(2);
        assertThat(results().get(1).get("replyTo").getAsString()).isEqualTo(COMPLETED);
        assertThat(results().get(1).getAsJsonObject("payload"))
                .isEqualTo(results().get(0).getAsJsonObject("payload"));
    }

    @Test
    @DisplayName("a duplicate while the handler is still running is ignored and exactly one result is sent")
    void duplicateWhileRunningIsIgnored() {
        CompletableFuture<GameAccountResult> running = new CompletableFuture<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        links.setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                calls.add(completion.completionId());
                return running;
            }
        });

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(calls).hasSize(1);
        assertThat(results()).isEmpty();

        running.complete(GameAccountResult.CREATED);
        assertThat(results()).hasSize(1);
    }

    @Test
    @DisplayName("a completion the handler answers after a reconnect is sent on the live connection, exactly once")
    void lateCompletionIsAnsweredOnTheLiveConnection() {
        RecordingChannel first = new RecordingChannel();
        RecordingChannel second = new RecordingChannel();
        AtomicReference<CloudChannel> live = new AtomicReference<CloudChannel>(first);
        PlayerLinkChannel reconnecting = new PlayerLinkChannel(live::get, ledger, () -> false, () -> 0L,
                timers.service(), Runnable::run);
        AgentMessageRouter reconnectingRouter = new AgentMessageRouter(capability -> true,
                capability -> "not available");
        reconnecting.register(reconnectingRouter);
        final CompletableFuture<GameAccountResult> running = new CompletableFuture<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        new CloudPlayerLinks(reconnecting).setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                calls.add(completion.completionId());
                return running;
            }
        });

        reconnectingRouter.dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID).toString(), first);
        first.setOpen(false);
        reconnecting.onChannelClosed();
        live.set(second);
        reconnectingRouter.dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID).toString(), second);
        assertThat(calls).as("the redelivery is a duplicate of the call in flight").hasSize(1);

        running.complete(GameAccountResult.CREATED);

        assertThat(PlayerLinkEndToEndTest.Frames.ofType(first, "player.link.completed.result"))
                .as("the closed connection gets nothing").isEmpty();
        assertThat(PlayerLinkEndToEndTest.Frames.ofType(second, "player.link.completed.result"))
                .as("the live connection gets the result").hasSize(1);
        assertThat(ledger.receiptFor(COMPLETED)).as("the result is persisted for the next redelivery").isNotNull();
    }

    @Test
    @DisplayName("a result answered with no live connection is kept and replayed by the next redelivery")
    void resultWithNoLiveConnectionIsReplayedOnRedelivery() {
        AtomicReference<CloudChannel> live = new AtomicReference<CloudChannel>(recording);
        PlayerLinkChannel reconnecting = new PlayerLinkChannel(live::get, ledger, () -> false, () -> 0L,
                timers.service(), Runnable::run);
        AgentMessageRouter reconnectingRouter = new AgentMessageRouter(capability -> true,
                capability -> "not available");
        reconnecting.register(reconnectingRouter);
        final CompletableFuture<GameAccountResult> running = new CompletableFuture<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        new CloudPlayerLinks(reconnecting).setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                calls.add(completion.completionId());
                return running;
            }
        });

        reconnectingRouter.dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID).toString(), recording);
        recording.setOpen(false);
        live.set(null);
        reconnecting.onChannelClosed();
        running.complete(GameAccountResult.CREATED);

        assertThat(results()).as("nothing can be sent while no connection is open").isEmpty();
        assertThat(ledger.receiptFor(COMPLETED)).as("the receipt outlives the connection").isNotNull();

        RecordingChannel afterReconnect = new RecordingChannel();
        live.set(afterReconnect);
        reconnectingRouter.dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID).toString(),
                afterReconnect);

        assertThat(calls).as("the recorded result is replayed, the handler is not asked again").hasSize(1);
        assertThat(PlayerLinkEndToEndTest.Frames.ofType(afterReconnect, "player.link.completed.result"))
                .as("the live connection gets the recorded result").hasSize(1);
    }

    @Test
    @DisplayName("completions and expiries reach the module only through the injected worker executor")
    void handlerRunsOnlyOnTheWorkerExecutor() {
        List<Runnable> queue = new ArrayList<>();
        PlayerLinkChannel queued = new PlayerLinkChannel(() -> recording, ledger, () -> false, () -> 0L,
                timers.service(), queue::add);
        AgentMessageRouter queuedRouter = new AgentMessageRouter(capability -> true, capability -> "not available");
        queued.register(queuedRouter);
        final List<String> calls = new CopyOnWriteArrayList<>();
        new CloudPlayerLinks(queued).setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                calls.add("completed");
                return CompletableFuture.completedFuture(GameAccountResult.CREATED);
            }

            @Override
            public void onExpired(LinkExpiry expiry) {
                calls.add("expired");
            }
        });

        queuedRouter.dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID).toString(), recording);
        queuedRouter.dispatch(ContractFixtures.object(
                "fixtures/agent/player.link.expired/valid/expired.json").toString(), recording);

        assertThat(calls).as("nothing runs on the socket thread").isEmpty();
        assertThat(results()).as("nothing is answered before the module ran").isEmpty();

        for (Runnable task : new ArrayList<>(queue)) {
            task.run();
        }

        assertThat(calls).as("the handler and the expiry ran, in arrival order").containsExactly("completed", "expired");
        assertThat(results()).as("the completion is answered once the handler ran").hasSize(1);
    }

    @Test
    @DisplayName("a handler that does not answer in time sends nothing and is asked again on redelivery")
    void handlerTimeoutSendsNothing() {
        List<String> calls = new CopyOnWriteArrayList<>();
        links.setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                calls.add(completion.completionId());
                return new CompletableFuture<>();
            }
        });

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        timers.runAll(PlayerLinkChannel.HANDLER_DEADLINE_MS);

        assertThat(results()).isEmpty();
        assertThat(ledger.receiptFor(COMPLETED)).isNull();

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(calls).as("the gateway redelivers, so the handler is asked again").hasSize(2);
    }

    @Test
    @DisplayName("a handler that fails sends nothing and is asked again on redelivery")
    void handlerFailureSendsNothing() {
        List<String> calls = new CopyOnWriteArrayList<>();
        links.setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                calls.add(completion.completionId());
                CompletableFuture<GameAccountResult> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("synthetic failure"));
                return failed;
            }
        });

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(results()).isEmpty();
        assertThat(ledger.receiptFor(COMPLETED)).isNull();

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(calls).hasSize(2);
    }

    @Test
    @DisplayName("with no handler, provisioning is refused and a non-provisioning completion is acknowledged")
    void noHandlerAnswersByProvisioning() {
        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        JsonObject refused = results().get(0);
        assertThat(refused.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("auth_plugin_unavailable");

        dispatch(inGameFrame(NO_PROVISION, PlayerLinkEndToEndTest.CREATE_ID));
        JsonObject answered = results().get(1);
        assertThat(answered.getAsJsonObject("payload").getAsJsonObject("data").get("gameAccount").getAsString())
                .isEqualTo("not_requested");

        assertThat(ledger.receiptFor(COMPLETED)).isNotNull();
        assertThat(ledger.receiptFor(NO_PROVISION)).isNotNull();

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(results()).as("the recorded refusal is replayed").hasSize(3);
        assertThat(results().get(2).getAsJsonObject("payload")).isEqualTo(refused.getAsJsonObject("payload"));
    }

    @Test
    @DisplayName("ACCOUNT_EXISTS and AUTH_PLUGIN_UNAVAILABLE become their contract error codes")
    void handlerErrorsMapToCodes() {
        links.setHandler(plugin, new FixedHandler(new AtomicReference<LinkCompletion>(), GameAccountResult.ACCOUNT_EXISTS));
        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(results().get(0).getAsJsonObject("payload").get("code").getAsString()).isEqualTo("account_exists");

        links.setHandler(plugin, new FixedHandler(new AtomicReference<LinkCompletion>(),
                GameAccountResult.AUTH_PLUGIN_UNAVAILABLE));
        dispatch(completedFrame(NO_PROVISION, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(results().get(1).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("auth_plugin_unavailable");
    }

    @Test
    @DisplayName("REGISTRATION_REJECTED is the frozen fixture's code and payload shape; unknown codes are refused")
    void registrationRejectedMatchesTheFrozenFixture() {
        links.setHandler(plugin, new FixedHandler(new AtomicReference<LinkCompletion>(),
                GameAccountResult.REGISTRATION_REJECTED));

        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));

        JsonObject sent = results().get(0);
        JsonObject fixture = ContractFixtures.object(
                "fixtures/agent/player.link.completed.result/valid/registration-rejected.json");
        JsonObject sentPayload = sent.getAsJsonObject("payload");
        JsonObject expectedPayload = fixture.getAsJsonObject("payload");
        assertThat(sent.get("type").getAsString()).isEqualTo(fixture.get("type").getAsString());
        assertThat(sent.get("replyTo").getAsString()).isEqualTo(COMPLETED);
        assertThat(sentPayload.keySet()).isEqualTo(expectedPayload.keySet());
        assertThat(sentPayload.get("status").getAsString()).isEqualTo(expectedPayload.get("status").getAsString());
        assertThat(sentPayload.get("code").getAsString()).isEqualTo(expectedPayload.get("code").getAsString());
        assertThat(sentPayload.get("message").getAsString()).isNotEmpty();

        assertThat(ContractSchemas.isValid(ContractFixtures.object(
                "fixtures/agent/player.link.completed.result/invalid/unknown-code.json").getAsJsonObject("frame")))
                .as("a code outside the enum can never validate").isFalse();
    }

    @Test
    @DisplayName("a cancelled expiry reaches the handler once and its envelope id is persisted first")
    void cancelledExpiryReachesTheHandlerOnce() {
        final AtomicReference<LinkExpiry> seen = new AtomicReference<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        links.setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                return CompletableFuture.completedFuture(GameAccountResult.NOT_REQUESTED);
            }

            @Override
            public void onExpired(LinkExpiry expiry) {
                calls.add(expiry.linkId());
                seen.set(expiry);
            }
        });

        JsonObject frame = ContractFixtures.object("fixtures/agent/player.link.expired/valid/cancelled.json");
        dispatch(frame);
        dispatch(frame);

        assertThat(calls).as("one delivery per envelope id").hasSize(1);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().reason()).isEqualTo(ExpiryReason.CANCELLED);
        assertThat(seen.get().linkId()).isEqualTo(PlayerLinkEndToEndTest.CREATE_ID);
        assertThat(seen.get().playerUuid()).isEqualTo(PlayerLinkEndToEndTest.STEVE);
        assertThat(seen.get().playerName()).isEqualTo("Steve");
        assertThat(recording.frames()).as("an expiry is never answered").isEmpty();
        assertThat(ledger.idsAtPersist).as("the expiry id is persisted before it is delivered").hasSize(1);
        assertThat(ledger.idsAtPersist.get(0)).as("the durable dedupe set holds the expiry id")
                .containsExactly(CANCELLED_EXPIRY_ID);
    }

    @Test
    @DisplayName("a completion that arrives before its created answer is held and then delivered")
    void completionBeforeCreatedIsHeld() throws Exception {
        CompletableFuture<LinkCreation> future = links.create(plugin, PlayerLinkEndToEndTest.STEVE, "Steve",
                GameAuth.none(), EnumSet.of(LinkProof.IN_GAME_SESSION));
        String createId = PlayerLinkEndToEndTest.Frames.last(recording, "player.link.create").get("id").getAsString();
        AtomicReference<LinkCompletion> received = new AtomicReference<>();
        links.setHandler(plugin, new FixedHandler(received, GameAccountResult.CREATED));

        dispatch(completedFrame(HELD, createId));
        assertThat(received.get()).as("held until the create is answered").isNull();
        assertThat(future.isDone()).isFalse();

        dispatch(PlayerLinkEndToEndTest.Frames.fixture("fixtures/agent/player.link.created/valid/url.json", createId));

        assertThat(future.get(1, TimeUnit.SECONDS).url()).isNotEmpty();
        assertThat(received.get()).isNotNull();
        assertThat(received.get().linkId()).isEqualTo(createId);
        assertThat(results()).hasSize(1);
    }

    @Test
    @DisplayName("create failures map rate_limited, link_not_permitted, an error reply and a deadline")
    void createFailuresMapToFailures() throws Exception {
        assertThat(failureOf("fixtures/agent/player.link.created/valid/not-permitted.json"))
                .isEqualTo(Failure.LINK_NOT_PERMITTED);
        assertThat(failureOfRateLimited()).isEqualTo(Failure.RATE_LIMITED);
    }

    @Test
    @DisplayName("a correlated error reply completes the create as INTERNAL_ERROR with the payload message")
    void errorReplyCompletesTheCreate() throws Exception {
        CompletableFuture<LinkCreation> future = startCreate();
        String createId = PlayerLinkEndToEndTest.Frames.last(recording, "player.link.create").get("id").getAsString();
        JsonObject error = new JsonObject();
        error.addProperty("v", 2);
        error.addProperty("id", "01JB2Z8Y3K4M5N6P7Q8R9S0ERR");
        error.addProperty("type", "error");
        error.addProperty("ts", 1761274800000L);
        error.addProperty("replyTo", createId);
        JsonObject payload = new JsonObject();
        payload.addProperty("code", "internal_error");
        payload.addProperty("message", "the gateway could not issue a link");
        error.add("payload", payload);

        dispatch(error);

        LinkCreation creation = future.get(1, TimeUnit.SECONDS);
        assertThat(creation.failure()).isEqualTo(Failure.INTERNAL_ERROR);
        assertThat(creation.message()).isEqualTo("the gateway could not issue a link");
    }

    @Test
    @DisplayName("a create with no answer times out after the deadline")
    void createDeadlineTimesOut() throws Exception {
        CompletableFuture<LinkCreation> future = startCreate();

        timers.runAll(PlayerLinkChannel.REQUEST_DEADLINE_MS);

        assertThat(future.get(1, TimeUnit.SECONDS).failure()).isEqualTo(Failure.TIMED_OUT);
    }

    @Test
    @DisplayName("closing the connection completes every pending create as NOT_CONNECTED")
    void channelCloseFailsPendingCreates() throws Exception {
        CompletableFuture<LinkCreation> future = startCreate();

        channel.onChannelClosed();

        assertThat(future.get(1, TimeUnit.SECONDS).failure()).isEqualTo(Failure.NOT_CONNECTED);
    }

    @Test
    @DisplayName("the handler has exactly one owner and removal falls back to the no-handler answer")
    void handlerOwnershipIsSingle() {
        AtomicReference<LinkCompletion> owned = new AtomicReference<>();
        links.setHandler(plugin, new FixedHandler(owned, GameAccountResult.CREATED));
        assertThatThrownBy(() -> links.setHandler(other,
                new FixedHandler(new AtomicReference<LinkCompletion>(), GameAccountResult.NOT_REQUESTED)))
                .isInstanceOf(IllegalStateException.class);

        links.removeHandler(other);
        dispatch(completedFrame(COMPLETED, PlayerLinkEndToEndTest.CREATE_ID));
        assertThat(owned.get()).as("a different owner's removal must not take the handler away").isNotNull();

        links.removeHandler(plugin);
        dispatch(inGameFrame(NO_PROVISION, PlayerLinkEndToEndTest.CREATE_ID));
        JsonObject payload = PlayerLinkEndToEndTest.Frames.last(recording, "player.link.completed.result")
                .getAsJsonObject("payload");
        assertThat(payload.getAsJsonObject("data").get("gameAccount").getAsString()).isEqualTo("not_requested");
    }

    @Test
    @DisplayName("every invalid link fixture is refused as invalid_message and never reaches the handler")
    void invalidFixturesAreRefused() {
        List<String> calls = new CopyOnWriteArrayList<>();
        links.setHandler(plugin, new Handler() {
            @Override
            public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
                calls.add(completion.completionId());
                return CompletableFuture.completedFuture(GameAccountResult.CREATED);
            }
        });

        List<String> fixtures = new ArrayList<>();
        fixtures.add("fixtures/agent/player.link.created/invalid/http-url.json");
        fixtures.add("fixtures/agent/player.link.created/invalid/code-in-query.json");
        fixtures.add("fixtures/agent/player.link.created/invalid/missing-replyTo.json");
        fixtures.add("fixtures/agent/player.link.completed/invalid/with-password.json");
        fixtures.add("fixtures/agent/player.link.completed/invalid/web-registration-missing-username.json");
        fixtures.add("fixtures/agent/player.link.completed/invalid/web-registration-without-provision.json");
        fixtures.add("fixtures/agent/player.link.completed/invalid/missing-replyTo.json");
        fixtures.add("fixtures/agent/player.link.expired/invalid/timeout-reason.json");
        fixtures.add("fixtures/agent/player.link.expired/invalid/missing-replyTo.json");

        for (String path : fixtures) {
            recording.clear();
            dispatch(ContractFixtures.object(path).getAsJsonObject("frame"));
            List<JsonObject> errors = PlayerLinkEndToEndTest.Frames.ofType(recording, "error");
            assertThat(errors).as(path).isNotEmpty();
            assertThat(errors.get(0).getAsJsonObject("payload").get("code").getAsString())
                    .as(path).isEqualTo("invalid_message");
        }
        assertThat(calls).isEmpty();
    }

    private CompletableFuture<LinkCreation> startCreate() {
        return links.create(plugin, PlayerLinkEndToEndTest.STEVE, "Steve", GameAuth.none(),
                EnumSet.of(LinkProof.IN_GAME_SESSION));
    }

    private Failure failureOf(String createdFixture) throws Exception {
        CompletableFuture<LinkCreation> future = startCreate();
        String createId = PlayerLinkEndToEndTest.Frames.last(recording, "player.link.create").get("id").getAsString();
        dispatch(PlayerLinkEndToEndTest.Frames.fixture(createdFixture, createId));
        return future.get(1, TimeUnit.SECONDS).failure();
    }

    private Failure failureOfRateLimited() throws Exception {
        CompletableFuture<LinkCreation> future = startCreate();
        String createId = PlayerLinkEndToEndTest.Frames.last(recording, "player.link.create").get("id").getAsString();
        JsonObject frame = PlayerLinkEndToEndTest.Frames.fixture(
                "fixtures/agent/player.link.created/valid/not-permitted.json", createId);
        JsonObject payload = frame.getAsJsonObject("payload");
        payload.addProperty("code", "rate_limited");
        payload.addProperty("message", "slow down");
        dispatch(frame);
        return future.get(1, TimeUnit.SECONDS).failure();
    }

    /** A handler that must be overridden on both methods by its subclass. */
    private abstract static class Handler implements CloudPlayerLinks.LinkEventHandler {
    }

    private static final class FixedHandler implements CloudPlayerLinks.LinkEventHandler {
        private final AtomicReference<LinkCompletion> received;
        private final GameAccountResult result;

        private FixedHandler(AtomicReference<LinkCompletion> received, GameAccountResult result) {
            this.received = received;
            this.result = result;
        }

        @Override
        public CompletableFuture<GameAccountResult> onCompleted(LinkCompletion completion) {
            received.set(completion);
            return CompletableFuture.completedFuture(result);
        }
    }
}
