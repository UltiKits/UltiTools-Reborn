package com.ultikits.ultitools.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.ultikits.ultitools.websocket.CloudChannel;
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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handler-level contract: the responder's input carries {@code params} verbatim, and a result larger
 * than the frame budget is answered with the channel's {@code payload_too_large} error rather than a
 * truncated success (22-08 framing behaviour).
 */
class ModuleRequestHandlerTest {
    private static final String ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";
    private static final String TYPE = "ultimail.inbox.list";
    private static final String OWNER = "UltiMail";

    private final PanelResponderRegistry registry = new PanelResponderRegistry();

    @AfterEach
    void shutdownRegistry() {
        registry.shutdown();
    }

    @Test
    void nestedParamsAndArraysPassThroughUnchanged() throws InterruptedException {
        JsonObject params = JsonParser.parseString("{\"nested\":{\"a\":[1,2,{\"b\":null}]},\"list\":[[1,2],[]]}")
                .getAsJsonObject();
        AtomicReference<JsonObject> seen = new AtomicReference<>();
        registry.registerResponder(TYPE, data -> {
            seen.set(data.deepCopy());
            return CompletableFuture.completedFuture(new JsonObject());
        }, OWNER);

        new ModuleRequestHandler(registry).handle(request(params), new BudgetChannel(Integer.MAX_VALUE));

        JsonObject data = await(() -> seen.get(), "the responder to run");
        assertThat(data.getAsJsonObject("nested")).isEqualTo(params.getAsJsonObject("nested"));
        assertThat(data.getAsJsonArray("list")).isEqualTo(params.getAsJsonArray("list"));
        assertThat(data.getAsJsonObject("actor").get("userId").getAsLong()).isEqualTo(42L);
    }

    @Test
    void resultLargerThanTheFrameBudgetBecomesPayloadTooLargeNotTruncated() throws InterruptedException {
        JsonObject result = new JsonObject();
        result.addProperty("text", repeated('x', 4000));
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(result), OWNER);
        BudgetChannel channel = new BudgetChannel(1000);

        new ModuleRequestHandler(registry).handle(request(new JsonObject()), channel);

