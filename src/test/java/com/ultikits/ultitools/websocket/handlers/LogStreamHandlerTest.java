package com.ultikits.ultitools.websocket.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.ultikits.ultitools.manager.LogStreamManager;

/**
 * LogStreamHandler 测试类
 */
@DisplayName("LogStreamHandler 测试")
class LogStreamHandlerTest {

    @Mock
    private LogStreamManager mockLogStreamManager;

    private LogStreamHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new LogStreamHandler(mockLogStreamManager);
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该接受非空 LogStreamManager")
        void shouldAcceptNonNullManager() {
            LogStreamHandler h = new LogStreamHandler(mockLogStreamManager);
            assertThat(h).isNotNull();
        }

        @Test
        @DisplayName("应该接受 null LogStreamManager")
        void shouldAcceptNullManager() {
            LogStreamHandler h = new LogStreamHandler(null);
            assertThat(h).isNotNull();
        }
    }

    @Nested
    @DisplayName("getMessageType 测试")
    class GetMessageTypeTests {

        @Test
        @DisplayName("应该返回 'log_stream'")
        void shouldReturnLogStream() {
            assertThat(handler.getMessageType()).isEqualTo("log_stream");
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
        @DisplayName("应该处理包含 action 的消息")
        void shouldHandleMessageWithAction() {
            JsonObject message = new JsonObject();
            message.addProperty("action", "start");

            handler.handle(message);

            verify(mockLogStreamManager).handleLogStreamMessage(message);
        }

        @Test
        @DisplayName("应该处理 start 动作")
        void shouldHandleStartAction() {
            JsonObject message = new JsonObject();
            message.addProperty("action", "start");
            message.addProperty("filter", "INFO");

            handler.handle(message);

            verify(mockLogStreamManager).handleLogStreamMessage(message);
        }

        @Test
        @DisplayName("应该处理 stop 动作")
        void shouldHandleStopAction() {
            JsonObject message = new JsonObject();
            message.addProperty("action", "stop");

            handler.handle(message);

            verify(mockLogStreamManager).handleLogStreamMessage(message);
        }

        @Test
        @DisplayName("应该处理 pause 动作")
        void shouldHandlePauseAction() {
            JsonObject message = new JsonObject();
            message.addProperty("action", "pause");

            handler.handle(message);

            verify(mockLogStreamManager).handleLogStreamMessage(message);
        }

        @Test
        @DisplayName("应该处理 resume 动作")
        void shouldHandleResumeAction() {
            JsonObject message = new JsonObject();
            message.addProperty("action", "resume");

            handler.handle(message);

            verify(mockLogStreamManager).handleLogStreamMessage(message);
        }

        @Test
        @DisplayName("缺少 action 字段不应该调用管理器")
        void shouldNotHandleWithoutAction() {
            JsonObject message = new JsonObject();
            message.addProperty("filter", "INFO");

            handler.handle(message);

            verify(mockLogStreamManager, never()).handleLogStreamMessage(any());
        }

        @Test
        @DisplayName("action 为 null 不应该调用管理器")
        void shouldNotHandleNullAction() {
            JsonObject message = new JsonObject();
            message.add("action", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockLogStreamManager, never()).handleLogStreamMessage(any());
        }

        @Test
        @DisplayName("LogStreamManager 为 null 时不应抛出异常")
        void shouldNotThrowWhenManagerIsNull() {
            LogStreamHandler nullHandler = new LogStreamHandler(null);
            JsonObject message = new JsonObject();
            message.addProperty("action", "start");

            // 不应该抛出异常
            nullHandler.handle(message);
        }

        @Test
        @DisplayName("应该处理带有额外字段的消息")
        void shouldHandleMessageWithExtraFields() {
            JsonObject message = new JsonObject();
            message.addProperty("action", "start");
            message.addProperty("filter", "ERROR");
            message.addProperty("maxLines", 1000);
            message.addProperty("requestId", "req-789");

            handler.handle(message);

            verify(mockLogStreamManager).handleLogStreamMessage(message);
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
