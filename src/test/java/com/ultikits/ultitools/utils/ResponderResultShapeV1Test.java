package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * The v1 inbound path ({@link PluginInitiationUtils#handleInboundMessage}) relays every result
 * shape a responder may answer: an object's members are merged with {@code requestId} as before,
 * "no content" ({@code null} or {@link JsonNull}) is a success carrying only {@code requestId}, and
 * an array or primitive is carried under {@code result}.
 */
@DisplayName("v1 responder replies carry every result shape")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ResponderResultShapeV1Test {

    private static final String TYPE = "module_shape_query";
    private static final String REQUEST_ID = "req-shape";

    private PanelResponderRegistry registry;
    private UltiPanelWebSocketClient previousClient;
    private final List<JsonObject> sent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        CloudSession.resetForTesting();
        PluginInitiationUtils.startAcceptingPanelMessages();
        Logger logger = Logger.getLogger("UltiTools-ResultShapeV1");
        logger.setUseParentHandlers(false);
        registry = new PanelResponderRegistry();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(registry);
        });
        UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);
        lenient().when(client.isConnected()).thenReturn(true);
        lenient().when(client.getServerId()).thenReturn("shape-server");
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(client).sendMessage(any(JsonObject.class));
        previousClient = PluginInitiationUtils.setWebSocketClientForTesting(client);
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears UltiTools' instance field, as ResponderReplyRecheckTest does
    void tearDown() throws Exception {
        PluginInitiationUtils.startAcceptingPanelMessages();
        PluginInitiationUtils.setWebSocketClientForTesting(previousClient);
        registry.shutdown();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        CloudSession.resetForTesting();
    }

    private JsonObject replyData(JsonElement result) throws InterruptedException {
        registry.registerResponder(TYPE, data -> CompletableFuture.completedFuture(result), "module-shape");
        JsonObject data = new JsonObject();
        data.addProperty("requestId", REQUEST_ID);
        JsonObject message = new JsonObject();
        message.addProperty("type", TYPE);
        message.add("data", data);
        PluginInitiationUtils.handleInboundMessage(message);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sent.isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(sent).as("exactly one reply").hasSize(1);
        JsonObject reply = sent.get(0);
        assertThat(reply.get("type").getAsString()).isEqualTo(TYPE);
        JsonObject payload = reply.getAsJsonObject("data");
        assertThat(payload.get("requestId").getAsString()).isEqualTo(REQUEST_ID);
        return payload;
    }

    @Test
    void javaNullResultIsASuccessWithOnlyTheRequestId() throws Exception {
        JsonObject payload = replyData(null);

        assertThat(payload.keySet()).containsExactly("requestId");
    }

    @Test
    void jsonNullResultIsASuccessWithOnlyTheRequestId() throws Exception {
        JsonObject payload = replyData(JsonNull.INSTANCE);

        assertThat(payload.keySet()).containsExactly("requestId");
    }

    @Test
    void arrayResultIsCarriedUnderResult() throws Exception {
        JsonArray items = new JsonArray();
        items.add("a");
        items.add(1);

        JsonObject payload = replyData(items);

        assertThat(payload.keySet()).containsExactlyInAnyOrder("requestId", "result");
        assertThat(payload.get("result")).isEqualTo(items);
    }

    @Test
    void primitiveResultIsCarriedUnderResult() throws Exception {
        JsonObject payload = replyData(new JsonPrimitive("done"));

        assertThat(payload.keySet()).containsExactlyInAnyOrder("requestId", "result");
        assertThat(payload.get("result").getAsString()).isEqualTo("done");
    }

    @Test
    void objectResultMembersAreMergedAsBefore() throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("answer", 42);

        JsonObject payload = replyData(result);

        assertThat(payload.keySet()).containsExactlyInAnyOrder("requestId", "answer");
        assertThat(payload.get("answer").getAsInt()).isEqualTo(42);
        assertThat(result.has("requestId")).as("the responder's own object is not changed").isFalse();
    }
}
