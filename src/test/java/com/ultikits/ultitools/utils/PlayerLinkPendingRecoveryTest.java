package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlayerLinkPendingRecoveryTest {
    private static final String COMPLETION = "01JB2Z8Y3K4M5N6P7Q8R9S0CC1";
    private RecordingChannel recording;
    private PlayerLinkChannel transport;
    private CloudPlayerLinks links;
    private AgentMessageRouter router;
    private UltiToolsPlugin owner;
    private PlayerLinkEndToEndTest.ManualTimers timers;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        recording = new RecordingChannel();
        owner = mock(UltiToolsPlugin.class);
        timers = new PlayerLinkEndToEndTest.ManualTimers();
        transport = new PlayerLinkChannel(() -> recording, new PlayerLinkEndToEndTest.TestLedger(() -> 0),
                () -> false, () -> 0L, timers.service(), Runnable::run);
        links = new CloudPlayerLinks(transport);
        router = new AgentMessageRouter(capability -> true, capability -> "not available");
        transport.register(router);
        links.setHandler(owner, new CloudPlayerLinks.LinkEventHandler() {
            @Override
            public CompletableFuture<CloudPlayerLinks.GameAccountResult> onCompleted(
                    CloudPlayerLinks.LinkCompletion completion) {
                calls.add("completed");
                return CompletableFuture.completedFuture(CloudPlayerLinks.GameAccountResult.NOT_REQUESTED);
            }

            @Override
            public void onExpired(CloudPlayerLinks.LinkExpiry expiry) {
                calls.add("expired");
            }
        });
    }

    @Test
    void closingPendingCreateReleasesHeldCompletionForRedeliveryWithoutRestart() {
        CompletableFuture<CloudPlayerLinks.LinkCreation> creation = create();
        String id = createId();
        JsonObject completed = completed(id);
        dispatch(completed);
        assertThat(calls).isEmpty();

        transport.onChannelClosed();
        assertThat(creation.join().failure()).isEqualTo(CloudPlayerLinks.Failure.NOT_CONNECTED);
        dispatch(completed);

        assertThat(calls).containsExactly("completed");
        assertThat(results()).hasSize(1);
    }

    @Test
    void errorReplyReleasesHeldCompletionForRedeliveryWithoutRestart() {
        CompletableFuture<CloudPlayerLinks.LinkCreation> creation = create();
        String id = createId();
        JsonObject completed = completed(id);
        dispatch(completed);
        dispatch(error(id));
        assertThat(creation.join().failure()).isEqualTo(CloudPlayerLinks.Failure.INTERNAL_ERROR);

        dispatch(completed);

        assertThat(calls).containsExactly("completed");
        assertThat(results()).hasSize(1);
    }

    @Test
    void expiryWaitsUntilTheCreateAnswerHasReachedItsCaller() {
        CompletableFuture<CloudPlayerLinks.LinkCreation> creation = create();
        String id = createId();
        creation.thenAccept(answer -> calls.add("created"));
        JsonObject expiry = PlayerLinkEndToEndTest.Frames.fixture(
                "fixtures/agent/player.link.expired/valid/expired.json", id);
        dispatch(expiry);
        assertThat(calls).as("expiry may not overtake the create answer").isEmpty();

        dispatch(created(id));

        assertThat(calls).containsExactly("created", "expired");
    }

    @Test
    void completionArrivingInsideCreateCallbackWaitsUntilThatCallbackReturns() {
        CompletableFuture<CloudPlayerLinks.LinkCreation> creation = create();
        String id = createId();
        creation.thenAccept(answer -> {
            dispatch(completed(id));
            calls.add("created");
        });

        dispatch(created(id));

        assertThat(calls).containsExactly("created", "completed");
        assertThat(results()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "error", "deadline"})
    void failedCreateStillDeliversHeldExpiryAfterItsFailure(String terminal) {
        CompletableFuture<CloudPlayerLinks.LinkCreation> creation = create();
        String id = createId();
        creation.thenAccept(answer -> calls.add("settled"));
        dispatch(PlayerLinkEndToEndTest.Frames.fixture("fixtures/agent/player.link.expired/valid/expired.json", id));
        assertThat(calls).isEmpty();

        if ("close".equals(terminal)) {
            transport.onChannelClosed();
        } else if ("error".equals(terminal)) {
            dispatch(error(id));
        } else {
            timers.runAll(PlayerLinkChannel.REQUEST_DEADLINE_MS);
        }

        assertThat(creation.join().failure()).isNotNull();
        assertThat(calls).containsExactly("settled", "expired");
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "error", "deadline"})
    void cancellingTheCallerFutureCannotPreventTransportCleanup(String terminal) {
        CompletableFuture<CloudPlayerLinks.LinkCreation> creation = create();
        String id = createId();
        JsonObject completion = completed(id);
        dispatch(completion);
        creation.cancel(false);

        if ("close".equals(terminal)) {
            transport.onChannelClosed();
        } else if ("error".equals(terminal)) {
            dispatch(error(id));
        } else {
            timers.runAll(PlayerLinkChannel.REQUEST_DEADLINE_MS);
        }
        dispatch(completion);

        assertThat(creation).isCancelled();
        assertThat(calls).containsExactly("completed");
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void receiptLookupAndClaimCannotRaceASecondCompletedDelivery() throws Exception {
        CompletableFuture<Void> firstClaim = new CompletableFuture<>();
        CompletableFuture<Void> releaseClaim = new CompletableFuture<>();
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        Set<String> claims = new ConcurrentSkipListSet<String>() {
            @Override
            public boolean add(String value) {
                if (firstAttempt.compareAndSet(true, false)) {
                    firstClaim.complete(null);
                    releaseClaim.join();
                }
                return super.add(value);
            }
        };
        Field busy = PlayerLinkChannel.class.getDeclaredField("busyCompletions");
        busy.setAccessible(true);
        busy.set(transport, claims);
        String id = PlayerLinkEndToEndTest.CREATE_ID;
        FutureTask<Void> firstResult = new FutureTask<>(() -> dispatch(completed(id)), null);
        FutureTask<Void> secondResult = new FutureTask<>(() -> dispatch(completed(id)), null);
        Thread first = new Thread(firstResult, "link-first-claim");
        Thread second = new Thread(secondResult, "link-concurrent-redelivery");
        first.setDaemon(true);
        second.setDaemon(true);
        try {
            first.start();
            firstClaim.get(5, TimeUnit.SECONDS);
            second.start();
            // The old split check lets the second delivery finish while the first is paused.
            // An atomic check instead blocks it on the monitor owned by the first delivery.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (second.isAlive()) {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(second.getId());
                if (info != null && info.getLockOwnerId() == first.getId()) {
                    break;
                }
                assertThat(System.nanoTime()).as("redelivery must finish or wait on the ledger")
                        .isLessThan(deadline);
                Thread.yield();
            }
        } finally {
            releaseClaim.complete(null);
            first.join(5000);
            second.join(5000);
        }
        firstResult.get(5, TimeUnit.SECONDS);
        secondResult.get(5, TimeUnit.SECONDS);
        assertThat(calls).containsExactly("completed");
    }

    @Test
    void receiptReplayDoesNotCallTheHandlerAgain() {
        String id = PlayerLinkEndToEndTest.CREATE_ID;
        dispatch(completed(id));
        dispatch(completed(id));

        assertThat(calls).containsExactly("completed");
        assertThat(results()).hasSize(2);
    }

    private CompletableFuture<CloudPlayerLinks.LinkCreation> create() {
        return links.create(owner, PlayerLinkEndToEndTest.STEVE, "Steve", CloudPlayerLinks.GameAuth.none(),
                EnumSet.of(CloudPlayerLinks.LinkProof.IN_GAME_SESSION));
    }

    private String createId() {
        return PlayerLinkEndToEndTest.Frames.last(recording, "player.link.create").get("id").getAsString();
    }

    private List<JsonObject> results() {
        return PlayerLinkEndToEndTest.Frames.ofType(recording, "player.link.completed.result");
    }

    private static JsonObject completed(String id) {
        return PlayerLinkEndToEndTest.Frames.fixtureWithId(
                "fixtures/agent/player.link.completed/valid/in-game-session.json", COMPLETION, id);
    }

    private static JsonObject created(String id) {
        return PlayerLinkEndToEndTest.Frames.fixture("fixtures/agent/player.link.created/valid/url.json", id);
    }

    private static JsonObject error(String id) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/player.link.expired/valid/expired.json");
        frame.addProperty("type", "error");
        frame.addProperty("replyTo", id);
        JsonObject payload = new JsonObject();
        payload.addProperty("code", "internal_error");
        payload.addProperty("message", "The link could not be created.");
        frame.add("payload", payload);
        return frame;
    }

    private void dispatch(JsonObject frame) {
        router.dispatch(frame.toString(), recording);
    }
}
