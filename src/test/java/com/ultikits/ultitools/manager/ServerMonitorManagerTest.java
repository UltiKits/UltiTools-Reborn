package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * ServerMonitorManager 测试
 */
@DisplayName("ServerMonitorManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
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

        // Mock logger
        mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

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

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> serverMonitorManager.startMonitoring());
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
            assertDoesNotThrow(() -> manager.stopMonitoring());
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

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> serverMonitorManager.sendServerStatus());
        }

        @Test
        @DisplayName("WebSocket 未连接时应该不发送")
        void shouldNotSendIfNotConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            serverMonitorManager.sendServerStatus();

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 已连接时应该发送消息")
        void shouldSendWhenConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            serverMonitorManager.sendServerStatus();

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
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
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时应该不发送")
        void shouldNotSendIfNotConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            serverMonitorManager.sendServerStatusWithRequestId("test-request-id");

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
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
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时不应该发送")
        void shouldNotSendWhenNotConnected() throws Exception {
            // Arrange
            Field clientField = ServerMonitorManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(serverMonitorManager, null);

            // Act & Assert - 不应该有异常
            assertDoesNotThrow(() -> serverMonitorManager.sendMetricsData());
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
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("requestId 为 null 时不应该抛出异常")
        void shouldNotThrowWhenRequestIdIsNull() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act - 不应该抛出异常
            serverMonitorManager.sendMetricsDataWithRequestId(null);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
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
            Method method = ServerMonitorManager.class.getDeclaredMethod("updateTpsAndCpu");
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
            Method method = ServerMonitorManager.class.getDeclaredMethod("updateTpsAndCpu");
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
            org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("extra", "data");
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            serverMonitorManager.sendPlayerEvent("test_event", player, additionalData);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("additionalData 为 null 时不应该抛出异常")
        void shouldNotThrowWhenAdditionalDataIsNull() {
            // Arrange
            org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act - 不应该抛出异常
            serverMonitorManager.sendPlayerEvent("test_event", player, null);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
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
            JsonObject data = (JsonObject) method.invoke(serverMonitorManager);

            // Assert
            assertThat(data.has("playerCount")).isTrue();
            assertThat(data.has("maxPlayers")).isTrue();
            assertThat(data.has("serverVersion")).isTrue();
            assertThat(data.has("tps")).isTrue();
            assertThat(data.has("memory")).isTrue();
            assertThat(data.has("cpu")).isTrue();
            assertThat(data.has("uptime")).isTrue();
            assertThat(data.has("worlds")).isTrue();
        }

        @Test
        @DisplayName("内存数据应该包含 used、max 和 free")
        void memoryDataShouldContainRequiredFields() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("getCurrentServerStatusData");
            method.setAccessible(true);

            // Act
            JsonObject data = (JsonObject) method.invoke(serverMonitorManager);
            JsonObject memory = data.getAsJsonObject("memory");

            // Assert
            assertThat(memory.has("used")).isTrue();
            assertThat(memory.has("max")).isTrue();
            assertThat(memory.has("free")).isTrue();
        }

        @Test
        @DisplayName("getCurrentServerStatusData should include onlinePlayers array")
        void shouldIncludeOnlinePlayersArray() throws Exception {
            // Add a player to MockBukkit server
            server.addPlayer("Steve");

            Method method = ServerMonitorManager.class.getDeclaredMethod("getCurrentServerStatusData");
            method.setAccessible(true);
            JsonObject data = (JsonObject) method.invoke(serverMonitorManager);

            assertThat(data.has("onlinePlayers")).isTrue();
            JsonArray onlinePlayers = data.getAsJsonArray("onlinePlayers");
            assertThat(onlinePlayers.size()).isEqualTo(1);

            JsonObject playerObj = onlinePlayers.get(0).getAsJsonObject();
            assertThat(playerObj.get("name").getAsString()).isEqualTo("Steve");
            assertThat(playerObj.has("uuid")).isTrue();
            assertThat(playerObj.has("world")).isTrue();
            assertThat(playerObj.has("health")).isTrue();
            assertThat(playerObj.has("maxHealth")).isTrue();
            assertThat(playerObj.has("gameMode")).isTrue();
            assertThat(playerObj.has("x")).isTrue();
            assertThat(playerObj.has("y")).isTrue();
            assertThat(playerObj.has("z")).isTrue();
            assertThat(playerObj.has("op")).isTrue();
            assertThat(playerObj.has("foodLevel")).isTrue();
        }

        @Test
        @DisplayName("getCurrentServerStatusData should include enriched world objects")
        void shouldIncludeEnrichedWorldObjects() throws Exception {
            server.addSimpleWorld("test_world");

            Method method = ServerMonitorManager.class.getDeclaredMethod("getCurrentServerStatusData");
            method.setAccessible(true);
            JsonObject data = (JsonObject) method.invoke(serverMonitorManager);

            JsonArray worlds = data.getAsJsonArray("worlds");
            assertThat(worlds.size()).isGreaterThan(0);

            JsonObject world = worlds.get(0).getAsJsonObject();
            assertThat(world.get("name").getAsString()).isNotEmpty();
            assertThat(world.has("environment")).isTrue();
            assertThat(world.has("difficulty")).isTrue();
            assertThat(world.has("pvpEnabled")).isTrue();
            assertThat(world.has("loadedChunks")).isTrue();
            assertThat(world.has("playerCount")).isTrue();
            assertThat(world.has("spawnLocation")).isTrue();

            JsonObject spawnLoc = world.getAsJsonObject("spawnLocation");
            assertThat(spawnLoc.has("x")).isTrue();
            assertThat(spawnLoc.has("y")).isTrue();
            assertThat(spawnLoc.has("z")).isTrue();
        }

        @Test
        @DisplayName("onlinePlayers should be empty when no players online")
        void shouldReturnEmptyPlayersWhenNoneOnline() throws Exception {
            Method method = ServerMonitorManager.class.getDeclaredMethod("getCurrentServerStatusData");
            method.setAccessible(true);
            JsonObject data = (JsonObject) method.invoke(serverMonitorManager);

            assertThat(data.has("onlinePlayers")).isTrue();
            JsonArray onlinePlayers = data.getAsJsonArray("onlinePlayers");
            assertThat(onlinePlayers.size()).isEqualTo(0);
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

    @Nested
    @DisplayName("startMonitoring 完整测试")
    class StartMonitoringFullTests {

        @Test
        @DisplayName("startMonitoring 应该设置 isMonitoring 为 true")
        void shouldSetIsMonitoringToTrue() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            // Act
            serverMonitorManager.startMonitoring();
            
            // Assert
            assertThat(serverMonitorManager.isMonitoring()).isTrue();
        }

        @Test
        @DisplayName("startMonitoring 应该记录日志")
        void shouldLogStartMessage() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            // Act
            serverMonitorManager.startMonitoring();
            
            // Assert
            verify(mockLogger).log(any(java.util.logging.Level.class), org.mockito.ArgumentMatchers.contains("启动"));
        }
    }

    @Nested
    @DisplayName("stopMonitoring 完整测试")
    class StopMonitoringFullTests {

        @Test
        @DisplayName("stopMonitoring 应该设置 isMonitoring 为 false")
        void shouldSetIsMonitoringToFalse() throws Exception {
            // Arrange
            Field field = ServerMonitorManager.class.getDeclaredField("isMonitoring");
            field.setAccessible(true);
            field.setBoolean(serverMonitorManager, true);
            
            // Act
            serverMonitorManager.stopMonitoring();
            
            // Assert
            assertThat(serverMonitorManager.isMonitoring()).isFalse();
        }

        @Test
        @DisplayName("stopMonitoring 应该记录日志")
        void shouldLogStopMessage() throws Exception {
            // Arrange - 先设置为监控中
            Field field = ServerMonitorManager.class.getDeclaredField("isMonitoring");
            field.setAccessible(true);
            field.setBoolean(serverMonitorManager, true);
            
            // Act
            serverMonitorManager.stopMonitoring();
            
            // Assert
            verify(mockLogger).log(any(java.util.logging.Level.class), org.mockito.ArgumentMatchers.contains("停止"));
        }
    }

    @Nested
    @DisplayName("sendServerStatus 异常测试")
    class SendServerStatusExceptionTests {

        @Test
        @DisplayName("异常时应该记录警告日志")
        void shouldLogWarningOnException() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            org.mockito.Mockito.doThrow(new RuntimeException("Test exception"))
                .when(mockWebSocketClient).sendMessage(any(JsonObject.class));
            
            // Act
            serverMonitorManager.sendServerStatus();
            
            // Assert - 应该记录警告
            verify(mockLogger).log(any(java.util.logging.Level.class), org.mockito.ArgumentMatchers.contains("发送服务器状态失败"), any(Throwable.class));
        }
    }

    @Nested
    @DisplayName("updateTPS 扩展测试")
    class UpdateTPSExtendedTests {

        @Test
        @DisplayName("updateTPS 应该更新历史记录")
        void shouldUpdateHistory() throws Exception {
            // Arrange
            Method method = ServerMonitorManager.class.getDeclaredMethod("updateTpsAndCpu");
            method.setAccessible(true);

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> method.invoke(serverMonitorManager));
        }
    }

    @Nested
    @DisplayName("sendBatchUpdate 的 logs 能力网关")
    class SendBatchUpdateCapabilityTests {

        private LogStreamManager mockLogStreamManagerForBatch;
        private ErrorReportCollector mockErrorReportCollectorForBatch;

        @BeforeEach
        void setUpCapabilityGate() {
            mockLogStreamManagerForBatch = mock(LogStreamManager.class);
            mockErrorReportCollectorForBatch = mock(ErrorReportCollector.class);
            com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
                lenient().when(ultiTools.getLogStreamManager()).thenReturn(mockLogStreamManagerForBatch);
                lenient().when(ultiTools.getErrorReportCollector()).thenReturn(mockErrorReportCollectorForBatch);
            });
            // drainErrors 返回 Gson 的 JsonArray，不是 java.util.Collection，Mockito 的
            // RETURNS_DEFAULTS 不会替它兜底成空数组——不预先打桩，未显式关心 errors 的用例会在
            // sendBatchUpdate 里对 null 调 .size() 直接 NPE。
            lenient().when(mockErrorReportCollectorForBatch.drainErrors(org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new JsonArray());
        }

        private YamlConfiguration configWith(String path, boolean value) {
            YamlConfiguration config = new YamlConfiguration();
            config.set(path, value);
            return config;
        }

        private void invokeMaybeSendLogsOnly(ServerMonitorManager manager) throws Exception {
            Method method = ServerMonitorManager.class.getDeclaredMethod("maybeSendLogsOnly");
            method.setAccessible(true);
            method.invoke(manager);
        }

        private void invokeSendBatchUpdate(ServerMonitorManager manager) throws Exception {
            Method method = ServerMonitorManager.class.getDeclaredMethod("sendBatchUpdate");
            method.setAccessible(true);
            method.invoke(manager);
        }

        /** 反射读队列长度，与 CloudReconnectStateMachineTest 的 queueSizeOf 同一手法。 */
        private int queueSizeOf(UltiPanelLogTransmitter transmitter) throws Exception {
            Field queueField = UltiPanelLogTransmitter.class.getDeclaredField("logQueue");
            queueField.setAccessible(true);
            return ((Collection<?>) queueField.get(transmitter)).size();
        }

        /** Gate-2 P1 tests: reflectively backdate lastLogFlushMs to simulate elapsed time deterministically. */
        private void setLastLogFlushMs(ServerMonitorManager manager, long value) throws Exception {
            Field field = ServerMonitorManager.class.getDeclaredField("lastLogFlushMs");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) field.get(manager)).set(value);
        }

        @Test
        @DisplayName("logs 禁用时：batch_update 无 logs 成员，仍带 status/metrics，传输器队列未被排空")
        void logsDisabledNoLogsMemberAndQueueUnchanged() throws Exception {
            when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith(Capability.LOGS.getConfigPath(), false));

            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.info("queued line", "test");
                int queueSizeBefore = queueSizeOf(transmitter);
                assertThat(queueSizeBefore).as("前置条件：队列里得真有东西，否则本用例是空的").isPositive();

                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);

                invokeSendBatchUpdate(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject data = sent.getValue().getAsJsonObject("data");

                assertThat(data.has("logs")).as("logs 禁用时不应带 logs 成员").isFalse();
                assertThat(data.has("status")).as("logs 开关不应影响 status").isTrue();
                assertThat(data.has("metrics")).as("logs 开关不应影响 metrics").isTrue();
                assertThat(queueSizeOf(transmitter))
                        .as("排空前置的门必须挡在 drainQueue 之前，队列不能被动过")
                        .isEqualTo(queueSizeBefore);
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("logs 禁用时：errors 排空仍照常进行，不受任何 Capability 影响（D-07）")
        void errorsDrainStillOccursWithLogsDisabled() throws Exception {
            when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith(Capability.LOGS.getConfigPath(), false));

            JsonArray errors = new JsonArray();
            JsonObject errorEntry = new JsonObject();
            errorEntry.addProperty("message", "boom");
            errors.add(errorEntry);
            when(mockErrorReportCollectorForBatch.drainErrors(10)).thenReturn(errors);

            invokeSendBatchUpdate(serverMonitorManager);

            ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockWebSocketClient).sendMessage(sent.capture());
            JsonObject data = sent.getValue().getAsJsonObject("data");

            assertThat(data.has("errors")).as("errors 只受 error-reporting.enabled 控制，不受 LOGS 影响").isTrue();
        }

        @Test
        @DisplayName("logs 启用时：batch_update 带 logs 成员，与今天行为一致")
        void logsEnabledStillCarriesLogsMember() throws Exception {
            // LOGS 出厂默认即为开启（D-08），空配置已经够了。
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.info("queued line", "test");
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);

                invokeSendBatchUpdate(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject data = sent.getValue().getAsJsonObject("data");

                assertThat(data.has("logs")).as("logs 开启且队列非空时应带 logs 成员").isTrue();
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 P1: interval 大于 5 秒时（如 15000ms），logs 排空遵循传输器自己配置的 interval，而不是每 5 秒 tick 都排空")
        void logsRespectTheTransmittersConfiguredIntervalNotTheFixedFiveSecondTick() throws Exception {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.setIntervalMs(15000); // longer than the hardcoded 5s batch_update tick
                transmitter.info("queued line", "test");
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);

                // A prior tick flushed 5 seconds ago -- well under the configured 15-second interval.
                setLastLogFlushMs(serverMonitorManager, System.currentTimeMillis() - 5000);

                invokeSendBatchUpdate(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject data = sent.getValue().getAsJsonObject("data");

                assertThat(data.has("logs"))
                        .as("only 5 seconds have passed against a configured 15-second interval")
                        .isFalse();
                assertThat(queueSizeOf(transmitter))
                        .as("the queued line must still be waiting, not silently dropped")
                        .isEqualTo(1);
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 P1 对照：一旦经过完整的已配置 interval，logs 排空照常发生")
        void logsFlushOnceTheConfiguredIntervalHasElapsed() throws Exception {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.setIntervalMs(15000);
                transmitter.info("queued line", "test");
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);

                // A prior tick flushed 16 seconds ago -- past the configured 15-second interval.
                setLastLogFlushMs(serverMonitorManager, System.currentTimeMillis() - 16000);

                invokeSendBatchUpdate(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject data = sent.getValue().getAsJsonObject("data");

                assertThat(data.has("logs")).as("16 seconds have passed, past the 15-second interval").isTrue();
                assertThat(queueSizeOf(transmitter)).as("the queue was drained").isZero();
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 round 5: sendBatchUpdate 排空时使用 transmitter 自己配置的 batchSize，而不是硬编码的 50")
        void sendBatchUpdateDrainsUpToTheConfiguredBatchSizeNotAHardcodedFifty() throws Exception {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                // External drain mode is what monitoring actually enables (round 4's fix) --
                // without it, addToBatch's own size-threshold send would drain the queue itself
                // before sendBatchUpdate ever runs.
                transmitter.setExternalDrainMode(true);
                transmitter.setBatchSize(3);
                for (int i = 0; i < 5; i++) {
                    transmitter.info("line-" + i, "test");
                }
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);

                invokeSendBatchUpdate(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject data = sent.getValue().getAsJsonObject("data");
                assertThat(data.getAsJsonArray("logs").size())
                        .as("must drain exactly the configured batchSize (3), not a hardcoded 50")
                        .isEqualTo(3);
                assertThat(queueSizeOf(transmitter))
                        .as("2 of the 5 queued lines must still be waiting -- proves the cap actually applied")
                        .isEqualTo(2);
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 round 5: maybeSendLogsOnly 排空时同样使用 transmitter 自己配置的 batchSize")
        void maybeSendLogsOnlyDrainsUpToTheConfiguredBatchSizeNotAHardcodedFifty() throws Exception {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.setExternalDrainMode(true);
                transmitter.setBatchSize(3);
                for (int i = 0; i < 5; i++) {
                    transmitter.info("line-" + i, "test");
                }
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);

                invokeMaybeSendLogsOnly(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject data = sent.getValue().getAsJsonObject("data");
                assertThat(data.getAsJsonArray("logs").size()).isEqualTo(3);
                assertThat(queueSizeOf(transmitter)).isEqualTo(2);
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 round 3: maybeSendLogsOnly 在 interval 到期时独立于 5 秒的 sendBatchUpdate tick 发送仅含 logs 的 batch_update")
        void maybeSendLogsOnlySendsALogsOnlyBatchUpdateWhenDue() throws Exception {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.setIntervalMs(7000); // not a multiple of the 5s sendBatchUpdate tick
                transmitter.info("queued line", "test");
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);
                setLastLogFlushMs(serverMonitorManager, System.currentTimeMillis() - 7500);

                invokeMaybeSendLogsOnly(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject message = sent.getValue();
                assertThat(message.get("type").getAsString()).isEqualTo("batch_update");
                JsonObject data = message.getAsJsonObject("data");
                assertThat(data.has("logs")).isTrue();
                assertThat(data.has("status"))
                        .as("a logs-only tick must not also carry status/metrics -- that is sendBatchUpdate()'s own job")
                        .isFalse();
                assertThat(data.has("metrics")).isFalse();
                assertThat(queueSizeOf(transmitter)).isZero();
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 round 3: interval 未到期时 maybeSendLogsOnly 不发送任何消息")
        void maybeSendLogsOnlySendsNothingWhenNotYetDue() throws Exception {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.setIntervalMs(7000);
                transmitter.info("queued line", "test");
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);
                setLastLogFlushMs(serverMonitorManager, System.currentTimeMillis() - 2000); // well under 7s

                invokeMaybeSendLogsOnly(serverMonitorManager);

                verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
                assertThat(queueSizeOf(transmitter))
                        .as("nothing drained -- the queued line must still be waiting")
                        .isEqualTo(1);
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 round 3: maybeSendLogsOnly 与 sendBatchUpdate 共享同一个 lastLogFlushMs 闸门 -- 谁先到都不会重复发送")
        void maybeSendLogsOnlyAndSendBatchUpdateShareTheSameGateNoDoubleSend() throws Exception {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.setIntervalMs(1000);
                transmitter.info("queued line", "test");
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);
                setLastLogFlushMs(serverMonitorManager, System.currentTimeMillis() - 5000);

                // The 1-second task fires first and drains.
                invokeMaybeSendLogsOnly(serverMonitorManager);
                reset(mockWebSocketClient);
                when(mockWebSocketClient.isConnected()).thenReturn(true);

                // sendBatchUpdate()'s own 5-second tick fires immediately after -- must see the
                // gate already satisfied by maybeSendLogsOnly and NOT re-include an (empty) logs
                // array from an already-drained queue.
                invokeSendBatchUpdate(serverMonitorManager);

                ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
                verify(mockWebSocketClient).sendMessage(sent.capture());
                JsonObject data = sent.getValue().getAsJsonObject("data");
                assertThat(data.has("logs"))
                        .as("the queue was already drained by maybeSendLogsOnly -- nothing left to include")
                        .isFalse();
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 P2 (round 4): claimLogFlushWindow 在并发竞争同一窗口时只有一个调用者能成功 claim -- 确定性压力测试，不依赖 sleep")
        void claimLogFlushWindowIsRaceSafeUnderConcurrentContention() throws Exception {
            Method claimMethod = ServerMonitorManager.class.getDeclaredMethod(
                    "claimLogFlushWindow", long.class, int.class);
            claimMethod.setAccessible(true);

            int threadCount = 20;
            long now = System.currentTimeMillis();
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                for (int i = 0; i < threadCount; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        // All threads race to claim the SAME window (interval=1000, well elapsed
                        // since lastLogFlushMs starts at 0) at as close to the same instant as
                        // the test harness can force deterministically.
                        return (Boolean) claimMethod.invoke(serverMonitorManager, now, 1000);
                    }));
                }
                ready.await();
                go.countDown();

                long successCount = 0;
                for (Future<Boolean> f : results) {
                    if (Boolean.TRUE.equals(f.get())) {
                        successCount++;
                    }
                }
                assertThat(successCount)
                        .as("exactly one of the 20 racing callers must win the claim -- a plain "
                                + "volatile check-then-write let more than one through (Gate-2 round 4)")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("Gate-2 P2 (round 7): logDrainLock 持有期间，drainLogsNow() 的并发调用必须等待 -- 证明三条排空路径共享同一把互斥锁")
        void logDrainLockBlocksConcurrentDrainLogsNowWhileHeld() throws Exception {
            Field lockField = ServerMonitorManager.class.getDeclaredField("logDrainLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(serverMonitorManager);

            when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith(Capability.LOGS.getConfigPath(), true));
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            try {
                transmitter.info("queued line", "test");
                when(mockLogStreamManagerForBatch.getLogTransmitter()).thenReturn(transmitter);

                CountDownLatch workerStarted = new CountDownLatch(1);
                CountDownLatch workerDone = new CountDownLatch(1);
                Thread worker = new Thread(() -> {
                    workerStarted.countDown();
                    serverMonitorManager.drainLogsNow();
                    workerDone.countDown();
                });

                try {
                    synchronized (lock) {
                        worker.start();
                        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(workerDone.await(300, TimeUnit.MILLISECONDS))
                                .as("drainLogsNow() must block on logDrainLock while it is held elsewhere")
                                .isFalse();
                    }
                    assertThat(workerDone.await(5, TimeUnit.SECONDS))
                            .as("drainLogsNow() must proceed once the lock is released")
                            .isTrue();
                } finally {
                    worker.join(5000);
                }
            } finally {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("Gate-2 P2 (round 9): logDrainLock 现在覆盖 sendBatchUpdate 自己最终的 sendMessage 调用，而不仅仅是 drainQueue")
        void logDrainLockNowCoversSendBatchUpdatesOwnFinalSendNotJustTheDrain() throws Exception {
            Field lockField = ServerMonitorManager.class.getDeclaredField("logDrainLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(serverMonitorManager);

            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch workerDone = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                workerStarted.countDown();
                try {
                    invokeSendBatchUpdate(serverMonitorManager);
                } catch (Exception e) {
                    // Fail the test rather than throw a raw exception type out of a Runnable
                    // (PMD.AvoidThrowingRawExceptionTypes) -- fail() records the cause without
                    // this call site itself constructing one.
                    org.junit.jupiter.api.Assertions.fail(e);
                }
                workerDone.countDown();
            });

            try {
                synchronized (lock) {
                    worker.start();
                    assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    // sendBatchUpdate() must not be able to reach its own final sendMessage()
                    // call while this thread holds the SAME logDrainLock -- before round 9, the
                    // synchronized block only wrapped drainQueue(), so a size-triggered drain
                    // elsewhere could squeeze its own send in between this method's drain and its
                    // own send, letting a NEWER frame overtake an OLDER one already drained here.
                    assertThat(workerDone.await(300, TimeUnit.MILLISECONDS))
                            .as("sendBatchUpdate() must block on logDrainLock for its whole tail, "
                                    + "including its own final sendMessage() call")
                            .isFalse();
                    verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
                }
                assertThat(workerDone.await(5, TimeUnit.SECONDS))
                        .as("sendBatchUpdate() must proceed once the lock is released")
                        .isTrue();
                verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
            } finally {
                worker.join(5000);
            }
        }
    }

    /**
     * {@code diskUsage} and {@code enabledPlugins} in {@code getCurrentMetricsData}'s {@code
     * serverPerformance}/{@code pluginUsage} objects -- issue #436/#437, D-14.
     *
     * <p>{@code diskUsage} was a hardcoded {@code 0.0} ({@code
     * ServerMonitorManager.java:671} before this fix); {@code enabledPlugins} was {@code
     * snapshot.pluginCount} -- every installed plugin, not filtered by whether it is actually
     * enabled. Both gave a panel operator a confident, wrong answer.
     */
    @Nested
    @DisplayName("diskUsage 与 enabledPlugins -- issue #436/#437, D-14")
    class DiskUsageAndEnabledPluginsTests {

        private JsonObject capturedMetricsData() {
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(captor.capture());
            return captor.getValue().getAsJsonObject("data");
        }

        private JsonObject capturedServerPerformance() {
            return capturedMetricsData().getAsJsonObject("serverPerformance");
        }

        private JsonObject pluginJson(String name, boolean enabled) {
            JsonObject plugin = new JsonObject();
            plugin.addProperty("name", name);
            plugin.addProperty("enabled", enabled);
            return plugin;
        }

        @Test
        @DisplayName("真实文件系统上 diskUsage 严格大于 0 且不超过 100，按 memoryUsage 同款方式保留两位小数")
        void diskUsageOnRealFilesystemIsInRangeAndRounded() {
            serverMonitorManager.refreshStateSnapshot();
            serverMonitorManager.sendMetricsData();

            double diskUsage = capturedServerPerformance().get("diskUsage").getAsDouble();

            assertThat(diskUsage).isGreaterThan(0.0).isLessThanOrEqualTo(100.0);
            // Same convention as memoryUsage: Math.round(value * 100.0) / 100.0 -- multiplying
            // by 100 and rounding must land on (approximately) a whole number.
            double scaled = diskUsage * 100.0;
            assertThat(scaled).isCloseTo(Math.round(scaled), org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("diskUsage 取自注入的 total/usable 读数，不是目录遍历 -- free == usable 时（无 root 保留块），等价于旧公式")
        void diskUsageIsComputedFromInjectedTotalAndUsableSpace() {
            // free == usable == 750: no root-reserved allocation, so WR-03's df-style formula
            // ((total - free) / ((total - free) + usable)) coincides with the simpler
            // (total - usable) / total this scenario used to assert -- both give 25%.
            serverMonitorManager.setDiskSpaceReaders(root -> 1000L, root -> 750L, root -> 750L);
            serverMonitorManager.refreshStateSnapshot();
            serverMonitorManager.sendMetricsData();

            assertThat(capturedServerPerformance().get("diskUsage").getAsDouble()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("WR-03: free < usable（有 root 保留块）时按 df 的 Use% 惯例计算，而不是 (total-usable)/total")
        void diskUsageFollowsDfConventionWhenReservedBlocksExist() {
            // total=1000, free=550 (includes the 200 reserved for root), usable=350 (excludes
            // it) -- a stand-in for a real ext4 volume's ~5-15% reserved-block allocation.
            // df-style: used = total - free = 450; Use% = used / (used + usable) = 450/800 = 56.25%.
            // The OLD (total - usable) / total formula would have given (1000-350)/1000 = 65.0%
            // -- a ~9-point divergence, confirming the two formulas are NOT interchangeable here.
            serverMonitorManager.setDiskSpaceReaders(root -> 1000L, root -> 550L, root -> 350L);
            serverMonitorManager.refreshStateSnapshot();
            serverMonitorManager.sendMetricsData();

            assertThat(capturedServerPerformance().get("diskUsage").getAsDouble()).isEqualTo(56.25);
        }

        @Test
        @DisplayName("文件系统报告总空间为零时返回 0.0，而不是除零错误")
        void zeroTotalSpaceProducesZeroNotADivisionError() {
            serverMonitorManager.setDiskSpaceReaders(root -> 0L, root -> 0L, root -> 0L);
            serverMonitorManager.refreshStateSnapshot();

            assertDoesNotThrow(() -> serverMonitorManager.sendMetricsData());

            assertThat(capturedServerPerformance().get("diskUsage").getAsDouble()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("used+avail 分母为零（free 等于 total 且 usable 为零）时返回 0.0，而不是除零错误")
        void zeroDenominatorProducesZeroNotADivisionError() {
            // total == free (nothing used at all) and usable == 0 -- used = 0, denominator =
            // used + usable = 0. This is a distinct edge case from the zero-total-space one
            // above: total is nonzero here, so the first guard does not catch it.
            serverMonitorManager.setDiskSpaceReaders(root -> 1000L, root -> 1000L, root -> 0L);
            serverMonitorManager.refreshStateSnapshot();

            assertDoesNotThrow(() -> serverMonitorManager.sendMetricsData());

            assertThat(capturedServerPerformance().get("diskUsage").getAsDouble()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("enabledPlugins 只数 enabled 标记为真的插件，不是全部已安装的插件")
        void enabledPluginsCountsOnlyTheEnabledFlaggedOnes() {
            JsonArray plugins = new JsonArray();
            plugins.add(pluginJson("Alpha", true));
            plugins.add(pluginJson("Beta", false));
            plugins.add(pluginJson("Gamma", true));

            assertThat(ServerMonitorManager.countEnabledPlugins(plugins)).isEqualTo(2);
        }

        @Test
        @DisplayName("没有任何插件时计数为零，不抛异常")
        void noPluginsAtAllProducesZeroWithoutThrowing() {
            assertThat(ServerMonitorManager.countEnabledPlugins(new JsonArray())).isZero();
        }

        @Test
        @DisplayName("metrics 消息的字段名与类型不变，没有新增/改名/改类型的字段")
        void metricsMessageFieldsAndWireTypesUnchanged() {
            serverMonitorManager.refreshStateSnapshot();
            serverMonitorManager.sendMetricsData();

            JsonObject data = capturedMetricsData();
            assertThat(data.getAsJsonObject("serverPerformance").keySet())
                    .containsExactlyInAnyOrder("averageTPS", "memoryUsage", "diskUsage");
            assertThat(data.getAsJsonObject("pluginUsage").keySet())
                    .containsExactlyInAnyOrder("enabledPlugins", "loadedWorlds");
            assertThat(data.getAsJsonObject("serverPerformance").get("diskUsage").getAsJsonPrimitive().isNumber())
                    .isTrue();
            assertThat(data.getAsJsonObject("pluginUsage").get("enabledPlugins").getAsJsonPrimitive().isNumber())
                    .isTrue();
        }
    }
}