        JsonObject frame = await(() -> channel.frames().isEmpty() ? null : channel.frames().get(0),
                "the oversized reply to be refused");
        assertThat(frame.get("type").getAsString()).isEqualTo("error");
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("payload_too_large");
        assertThat(channel.lastData()).isEqualTo(result);
    }

    @Test
    void shuttingDownAtTheHandOffAnswersInternalErrorAndNeverRunsTheResponder() throws InterruptedException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        registry.registerResponder(TYPE, data -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new JsonObject());
        }, OWNER);
        BudgetChannel channel = new BudgetChannel(Integer.MAX_VALUE);
        ShutdownGate.Signal refusing = new ShutdownGate.Signal() {
            @Override
            public boolean enter() {
                return false;
            }

            @Override
            public void exit() {
                throw new AssertionError("nothing was taken");
            }
        };

        new ModuleRequestHandler(registry, refusing).handle(request(new JsonObject()), channel);

        JsonObject frame = await(() -> channel.frames().isEmpty() ? null : channel.frames().get(0),
                "the shutting-down refusal");
        assertThat(frame.get("type").getAsString()).isEqualTo("module.response");
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("internal_error");
        assertThat(frame.getAsJsonObject("payload").get("message").getAsString()).isEqualTo(ShutdownGate.MESSAGE);
        assertThat(calls.get()).isZero();
    }

    @Test
    void thePermitIsReturnedOnceWhenTheResponderEnds() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        registry.registerResponder(TYPE, data -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(new JsonObject());
        }, OWNER);
        java.util.concurrent.atomic.AtomicInteger entered = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger exited = new java.util.concurrent.atomic.AtomicInteger();
        ShutdownGate.Signal counting = new ShutdownGate.Signal() {
            @Override
            public boolean enter() {
                entered.incrementAndGet();
                return true;
            }

            @Override
            public void exit() {
                exited.incrementAndGet();
            }
        };
        BudgetChannel channel = new BudgetChannel(Integer.MAX_VALUE);

        new ModuleRequestHandler(registry, counting).handle(request(new JsonObject()), channel);

        assertThat(entered.get()).isEqualTo(1);
        assertThat(exited.get()).as("held while the responder runs").isZero();
        release.countDown();
        await(() -> exited.get() == 1 ? Boolean.TRUE : null, "the permit to be returned");
        assertThat(exited.get()).isEqualTo(1);
    }

    @Test
    void shutDownRegistryRepliesModuleError() throws InterruptedException {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(new JsonObject()), OWNER);
        registry.shutdown();
        BudgetChannel channel = new BudgetChannel(Integer.MAX_VALUE);

        new ModuleRequestHandler(registry).handle(request(new JsonObject()), channel);

        JsonObject frame = await(() -> channel.frames().isEmpty() ? null : channel.frames().get(0),
                "the refusal of a stopped registry");
        assertThat(frame.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("error");
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("module_error");
    }

    /**
     * The actor the gateway asserts is appended after the request's params, so a module cannot make
     * the panel's own {@code params.actor} reach it as the actor (T-22-98, D-25).
     */
    @Test
    void paramsCannotOverrideTheGatewayActor() throws InterruptedException {
        JsonObject params = JsonParser.parseString(
                "{\"page\":2,\"actor\":{\"userId\":1,\"minecraftUuid\":null}}").getAsJsonObject();
        AtomicReference<JsonObject> seen = new AtomicReference<>();
        registry.registerResponder(TYPE, data -> {
            seen.set(data.deepCopy());
            return CompletableFuture.completedFuture(new JsonObject());
        }, OWNER);

        new ModuleRequestHandler(registry).handle(request(params), new BudgetChannel(Integer.MAX_VALUE));

        JsonObject data = await(() -> seen.get(), "the responder to run");
        assertThat(data.getAsJsonObject("actor").get("userId").getAsLong()).isEqualTo(42L);
        assertThat(data.getAsJsonObject("actor").get("minecraftUuid").isJsonNull()).isTrue();
        assertThat(data.get("page").getAsInt()).isEqualTo(2);
    }

    /**
     * Gson writes a {@code JsonElement} leniently, so a responder's NaN would reach the socket as
     * the bare token {@code NaN} — a frame no strict parser accepts. The gateway then drops it, the
     * panel waits for its deadline, and repeated such frames close the whole connection with 4429.
     */
    @Test
    void nonFiniteNumberInTheResultBecomesModuleErrorAndNotANonJsonFrame() throws InterruptedException {
        registry.registerResponder(TYPE, data -> {
            JsonObject result = new JsonObject();
            result.addProperty("avg", Double.NaN);
            return CompletableFuture.completedFuture(result);
        }, OWNER);
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        FramedChannel channel = new FramedChannel(text -> {
            sent.add(text);
            latch.countDown();
            return true;
        }, () -> true, CloudLimits::defaults, Ulid::next, System::currentTimeMillis);

        new ModuleRequestHandler(registry).handle(request(new JsonObject()), channel);

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sent).hasSize(1);
        JsonObject frame = strictParse(sent.get(0));
        assertThat(frame.get("type").getAsString()).isEqualTo("module.response");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(ID);
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("module_error");
    }

    /**
     * The registry settles a request's future exactly once, so a reply that cannot be built would be
     * lost and the panel would wait for its deadline; the completion callback answers
     * {@code internal_error} instead.
     */
    @Test
    void aChannelThatRefusesTheSuccessReplyStillGetsExactlyOneInternalError() throws InterruptedException {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(new JsonObject()), OWNER);
        RefusingSuccessChannel channel = new RefusingSuccessChannel();

        new ModuleRequestHandler(registry).handle(request(new JsonObject()), channel);

        JsonObject frame = await(() -> channel.firstFrame(), "the fallback error");
        assertThat(frame.get("type").getAsString()).isEqualTo("module.response");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(ID);
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("internal_error");
        assertThat(channel.frames()).hasSize(1);
    }

    /** Parses {@code text} with Gson's strict reader, the way the gateway parses a frame. */
    private static JsonObject strictParse(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException notStrictJson) {
            throw new AssertionError("The frame is not strict JSON: " + notStrictJson.getMessage(), notStrictJson);
        }
    }

    private static CloudRequest request(JsonObject params) {
        JsonObject payload = new JsonObject();
        payload.addProperty("module", "ultimail");
        payload.addProperty("action", "inbox.list");
        payload.add("params", params);
        JsonObject actor = new JsonObject();
        actor.addProperty("userId", 42);
        actor.add("minecraftUuid", com.google.gson.JsonNull.INSTANCE);
        payload.add("actor", actor);
        return new CloudRequest(ID, "module.request", 1761274800000L, null, payload);
    }

    private static String repeated(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
    }

    private static <T> T await(java.util.function.Supplier<T> supplier, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("Timed out waiting for " + what + ".");
    }

    /** Records replies and throws when a success reply is built — the one reply must survive it. */
    private static final class RefusingSuccessChannel implements CloudChannel {
        private final List<JsonObject> frames = new ArrayList<>();
        private long sequence;

        private synchronized JsonObject firstFrame() {
            return frames.isEmpty() ? null : frames.get(0);
        }

        private synchronized List<JsonObject> frames() {
            List<JsonObject> copy = new ArrayList<>();
            for (JsonObject frame : frames) {
                copy.add(frame.deepCopy());
            }
            return copy;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public CloudLimits limits() {
            return CloudLimits.defaults();
        }

        @Override
        public boolean send(String type, JsonObject payload) {
            return false;
        }

        @Override
        public boolean reply(String replyTo, String type, JsonObject payload) {
            return record(replyTo, type, payload);
        }

        @Override
        public boolean replyOk(String replyTo, String type, JsonElement data) {
            throw new IllegalStateException("The framed channel refused to build the reply");
        }

        @Override
        public boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull,
                String messageOrNull) {
            return false;
        }

        @Override
        public boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull) {
            JsonObject payload = new JsonObject();
            if (!"error".equals(type)) {
                payload.addProperty("status", "error");
            }
            payload.addProperty("code", code);
            payload.addProperty("message", message);
            return reply(replyTo, type, payload);
        }

        private synchronized boolean record(String replyTo, String type, JsonObject payload) {
            JsonObject frame = new JsonObject();
            frame.addProperty("v", 2);
            frame.addProperty("id", String.format(Locale.ROOT, "%026d", ++sequence));
            frame.addProperty("type", type);
            frame.addProperty("ts", 1000L);
            if (replyTo != null) {
                frame.addProperty("replyTo", replyTo);
            }
            frame.add("payload", payload);
            frames.add(frame.deepCopy());
            return true;
        }
    }

    /** Records frames and refuses a frame larger than its budget the way the framed channel does. */
    private static final class BudgetChannel implements CloudChannel {
        private final int budget;
        private final List<JsonObject> frames = new ArrayList<>();
        private JsonElement lastData;
        private long sequence;

        private BudgetChannel(int budget) {
            this.budget = budget;
        }

        private synchronized List<JsonObject> frames() {
            List<JsonObject> copy = new ArrayList<>();
            for (JsonObject frame : frames) {
                copy.add(frame.deepCopy());
            }
            return copy;
        }

        private synchronized JsonElement lastData() {
            return lastData;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public CloudLimits limits() {
            return CloudLimits.defaults();
        }

        @Override
        public boolean send(String type, JsonObject payload) {
            return record(null, type, payload);
        }

        @Override
        public boolean reply(String replyTo, String type, JsonObject payload) {
            return record(replyTo, type, payload);
        }

        @Override
        public boolean replyOk(String replyTo, String type, JsonElement data) {
            synchronized (this) {
                lastData = data.deepCopy();
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("status", "ok");
            payload.add("data", data);
            return reply(replyTo, type, payload);
        }

        @Override
        public boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull,
                String messageOrNull) {
            return false;
        }

        @Override
        public boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull) {
            JsonObject payload = new JsonObject();
            if (!"error".equals(type)) {
                payload.addProperty("status", "error");
            }
            payload.addProperty("code", code);
            payload.addProperty("message", message);
            return reply(replyTo, type, payload);
        }

        private synchronized boolean record(String replyTo, String type, JsonObject payload) {
            JsonObject frame = new JsonObject();
            frame.addProperty("v", 2);
            frame.addProperty("id", String.format(Locale.ROOT, "%026d", ++sequence));
            frame.addProperty("type", type);
            frame.addProperty("ts", 1000L);
            if (replyTo != null) {
                frame.addProperty("replyTo", replyTo);
            }
            frame.add("payload", payload);
            if (frame.toString().length() > budget) {
                if ("error".equals(type)) {
                    return false;
                }
                return replyError(replyTo, "error", "payload_too_large",
                        "The reply exceeds the frame limit.", null);
            }
            frames.add(frame.deepCopy());
            return true;
        }
    }
}
