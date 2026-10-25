package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end contract for D-25: a {@code module.request} fixture routed through the real
 * {@link AgentMessageRouter} reaches the responder registered under {@code <module>.<action>} on the
 * unchanged 6.3.0 {@link PanelResponderRegistry} and is answered with a schema-valid
 * {@code module.response} carrying the request id in {@code replyTo}.
 */
class ModuleRequestEndToEndTest {
    private static final String ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";
    private static final String TYPE = "ultimail.inbox.list";
    private static final String OWNER = "UltiMail";
    private static final String FIXTURE = "fixtures/agent/module.request/valid/ultimail.json";

    private final PanelResponderRegistry registry = new PanelResponderRegistry();

    @AfterEach
    void shutdownRegistry() {
        registry.shutdown();
    }

    @Test
    void validRequestReachesTheOwningResponderAndRepliesOk() throws InterruptedException {
        AtomicReference<JsonObject> seen = new AtomicReference<>();
        registry.registerResponder(TYPE, data -> {
            seen.set(data.deepCopy());
            JsonObject result = new JsonObject();
            result.addProperty("items", 3);
            return CompletableFuture.completedFuture(result);
        }, OWNER);

        RecordingChannel channel = dispatchFixture(FIXTURE);

        JsonObject frame = awaitReply(channel, 5);
        assertThat(frame.get("type").getAsString()).isEqualTo("module.response");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(ID);
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").get("items").getAsInt()).isEqualTo(3);

        JsonObject requestPayload = fixturePayload();
        JsonObject data = seen.get();
        JsonObject params = requestPayload.getAsJsonObject("params");
        assertThat(data.get("page")).isEqualTo(params.get("page"));
        assertThat(data.getAsJsonObject("filter")).isEqualTo(params.getAsJsonObject("filter"));
        assertThat(data.getAsJsonObject("actor")).isEqualTo(requestPayload.getAsJsonObject("actor"));
        assertThat(data.getAsJsonObject("actor").has("minecraftUuid")).isTrue();
        assertThat(data.getAsJsonObject("actor").get("minecraftUuid").isJsonNull()).isTrue();
        assertThat(data.keySet()).containsExactlyInAnyOrder("page", "filter", "actor");
    }

    @Test
    void requestWithoutResponderRepliesModuleNotFound() throws InterruptedException {
        RecordingChannel channel = dispatchFixture(FIXTURE);

        assertError(channel, "module_not_found");
    }

