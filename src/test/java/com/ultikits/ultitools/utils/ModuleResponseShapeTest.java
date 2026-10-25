package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code data} shape the frozen {@code module.response} contract allows reaches the wire
 * through the real {@link FramedChannel}: an object, an array, a primitive, and {@code null} for a
 * responder that answers "ok, no content". Each frame is parsed strictly, the way the gateway
 * parses it, and validated against the vendored schema.
 */
class ModuleResponseShapeTest {
    private static final String ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";
    private static final String TYPE = "ultimail.inbox.list";
    private static final String OWNER = "UltiMail";

    private final PanelResponderRegistry registry = new PanelResponderRegistry();

    @AfterEach
    void shutdownRegistry() {
        registry.shutdown();
    }

    @Test
    void futureCompletingWithNullRepliesOkWithDataNull() throws InterruptedException {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(null), OWNER);

        JsonObject payload = okPayload(handleThroughFramedChannel());

        assertThat(payload.has("data")).isTrue();
        assertThat(payload.get("data").isJsonNull()).isTrue();
    }

    @Test
    void futureCompletingWithJsonNullRepliesOkWithDataNull() throws InterruptedException {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(JsonNull.INSTANCE), OWNER);

        JsonObject payload = okPayload(handleThroughFramedChannel());

        assertThat(payload.get("data").isJsonNull()).isTrue();
    }

    @Test
    void arrayResultRepliesOkWithThatArray() throws InterruptedException {
        JsonArray items = new JsonArray();
        items.add("first");
        items.add(2);
        items.add(JsonNull.INSTANCE);
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(items), OWNER);

        JsonObject payload = okPayload(handleThroughFramedChannel());

        assertThat(payload.get("data")).isEqualTo(items);
    }

    @Test
    void primitiveResultsReplyOkWithThatPrimitive() throws InterruptedException {
        JsonPrimitive[] values = {new JsonPrimitive("done"), new JsonPrimitive(42), new JsonPrimitive(false)};
        for (JsonPrimitive value : values) {
            PanelResponderRegistry own = new PanelResponderRegistry();
            try {
                own.registerResponder(TYPE, data -> CompletableFuture.completedFuture(value), OWNER);

                JsonObject payload = okPayload(handleThroughFramedChannel(own));

                assertThat(payload.get("data")).as("data for %s", value).isEqualTo(value);
            } finally {
                own.shutdown();
            }
        }
    }

    @Test
    void objectResultIsUnchanged() throws InterruptedException {
        JsonObject result = new JsonObject();
        result.addProperty("items", 3);
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(result), OWNER);

        JsonObject payload = okPayload(handleThroughFramedChannel());

        assertThat(payload.get("data")).isEqualTo(result);
    }

    @Test
    void responderReturningNoFutureRepliesModuleError() throws InterruptedException {
        registry.registerResponder(TYPE, data -> null, OWNER);

        assertModuleError(handleThroughFramedChannel());
    }

    @Test
    void nonFiniteNumberInsideAnArrayStillRepliesModuleError() throws InterruptedException {
        JsonArray items = new JsonArray();
        items.add(Double.POSITIVE_INFINITY);
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(items), OWNER);

        assertModuleError(handleThroughFramedChannel());
    }

    @Test
    void nonFinitePrimitiveStillRepliesModuleError() throws InterruptedException {
        registry.registerResponder(TYPE,
                data -> CompletableFuture.completedFuture(new JsonPrimitive(Double.NaN)), OWNER);

        assertModuleError(handleThroughFramedChannel());
    }

    private JsonObject handleThroughFramedChannel() throws InterruptedException {
        return handleThroughFramedChannel(registry);
    }

    private static JsonObject handleThroughFramedChannel(PanelResponderRegistry target) throws InterruptedException {
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        FramedChannel channel = new FramedChannel(text -> {
            sent.add(text);
            latch.countDown();
            return true;
        }, () -> true, CloudLimits::defaults, Ulid::next, System::currentTimeMillis);

        new ModuleRequestHandler(target).handle(request(), channel);

        assertThat(latch.await(10, TimeUnit.SECONDS)).as("a module.response was sent").isTrue();
        assertThat(sent).hasSize(1);
        JsonObject frame = strictParse(sent.get(0));
        ContractSchemas.assertFrameValid(frame);
        assertThat(frame.get("type").getAsString()).isEqualTo("module.response");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(ID);
        return frame;
    }

    private static JsonObject okPayload(JsonObject frame) {
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        return payload;
    }

    private static void assertModuleError(JsonObject frame) {
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("module_error");
    }

    /** Parses {@code text} with Gson's strict reader, the way the gateway parses a frame. */
    private static JsonObject strictParse(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.getAsJsonObject();
        } catch (IOException notStrictJson) {
            throw new AssertionError("The frame is not strict JSON: " + notStrictJson.getMessage(), notStrictJson);
        }
    }

    private static CloudRequest request() {
        JsonObject payload = new JsonObject();
        payload.addProperty("module", "ultimail");
        payload.addProperty("action", "inbox.list");
        payload.add("params", new JsonObject());
        JsonObject actor = new JsonObject();
        actor.addProperty("userId", 42);
        actor.add("minecraftUuid", JsonNull.INSTANCE);
        payload.add("actor", actor);
        return new CloudRequest(ID, "module.request", 1761274800000L, null, payload);
    }
}
