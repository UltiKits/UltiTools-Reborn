package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * 心跳路径上的存活判定与延迟测量（issue #235）。
 *
 * <p>这三样能力原先只存在于 WIRE-17 那批处理器类中一个从未接线的死类里（见 #233；该类已随
 * GEN-11 于 6.3.0 一并删除）。搬进客户端之前，连接健康的唯一判据是 socket 有没有断，而一条
 * TCP 连接完全可以在不产生 {@code onClose} 的情况下静默失效。
 *
 * <p>全部用假时钟推进，不用 {@code Thread.sleep} —— 真等两个心跳周期是 120 秒。
 */
@DisplayName("心跳存活判定")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // tearDown 需反射复位 UltiTools 单例（#250），与仓库其它测试类一致
class HeartbeatLivenessTest {

    /** 假时钟，测试自己推进。 */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private UltiPanelWebSocketClient client;

    @BeforeEach
    void setUp() throws Exception {
        Logger mockLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools ->
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger));

        client = new UltiPanelWebSocketClient("ws://localhost:1", "test-server", "test-token");
        client.setClock(now::get);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            // 构造函数里起了 heartbeatExecutor，不关就是泄漏线程。见 #250。
            client.disconnect();
        }
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /** 让假时钟前进若干秒。 */
    private void advanceSeconds(long seconds) {
        now.addAndGet(seconds * 1000);
    }

    /** 喂一条 pong 进真实的 onMessage 入口。 */
    private void receivePong() {
        client.onMessage("{\"type\":\"pong\",\"data\":{}}");
    }

    @Nested
    @DisplayName("存活判定")
    class Liveness {

        @Test
        @DisplayName("从未收到过 pong 时判为存活")
        void neverReceivedPongIsConsideredAlive() {
            // 刻意的语义：否则新连接会在第一个心跳周期就被判死。
            // 代价是发现不了「对端从来不应答」，但那是安全的方向。
            assertThat(client.getLastPongTime()).isZero();
            assertThat(client.isAlive(120_000)).isTrue();

            advanceSeconds(3600);
            assertThat(client.isAlive(120_000))
                    .as("即使过了一小时，只要一次 pong 都没收到过，也不该判死")
                    .isTrue();
        }

        @Test
        @DisplayName("阈值之内收到过 pong 判为存活")
        void recentPongIsAlive() {
            receivePong();
            advanceSeconds(119);

            assertThat(client.isAlive(120_000)).isTrue();
        }

        @Test
        @DisplayName("超过阈值未收到 pong 判为静默失效")
        void stalePongIsNotAlive() {
            receivePong();
            advanceSeconds(121);

            assertThat(client.isAlive(120_000))
                    .as("曾经在应答、后来不答了 —— 这正是静默失效")
                    .isFalse();
        }

        @Test
        @DisplayName("新的 pong 会刷新判定")
        void newPongRefreshesLiveness() {
            receivePong();
            advanceSeconds(119);
            receivePong();
            advanceSeconds(119);

            assertThat(client.isAlive(120_000))
                    .as("累计 238 秒，但最后一次 pong 在 119 秒前")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("延迟测量")
    class Latency {

        @Test
        @DisplayName("尚未测到时为 -1")
        void latencyIsUnsetInitially() {
            assertThat(client.getLatencyMs()).isEqualTo(-1);
        }

        @Test
        @DisplayName("ping 到 pong 的往返时间被记录下来")
        void latencyIsMeasuredAcrossPingPong() {
            client.sendPing();      // 未连接，消息发不出去，但发送时间已记录
            advanceSeconds(3);
            receivePong();

            assertThat(client.getLatencyMs()).isEqualTo(3000);
        }

        @Test
        @DisplayName("没发过 ping 就收到 pong 时，不产生虚假延迟")
        void unsolicitedPongDoesNotProduceLatency() {
            receivePong();

            assertThat(client.getLatencyMs())
                    .as("没有 ping 时间戳可比，延迟应当保持未测到状态")
                    .isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("心跳 tick 的行为")
    class HeartbeatTick {

        @Test
        @DisplayName("socket 未打开时 tick 什么都不做")
        void tickIsNoOpWhenSocketClosed() {
            // 本用例里 socket 从未连接，isOpen() 为假
            assertThatCode(client::heartbeatTick).doesNotThrowAnyException();

            assertThat(client.getLastPongTime())
                    .as("不该产生任何副作用")
                    .isZero();
        }

        @Test
        @DisplayName("静默失效的判定不依赖 onClose 被触发")
        void silentFailureIsDetectedWithoutOnClose() {
            // 这是整条改动的要点：onClose 从未触发，socket 在系统看来还开着，
            // 但对端已经不应答了。判定必须只依赖 pong 的时间戳。
            receivePong();
            assertThat(client.isAlive(120_000)).isTrue();

            advanceSeconds(121);

            assertThat(client.isAlive(120_000))
                    .as("没有任何 onClose 发生，仅凭 pong 静默这一点就应当判死")
                    .isFalse();
        }
    }
}
