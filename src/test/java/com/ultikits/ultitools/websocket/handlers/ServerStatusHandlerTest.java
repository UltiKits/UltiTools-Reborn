package com.ultikits.ultitools.websocket.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.manager.ServerMonitorManager;

/**
 * ServerStatusHandler 测试类
 */
@DisplayName("ServerStatusHandler 测试")
class ServerStatusHandlerTest {

    @Mock
    private ServerMonitorManager mockMonitorManager;

    private ServerStatusHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ServerStatusHandler(mockMonitorManager);
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该接受非空 ServerMonitorManager")
        void shouldAcceptNonNullManager() {
            ServerStatusHandler h = new ServerStatusHandler(mockMonitorManager);
            assertThat(h).isNotNull();
        }

        @Test
        @DisplayName("应该接受 null ServerMonitorManager")
        void shouldAcceptNullManager() {
            ServerStatusHandler h = new ServerStatusHandler(null);
            assertThat(h).isNotNull();
        }
    }

    @Nested
    @DisplayName("getMessageType 测试")
    class GetMessageTypeTests {

        @Test
        @DisplayName("应该返回 'server_status_request'")
        void shouldReturnServerStatusRequest() {
            assertThat(handler.getMessageType()).isEqualTo("server_status_request");
        }
    }

    @Nested
    @DisplayName("getPriority 测试")
    class GetPriorityTests {

        @Test
        @DisplayName("应该返回默认优先级 0")
        void shouldReturnDefaultPriority() {
            assertThat(handler.getPriority()).isZero();
        }
    }

    @Nested
    @DisplayName("handle 方法测试")
    class HandleTests {

        @Test
        @DisplayName("有 requestId 时应该调用 sendServerStatusWithRequestId")
        void shouldCallSendServerStatusWithRequestIdWhenPresent() {
            JsonObject message = new JsonObject();
            message.addProperty("requestId", "req-123");

            handler.handle(message);

            verify(mockMonitorManager).sendServerStatusWithRequestId("req-123");
            verify(mockMonitorManager, never()).sendServerStatus();
        }

        @Test
        @DisplayName("没有 requestId 时应该调用 sendServerStatus")
        void shouldCallSendServerStatusWhenNoRequestId() {
            JsonObject message = new JsonObject();

            handler.handle(message);

            verify(mockMonitorManager).sendServerStatus();
            verify(mockMonitorManager, never()).sendServerStatusWithRequestId(anyString());
        }

        @Test
        @DisplayName("requestId 为 null 时应该调用 sendServerStatus")
        void shouldCallSendServerStatusWhenRequestIdIsNull() {
            JsonObject message = new JsonObject();
            message.add("requestId", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockMonitorManager).sendServerStatus();
        }

        @Test
        @DisplayName("requestId 为空字符串时应该调用 sendServerStatus")
        void shouldCallSendServerStatusWhenRequestIdIsEmpty() {
            JsonObject message = new JsonObject();
            message.addProperty("requestId", "");

            handler.handle(message);

            verify(mockMonitorManager).sendServerStatus();
        }

        @Test
        @DisplayName("ServerMonitorManager 为 null 时不应抛出异常")
        void shouldNotThrowWhenManagerIsNull() {
            ServerStatusHandler nullHandler = new ServerStatusHandler(null);
            JsonObject message = new JsonObject();

            // 不应该抛出异常
            nullHandler.handle(message);
        }

        @Test
        @DisplayName("应该处理带有额外字段的消息")
        void shouldHandleMessageWithExtraFields() {
            JsonObject message = new JsonObject();
            message.addProperty("requestId", "req-456");
            message.addProperty("extraField", "extraValue");

            handler.handle(message);

            verify(mockMonitorManager).sendServerStatusWithRequestId("req-456");
        }

        @Test
        @DisplayName("应该正确处理空消息对象")
        void shouldHandleEmptyMessage() {
            JsonObject message = new JsonObject();

            handler.handle(message);

            verify(mockMonitorManager).sendServerStatus();
        }
    }

    @Nested
    @DisplayName("WebSocketMessageHandler 接口实现测试")
    class InterfaceImplementationTests {

        @Test
        @DisplayName("应该实现 WebSocketMessageHandler 接口")
        void shouldImplementWebSocketMessageHandler() {
            assertThat(handler).isInstanceOf(com.ultikits.ultitools.websocket.WebSocketMessageHandler.class);
        }
    }
}
