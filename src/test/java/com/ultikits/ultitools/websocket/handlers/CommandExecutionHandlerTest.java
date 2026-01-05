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
import com.ultikits.ultitools.manager.CommandExecutionManager;

/**
 * CommandExecutionHandler 测试类
 */
@DisplayName("CommandExecutionHandler 测试")
class CommandExecutionHandlerTest {

    @Mock
    private CommandExecutionManager mockCommandManager;

    private CommandExecutionHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new CommandExecutionHandler(mockCommandManager);
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该接受非空 CommandExecutionManager")
        void shouldAcceptNonNullManager() {
            CommandExecutionHandler h = new CommandExecutionHandler(mockCommandManager);
            assertThat(h).isNotNull();
        }

        @Test
        @DisplayName("应该接受 null CommandExecutionManager")
        void shouldAcceptNullManager() {
            CommandExecutionHandler h = new CommandExecutionHandler(null);
            assertThat(h).isNotNull();
        }
    }

    @Nested
    @DisplayName("getMessageType 测试")
    class GetMessageTypeTests {

        @Test
        @DisplayName("应该返回 'execute_command'")
        void shouldReturnExecuteCommand() {
            assertThat(handler.getMessageType()).isEqualTo("execute_command");
        }
    }

    @Nested
    @DisplayName("getPriority 测试")
    class GetPriorityTests {

        @Test
        @DisplayName("应该返回优先级 10")
        void shouldReturnPriority10() {
            assertThat(handler.getPriority()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("handle 方法测试")
    class HandleTests {

        @Test
        @DisplayName("应该执行包含所有字段的命令")
        void shouldExecuteCommandWithAllFields() {
            JsonObject message = new JsonObject();
            message.addProperty("command", "say hello");
            message.addProperty("executor", "console");
            message.addProperty("requestId", "req-123");
            message.addProperty("async", true);

            handler.handle(message);

            verify(mockCommandManager).executeCommand(message);
        }

        @Test
        @DisplayName("应该执行只有 command 的消息")
        void shouldExecuteCommandWithOnlyCommand() {
            JsonObject message = new JsonObject();
            message.addProperty("command", "gamemode creative");

            handler.handle(message);

            verify(mockCommandManager).executeCommand(message);
        }

        @Test
        @DisplayName("空命令不应该调用管理器")
        void shouldNotExecuteEmptyCommand() {
            JsonObject message = new JsonObject();
            message.addProperty("command", "");

            handler.handle(message);

            verify(mockCommandManager, never()).executeCommand(any());
        }

        @Test
        @DisplayName("null 命令不应该调用管理器")
        void shouldNotExecuteNullCommand() {
            JsonObject message = new JsonObject();
            message.add("command", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockCommandManager, never()).executeCommand(any());
        }

        @Test
        @DisplayName("缺少 command 字段不应该调用管理器")
        void shouldNotExecuteWithoutCommand() {
            JsonObject message = new JsonObject();
            message.addProperty("executor", "player");

            handler.handle(message);

            verify(mockCommandManager, never()).executeCommand(any());
        }

        @Test
        @DisplayName("CommandManager 为 null 时不应抛出异常")
        void shouldNotThrowWhenManagerIsNull() {
            CommandExecutionHandler nullHandler = new CommandExecutionHandler(null);
            JsonObject message = new JsonObject();
            message.addProperty("command", "test");

            // 不应该抛出异常
            nullHandler.handle(message);
        }

        @Test
        @DisplayName("executor 为 JsonNull 时应该正常处理")
        void shouldHandleNullExecutor() {
            JsonObject message = new JsonObject();
            message.addProperty("command", "test");
            message.add("executor", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockCommandManager).executeCommand(message);
        }

        @Test
        @DisplayName("requestId 为 JsonNull 时应该正常处理")
        void shouldHandleNullRequestId() {
            JsonObject message = new JsonObject();
            message.addProperty("command", "test");
            message.add("requestId", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockCommandManager).executeCommand(message);
        }

        @Test
        @DisplayName("async 为 JsonNull 时应该正常处理")
        void shouldHandleNullAsync() {
            JsonObject message = new JsonObject();
            message.addProperty("command", "test");
            message.add("async", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockCommandManager).executeCommand(message);
        }

        @Test
        @DisplayName("async 为 false 时应该正常处理")
        void shouldHandleAsyncFalse() {
            JsonObject message = new JsonObject();
            message.addProperty("command", "test");
            message.addProperty("async", false);

            handler.handle(message);

            verify(mockCommandManager).executeCommand(message);
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
