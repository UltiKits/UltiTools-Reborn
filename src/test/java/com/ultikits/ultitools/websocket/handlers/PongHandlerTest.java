package com.ultikits.ultitools.websocket.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * PongHandler 测试类
 */
@DisplayName("PongHandler 测试")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for internal state verification
class PongHandlerTest {

    private PongHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PongHandler();
    }

    @Nested
    @DisplayName("getMessageType 测试")
    class GetMessageTypeTests {

        @Test
        @DisplayName("应该返回 'pong'")
        void shouldReturnPong() {
            assertThat(handler.getMessageType()).isEqualTo("pong");
        }
    }

    @Nested
    @DisplayName("handle 方法测试")
    class HandleTests {

        @Test
        @DisplayName("处理消息应该更新最后 pong 时间")
        void shouldUpdateLastPongTime() throws InterruptedException {
            long beforeHandle = System.currentTimeMillis();

            handler.handle(new JsonObject());

            long afterHandle = System.currentTimeMillis();
            long lastPongTime = handler.getLastPongTime();

            assertThat(lastPongTime).isGreaterThanOrEqualTo(beforeHandle);
            assertThat(lastPongTime).isLessThanOrEqualTo(afterHandle);
        }

        @Test
        @DisplayName("处理 null 消息应该仍然更新时间")
        void shouldHandleNullMessage() {
            handler.handle(null);

            assertThat(handler.getLastPongTime()).isGreaterThan(0);
        }

        @Test
        @DisplayName("连续处理消息应该更新时间")
        void shouldUpdateTimeOnConsecutiveCalls() throws InterruptedException {
            handler.handle(new JsonObject());
            long firstTime = handler.getLastPongTime();

            Thread.sleep(10);

            handler.handle(new JsonObject());
            long secondTime = handler.getLastPongTime();

            assertThat(secondTime).isGreaterThan(firstTime);
        }
    }

    @Nested
    @DisplayName("recordPing 方法测试")
    class RecordPingTests {

        @Test
        @DisplayName("应该记录 ping 时间")
        void shouldRecordPingTime() {
            handler.recordPing();

            // 通过延迟计算来验证 ping 时间已记录
            handler.handle(new JsonObject());
            long latency = handler.getLatency();

            assertThat(latency).isGreaterThanOrEqualTo(0);
        }
        
        @Test
        @DisplayName("记录 ping 后收到 pong 应该计算延迟")
        void shouldCalculateLatencyAfterPingAndPong() throws InterruptedException {
            handler.recordPing();
            Thread.sleep(10);
            handler.handle(new JsonObject());

            assertThat(handler.getLatency()).isGreaterThanOrEqualTo(10);
        }
    }

    @Nested
    @DisplayName("getLatency 方法测试")
    class GetLatencyTests {

        @Test
        @DisplayName("没有记录 ping 和 pong 时应该返回 0")
        void shouldReturnZeroWhenNoPingRecorded() {
            assertThat(handler.getLatency()).isZero();
        }

        @Test
        @DisplayName("只记录 ping 没有收到 pong 时应该返回 0")
        void shouldReturnZeroWhenNoPongReceived() {
            handler.recordPing();

            assertThat(handler.getLatency()).isZero();
        }

        @Test
        @DisplayName("ping 之后收到 pong 应该返回延迟")
        void shouldReturnLatencyAfterPingAndPong() throws InterruptedException {
            handler.recordPing();
            Thread.sleep(10);
            handler.handle(new JsonObject());

            long latency = handler.getLatency();

            assertThat(latency).isGreaterThanOrEqualTo(10);
        }

        @Test
        @DisplayName("没有 ping 但收到 pong 时 latency 保持为 0")
        void shouldReturnZeroWhenPongWithoutPing() throws Exception {
            // 先处理 pong，但没有 ping
            handler.handle(new JsonObject());

            // latency 仍然是 0，因为 lastPingTime 是 0
            assertThat(handler.getLatency()).isZero();
        }
    }

    @Nested
    @DisplayName("getLastPongTime 方法测试")
    class GetLastPongTimeTests {

        @Test
        @DisplayName("初始状态应该返回 0")
        void shouldReturnZeroInitially() {
            assertThat(handler.getLastPongTime()).isZero();
        }

        @Test
        @DisplayName("处理消息后应该返回当前时间")
        void shouldReturnCurrentTimeAfterHandle() {
            long beforeHandle = System.currentTimeMillis();

            handler.handle(new JsonObject());

            long lastPongTime = handler.getLastPongTime();
            long afterHandle = System.currentTimeMillis();

            assertThat(lastPongTime).isBetween(beforeHandle, afterHandle);
        }
    }

    @Nested
    @DisplayName("isAlive 方法测试")
    class IsAliveTests {

        @Test
        @DisplayName("从未收到 pong 时应该返回 true (假设存活)")
        void shouldReturnTrueWhenNeverReceivedPong() {
            // 根据实现，lastPongTime == 0 时返回 true，假设连接还活着
            assertThat(handler.isAlive(30000)).isTrue();
        }

        @Test
        @DisplayName("在超时时间内应该返回 true")
        void shouldReturnTrueWhenWithinTimeout() {
            handler.handle(new JsonObject());

            assertThat(handler.isAlive(30000)).isTrue();
        }

        @Test
        @DisplayName("超过超时时间应该返回 false")
        void shouldReturnFalseWhenExceedsTimeout() throws Exception {
            handler.handle(new JsonObject());

            // 手动设置 lastPongTime 为过去时间
            Field lastPongTimeField = PongHandler.class.getDeclaredField("lastPongTime");
            lastPongTimeField.setAccessible(true);
            lastPongTimeField.set(handler, System.currentTimeMillis() - 60000);

            assertThat(handler.isAlive(30000)).isFalse();
        }

        @Test
        @DisplayName("边界情况 - 刚好在超时时间内")
        void shouldReturnTrueAtExactTimeout() throws Exception {
            handler.handle(new JsonObject());

            // 设置 lastPongTime 为刚好在超时时间内 (使用 29000ms 而不是 29999ms 以避免竞态条件)
            // Use 29000ms instead of 29999ms to avoid race condition in CI environments
            Field lastPongTimeField = PongHandler.class.getDeclaredField("lastPongTime");
            lastPongTimeField.setAccessible(true);
            lastPongTimeField.set(handler, System.currentTimeMillis() - 29000);

            assertThat(handler.isAlive(30000)).isTrue();
        }
    }

    @Nested
    @DisplayName("getPriority 方法测试")
    class GetPriorityTests {

        @Test
        @DisplayName("应该返回默认优先级 0")
        void shouldReturnDefaultPriority() {
            assertThat(handler.getPriority()).isZero();
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
