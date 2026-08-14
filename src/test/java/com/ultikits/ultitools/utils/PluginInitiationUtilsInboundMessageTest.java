package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * {@link PluginInitiationUtils#handleInboundMessage(JsonObject)} 对畸形入站消息的行为。
 *
 * <p>这条路径此前没有任何覆盖：处理器原本是 {@code initWebsocket()} 里的匿名 lambda，
 * 构造它需要真实的鉴权 token 与真实的 WebSocket 客户端。见 issue #234。
 *
 * <p>回归的具体形状是 {@code message.get("type").getAsString()} 写在 try 之外：缺 type
 * 字段时 {@code get} 返回 null，{@code getAsString()} 抛 NPE。该 NPE 不会中断接收循环
 * （{@code UltiPanelWebSocketClient.onMessage} 有自己的 try），但会被记成
 * 「WebSocket消息解析失败」——而解析是成功的，于是消息被静默丢弃且诊断指错方向。
 */
@DisplayName("PluginInitiationUtils 入站消息守卫")
class PluginInitiationUtilsInboundMessageTest {

    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        mockLogger = mock(Logger.class);
        // 必须用 Consumer 重载：先打桩、后发布。见 TestHelper 的 javadoc 与 issue #250。
        TestHelper.mockUltiToolsInstance(ultiTools ->
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger));
    }

    @AfterEach
    void tearDown() throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /** 取出所有以指定级别记录的日志正文。 */
    private List<String> loggedAt(Level level) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, atLeastOnce()).log(eq(level), captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("畸形 type")
    class MalformedType {

        @Test
        @DisplayName("缺少 type 字段时不抛异常，只记一条 WARNING")
        void missingTypeFieldIsRejectedWithWarning() {
            JsonObject message = new JsonObject();
            message.addProperty("data", "irrelevant");

            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(message))
                    .doesNotThrowAnyException();

            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("缺少有效的 type 字段"));
        }

        @Test
        @DisplayName("type 为 JSON null 时安全返回")
        void jsonNullTypeIsRejected() {
            JsonObject message = new JsonObject();
            message.add("type", JsonNull.INSTANCE);

            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(message))
                    .doesNotThrowAnyException();

            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("缺少有效的 type 字段"));
        }

        @Test
        @DisplayName("type 为空字符串时安全返回")
        void emptyStringTypeIsRejected() {
            JsonObject message = new JsonObject();
            message.addProperty("type", "");

            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(message))
                    .doesNotThrowAnyException();

            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("缺少有效的 type 字段"));
        }

        @Test
        @DisplayName("message 本身为 null 时安全返回")
        void nullMessageIsRejected() {
            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(null))
                    .doesNotThrowAnyException();

            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("null 消息"));
        }

        @Test
        @DisplayName("type 为 JSON 对象或数组时按畸形处理，走同一条 WARNING 分支")
        void nonPrimitiveTypeIsRejected() {
            // getAsString() 对 JsonObject / JsonArray 抛 UnsupportedOperationException，
            // has() + !isJsonNull() 挡不住这种，所以守卫用的是 isJsonPrimitive()。
            // 这类和缺字段、JSON null、空串属于同一类畸形，不该被记成 SEVERE「处理时发生错误」。
            JsonObject objectType = new JsonObject();
            objectType.add("type", new JsonObject());

            JsonObject arrayType = new JsonObject();
            arrayType.add("type", new JsonArray());

            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(objectType))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(arrayType))
                    .doesNotThrowAnyException();

            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("缺少有效的 type 字段"));
            verify(mockLogger, never()).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
        }
    }

    @Nested
    @DisplayName("客户端不提前吞掉畸形消息")
    class ClientForwarding {

        /**
         * {@code UltiPanelWebSocketClient.onMessage} 在调用 messageHandler 之前，会自己取一次
         * type 只为打一条 FINE 日志。那一行原本写的是 {@code !isJsonNull()}，于是 type 为对象或
         * 数组时 {@code getAsString()} 在那里就抛了，消息根本到不了处理器——处理器侧的守卫写得
         * 再对也没用，这条路径在生产上不可达。
         *
         * <p>本用例走真实的 {@code onMessage(String)} 入口，确认消息现在能到达处理器。
         */
        @Test
        @DisplayName("type 为对象的消息仍能到达 messageHandler")
        void nonPrimitiveTypeStillReachesHandler() throws Exception {
            UltiPanelWebSocketClient client =
                    new UltiPanelWebSocketClient("ws://localhost:1", "test-server", "test-token");
            try {
                List<JsonObject> received = new ArrayList<>();
                client.setMessageHandler(received::add);

                client.onMessage("{\"type\":{},\"data\":{}}");

                assertThat(received)
                        .as("type 为对象时消息也应到达 handler，而不是在客户端打日志那一行就抛掉")
                        .hasSize(1);
            } finally {
                // 构造函数里起了 heartbeatExecutor，不关就是一条泄漏线程。见 issue #250。
                client.disconnect();
            }
        }
    }

    @Nested
    @DisplayName("正常 type 行为不变")
    class WellFormedType {

        @ParameterizedTest(name = "{0} 只记日志，不抛异常")
        @ValueSource(strings = {"server_properties_result", "auth_complete", "magic_link_response"})
        @DisplayName("仅记录日志的已知类型行为不变")
        void logOnlyTypesStillHandled(String type) {
            JsonObject message = new JsonObject();
            message.addProperty("type", type);

            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(message))
                    .doesNotThrowAnyException();

            // 不能被守卫误伤：这些是合法 type，不该走「缺少有效的 type 字段」那条分支
            verify(mockLogger, never()).log(eq(Level.WARNING),
                    org.mockito.ArgumentMatchers.contains("缺少有效的 type 字段"));
        }

        @Test
        @DisplayName("未知但合法的 type 仍然走 default 分支")
        void unknownTypeStillReachesDefaultBranch() {
            JsonObject message = new JsonObject();
            message.addProperty("type", "definitely_not_a_real_type");

            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(message))
                    .doesNotThrowAnyException();

            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("未知的消息类型"))
                    .noneSatisfy(line -> assertThat(line).contains("缺少有效的 type 字段"));
        }

        @Test
        @DisplayName("data 字段缺失或非对象时不影响 type 分发")
        void missingOrNonObjectDataDoesNotBreakDispatch() {
            JsonObject noData = new JsonObject();
            noData.addProperty("type", "auth_complete");

            JsonObject scalarData = new JsonObject();
            scalarData.addProperty("type", "auth_complete");
            scalarData.addProperty("data", 42);

            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(noData))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PluginInitiationUtils.handleInboundMessage(scalarData))
                    .doesNotThrowAnyException();
        }
    }
}
