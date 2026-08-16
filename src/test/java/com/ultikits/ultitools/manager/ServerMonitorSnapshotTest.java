package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

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
@SuppressWarnings({
        "PMD.AvoidAccessibilityAlteration", // 测试需要反射读任务句柄，与仓库其它测试类一致
        // PMD 只认 assert*/fail* 这类方法名，识别不了 AssertJ 的流式断言
        // （doesNotSampleWithoutClient 断的是 assertThatCode(...).doesNotThrowAnyException()）。
        "PMD.JUnitTestsShouldIncludeAssert"
})
class ServerMonitorSnapshotTest {

    private ServerMock server;
    private ServerMonitorManager manager;
    private UltiPanelWebSocketClient mockClient;
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

        mockClient = mock(UltiPanelWebSocketClient.class);
        when(mockClient.isConnected()).thenReturn(true);
        when(mockClient.getServerId()).thenReturn("test-server");

        manager = new ServerMonitorManager();
        manager.setWebSocketClient(mockClient);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stopMonitoring();
        }
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

    /** 抓取发往面板的 server_status 消息里的 data 段。 */
    private JsonObject captureStatusData() {
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockClient, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue().getAsJsonObject("data");
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
        void samplingOnPrimaryThreadPopulatesSnapshot() {
            server.addPlayer();
            server.addPlayer();

            manager.refreshStateSnapshot();

            // 从非主线程发送，确保读到的只可能是快照
            manager.sendServerStatus();
            assertThat(captureStatusData().get("playerCount").getAsInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("非主线程上采样被拒绝，并记 SEVERE")
        void samplingOffPrimaryThreadIsRefused() throws InterruptedException {
            server.addPlayer();

            runOffPrimaryThread(manager::refreshStateSnapshot);

            verify(mockLogger).log(eq(Level.SEVERE), anyString());

            // 快照没被填上：异步读到的仍然是空的那份
            runOffPrimaryThread(manager::sendServerStatus);
            assertThat(captureStatusData().get("playerCount").getAsInt())
                    .as("被拒绝的采样不该留下任何痕迹")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("异步发送读的是快照，不是活的 Bukkit 状态")
    class AsyncPathReadsSnapshot {

        @Test
        @DisplayName("采样之后世界变了，异步发送仍然报采样时刻的值")
        void asyncSendReportsSnapshotNotLiveState() throws InterruptedException {
            server.addPlayer();
            manager.refreshStateSnapshot();     // 主线程采样：此刻 1 人

            server.addPlayer();
            server.addPlayer();                 // 活的 Bukkit 现在是 3 人，但没有重新采样

            runOffPrimaryThread(manager::sendServerStatus);

            assertThat(captureStatusData().get("playerCount").getAsInt())
                    .as("报 3 就说明异步线程直接读了 Bukkit——正是本 issue 要消灭的行为")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("从未采样时，异步发送不会就地去读 Bukkit")
        void asyncSendDoesNotLazilySample() throws InterruptedException {
            server.addPlayer();
            server.addPlayer();
            // 刻意不调 refreshStateSnapshot

            runOffPrimaryThread(manager::sendServerStatus);

            JsonObject data = captureStatusData();
            assertThat(data.get("playerCount").getAsInt())
                    .as("非主线程上宁可报空快照，也不能去读 Bukkit")
                    .isZero();
            assertThat(data.getAsJsonArray("worlds")).isEmpty();
            assertThat(data.getAsJsonArray("onlinePlayers")).isEmpty();
        }

        @Test
        @DisplayName("从未采样但当前就在主线程时，就地补采一次")
        void primaryThreadLazilySamplesOnFirstUse() {
            server.addPlayer();
            server.addPlayer();
            // 同样不调 refreshStateSnapshot，但这次在主线程上发送

            manager.sendServerStatus();

            assertThat(captureStatusData().get("playerCount").getAsInt())
                    .as("否则连接建立后的第一帧会是一片零")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("发给面板的 JSON 形状没有变")
    class PayloadContract {

        @Test
        @DisplayName("status 顶层字段齐全")
        void statusKeysUnchanged() {
            manager.refreshStateSnapshot();
            manager.sendServerStatus();

            assertThat(captureStatusData().keySet()).contains(
                    "playerCount", "maxPlayers", "onlineMode", "serverVersion",
                    "tps", "memory", "cpu", "uptime", "worlds", "onlinePlayers");
        }

        @Test
        @DisplayName("worlds 元素字段齐全")
        void worldObjectKeysUnchanged() {
            manager.refreshStateSnapshot();
            manager.sendServerStatus();

            JsonArray worlds = captureStatusData().getAsJsonArray("worlds");
            assertThat(worlds).as("MockBukkit 默认没有世界的话这条就没在测东西").isNotEmpty();
            assertThat(worlds.get(0).getAsJsonObject().keySet()).containsExactlyInAnyOrder(
                    "name", "environment", "difficulty", "playerCount",
                    "loadedChunks", "pvpEnabled", "spawnLocation");
        }

        @Test
        @DisplayName("onlinePlayers 元素字段齐全")
        void playerObjectKeysUnchanged() {
            server.addPlayer();
            manager.refreshStateSnapshot();
            manager.sendServerStatus();

            JsonArray players = captureStatusData().getAsJsonArray("onlinePlayers");
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
            manager.refreshStateSnapshot();
            manager.sendServerStatus();

            JsonObject data = captureStatusData();
            assertThat(data.get("playerCount").getAsInt())
                    .as("两者由同一次采样的同一个数组导出，不该有分歧")
                    .isEqualTo(data.getAsJsonArray("onlinePlayers").size())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("metrics 里的插件与世界计数取自快照")
        void metricsCountsComeFromSnapshot() {
            manager.refreshStateSnapshot();
            manager.sendMetricsData();

            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockClient, atLeastOnce()).sendMessage(captor.capture());
            JsonObject pluginUsage = captor.getValue()
                    .getAsJsonObject("data").getAsJsonObject("pluginUsage");

            assertThat(pluginUsage.get("loadedWorlds").getAsInt())
                    .isEqualTo(Bukkit.getWorlds().size());
            assertThat(pluginUsage.get("enabledPlugins").getAsInt())
                    .isEqualTo(Bukkit.getPluginManager().getPlugins().length);
        }
    }

    @Nested
    @DisplayName("断连与停止监控时不做无谓的主线程工作（#265 评审）")
    class NoWorkWhenIdle {

        @Test
        @DisplayName("未连接时不采样")
        void doesNotSampleWhileDisconnected() {
            // 修复前 sendBatchUpdate 在 !isConnected 时是先返回、再遍历的，所以永久断连的
            // 服务器一次遍历都不做。采样搬到主线程之后必须保住这个性质，否则断连反而变成了
            // 每 5 秒白遍历一遍所有世界和区块——而且是在主线程上。
            server.addPlayer();
            when(mockClient.isConnected()).thenReturn(false);

            manager.refreshStateSnapshot();

            // 直接看快照有没有被填，而不是去看发出去的 JSON——后者会撞上「主线程惰性补采」
            // 那条刻意保留的路径（见 primaryThreadLazilySamplesOnFirstUse），测错东西。
            assertThat(manager.hasSampledState())
                    .as("断连期间不该留下任何采样结果")
                    .isFalse();
        }

        @Test
        @DisplayName("客户端为 null 时不采样也不抛异常")
        void doesNotSampleWithoutClient() {
            ServerMonitorManager fresh = new ServerMonitorManager();
            server.addPlayer();

            org.assertj.core.api.Assertions.assertThatCode(fresh::refreshStateSnapshot)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("断连时线程契约的闸仍然有效")
        void threadGuardStillFiresWhileDisconnected() throws InterruptedException {
            // 线程检查排在连接判断之前：否则这道防御闸在最需要它的场景
            //（连不上、于是走各种异常路径）反而是哑的。
            when(mockClient.isConnected()).thenReturn(false);

            runOffPrimaryThread(manager::refreshStateSnapshot);

            verify(mockLogger).log(eq(Level.SEVERE), anyString());
        }

        @Test
        @DisplayName("stopMonitoring 取消两个主线程定时任务")
        void stopMonitoringCancelsMainThreadTasks() throws Exception {
            manager.startMonitoring();

            java.lang.reflect.Field snapshotField =
                    ServerMonitorManager.class.getDeclaredField("snapshotTask");
            java.lang.reflect.Field tpsField =
                    ServerMonitorManager.class.getDeclaredField("tpsTask");
            snapshotField.setAccessible(true);
            tpsField.setAccessible(true);
            assertThat(snapshotField.get(manager)).as("启动后应当持有任务句柄").isNotNull();
            assertThat(tpsField.get(manager)).isNotNull();

            manager.stopMonitoring();

            assertThat(snapshotField.get(manager))
                    .as("不取消的话，「停止监控」会变成「不再发送、但照样每 5 秒遍历所有世界」")
                    .isNull();
            assertThat(tpsField.get(manager)).isNull();
        }
    }

    @Nested
    @DisplayName("采样失败不影响发送")
    class Resilience {

        @Test
        @DisplayName("重复采样是安全的")
        void repeatedSamplingIsSafe() {
            server.addPlayer();
            manager.refreshStateSnapshot();
            manager.refreshStateSnapshot();
            manager.refreshStateSnapshot();

            manager.sendServerStatus();
            assertThat(captureStatusData().get("playerCount").getAsInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("WebSocket 未连接时不发送")
        void doesNotSendWhenDisconnected() {
            when(mockClient.isConnected()).thenReturn(false);
            manager.refreshStateSnapshot();

            manager.sendServerStatus();

            verify(mockClient, org.mockito.Mockito.never()).sendMessage(any(JsonObject.class));
        }
    }
}