    @Test
    void requestForAnotherModulesResponderRepliesModuleNotFound() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        registry.registerResponder(TYPE, data -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new JsonObject());
        }, "UltiMenu");

        RecordingChannel channel = dispatchFixture(FIXTURE);

        assertError(channel, "module_not_found");
        assertThat(calls).hasValue(0);
    }

    @Test
    void slowResponderRepliesDeadlineExceeded() throws InterruptedException {
        registry.registerResponder(TYPE, data -> new CompletableFuture<>(), OWNER);

        RecordingChannel channel = dispatchFixture(FIXTURE);

        assertErrorIn(awaitReply(channel, 10), "deadline_exceeded");
    }

    @Test
    void throwingResponderRepliesModuleErrorWithTheRootCauseMessage() throws InterruptedException {
        IllegalStateException root = new IllegalStateException("Inbox storage is offline");
        registry.registerResponder(TYPE, data -> {
            throw new IllegalStateException("Responder could not read the inbox", root);
        }, OWNER);

        RecordingChannel channel = dispatchFixture(FIXTURE);

        assertError(channel, "module_error");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").get("message").getAsString())
                .isEqualTo("Inbox storage is offline");
    }

    @Test
    void exceptionallyCompletedResponderRepliesModuleError() throws InterruptedException {
        registry.registerResponder(TYPE, data -> {
            CompletableFuture<JsonObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Mailbox unavailable"));
            return failed;
        }, OWNER);

        RecordingChannel channel = dispatchFixture(FIXTURE);

        assertError(channel, "module_error");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").get("message").getAsString())
                .isEqualTo("Mailbox unavailable");
    }

    /**
     * Documents an intentional deviation: the 6.3.0 extension point is typed
     * {@code Function<JsonObject, CompletableFuture<JsonObject>>} and always treats a null result as
     * a failure, so the contract's {@code ok} + {@code data: null} shape cannot be produced through
     * it. Reaching that shape needs the registry's result type widened, which is a public API change
     * this plan does not carry.
     */
    @Test
    void nullResponderResultIsModuleErrorBecauseTheRegistryRejectsNullResults() throws InterruptedException {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(null), OWNER);

        RecordingChannel channel = dispatchFixture(FIXTURE);

        assertError(channel, "module_error");
    }

    @Test
    void everyInvalidModuleRequestFixtureRepliesInvalidMessage() throws InterruptedException {
        for (ContractFixtures.Fixture fixture : ContractFixtures.invalidFrames("module.request")) {
            RecordingChannel channel = dispatchFrame(fixture.frame());
            JsonObject frame = awaitReply(channel, 5);
            assertThat(channel.frames()).as(fixture.path()).hasSize(1);
            assertThat(frame.getAsJsonObject("payload").get("code").getAsString())
                    .as(fixture.path()).isEqualTo("invalid_message");
            assertThat(channel.frames().get(0).get("replyTo").getAsString()).isEqualTo(ID);
        }
    }

    /**
     * The actor is the gateway's assertion about who asked. A value the contract's actor object
     * forbids must be refused before any module code runs, exactly like the other invalid fixtures.
     */
    @Test
    void actorValuesTheContractForbidsReplyInvalidMessage() throws InterruptedException {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(new JsonObject()), OWNER);

        assertInvalidActor(actor(0L, null));
        assertInvalidActor(actor(9007199254740992L, null));
        assertInvalidActor(actor(42L, "069A79F4-44E9-4726-A5BE-FCA90E38AAF5"));
        assertInvalidActor(actor(42L, "069a79f444e94726a5befca90e38aaf5"));
        JsonObject extra = actor(42L, null);
        extra.addProperty("extra", 1);
        assertInvalidActor(extra);
    }

    /**
     * The schema caps a response message at 500 characters. A responder's exception message is
     * module-written text and can be longer; without the cut the frame would be schema-invalid.
     */
    @Test
    void failureMessageIsCutToTheContractedLength() throws InterruptedException {
        StringBuilder longMessage = new StringBuilder();
        for (int index = 0; index < 600; index++) {
            longMessage.append('x');
        }
        String text = longMessage.toString();
        registry.registerResponder(TYPE, data -> {
            throw new IllegalStateException(text);
        }, OWNER);

        RecordingChannel channel = dispatchFixture(FIXTURE);

        assertError(channel, "module_error");
        String message = channel.frames().get(0).getAsJsonObject("payload").get("message").getAsString();
        assertThat(message).hasSize(500).isEqualTo(text.substring(0, 500));
    }

    private void assertInvalidActor(JsonObject actor) throws InterruptedException {
        JsonObject frame = ContractFixtures.object(FIXTURE);
        frame.getAsJsonObject("payload").add("actor", actor);

        RecordingChannel channel = dispatchFrame(frame);

        JsonObject reply = awaitReply(channel, 5);
        assertThat(channel.frames()).as(actor.toString()).hasSize(1);
        assertThat(reply.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
        assertThat(channel.frames().get(0).get("replyTo").getAsString()).isEqualTo(ID);
    }

    private static JsonObject actor(long userId, String minecraftUuid) {
        JsonObject actor = new JsonObject();
        actor.addProperty("userId", userId);
        if (minecraftUuid == null) {
            actor.add("minecraftUuid", com.google.gson.JsonNull.INSTANCE);
        } else {
            actor.addProperty("minecraftUuid", minecraftUuid);
        }
        return actor;
    }

    private RecordingChannel dispatchFixture(String path) {
        return dispatchFrame(ContractFixtures.object(path));
    }

    private RecordingChannel dispatchFrame(JsonObject frame) {
        AgentMessageRouter router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("module.request", new ModuleRequestHandler(registry));
        RecordingChannel channel = new RecordingChannel();
        router.dispatch(frame.toString(), channel);
        return channel;
    }

    private static JsonObject fixturePayload() {
        return ContractFixtures.object(FIXTURE).getAsJsonObject("payload");
    }

    private static JsonObject awaitReply(RecordingChannel channel, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            List<JsonObject> frames = channel.frames();
            if (!frames.isEmpty()) {
                return frames.get(0);
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("No module.response arrived within " + seconds + " seconds.");
    }

    private static void assertError(RecordingChannel channel, String code) throws InterruptedException {
        JsonObject frame = awaitReply(channel, 10);
        assertThat(channel.frames()).hasSize(1);
        assertErrorIn(frame, code);
    }

    private static void assertErrorIn(JsonObject frame, String code) {
        assertErrorIn(frame, code, "module.response");
    }

    private static void assertErrorIn(JsonObject frame, String code, String description) {
        assertThat(frame.get("type").getAsString()).as(description).isEqualTo("module.response");
        assertThat(frame.get("replyTo").getAsString()).as(description).isEqualTo(ID);
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).as(description).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).as(description).isEqualTo(code);
    }
}
