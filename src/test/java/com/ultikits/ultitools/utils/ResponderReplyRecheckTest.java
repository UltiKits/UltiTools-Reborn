package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Case a0cca09d (UltiTools-Reborn#677): a module responder's result that completes after the
 * request was admitted must be sent only while UltiTools still accepts panel work, and only through
 * the still-connected client of the still-current session that received the request. Drives the
 * real inbound entry point, {@link PluginInitiationUtils#handleInboundMessage}, with a real
 * {@link PanelResponderRegistry}; the responder hands the test the future it returns so the test
 * decides when the result arrives.
 * <br>
 * 案例 a0cca09d：responder 的结果在请求被接收之后才完成时，只有在 UltiTools 仍接收面板消息、且接收该请求的
 * 会话与连接仍然有效时才发送回复。
 */
@DisplayName("#677 a responder reply rechecks acceptance, session and connection before sending")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ResponderReplyRecheckTest {

    private static final String TYPE = "module_recheck_query";
    private static final String REQUEST_ID = "req-recheck";
    private static final String SERVER_ID_1 = "recheck-server-1";
    private static final String SERVER_ID_2 = "recheck-server-2";
    private static final String DISABLING_DROP = "dropped: UltiTools is disabling";
    private static final String CONNECTION_DROP = "has closed or been replaced";
    private static final long BOUND_SECONDS = 10L;

    private PanelResponderRegistry registry;
    private UltiPanelWebSocketClient client1;
    private UltiPanelWebSocketClient client2;
    private UltiPanelWebSocketClient previousClient;
    private final BlockingQueue<CompletableFuture<JsonObject>> handedOut = new LinkedBlockingQueue<>();
    private final List<JsonObject> sentOnClient1 = new CopyOnWriteArrayList<>();
    private final List<JsonObject> sentOnClient2 = new CopyOnWriteArrayList<>();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        CloudSession.resetForTesting();
        PluginInitiationUtils.startAcceptingPanelMessages();

        Logger logger = Logger.getLogger("UltiTools-ReplyRecheck-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        });

        registry = new PanelResponderRegistry();
        registry.registerResponder(TYPE, data -> {
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            handedOut.add(future);
            return future;
        }, "module-recheck");

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(registry);
        });

        client1 = connectedClient(SERVER_ID_1, sentOnClient1);
        client2 = connectedClient(SERVER_ID_2, sentOnClient2);
        previousClient = PluginInitiationUtils.setWebSocketClientForTesting(client1);
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears UltiTools' instance field, as ShutdownPanelMessageTracerTest does
    void tearDown() throws Exception {
        for (CompletableFuture<JsonObject> future : handedOut) {
            future.cancel(false);
        }
        PluginInitiationUtils.startAcceptingPanelMessages();
        PluginInitiationUtils.setWebSocketClientForTesting(previousClient);
        registry.shutdown();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        CloudSession.resetForTesting();
    }

    private static UltiPanelWebSocketClient connectedClient(String serverId, List<JsonObject> sent) {
        UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);
        lenient().when(client.isConnected()).thenReturn(true);
        lenient().when(client.getServerId()).thenReturn(serverId);
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(client).sendMessage(any(JsonObject.class));
        return client;
    }

    private static JsonObject request(String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("requestId", requestId);
        JsonObject message = new JsonObject();
        message.addProperty("type", TYPE);
        message.add("data", data);
        return message;
    }

    private static JsonObject result() {
        JsonObject result = new JsonObject();
        result.addProperty("answer", 42);
        return result;
    }

    /**
     * Admits the request through the real inbound entry point, takes the future the responder
     * returned, and clears the records: {@code handleInboundMessage} itself logs two FINE lines
     * naming the type while admitting, which are not reply-drop lines and must not be counted.
     */
    private CompletableFuture<JsonObject> admit() throws InterruptedException {
        return admit(REQUEST_ID);
    }

    private CompletableFuture<JsonObject> admit(String requestId) throws InterruptedException {
        PluginInitiationUtils.handleInboundMessage(request(requestId));
        CompletableFuture<JsonObject> future = handedOut.poll(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(future).as("the responder was called and handed out its future").isNotNull();
        records.clear();
        return future;
    }

    private List<LogRecord> replyDropLines(String wording) {
        return records.stream()
                .filter(r -> Level.FINE.equals(r.getLevel()))
                .filter(r -> r.getMessage() != null && r.getMessage().contains(wording)
                        && r.getMessage().contains(TYPE))
                .collect(Collectors.toList());
    }

    private List<String> linesAtOrAbove(Level level) {
        return records.stream()
                .filter(r -> r.getLevel().intValue() >= level.intValue())
                .map(r -> r.getMessage() + " | thrown: " + r.getThrown())
                .collect(Collectors.toList());
    }

    /**
     * Bounded wait for the completion callback to have run: either a reply-drop line with
     * {@code wording} was logged or some client was sent something. Never a sleep.
     */
    private void awaitCompletionOutcome(String wording) throws InterruptedException {
        BooleanSupplier done = () -> !replyDropLines(wording).isEmpty()
                || !sentOnClient1.isEmpty() || !sentOnClient2.isEmpty();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(done.getAsBoolean())
                .as("the completion callback ran: a reply-drop line or a sent reply within %ss", BOUND_SECONDS)
                .isTrue();
    }

    @Test
    @DisplayName("case a0cca09d: a result completing after disable starts is not sent; one FINE line, no WARNING")
    void replyCompletingAfterDisableIsNotSent() throws Exception {
        CompletableFuture<JsonObject> future = admit();

        PluginInitiationUtils.stopAcceptingPanelMessages();
        future.complete(result());
        awaitCompletionOutcome(DISABLING_DROP);

        assertThat(sentOnClient1).as("no reply sent on the admitted client after disable started").isEmpty();
        assertThat(sentOnClient2).as("no reply sent on any other client").isEmpty();
        List<LogRecord> drops = replyDropLines(DISABLING_DROP);
        assertThat(drops).as("one reply-drop FINE line naming the type").hasSize(1);
        assertThat(drops.get(0).getMessage()).contains("responder reply");
        assertThat(drops.get(0).getThrown()).isNull();
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines").isEmpty();
    }

    @Test
    @DisplayName("a result completing after logout is sent neither to the old client nor to the new session's client")
    void replyCompletingAfterLogoutIsNotSentToTheNewSession() throws Exception {
        CompletableFuture<JsonObject> future = admit();

        CloudSession.startNew();
        PluginInitiationUtils.setWebSocketClientForTesting(client2);
        future.complete(result());
        awaitCompletionOutcome(CONNECTION_DROP);

        assertThat(sentOnClient1).as("no reply on the logged-out session's client").isEmpty();
        assertThat(sentOnClient2).as("no reply on the new session's client").isEmpty();
        assertThat(replyDropLines(CONNECTION_DROP)).as("one reply-drop FINE line naming the type").hasSize(1);
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines").isEmpty();
    }

    @Test
    @DisplayName("a result completing while logout is invalidating the session, client still installed, is not sent")
    void replyCompletingWhileTheAdmittedSessionIsInvalidatedIsNotSent() throws Exception {
        CompletableFuture<JsonObject> future = admit();

        // invalidate() sets the flag first and clears the client last; this holds the moment
        // between the two, where only the session-currency clause can stop the reply.
        CloudSession.current().markInvalidatedForTesting();
        future.complete(result());
        awaitCompletionOutcome(CONNECTION_DROP);

        assertThat(sentOnClient1).as("no reply on the invalidated session's still-installed client").isEmpty();
        assertThat(sentOnClient2).as("no reply on any other client").isEmpty();
        assertThat(replyDropLines(CONNECTION_DROP)).as("one reply-drop FINE line naming the type").hasSize(1);
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines").isEmpty();
    }

    @Test
    @DisplayName("a result completing after the connection was replaced in the same session is not sent")
    void replyCompletingAfterTheConnectionWasReplacedIsNotSent() throws Exception {
        CompletableFuture<JsonObject> future = admit();

        // As reinitWebSocket does: a new client on the still-current session.
        PluginInitiationUtils.setWebSocketClientForTesting(client2);
        future.complete(result());
        awaitCompletionOutcome(CONNECTION_DROP);

        assertThat(sentOnClient1).as("no reply on the replaced client").isEmpty();
        assertThat(sentOnClient2).as("no reply on the replacing client, which never saw the request").isEmpty();
        assertThat(replyDropLines(CONNECTION_DROP)).as("one reply-drop FINE line naming the type").hasSize(1);
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines").isEmpty();
    }

    @Test
    @DisplayName("a result for an admitted connection that has closed is dropped without a WARNING")
    void replyToAClosedAdmittedConnectionIsDroppedWithoutWarning() throws Exception {
        CompletableFuture<JsonObject> future = admit();

        when(client1.isConnected()).thenReturn(false);
        future.complete(result());
        awaitCompletionOutcome(CONNECTION_DROP);

        assertThat(sentOnClient1).as("sendMessage never called on the closed admitted client").isEmpty();
        assertThat(sentOnClient2).as("no reply on any other client").isEmpty();
        List<LogRecord> drops = replyDropLines(CONNECTION_DROP);
        assertThat(drops).as("one reply-drop FINE line naming the type").hasSize(1);
        assertThat(drops.get(0).getThrown()).isNull();
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines").isEmpty();
    }

    @Test
    @DisplayName("control: on the admitted live client of the current session the reply is sent exactly once")
    void replyOnTheAdmittedLiveConnectionIsSentOnce() throws Exception {
        CompletableFuture<JsonObject> future = admit();

        future.complete(result());
        awaitCompletionOutcome(CONNECTION_DROP);

        assertThat(sentOnClient1).as("exactly one reply on the admitted client").hasSize(1);
        JsonObject reply = sentOnClient1.get(0);
        assertThat(reply.get("type").getAsString()).isEqualTo(TYPE);
        assertThat(reply.get("serverId").getAsString()).isEqualTo(SERVER_ID_1);
        JsonObject data = reply.getAsJsonObject("data");
        assertThat(data.get("requestId").getAsString()).isEqualTo(REQUEST_ID);
        assertThat(data.get("answer").getAsInt()).isEqualTo(42);
        assertThat(sentOnClient2).as("no reply on any other client").isEmpty();
        assertThat(replyDropLines(CONNECTION_DROP)).as("no reply-drop line").isEmpty();
        assertThat(replyDropLines(DISABLING_DROP)).as("no disabling drop line").isEmpty();
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines").isEmpty();
    }

    @Test
    @DisplayName("two in-flight requests answered from one shared cached result each reply with their own requestId; the cached object is untouched")
    void repliesFromOneSharedCachedResultKeepTheirOwnRequestIds() throws Exception {
        String firstId = "req-shared-1";
        String secondId = "req-shared-2";
        List<String> wireFrames = new CopyOnWriteArrayList<>();
        CountDownLatch firstSendEntered = new CountDownLatch(1);
        CountDownLatch secondSendEntered = new CountDownLatch(1);
        AtomicInteger sendCalls = new AtomicInteger();
        UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);
        lenient().when(client.isConnected()).thenReturn(true);
        lenient().when(client.getServerId()).thenReturn(SERVER_ID_1);
        doAnswer(invocation -> {
            JsonObject frame = invocation.getArgument(0);
            if (sendCalls.incrementAndGet() == 1) {
                // A slow socket write: the first reply is serialized only after the second reply has
                // been built and handed to the client, the interleaving two workers can produce.
                firstSendEntered.countDown();
                secondSendEntered.await(BOUND_SECONDS, TimeUnit.SECONDS);
            } else {
                secondSendEntered.countDown();
            }
            wireFrames.add(frame.toString());
            return null;
        }).when(client).sendMessage(any(JsonObject.class));
        PluginInitiationUtils.setWebSocketClientForTesting(client);

        CompletableFuture<JsonObject> first = admit(firstId);
        CompletableFuture<JsonObject> second = admit(secondId);
        JsonObject cached = result();
        JsonObject cachedAsBuilt = cached.deepCopy();

        // Each future completes on its own thread, so each reply is built on its own thread.
        Thread firstCompleter = new Thread(() -> first.complete(cached), "shared-result-completer-1");
        firstCompleter.start();
        assertThat(firstSendEntered.await(BOUND_SECONDS, TimeUnit.SECONDS))
                .as("the first reply reached the client").isTrue();
        Thread secondCompleter = new Thread(() -> second.complete(cached), "shared-result-completer-2");
        secondCompleter.start();
        firstCompleter.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
        secondCompleter.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));

        assertThat(wireFrames).as("two replies written").hasSize(2);
        List<String> repliedIds = wireFrames.stream()
                .map(frame -> JsonParser.parseString(frame).getAsJsonObject()
                        .getAsJsonObject("data").get("requestId").getAsString())
                .collect(Collectors.toList());
        assertThat(repliedIds).as("each reply carries its own request's requestId")
                .containsExactlyInAnyOrder(firstId, secondId);
        wireFrames.forEach(frame -> assertThat(JsonParser.parseString(frame).getAsJsonObject()
                .getAsJsonObject("data").get("answer").getAsInt()).isEqualTo(42));
        assertThat(cached).as("the responder's cached result is never changed by a reply")
                .isEqualTo(cachedAsBuilt);
        assertThat(linesAtOrAbove(Level.WARNING)).as("WARNING or SEVERE lines").isEmpty();
    }
}
