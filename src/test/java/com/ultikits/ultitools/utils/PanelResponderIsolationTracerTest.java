package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.PanelMessageEvent;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Tracer for case 2d5bd984 (UltiTools-Reborn#677): a module responder whose call blocks (a slow
 * database or disk call) must not hold the thread that reads the panel connection. Drives the real
 * inbound entry point, {@link PluginInitiationUtils#handleInboundMessage}, with a real
 * {@link PanelResponderRegistry} built through its public constructor and a real {@link EventBus}.
 * <br>
 * 案例 2d5bd984 的示踪测试：阻塞的模块 responder 不得占住读取面板连接的线程。
 */
@DisplayName("#677 a blocking panel responder does not hold the connection thread")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PanelResponderIsolationTracerTest {

    private static final String TYPE_A = "module_a_slow_query";
    private static final String TYPE_B = "module_b_fast_query";
    /** Mirrors {@code PanelResponderRegistry.RESPONDER_TIMEOUT_MILLIS}; the timeout text below pins it. */
    private static final long DEADLINE_MILLIS = 3000L;
    private static final long BOUND_SECONDS = 10L;

    private ServerMock server;
    private Thread primaryThread;
    private EventBus eventBus;
    private PanelResponderRegistry registry;
    private UltiPanelWebSocketClient previousPanelWs;
    private CountDownLatch aStarted;
    private CountDownLatch aRelease;
    private CountDownLatch aReturning;
    private final BlockingQueue<JsonObject> sent = new LinkedBlockingQueue<>();
    private final List<PanelMessageEvent> published = new CopyOnWriteArrayList<>();
    private final List<Boolean> publishedOnPrimary = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        CloudSession.resetForTesting();
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
        }, "module-a");
        registry.registerResponder(TYPE_B, data -> {
            JsonObject result = new JsonObject();
            result.addProperty("from", "module-b");
            return CompletableFuture.completedFuture(result);
        }, "module-b");

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
            lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(registry);
        });

        UltiPanelWebSocketClient panelWs = mock(UltiPanelWebSocketClient.class);
        lenient().when(panelWs.isConnected()).thenReturn(true);
        lenient().when(panelWs.getServerId()).thenReturn("isolation-tracer-server");
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(panelWs).sendMessage(any(JsonObject.class));
        previousPanelWs = PluginInitiationUtils.setWebSocketClientForTesting(panelWs);
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears UltiTools' instance field, as ShutdownPanelMessageTracerTest does
    void tearDown() throws Exception {
        aRelease.countDown();
        PluginInitiationUtils.setWebSocketClientForTesting(previousPanelWs);
        registry.shutdown();
        eventBus.shutdown();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        MockBukkitHelper.safeUnmock();
        CloudSession.resetForTesting();
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

    private static JsonObject message(String type, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("requestId", requestId);
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.add("data", data);
        return message;
    }

    private static String requestIdOf(JsonObject reply) {
        return reply.getAsJsonObject("data").get("requestId").getAsString();
    }

    @Test
    @DisplayName("case 2d5bd984: the inbound call returns while module A's responder is still blocked")
    void blockingResponderDoesNotHoldTheConnectionThread() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> PluginInitiationUtils.handleInboundMessage(message(TYPE_A, "req-a")),
                "handleInboundMessage must return while module A's responder blocks inside its call");

        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS))
                .as("module A's responder was called")
                .isTrue();
        assertThat(aReturning.getCount()).as("module A's responder is still blocked").isEqualTo(1L);
    }

    @Test
    @DisplayName("while module A is blocked, module B's request is answered with B's requestId")
    void anotherModulesResponderIsServedWhileOneIsBlocked() throws Exception {
        PluginInitiationUtils.handleInboundMessage(message(TYPE_A, "req-a"));
        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's responder was called").isTrue();

        PluginInitiationUtils.handleInboundMessage(message(TYPE_B, "req-b"));

        JsonObject reply = sent.poll(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(reply).as("a reply was sent while module A is blocked").isNotNull();
        assertThat(reply.get("type").getAsString()).isEqualTo(TYPE_B);
        assertThat(requestIdOf(reply)).isEqualTo("req-b");
        assertThat(reply.getAsJsonObject("data").get("from").getAsString()).isEqualTo("module-b");
        assertThat(aReturning.getCount()).as("module A's responder is still blocked").isEqualTo(1L);
    }

    @Test
    @DisplayName("module A gets one deadline reply counted from admission and no second reply after it returns")
    void blockedResponderGetsOneDeadlineReplyCountedFromAdmission() throws Exception {
        long admittedNanos = System.nanoTime();
        PluginInitiationUtils.handleInboundMessage(message(TYPE_A, "req-a"));
        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's responder was called").isTrue();

        JsonObject reply = sent.poll(DEADLINE_MILLIS + 5000L, TimeUnit.MILLISECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - admittedNanos);

        assertThat(reply).as("the deadline reply for module A").isNotNull();
        assertThat(reply.get("type").getAsString()).isEqualTo(TYPE_A);
        assertThat(requestIdOf(reply)).isEqualTo("req-a");
        assertThat(reply.getAsJsonObject("data").get("error").getAsString())
                .contains(TYPE_A)
                .contains("module-a")
                .contains("did not complete within " + DEADLINE_MILLIS + "ms");
        assertThat(elapsedMillis)
                .as("the deadline counts from admission, not from the end of the blocked call")
                .isLessThanOrEqualTo(DEADLINE_MILLIS + 1500L);
        assertThat(aReturning.getCount()).as("module A was still blocked when its deadline reply went out")
                .isEqualTo(1L);

        aRelease.countDown();
        assertThat(aReturning.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's call returned").isTrue();
        assertThat(sent.poll(500, TimeUnit.MILLISECONDS))
                .as("no second reply for req-a once module A's late result arrives")
                .isNull();
    }

    @Test
    @DisplayName("the PanelMessageEvent for module A's type is still published once, on the primary thread")
    void panelMessageEventStillPublishedOnTheMainThread() throws Exception {
        PluginInitiationUtils.handleInboundMessage(message(TYPE_A, "req-a"));
        assertThat(aStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).as("module A's responder was called").isTrue();

        server.getScheduler().performOneTick();

        assertThat(published).hasSize(1);
        assertThat(published.get(0).getType()).isEqualTo(TYPE_A);
        assertThat(publishedOnPrimary).containsExactly(true);
        assertThat(aReturning.getCount()).as("module A's responder is still blocked").isEqualTo(1L);
    }
}
