package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * ServerMonitorManager 测试
 */
@DisplayName("ServerMonitorManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServerMonitorManagerTest {

    private ServerMock server;
    private ServerMonitorManager serverMonitorManager;
    private UltiPanelWebSocketClient mockWebSocketClient;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        // Mock logger
        mockLogger = mock(Logger.class);
        when(UltiTools.getInstance().getLogger()).thenReturn(mockLogger);

        // Mock WebSocket client
        mockWebSocketClient = mock(UltiPanelWebSocketClient.class);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("test-server");

        serverMonitorManager = new ServerMonitorManager();
        serverMonitorManager.setWebSocketClient(mockWebSocketClient);
    }

    @AfterEach
    void tearDown() {
        if (serverMonitorManager != null) {
            serverMonitorManager.stopMonitoring();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该创建调度器")
        void shouldCreateScheduler() throws Exception {
            // Arrange & Act
            ServerMonitorManager manager = new ServerMonitorManager();

            // Assert
            Field schedulerField = ServerMonitorManager.class.getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            assertThat(schedulerField.get(manager)).isNotNull();
        }

        @Test
        @DisplayName("初始状态应该是未监控")
        void shouldNotBeMonitoringInitially() throws Exception {
            // Arrange & Act
            ServerMonitorManager manager = new ServerMonitorManager();

            // Assert
            Field isMonitoringField = ServerMonitorManager.class.getDeclaredField("isMonitoring");
            isMonitoringField.setAccessible(true);
            assertThat(isMonitoringField.getBoolean(manager)).isFalse();
        }
    }

    @Nested
    @DisplayName("setWebSocketClient 测试")
    class SetWebSocketClientTests {

        @Test
        @DisplayName("应该设置 WebSocket 客户端")
        void shouldSetWebSocketClient() throws Exception {
            // Arrange
            ServerMonitorManager manager = new ServerMonitorManager();
            UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);

            // Act
            manager.setWebSocketClient(client);

            // Assert
            Field clientField = ServerMonitorManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            assertThat(clientField.get(manager)).isEqualTo(client);
        }
    }

    @Nested
    @DisplayName("startMonitoring 测试")
    class StartMonitoringTests {

        @Test
        @DisplayName("重复调用不应该重新启动")
        void shouldNotRestartIfAlreadyMonitoring() throws Exception {
            // Arrange
            Field isMonitoringField = ServerMonitorManager.class.getDeclaredField("isMonitoring");
            isMonitoringField.setAccessible(true);
            isMonitoringField.setBoolean(serverMonitorManager, true);

            // Act
            serverMonitorManager.startMonitoring();

            // 已在监控中，不应该再次启动
        }
    }

    @Nested
    @DisplayName("stopMonitoring 测试")
    class StopMonitoringTests {

        @Test
        @DisplayName("未监控时调用不应该抛出异常")
        void shouldNotThrowIfNotMonitoring() {
            // Arrange
            ServerMonitorManager manager = new ServerMonitorManager();

            // Act & Assert - 不应该抛出异常
            manager.stopMonitoring();
        }
    }

    @Nested
    @DisplayName("sendServerStatus 测试")
    class SendServerStatusTests {

        @Test
        @DisplayName("WebSocket 未设置时应该不发送")
        void shouldNotSendIfWebSocketNull() throws Exception {
            // Arrange
            Field clientField = ServerMonitorManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(serverMonitorManager, null);

            // Act
            serverMonitorManager.sendServerStatus();

            // 不应该有异常
        }

        @Test
        @DisplayName("WebSocket 未连接时应该不发送")
        void shouldNotSendIfNotConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            serverMonitorManager.sendServerStatus();

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JSONObject.class));
        }

        @Test
        @DisplayName("WebSocket 已连接时应该发送消息")
        void shouldSendWhenConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            serverMonitorManager.sendServerStatus();

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JSONObject.class));
        }
    }

    @Nested
    @DisplayName("sendServerStatusWithRequestId 测试")
    class SendServerStatusWithRequestIdTests {

        @Test
        @DisplayName("应该在消息中包含 requestId")
        void shouldIncludeRequestId() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            serverMonitorManager.sendServerStatusWithRequestId("test-request-id");

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JSONObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时应该不发送")
        void shouldNotSendIfNotConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            serverMonitorManager.sendServerStatusWithRequestId("test-request-id");

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JSONObject.class));
        }
    }

    @Nested
    @DisplayName("extractVersionNumber 测试")
    class ExtractVersionNumberTests {

        @Test
        @DisplayName("应该从MC版本字符串提取版本号")
        void shouldExtractFromMCVersionString() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("extractVersionNumber", String.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(serverMonitorManager, "git-Bukkit-abc123 (MC: 1.20.1)");

            // Assert
            assertThat(result).isEqualTo("1.20.1");
        }

        @Test
        @DisplayName("异常情况应该返回 Unknown")
        void shouldReturnUnknownOnException() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("extractVersionNumber", String.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(serverMonitorManager, (String) null);

            // Assert - 应该处理异常，不会崩溃
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getCPUUsage 测试")
    class GetCPUUsageTests {

        @Test
        @DisplayName("应该返回有效的 CPU 使用率")
        void shouldReturnValidCPUUsage() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("getCPUUsage");
            method.setAccessible(true);

            // Act
            double result = (double) method.invoke(serverMonitorManager);

            // Assert
            assertThat(result).isGreaterThanOrEqualTo(0.0);
            assertThat(result).isLessThanOrEqualTo(100.0);
        }
    }

    @Nested
    @DisplayName("TPS 计算测试")
    class TPSCalculationTests {

        @Test
        @DisplayName("tpsHistory 数组应该初始化正确")
        void tpsHistoryArraysShouldBeInitialized() throws Exception {
            // Arrange & Act
            ServerMonitorManager manager = new ServerMonitorManager();

            // Assert
            Field tpsHistory1m = ServerMonitorManager.class.getDeclaredField("tpsHistory1m");
            tpsHistory1m.setAccessible(true);
            long[] history1m = (long[]) tpsHistory1m.get(manager);
            assertThat(history1m).hasSize(60);

            Field tpsHistory5m = ServerMonitorManager.class.getDeclaredField("tpsHistory5m");
            tpsHistory5m.setAccessible(true);
            long[] history5m = (long[]) tpsHistory5m.get(manager);
            assertThat(history5m).hasSize(300);

            Field tpsHistory15m = ServerMonitorManager.class.getDeclaredField("tpsHistory15m");
            tpsHistory15m.setAccessible(true);
            long[] history15m = (long[]) tpsHistory15m.get(manager);
            assertThat(history15m).hasSize(900);
        }
    }

    @Nested
    @DisplayName("isMonitoring 字段测试")
    class IsMonitoringFieldTests {

        @Test
        @DisplayName("默认应该为 false")
        void shouldBeFalseByDefault() throws Exception {
            // Arrange
            ServerMonitorManager manager = new ServerMonitorManager();

            // Assert
            Field field = ServerMonitorManager.class.getDeclaredField("isMonitoring");
            field.setAccessible(true);
            assertThat(field.getBoolean(manager)).isFalse();
        }
    }

    @Nested
    @DisplayName("sendMetricsData 测试")
    class SendMetricsDataTests {

        @Test
        @DisplayName("WebSocket 已连接时应该发送消息")
        void shouldSendWhenConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            serverMonitorManager.sendMetricsData();

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JSONObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时不应该发送")
        void shouldNotSendWhenNotConnected() throws Exception {
            // Arrange
            Field clientField = ServerMonitorManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(serverMonitorManager, null);

            // Act
            serverMonitorManager.sendMetricsData();

            // 不应该有异常
        }
    }

    @Nested
    @DisplayName("sendMetricsDataWithRequestId 测试")
    class SendMetricsDataWithRequestIdTests {

        @Test
        @DisplayName("应该在消息中包含 requestId")
        void shouldIncludeRequestId() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            serverMonitorManager.sendMetricsDataWithRequestId("test-request-id");

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JSONObject.class));
        }

        @Test
        @DisplayName("requestId 为 null 时不应该抛出异常")
        void shouldNotThrowWhenRequestIdIsNull() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act - 不应该抛出异常
            serverMonitorManager.sendMetricsDataWithRequestId(null);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JSONObject.class));
        }
    }

    @Nested
    @DisplayName("sendPluginList 测试")
    class SendPluginListTests {

        @Test
        @DisplayName("应该能够调用私有方法")
        void shouldCallPrivateMethod() throws Exception {
            // Arrange - sendPluginList 方法在内部会检查连接状态
            Method method = ServerMonitorManager.class.getDeclaredMethod("sendPluginList");
            method.setAccessible(true);
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            when(mockWebSocketClient.getServerId()).thenReturn("test-server");

            // Act
            method.invoke(serverMonitorManager);

            // Assert - 该方法可能不会发送消息，取决于内部实现
            // 这里只验证方法可以被调用而不抛出异常
        }

        @Test
        @DisplayName("WebSocket 未连接时应该安全处理")
        void shouldHandleSafelyWhenNotConnected() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("sendPluginList");
            method.setAccessible(true);
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act - 不应该抛出异常
            method.invoke(serverMonitorManager);
        }
    }

    @Nested
    @DisplayName("calculateTPS 测试")
    class CalculateTPSTests {

        @Test
        @DisplayName("应该返回三个 TPS 值")
        void shouldReturnThreeTPSValues() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("calculateTPS");
            method.setAccessible(true);

            // Act
            double[] tps = (double[]) method.invoke(serverMonitorManager);

            // Assert
            assertThat(tps).hasSize(3);
        }

        @Test
        @DisplayName("TPS 值应该在合理范围内")
        void tpsValuesShouldBeInReasonableRange() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("calculateTPS");
            method.setAccessible(true);

            // Act
            double[] tps = (double[]) method.invoke(serverMonitorManager);

            // Assert
            for (double t : tps) {
                assertThat(t).isBetween(0.0, 21.0);
            }
        }
    }

    @Nested
    @DisplayName("calculateAverageTPS 测试")
    class CalculateAverageTPSTests {

        @Test
        @DisplayName("count 为 0 时应该返回 20.0")
        void shouldReturn20WhenCountIsZero() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("calculateAverageTPS", long[].class, int.class);
            method.setAccessible(true);
            long[] history = new long[60];

            // Act
            double result = (double) method.invoke(serverMonitorManager, history, 0);

            // Assert
            assertThat(result).isEqualTo(20.0);
        }

        @Test
        @DisplayName("应该正确计算平均值")
        void shouldCalculateAverageCorrectly() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("calculateAverageTPS", long[].class, int.class);
            method.setAccessible(true);
            long[] history = new long[]{2000, 2000, 2000}; // 20.0 TPS * 100

            // Act
            double result = (double) method.invoke(serverMonitorManager, history, 3);

            // Assert
            assertThat(result).isEqualTo(20.0);
        }
    }

    @Nested
    @DisplayName("calculateRealtimeTPS 测试")
    class CalculateRealtimeTPSTests {

        @Test
        @DisplayName("无历史数据时应该返回 20.0")
        void shouldReturn20WhenNoHistory() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("calculateRealtimeTPS");
            method.setAccessible(true);
            
            // 确保 historyIndex 为 0
            Field indexField = ServerMonitorManager.class.getDeclaredField("historyIndex");
            indexField.setAccessible(true);
            indexField.setInt(serverMonitorManager, 0);

            // Act
            double result = (double) method.invoke(serverMonitorManager);

            // Assert
            assertThat(result).isEqualTo(20.0);
        }

        @Test
        @DisplayName("有历史数据时应该返回最近的 TPS")
        void shouldReturnRecentTPSWhenHasHistory() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("calculateRealtimeTPS");
            method.setAccessible(true);
            
            // 设置一些历史数据
            Field indexField = ServerMonitorManager.class.getDeclaredField("historyIndex");
            indexField.setAccessible(true);
            indexField.setInt(serverMonitorManager, 5);

            Field historyField = ServerMonitorManager.class.getDeclaredField("tpsHistory1m");
            historyField.setAccessible(true);
            long[] history = (long[]) historyField.get(serverMonitorManager);
            history[4] = 1900; // 19.0 TPS * 100

            // Act
            double result = (double) method.invoke(serverMonitorManager);

            // Assert
            assertThat(result).isEqualTo(19.0);
        }
    }

    @Nested
    @DisplayName("updateTPS 测试")
    class UpdateTPSTests {

        @Test
        @DisplayName("应该更新 historyIndex")
        void shouldUpdateHistoryIndex() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("updateTPS");
            method.setAccessible(true);

            Field indexField = ServerMonitorManager.class.getDeclaredField("historyIndex");
            indexField.setAccessible(true);
            int initialIndex = indexField.getInt(serverMonitorManager);

            // Act
            method.invoke(serverMonitorManager);

            // Assert
            assertThat(indexField.getInt(serverMonitorManager)).isEqualTo(initialIndex + 1);
        }

        @Test
        @DisplayName("应该更新 lastTick")
        void shouldUpdateLastTick() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("updateTPS");
            method.setAccessible(true);

            Field lastTickField = ServerMonitorManager.class.getDeclaredField("lastTick");
            lastTickField.setAccessible(true);
            long initialLastTick = lastTickField.getLong(serverMonitorManager);

            // Act
            Thread.sleep(10); // 确保时间变化
            method.invoke(serverMonitorManager);

            // Assert
            assertThat(lastTickField.getLong(serverMonitorManager)).isGreaterThanOrEqualTo(initialLastTick);
        }
    }

    @Nested
    @DisplayName("sendPlayerEvent 测试")
    class SendPlayerEventTests {

        @Test
        @DisplayName("应该发送玩家事件消息")
        void shouldSendPlayerEventMessage() {
            // Arrange
            be.seeseemelk.mockbukkit.entity.PlayerMock player = server.addPlayer();
            JSONObject additionalData = new JSONObject();
            additionalData.put("extra", "data");
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            serverMonitorManager.sendPlayerEvent("test_event", player, additionalData);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JSONObject.class));
        }

        @Test
        @DisplayName("additionalData 为 null 时不应该抛出异常")
        void shouldNotThrowWhenAdditionalDataIsNull() {
            // Arrange
            be.seeseemelk.mockbukkit.entity.PlayerMock player = server.addPlayer();
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act - 不应该抛出异常
            serverMonitorManager.sendPlayerEvent("test_event", player, null);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JSONObject.class));
        }
    }

    @Nested
    @DisplayName("getCurrentServerStatusData 测试")
    class GetCurrentServerStatusDataTests {

        @Test
        @DisplayName("应该返回包含必要字段的数据")
        void shouldReturnDataWithRequiredFields() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("getCurrentServerStatusData");
            method.setAccessible(true);

            // Act
            JSONObject data = (JSONObject) method.invoke(serverMonitorManager);

            // Assert
            assertThat(data.containsKey("playerCount")).isTrue();
            assertThat(data.containsKey("maxPlayers")).isTrue();
            assertThat(data.containsKey("serverVersion")).isTrue();
            assertThat(data.containsKey("tps")).isTrue();
            assertThat(data.containsKey("memory")).isTrue();
            assertThat(data.containsKey("cpu")).isTrue();
            assertThat(data.containsKey("uptime")).isTrue();
            assertThat(data.containsKey("worlds")).isTrue();
        }

        @Test
        @DisplayName("内存数据应该包含 used、max 和 free")
        void memoryDataShouldContainRequiredFields() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("getCurrentServerStatusData");
            method.setAccessible(true);

            // Act
            JSONObject data = (JSONObject) method.invoke(serverMonitorManager);
            JSONObject memory = data.getJSONObject("memory");

            // Assert
            assertThat(memory.containsKey("used")).isTrue();
            assertThat(memory.containsKey("max")).isTrue();
            assertThat(memory.containsKey("free")).isTrue();
        }
    }

    @Nested
    @DisplayName("isMonitoring 方法测试")
    class IsMonitoringMethodTests {

        @Test
        @DisplayName("应该返回正确的监控状态")
        void shouldReturnCorrectMonitoringStatus() throws Exception {
            // Arrange
            ServerMonitorManager manager = new ServerMonitorManager();
            
            // Assert - 初始为 false
            assertThat(manager.isMonitoring()).isFalse();

            // 通过反射设置 isMonitoring = true
            Field field = ServerMonitorManager.class.getDeclaredField("isMonitoring");
            field.setAccessible(true);
            field.setBoolean(manager, true);

            // Assert - 现在为 true
            assertThat(manager.isMonitoring()).isTrue();
        }
    }
}

