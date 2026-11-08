package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonArray;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 服务器状态采样的线程契约（issue #179）。
 *
 * <p>在这之前，{@code sendBatchUpdate} 跑在普通 {@code ScheduledThreadPool} 上，却在那里直接调
 * {@code Bukkit.getWorlds()}、{@code world.getLoadedChunks()}、{@code Bukkit.getOnlinePlayers()}、
 * {@code player.getLocation()}——全是 Paper 明确不支持在异步线程上碰的可变世界状态。
 * 同一个类里的 TPS/CPU 采样早就正确地 hop 到了主线程，说明契约当时就被识别到了，只应用了一半。
 *
 * <p>这里钉住三件事：采样只在主线程发生；异步发送路径读的是快照而不是活的 Bukkit 状态；
 * 以及重构没有改变发给面板的 JSON 形状。最后一条最要紧——面板那边靠这些字段名工作，
 * 而这次改动把整段构造代码搬了家。
 */
@DisplayName("服务器状态采样的线程契约")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServerMonitorSnapshotTest {

    private ServerMock server;
    private ServerMonitorManager manager;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // MockBukkit 默认一个世界都没有，而 worlds 数组正是本次改动搬家的重点之一，
        // 没有世界的话形状断言会变成空转。
        server.addSimpleWorld("world");

        mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        manager = new ServerMonitorManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /** 在一条非主线程上跑给定动作，并把抛出的异常带回来。 */
    private void runOffPrimaryThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                assertThat(Bukkit.isPrimaryThread())
                        .as("这条线程必须不是主线程，否则整个用例失去意义")
                        .isFalse();
                action.run();
            } catch (Exception | AssertionError t) {
                // 精确到这两类：要带回来的是「动作失败」和「线程里的断言没过」，
                // 顺手吞掉 OOM / StackOverflow 只会让失败更难看懂。
                thrown.set(t);
            }
        }, "off-primary-test-thread");
        worker.start();
        worker.join(10_000);
        if (thrown.get() != null) {
            throw new AssertionError("非主线程上的动作抛异常了", thrown.get());
        }
    }

    /** Reads the published snapshot on a thread that is not the main thread, as the v2 telemetry sender does. */
    private ServerMonitorManager.ServerStateSnapshot snapshotOffPrimaryThread() throws InterruptedException {
        AtomicReference<ServerMonitorManager.ServerStateSnapshot> read = new AtomicReference<>();
        runOffPrimaryThread(() -> read.set(manager.currentStateSnapshot()));
        return read.get();
    }

    @Nested
    @DisplayName("采样只在主线程发生")
    class SamplingThreadContract {

        @Test
        @DisplayName("测试线程就是 MockBukkit 认的主线程")
        void testThreadIsPrimary() {
            // 这条是给下面所有用例做的前置断言：如果 MockBukkit 不把测试线程当主线程，
            // 「主线程 / 非主线程」的对比就全部失效，那时候失败的应该是这一条而不是别的。
            assertThat(Bukkit.isPrimaryThread()).isTrue();
        }

        @Test
        @DisplayName("主线程上采样会填充快照")
        void samplingOnPrimaryThreadPopulatesSnapshot() throws InterruptedException {
            server.addPlayer();
            server.addPlayer();

            assertThat(manager.sampleAndPublishState()).isTrue();

            // 从非主线程读取，确保读到的只可能是快照
            assertThat(snapshotOffPrimaryThread().playerCount).isEqualTo(2);
        }

        @Test
        @DisplayName("非主线程上采样被拒绝，并记 SEVERE")
        void samplingOffPrimaryThreadIsRefused() throws InterruptedException {
            server.addPlayer();
            AtomicReference<Boolean> sampled = new AtomicReference<>();

            runOffPrimaryThread(() -> sampled.set(manager.sampleAndPublishState()));

            assertThat(sampled.get()).isFalse();
            verify(mockLogger).log(eq(Level.SEVERE), anyString());
            assertThat(manager.hasSampledState())
                    .as("被拒绝的采样不该留下任何痕迹")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("异步读取的是快照，不是活的 Bukkit 状态")
    class AsyncPathReadsSnapshot {

        @Test
        @DisplayName("采样之后世界变了，异步读取仍然是采样时刻的值")
        void asyncSendReportsSnapshotNotLiveState() throws InterruptedException {
            server.addPlayer();
            manager.sampleAndPublishState();    // 主线程采样：此刻 1 人

            server.addPlayer();
            server.addPlayer();                 // 活的 Bukkit 现在是 3 人，但没有重新采样

            assertThat(snapshotOffPrimaryThread().playerCount)
                    .as("报 3 就说明异步线程直接读了 Bukkit——正是本 issue 要消灭的行为")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("从未采样时，异步读取不会就地去读 Bukkit")
        void asyncSendDoesNotLazilySample() throws InterruptedException {
            server.addPlayer();
            server.addPlayer();
            // 刻意不采样

            ServerMonitorManager.ServerStateSnapshot snapshot = snapshotOffPrimaryThread();
            assertThat(snapshot.playerCount)
                    .as("非主线程上宁可报空快照，也不能去读 Bukkit")
                    .isZero();
            assertThat(snapshot.worlds).isEmpty();
            assertThat(snapshot.onlinePlayers).isEmpty();
        }

        @Test
        @DisplayName("从未采样但当前就在主线程时，就地补采一次")
        void primaryThreadLazilySamplesOnFirstUse() {
            server.addPlayer();
            server.addPlayer();
            // 同样不采样，但这次在主线程上读取

            assertThat(manager.currentStateSnapshot().playerCount)
                    .as("否则连接建立后的第一帧会是一片零")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("快照里的元素形状没有变")
    class PayloadContract {

        @Test
        @DisplayName("worlds 元素字段齐全")
        void worldObjectKeysUnchanged() {
            manager.sampleAndPublishState();

            JsonArray worlds = manager.currentStateSnapshot().worlds;
            assertThat(worlds).as("MockBukkit 默认没有世界的话这条就没在测东西").isNotEmpty();
            assertThat(worlds.get(0).getAsJsonObject().keySet()).containsExactlyInAnyOrder(
                    "name", "environment", "difficulty", "playerCount",
                    "loadedChunks", "pvpEnabled", "spawnLocation");
        }

        @Test
        @DisplayName("onlinePlayers 元素字段齐全")
        void playerObjectKeysUnchanged() {
            server.addPlayer();
            manager.sampleAndPublishState();

            JsonArray players = manager.currentStateSnapshot().onlinePlayers;
            assertThat(players).isNotEmpty();
            assertThat(players.get(0).getAsJsonObject().keySet()).containsExactlyInAnyOrder(
                    "uuid", "name", "world", "x", "y", "z",
                    "health", "maxHealth", "foodLevel", "gameMode", "op");
        }

        @Test
        @DisplayName("playerCount 与 onlinePlayers 长度永远一致")
        void playerCountMatchesPlayerArrayLength() {
            server.addPlayer();
            server.addPlayer();
            server.addPlayer();
            manager.sampleAndPublishState();

            ServerMonitorManager.ServerStateSnapshot snapshot = manager.currentStateSnapshot();
            assertThat(snapshot.playerCount)
                    .as("两者由同一次采样的同一个数组导出，不该有分歧")
                    .isEqualTo(snapshot.onlinePlayers.size())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("插件与世界计数取自同一次采样")
        void metricsCountsComeFromSnapshot() {
            manager.sampleAndPublishState();

            ServerMonitorManager.ServerStateSnapshot snapshot = manager.currentStateSnapshot();
            assertThat(snapshot.worldCount).isEqualTo(Bukkit.getWorlds().size());
            assertThat(snapshot.pluginCount).isEqualTo(Bukkit.getPluginManager().getPlugins().length);
        }
    }

    @Nested
    @DisplayName("采样失败不影响读取")
    class Resilience {

        @Test
        @DisplayName("重复采样是安全的")
        void repeatedSamplingIsSafe() {
            server.addPlayer();
            manager.sampleAndPublishState();
            manager.sampleAndPublishState();
            manager.sampleAndPublishState();

            assertThat(manager.currentStateSnapshot().playerCount).isEqualTo(1);
        }
    }
}
