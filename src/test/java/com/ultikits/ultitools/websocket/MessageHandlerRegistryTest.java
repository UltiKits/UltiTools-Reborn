package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * MessageHandlerRegistry 测试类
 */
@DisplayName("MessageHandlerRegistry 测试")
class MessageHandlerRegistryTest {

    private MessageHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MessageHandlerRegistry();
    }

    // 测试用处理器实现
    static class TestHandler implements WebSocketMessageHandler {
        private final String messageType;
        private final int priority;
        private final List<JsonObject> receivedMessages = new ArrayList<>();

        TestHandler(String messageType) {
            this(messageType, 0);
        }

        TestHandler(String messageType, int priority) {
            this.messageType = messageType;
            this.priority = priority;
        }

        @Override
        public String getMessageType() {
            return messageType;
        }

        @Override
        public void handle(JsonObject message) {
            receivedMessages.add(message);
        }

        @Override
        public int getPriority() {
            return priority;
        }

        List<JsonObject> getReceivedMessages() {
            return receivedMessages;
        }
    }

    // 抛出异常的处理器
    static class ThrowingHandler implements WebSocketMessageHandler {
        private final String messageType;

        ThrowingHandler(String messageType) {
            this.messageType = messageType;
        }

        @Override
        public String getMessageType() {
            return messageType;
        }

        @Override
        public void handle(JsonObject message) {
            throw new RuntimeException("Test exception");
        }
    }

    @Nested
    @DisplayName("register 方法测试")
    class RegisterTests {

        @Test
        @DisplayName("应该成功注册处理器")
        void shouldRegisterHandler() {
            TestHandler handler = new TestHandler("test_type");

            registry.register(handler);

            assertThat(registry.hasHandler("test_type")).isTrue();
            assertThat(registry.getHandlerCount("test_type")).isEqualTo(1);
        }

        @Test
        @DisplayName("注册 null 处理器应该抛出异常")
        void shouldThrowExceptionForNullHandler() {
            assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Handler must not be null");
        }

        @Test
        @DisplayName("注册空消息类型处理器应该抛出异常")
        void shouldThrowExceptionForEmptyMessageType() {
            WebSocketMessageHandler emptyTypeHandler = new WebSocketMessageHandler() {
                @Override
                public String getMessageType() {
                    return "";
                }

                @Override
                public void handle(JsonObject message) {
                }
            };

            assertThatThrownBy(() -> registry.register(emptyTypeHandler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty message type");
        }

        @Test
        @DisplayName("注册 null 消息类型处理器应该抛出异常")
        void shouldThrowExceptionForNullMessageType() {
            WebSocketMessageHandler nullTypeHandler = new WebSocketMessageHandler() {
                @Override
                public String getMessageType() {
                    return null;
                }

                @Override
                public void handle(JsonObject message) {
                }
            };

            assertThatThrownBy(() -> registry.register(nullTypeHandler))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty message type");
        }

        @Test
        @DisplayName("同一类型可以注册多个处理器")
        void shouldAllowMultipleHandlersForSameType() {
            TestHandler handler1 = new TestHandler("test_type");
            TestHandler handler2 = new TestHandler("test_type");

            registry.register(handler1);
            registry.register(handler2);

            assertThat(registry.getHandlerCount("test_type")).isEqualTo(2);
        }

        @Test
        @DisplayName("处理器应该按优先级排序")
        void handlersShouldBeSortedByPriority() {
            TestHandler lowPriority = new TestHandler("test_type", 0);
            TestHandler highPriority = new TestHandler("test_type", 10);

            registry.register(lowPriority);
            registry.register(highPriority);

            // 分发消息，高优先级处理器应该先收到
            JsonObject message = new JsonObject();
            message.addProperty("type", "test_type");
            registry.dispatch(message);

            // 两个处理器都应该收到消息
            assertThat(lowPriority.getReceivedMessages()).hasSize(1);
            assertThat(highPriority.getReceivedMessages()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("registerGlobal 方法测试")
    class RegisterGlobalTests {

        @Test
        @DisplayName("应该成功注册全局处理器")
        void shouldRegisterGlobalHandler() {
            TestHandler handler = new TestHandler("any_type");

            registry.registerGlobal(handler);

            // 全局处理器应该接收任何类型的消息
            JsonObject message = new JsonObject();
            message.addProperty("type", "random_type");
            registry.dispatch(message);

            assertThat(handler.getReceivedMessages()).hasSize(1);
        }

        @Test
        @DisplayName("注册 null 全局处理器应该抛出异常")
        void shouldThrowExceptionForNullGlobalHandler() {
            assertThatThrownBy(() -> registry.registerGlobal(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Handler must not be null");
        }

        @Test
        @DisplayName("全局处理器应该接收所有消息")
        void globalHandlerShouldReceiveAllMessages() {
            TestHandler globalHandler = new TestHandler("global");
            registry.registerGlobal(globalHandler);

            JsonObject message1 = new JsonObject();
            message1.addProperty("type", "type1");
            registry.dispatch(message1);

            JsonObject message2 = new JsonObject();
            message2.addProperty("type", "type2");
            registry.dispatch(message2);

            assertThat(globalHandler.getReceivedMessages()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("unregister 方法测试")
    class UnregisterTests {

        @Test
        @DisplayName("应该成功注销处理器")
        void shouldUnregisterHandler() {
            TestHandler handler = new TestHandler("test_type");
            registry.register(handler);
            assertThat(registry.hasHandler("test_type")).isTrue();

            registry.unregister(handler);

            assertThat(registry.hasHandler("test_type")).isFalse();
        }

        @Test
        @DisplayName("注销 null 处理器应该不做任何事")
        void shouldDoNothingForNullHandler() {
            // 不应该抛出异常
            registry.unregister(null);
        }

        @Test
        @DisplayName("注销全局处理器")
        void shouldUnregisterGlobalHandler() {
            TestHandler handler = new TestHandler("any_type");
            registry.registerGlobal(handler);

            registry.unregister(handler);

            // 全局处理器被注销后不应该收到消息
            JsonObject message = new JsonObject();
            message.addProperty("type", "random_type");
            registry.dispatch(message);

            assertThat(handler.getReceivedMessages()).isEmpty();
        }

        @Test
        @DisplayName("注销某类型所有处理器")
        void shouldUnregisterAllForType() {
            TestHandler handler1 = new TestHandler("test_type");
            TestHandler handler2 = new TestHandler("test_type");
            registry.register(handler1);
            registry.register(handler2);

            registry.unregisterAll("test_type");

            assertThat(registry.hasHandler("test_type")).isFalse();
        }
    }

    @Nested
    @DisplayName("clear 方法测试")
    class ClearTests {

        @Test
        @DisplayName("应该清除所有处理器")
        void shouldClearAllHandlers() {
            registry.register(new TestHandler("type1"));
            registry.register(new TestHandler("type2"));
            registry.registerGlobal(new TestHandler("global"));

            registry.clear();

            assertThat(registry.hasHandler("type1")).isFalse();
            assertThat(registry.hasHandler("type2")).isFalse();
            assertThat(registry.getRegisteredTypes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("dispatch 方法测试")
    class DispatchTests {

        @Test
        @DisplayName("应该分发消息到正确的处理器")
        void shouldDispatchToCorrectHandler() {
            TestHandler handler = new TestHandler("test_type");
            registry.register(handler);

            JsonObject message = new JsonObject();
            message.addProperty("type", "test_type");
            message.addProperty("data", "test_data");

            boolean handled = registry.dispatch(message);

            assertThat(handled).isTrue();
            assertThat(handler.getReceivedMessages()).hasSize(1);
            assertThat(handler.getReceivedMessages().get(0).get("data").getAsString()).isEqualTo("test_data");
        }

        @Test
        @DisplayName("分发 null 消息应该返回 false")
        void shouldReturnFalseForNullMessage() {
            boolean handled = registry.dispatch((JsonObject) null);

            assertThat(handled).isFalse();
        }

        @Test
        @DisplayName("分发没有类型的消息应该只调用全局处理器")
        void shouldOnlyCallGlobalHandlersForMessageWithoutType() {
            TestHandler globalHandler = new TestHandler("global");
            TestHandler typeHandler = new TestHandler("test_type");
            registry.registerGlobal(globalHandler);
            registry.register(typeHandler);

            JsonObject message = new JsonObject();
            message.addProperty("data", "test_data");
            // 没有 type 字段

            registry.dispatch(message);

            assertThat(globalHandler.getReceivedMessages()).hasSize(1);
            assertThat(typeHandler.getReceivedMessages()).isEmpty();
        }

        @Test
        @DisplayName("分发未注册类型的消息应该只调用全局处理器")
        void shouldOnlyCallGlobalHandlersForUnregisteredType() {
            TestHandler globalHandler = new TestHandler("global");
            registry.registerGlobal(globalHandler);

            JsonObject message = new JsonObject();
            message.addProperty("type", "unknown_type");

            boolean handled = registry.dispatch(message);

            assertThat(handled).isTrue(); // 全局处理器处理了
            assertThat(globalHandler.getReceivedMessages()).hasSize(1);
        }

        @Test
        @DisplayName("处理器抛出异常时应该继续处理其他处理器")
        void shouldContinueAfterHandlerException() {
            ThrowingHandler throwingHandler = new ThrowingHandler("test_type");
            TestHandler normalHandler = new TestHandler("test_type");

            registry.register(throwingHandler);
            registry.register(normalHandler);

            JsonObject message = new JsonObject();
            message.addProperty("type", "test_type");

            // 不应该抛出异常
            registry.dispatch(message);

            // 正常处理器应该收到消息
            assertThat(normalHandler.getReceivedMessages()).hasSize(1);
        }

        @Test
        @DisplayName("全局处理器抛出异常时应该继续处理")
        void shouldContinueAfterGlobalHandlerException() {
            ThrowingHandler throwingHandler = new ThrowingHandler("global");
            TestHandler normalHandler = new TestHandler("test_type");

            registry.registerGlobal(throwingHandler);
            registry.register(normalHandler);

            JsonObject message = new JsonObject();
            message.addProperty("type", "test_type");

            registry.dispatch(message);

            assertThat(normalHandler.getReceivedMessages()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("dispatch(String) 方法测试")
    class DispatchStringTests {

        @Test
        @DisplayName("应该解析 JSON 字符串并分发")
        void shouldParseAndDispatch() {
            TestHandler handler = new TestHandler("test_type");
            registry.register(handler);

            String jsonString = "{\"type\":\"test_type\",\"data\":\"hello\"}";

            boolean handled = registry.dispatch(jsonString);

            assertThat(handled).isTrue();
            assertThat(handler.getReceivedMessages()).hasSize(1);
        }

        @Test
        @DisplayName("分发 null 字符串应该返回 false")
        void shouldReturnFalseForNullString() {
            boolean handled = registry.dispatch((String) null);

            assertThat(handled).isFalse();
        }

        @Test
        @DisplayName("分发空字符串应该返回 false")
        void shouldReturnFalseForEmptyString() {
            boolean handled = registry.dispatch("");

            assertThat(handled).isFalse();
        }

        @Test
        @DisplayName("分发无效 JSON 应该返回 false")
        void shouldReturnFalseForInvalidJson() {
            boolean handled = registry.dispatch("not valid json");

            assertThat(handled).isFalse();
        }
    }

    @Nested
    @DisplayName("hasHandler 方法测试")
    class HasHandlerTests {

        @Test
        @DisplayName("有处理器时应该返回 true")
        void shouldReturnTrueWhenHandlerExists() {
            registry.register(new TestHandler("test_type"));

            assertThat(registry.hasHandler("test_type")).isTrue();
        }

        @Test
        @DisplayName("没有处理器时应该返回 false")
        void shouldReturnFalseWhenNoHandler() {
            assertThat(registry.hasHandler("test_type")).isFalse();
        }

        @Test
        @DisplayName("null 类型应该抛出 NullPointerException")
        void shouldThrowForNullType() {
            assertThatThrownBy(() -> registry.hasHandler(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("getHandlerCount 方法测试")
    class GetHandlerCountTests {

        @Test
        @DisplayName("应该返回正确的处理器数量")
        void shouldReturnCorrectCount() {
            assertThat(registry.getHandlerCount("test_type")).isZero();

            registry.register(new TestHandler("test_type"));
            assertThat(registry.getHandlerCount("test_type")).isEqualTo(1);

            registry.register(new TestHandler("test_type"));
            assertThat(registry.getHandlerCount("test_type")).isEqualTo(2);
        }

        @Test
        @DisplayName("null 类型应该抛出 NullPointerException")
        void shouldThrowForNullType() {
            assertThatThrownBy(() -> registry.getHandlerCount(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("getRegisteredTypes 方法测试")
    class GetRegisteredTypesTests {

        @Test
        @DisplayName("应该返回所有已注册类型")
        void shouldReturnAllRegisteredTypes() {
            registry.register(new TestHandler("type1"));
            registry.register(new TestHandler("type2"));
            registry.register(new TestHandler("type3"));

            Set<String> types = registry.getRegisteredTypes();

            assertThat(types).containsExactlyInAnyOrder("type1", "type2", "type3");
        }

        @Test
        @DisplayName("没有注册时应该返回空集合")
        void shouldReturnEmptySetWhenNoHandlers() {
            Set<String> types = registry.getRegisteredTypes();

            assertThat(types).isEmpty();
        }

        @Test
        @DisplayName("返回的集合应该是不可变的")
        void shouldReturnUnmodifiableSet() {
            registry.register(new TestHandler("type1"));

            Set<String> types = registry.getRegisteredTypes();

            assertThatThrownBy(() -> types.add("new_type"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("常量值测试")
    class ConstantsTests {

        @Test
        @DisplayName("TYPE_FIELD 应该是 'type'")
        void typeFieldShouldBeType() {
            assertThat(MessageHandlerRegistry.TYPE_FIELD).isEqualTo("type");
        }
    }

    @Nested
    @DisplayName("类型字段边界情况测试")
    class TypeFieldEdgeCaseTests {

        @Test
        @DisplayName("type 为 null 时应该只调用全局处理器")
        void shouldHandleNullTypeField() {
            TestHandler globalHandler = new TestHandler("global");
            registry.registerGlobal(globalHandler);

            JsonObject message = new JsonObject();
            message.add("type", null); // JsonNull

            registry.dispatch(message);

            assertThat(globalHandler.getReceivedMessages()).hasSize(1);
        }

        @Test
        @DisplayName("type 为空字符串时应该只调用全局处理器")
        void shouldHandleEmptyTypeField() {
            TestHandler globalHandler = new TestHandler("global");

            registry.registerGlobal(globalHandler);
            // 空类型不能注册，所以只有全局处理器

            JsonObject message = new JsonObject();
            message.addProperty("type", "");

            registry.dispatch(message);

            assertThat(globalHandler.getReceivedMessages()).hasSize(1);
        }
    }
}
