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
import com.ultikits.ultitools.manager.FileOperationManager;

/**
 * FileOperationHandler 测试类
 */
@DisplayName("FileOperationHandler 测试")
class FileOperationHandlerTest {

    @Mock
    private FileOperationManager mockFileManager;

    private FileOperationHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new FileOperationHandler(mockFileManager);
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该接受非空 FileOperationManager")
        void shouldAcceptNonNullManager() {
            FileOperationHandler h = new FileOperationHandler(mockFileManager);
            assertThat(h).isNotNull();
        }

        @Test
        @DisplayName("应该接受 null FileOperationManager")
        void shouldAcceptNullManager() {
            FileOperationHandler h = new FileOperationHandler(null);
            assertThat(h).isNotNull();
        }
    }

    @Nested
    @DisplayName("getMessageType 测试")
    class GetMessageTypeTests {

        @Test
        @DisplayName("应该返回 'file_operation'")
        void shouldReturnFileOperation() {
            assertThat(handler.getMessageType()).isEqualTo("file_operation");
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
        @DisplayName("应该处理包含所有字段的消息")
        void shouldHandleMessageWithAllFields() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "read");
            message.addProperty("path", "/server/config.yml");
            message.addProperty("requestId", "req-456");

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
        }

        @Test
        @DisplayName("应该处理只有 operation 的消息")
        void shouldHandleMessageWithOnlyOperation() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "list");

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
        }

        @Test
        @DisplayName("应该处理 read 操作")
        void shouldHandleReadOperation() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "read");
            message.addProperty("path", "/test.txt");

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
        }

        @Test
        @DisplayName("应该处理 write 操作")
        void shouldHandleWriteOperation() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "write");
            message.addProperty("path", "/test.txt");
            message.addProperty("content", "test content");

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
        }

        @Test
        @DisplayName("应该处理 delete 操作")
        void shouldHandleDeleteOperation() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "delete");
            message.addProperty("path", "/test.txt");

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
        }

        @Test
        @DisplayName("应该处理 list 操作")
        void shouldHandleListOperation() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "list");
            message.addProperty("path", "/plugins");

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
        }

        @Test
        @DisplayName("缺少 operation 字段不应该调用管理器")
        void shouldNotHandleWithoutOperation() {
            JsonObject message = new JsonObject();
            message.addProperty("path", "/test.txt");

            handler.handle(message);

            verify(mockFileManager, never()).handleFileOperation(any());
        }

        @Test
        @DisplayName("operation 为 null 不应该调用管理器")
        void shouldNotHandleNullOperation() {
            JsonObject message = new JsonObject();
            message.add("operation", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockFileManager, never()).handleFileOperation(any());
        }

        @Test
        @DisplayName("FileManager 为 null 时不应抛出异常")
        void shouldNotThrowWhenManagerIsNull() {
            FileOperationHandler nullHandler = new FileOperationHandler(null);
            JsonObject message = new JsonObject();
            message.addProperty("operation", "read");

            // 不应该抛出异常
            nullHandler.handle(message);
        }

        @Test
        @DisplayName("path 为 JsonNull 时应该正常处理")
        void shouldHandleNullPath() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "list");
            message.add("path", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
        }

        @Test
        @DisplayName("requestId 为 JsonNull 时应该正常处理")
        void shouldHandleNullRequestId() {
            JsonObject message = new JsonObject();
            message.addProperty("operation", "read");
            message.add("requestId", JsonNull.INSTANCE);

            handler.handle(message);

            verify(mockFileManager).handleFileOperation(message);
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
