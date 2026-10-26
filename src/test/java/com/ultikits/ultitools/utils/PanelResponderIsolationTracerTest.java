package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.PanelMessageEvent;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * Tracer for case 2d5bd984 (UltiTools-Reborn#677): a module responder whose call blocks (a slow
 * database or disk call) must not hold the thread that reads the panel connection. Rewritten in plan
 * 22-23 onto the protocol v2 path (the v1 inbound dispatch it used to drive is removed): the real
 * {@link ModuleRequestHandler}, the handler the connection's router runs for {@code module.request}, with a
 * real {@link PanelResponderRegistry} built through its public constructor and a real {@link EventBus}.
 * Every assertion of the v1 tracer survives; the reply is now a {@code module.response} correlated by
 * {@code replyTo}, and the deadline reply is the contract's {@code deadline_exceeded}.
 * <br>
 * 案例 2d5bd984 的示踪测试：阻塞的模块 responder 不得占住读取面板连接的线程。
 */
@DisplayName("#677 a blocking panel responder does not hold the connection thread")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PanelResponderIsolationTracerTest {

    private static final String TYPE_A = "modulea.slow";
    private static final String TYPE_B = "moduleb.fast";
    private static final String ID_A = "01JB2Z8Y3K4M5N6P7Q8R9S0AAA";
    private static final String ID_B = "01JB2Z8Y3K4M5N6P7Q8R9S0BBB";
    /** Mirrors {@code PanelResponderRegistry.RESPONDER_TIMEOUT_MILLIS}. */
    private static final long DEADLINE_MILLIS = 3000L;
    private static final long BOUND_SECONDS = 10L;

    private ServerMock server;
    private Thread primaryThread;
    private EventBus eventBus;
    private PanelResponderRegistry registry;
    private ModuleRequestHandler handler;
    private CountDownLatch aStarted;
    private CountDownLatch aRelease;
    private CountDownLatch aReturning;
    private final RecordingChannel channel = new RecordingChannel();
    private final List<PanelMessageEvent> published = new CopyOnWriteArrayList<>();
    private final List<Boolean> publishedOnPrimary = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        PluginInitiationUtils.startAcceptingPanelMessages();
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        primaryThread = Thread.currentThread();

        aStarted = new CountDownLatch(1);
        aRelease = new CountDownLatch(1);
        aReturning = new CountDownLatch(1);

        Logger logger = Logger.getLogger("UltiTools-IsolationTracer-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        eventBus = new EventBus();
        eventBus.subscribe(PanelMessageEvent.class, event -> {
            published.add(event);
            publishedOnPrimary.add(server.isPrimaryThread() && isTheSetUpThread(Thread.currentThread()));
        });

        registry = new PanelResponderRegistry();
        registry.registerResponder(TYPE_A, data -> {
            aStarted.countDown();
            awaitQuietly(aRelease);
            aReturning.countDown();
            JsonObject result = new JsonObject();
            result.addProperty("from", "module-a");
            return CompletableFuture.completedFuture(result);
        }, "ModuleA");
        registry.registerResponder(TYPE_B, data -> {
            JsonObject result = new JsonObject();
            result.addProperty("from", "module-b");
            return CompletableFuture.completedFuture(result);
        }, "ModuleB");
        handler = new ModuleRequestHandler(registry);

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
            lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(registry);
        });
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears UltiTools' instance field, as other tests do
    void tearDown() throws Exception {
        aRelease.countDown();
        registry.shutdown();
        eventBus.shutdown();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        MockBukkitHelper.safeUnmock();
    }

    /** Whether {@code thread} is the very thread that ran {@link #setUp}, the server's primary thread. */
    // PMD.CompareObjectsWithEquals: thread identity is the point; Thread does not override equals().
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private boolean isTheSetUpThread(Thread thread) {
        return thread == primaryThread;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A {@code module.request} for {@code <module>.<action>} as the router hands it to the handler. */
    private static CloudRequest request(String id, String type) {
        int dot = type.indexOf('.');
        JsonObject payload = new JsonObject();
        payload.addProperty("module", type.substring(0, dot));
        payload.addProperty("action", type.substring(dot + 1));
        payload.add("params", new JsonObject());
        JsonObject actor = new JsonObject();
        actor.addProperty("userId", 42);
        actor.add("minecraftUuid", JsonNull.INSTANCE);
        payload.add("actor", actor);
        return new CloudRequest(id, "module.request", 1761274800000L, null, payload);
    }

    @Test
    @DisplayName("case 2d5bd984: the inbound call returns while module A's responder is still blocked")
    void blockingResponderDoesNotHoldTheConnectionThread() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> handler.handle(request(ID_A, TYPE_A), channel),
                "the module.request handler must return while module A's responder blocks inside its call");

        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS))
                .as("module A's responder was called")
                .isTrue();
        assertThat(aReturning.getCount()).as("module A's responder is still blocked").isEqualTo(1L);
    }

    @Test
    @DisplayName("while module A is blocked, module B's request is answered, correlated to B's request")
    void anotherModulesResponderIsServedWhileOneIsBlocked() throws Exception {
        handler.handle(request(ID_A, TYPE_A), channel);
        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's responder was called").isTrue();

        handler.handle(request(ID_B, TYPE_B), channel);

        JsonObject reply = channel.frames.poll(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(reply).as("a reply was sent while module A is blocked").isNotNull();
        assertThat(reply.get("type").getAsString()).isEqualTo("module.response");
        assertThat(reply.get("replyTo").getAsString()).isEqualTo(ID_B);
        assertThat(reply.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(reply.getAsJsonObject("payload").getAsJsonObject("data").get("from").getAsString())
                .isEqualTo("module-b");
        assertThat(aReturning.getCount()).as("module A's responder is still blocked").isEqualTo(1L);
    }

    @Test
    @DisplayName("module A gets one deadline reply counted from admission and no second reply after it returns")
    void blockedResponderGetsOneDeadlineReplyCountedFromAdmission() throws Exception {
        long admittedNanos = System.nanoTime();
        handler.handle(request(ID_A, TYPE_A), channel);
        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's responder was called").isTrue();

        JsonObject reply = channel.frames.poll(DEADLINE_MILLIS + 5000L, TimeUnit.MILLISECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - admittedNanos);

        assertThat(reply).as("the deadline reply for module A").isNotNull();
        assertThat(reply.get("type").getAsString()).isEqualTo("module.response");
        assertThat(reply.get("replyTo").getAsString()).isEqualTo(ID_A);
        assertThat(reply.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("error");
        assertThat(reply.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("deadline_exceeded");
        assertThat(elapsedMillis)
                .as("the deadline counts from admission, not from the end of the blocked call")
                .isLessThanOrEqualTo(DEADLINE_MILLIS + 1500L);
        assertThat(aReturning.getCount()).as("module A was still blocked when its deadline reply went out")
                .isEqualTo(1L);

        aRelease.countDown();
        assertThat(aReturning.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's call returned").isTrue();
        assertThat(channel.frames.poll(500, TimeUnit.MILLISECONDS))
                .as("no second reply for module A's request once its late result arrives")
                .isNull();
    }

    @Test
    @DisplayName("the PanelMessageEvent for module A's type is still published once, on the primary thread")
    void panelMessageEventStillPublishedOnTheMainThread() throws Exception {
        handler.handle(request(ID_A, TYPE_A), channel);
        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's responder was called").isTrue();

        long end = System.currentTimeMillis() + BOUND_SECONDS * 1000L;
        while (published.isEmpty() && System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            Thread.sleep(10L);
        }

        assertThat(published).hasSize(1);
        assertThat(published.get(0).getType()).isEqualTo(TYPE_A);
        assertThat(publishedOnPrimary).containsExactly(true);
        assertThat(aReturning.getCount()).as("module A's responder is still blocked").isEqualTo(1L);
    }

    /** Records every frame the handler answers with, as {@code {type, replyTo, payload}}. */
    private static final class RecordingChannel implements CloudChannel {
        private final BlockingQueue<JsonObject> frames = new LinkedBlockingQueue<>();

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
            JsonObject frame = new JsonObject();
            frame.addProperty("type", type);
            frame.addProperty("replyTo", replyTo);
            frame.add("payload", payload.deepCopy());
            frames.add(frame);
            return true;
        }

        @Override
        public boolean replyOk(String replyTo, String type, JsonElement data) {
            JsonObject payload = new JsonObject();
            payload.addProperty("status", "ok");
            payload.add("data", data == null ? JsonNull.INSTANCE : data.deepCopy());
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
            payload.addProperty("status", "error");
            payload.addProperty("code", code);
            payload.addProperty("message", message);
            return reply(replyTo, type, payload);
        }
    }
}
