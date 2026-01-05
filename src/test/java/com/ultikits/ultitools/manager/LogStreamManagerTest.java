package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
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
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * LogStreamManager 测试
 * 参考 UltiPanel 后端 WebSocket 消息格式进行全面覆盖测试
 */
@DisplayName("LogStreamManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LogStreamManagerTest {

    private ServerMock server;
    private LogStreamManager logStreamManager;
    private UltiPanelWebSocketClient mockWebSocketClient;
    private Logger mockLogger;
    private FileConfiguration mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        // Mock logger
        mockLogger = mock(Logger.class);
        when(UltiTools.getInstance().getLogger()).thenReturn(mockLogger);

        // Mock config with detailed configuration
        mockConfig = mock(FileConfiguration.class);
        when(UltiTools.getInstance().getConfig()).thenReturn(mockConfig);

        // Mock WebSocket client
        mockWebSocketClient = mock(UltiPanelWebSocketClient.class);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("test-server-uuid");

        // 重置单例
        resetSingleton();
        logStreamManager = LogStreamManager.getInstance();
    }

    private void resetSingleton() throws Exception {
        Field instanceField = LogStreamManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private void setWebSocketClient(UltiPanelWebSocketClient client) throws Exception {
        Field wsField = LogStreamManager.class.getDeclaredField("webSocketClient");
        wsField.setAccessible(true);
        wsField.set(logStreamManager, client);
    }

    private void setLogTransmitter(UltiPanelLogTransmitter transmitter) throws Exception {
        Field field = LogStreamManager.class.getDeclaredField("logTransmitter");
        field.setAccessible(true);
        field.set(logStreamManager, transmitter);
    }

    private void setSystemLogHandler(SystemLogHandler handler) throws Exception {
        Field field = LogStreamManager.class.getDeclaredField("systemLogHandler");
        field.setAccessible(true);
        field.set(logStreamManager, handler);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Boolean> getSubscribedClients() throws Exception {
        Field field = LogStreamManager.class.getDeclaredField("subscribedClients");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Boolean>) field.get(logStreamManager);
    }

    private AtomicBoolean getStreamingState() throws Exception {
        Field field = LogStreamManager.class.getDeclaredField("streaming");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(logStreamManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        resetSingleton();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // ==================== 单例模式测试 ====================
    @Nested
    @DisplayName("单例模式测试")
    class SingletonTests {

        @Test
        @DisplayName("getInstance 应该返回同一个实例")
        void shouldReturnSameInstance() {
            LogStreamManager instance1 = LogStreamManager.getInstance();
            LogStreamManager instance2 = LogStreamManager.getInstance();
            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        @DisplayName("单例应该在首次调用时创建")
        void singletonShouldBeCreatedOnFirstCall() throws Exception {
            resetSingleton();
            
            Field instanceField = LogStreamManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            assertThat(instanceField.get(null)).isNull();
            
            LogStreamManager.getInstance();
            assertThat(instanceField.get(null)).isNotNull();
        }
    }

    // ==================== 初始状态测试 ====================
    @Nested
    @DisplayName("初始状态测试")
    class InitialStateTests {

        @Test
        @DisplayName("初始状态应该是未流式传输")
        void shouldNotBeStreamingInitially() {
            assertThat(logStreamManager.isStreaming()).isFalse();
        }

        @Test
        @DisplayName("初始订阅客户端数量应该为0")
        void subscriberCountShouldBeZeroInitially() {
            assertThat(logStreamManager.getSubscriberCount()).isZero();
        }

        @Test
        @DisplayName("初始化前 logTransmitter 应该为 null")
        void logTransmitterShouldBeNullBeforeInitialization() {
            assertThat(logStreamManager.getLogTransmitter()).isNull();
        }

        @Test
        @DisplayName("subscribedClients Map 应该存在但为空")
        void subscribedClientsShouldBeEmptyInitially() throws Exception {
            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients).isEmpty();
        }
    }

    // ==================== handleLogStreamMessage 测试 (参考后端协议) ====================
    @Nested
    @DisplayName("handleLogStreamMessage 测试 - 参考 UltiPanel WebSocket 协议")
    class HandleLogStreamMessageTests {

        @Test
        @DisplayName("null 消息应该记录警告并安全返回")
        void shouldLogWarningForNullMessage() {
            logStreamManager.handleLogStreamMessage(null);
            verify(mockLogger).warning("LogStreamManager: 收到空的日志流消息");
        }

        @Test
        @DisplayName("处理 start action - 应该启动日志流")
        void shouldHandleStartAction() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("action", "start");
            data.addProperty("clientId", "client-123");
            data.addProperty("level", "info");

            logStreamManager.handleLogStreamMessage(data);

            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients).containsKey("client-123");
            assertThat(clients.get("client-123")).isTrue();
            assertThat(logStreamManager.isStreaming()).isTrue();
        }

        @Test
        @DisplayName("处理 stop action - 应该停止日志流")
        void shouldHandleStopAction() throws Exception {
            // 先启动
            logStreamManager.startLogStream("client-123", "info");
            assertThat(logStreamManager.isStreaming()).isTrue();

            // 停止
            JsonObject data = new JsonObject();
            data.addProperty("action", "stop");
            data.addProperty("clientId", "client-123");
            
            logStreamManager.handleLogStreamMessage(data);

            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients).doesNotContainKey("client-123");
            assertThat(logStreamManager.isStreaming()).isFalse();
        }

        @Test
        @DisplayName("处理 pause action - 应该暂停日志流")
        void shouldHandlePauseAction() throws Exception {
            logStreamManager.startLogStream("client-123", "info");

            JsonObject data = new JsonObject();
            data.addProperty("action", "pause");
            data.addProperty("clientId", "client-123");

            logStreamManager.handleLogStreamMessage(data);

            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients.get("client-123")).isFalse(); // 暂停状态
        }

        @Test
        @DisplayName("处理 resume action - 应该恢复日志流")
        void shouldHandleResumeAction() throws Exception {
            logStreamManager.startLogStream("client-123", "info");
            logStreamManager.pauseLogStream("client-123");

            JsonObject data = new JsonObject();
            data.addProperty("action", "resume");
            data.addProperty("clientId", "client-123");

            logStreamManager.handleLogStreamMessage(data);

            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients.get("client-123")).isTrue(); // 恢复状态
        }

        @Test
        @DisplayName("处理 status action - 应该发送状态")
        void shouldHandleStatusAction() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            JsonObject data = new JsonObject();
            data.addProperty("action", "status");
            data.addProperty("clientId", "client-123");

            logStreamManager.handleLogStreamMessage(data);

            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("处理 config action - 应该更新配置")
        void shouldHandleConfigAction() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            JsonObject batchConfig = new JsonObject();
            batchConfig.addProperty("enabled", true);
            batchConfig.addProperty("size", 20);
            batchConfig.addProperty("interval", 3000);

            JsonObject data = new JsonObject();
            data.addProperty("action", "config");
            data.addProperty("clientId", "client-123");
            data.add("batchConfig", batchConfig);

            logStreamManager.handleLogStreamMessage(data);

            verify(mockTransmitter).setBatchEnabled(true);
            verify(mockTransmitter).setBatchSize(20);
            verify(mockTransmitter).setIntervalMs(3000);
        }

        @Test
        @DisplayName("处理未知 action - 应该记录警告并发送错误响应")
        void shouldHandleUnknownAction() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            JsonObject data = new JsonObject();
            data.addProperty("action", "unknown_action");
            data.addProperty("clientId", "client-123");

            logStreamManager.handleLogStreamMessage(data);

            verify(mockLogger).warning(contains("未知的日志流操作: unknown_action"));
        }

        @Test
        @DisplayName("空 action 应该视为未知操作")
        void shouldHandleEmptyAction() {
            JsonObject data = new JsonObject();
            data.addProperty("clientId", "client-123");
            // action 为 null

            logStreamManager.handleLogStreamMessage(data);

            verify(mockLogger).warning(contains("未知的日志流操作"));
        }

        @Test
        @DisplayName("空 clientId 应该使用默认值 'default'")
        void shouldUseDefaultClientIdWhenNull() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("action", "start");
            // clientId 为 null

            logStreamManager.handleLogStreamMessage(data);

            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients).containsKey("default");
        }

        @Test
        @DisplayName("处理带 levels 的 config action")
        void shouldHandleConfigWithLevels() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            // 需要设置 systemLogHandler
            SystemLogHandler mockHandler = mock(SystemLogHandler.class);
            setSystemLogHandler(mockHandler);

            JsonArray levelsArray = new JsonArray();
            levelsArray.add("info");
            levelsArray.add("warning");
            levelsArray.add("error");

            JsonObject data = new JsonObject();
            data.addProperty("action", "config");
            data.addProperty("clientId", "client-123");
            data.add("levels", levelsArray);

            logStreamManager.handleLogStreamMessage(data);

            // 验证日志记录
            verify(mockLogger).info(contains("收到日志级别配置更新请求"));
        }
    }

    // ==================== startLogStream 测试 ====================
    @Nested
    @DisplayName("startLogStream 测试")
    class StartLogStreamTests {

        @Test
        @DisplayName("应该正确启动日志流（带级别参数）")
        void shouldStartLogStreamWithLevel() throws Exception {
            logStreamManager.startLogStream("client-1", "debug");

            assertThat(logStreamManager.isStreaming()).isTrue();
            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(1);
            
            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients.get("client-1")).isTrue();
        }

        @Test
        @DisplayName("应该正确启动日志流（兼容旧版本，无级别参数）")
        void shouldStartLogStreamWithoutLevel() throws Exception {
            logStreamManager.startLogStream("client-1");

            assertThat(logStreamManager.isStreaming()).isTrue();
            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients.get("client-1")).isTrue();
        }

        @Test
        @DisplayName("多个客户端应该都能订阅")
        void shouldAllowMultipleClients() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.startLogStream("client-2", "debug");
            logStreamManager.startLogStream("client-3", "warning");

            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("WebSocket 连接时应该发送响应")
        void shouldSendResponseWhenConnected() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            logStreamManager.startLogStream("client-1", "info");

            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时不应该发送响应")
        void shouldNotSendResponseWhenDisconnected() throws Exception {
            when(mockWebSocketClient.isConnected()).thenReturn(false);
            setWebSocketClient(mockWebSocketClient);
            
            logStreamManager.startLogStream("client-1", "info");

            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }
    }

    // ==================== stopLogStream 测试 ====================
    @Nested
    @DisplayName("stopLogStream 测试")
    class StopLogStreamTests {

        @Test
        @DisplayName("应该正确停止单个客户端")
        void shouldStopSingleClient() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.stopLogStream("client-1");

            assertThat(logStreamManager.getSubscriberCount()).isZero();
            assertThat(logStreamManager.isStreaming()).isFalse();
        }

        @Test
        @DisplayName("停止一个客户端不应影响其他客户端")
        void shouldNotAffectOtherClients() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.startLogStream("client-2", "info");
            
            logStreamManager.stopLogStream("client-1");

            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(1);
            assertThat(logStreamManager.isStreaming()).isTrue();
            
            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients).containsKey("client-2");
            assertThat(clients).doesNotContainKey("client-1");
        }

        @Test
        @DisplayName("停止最后一个客户端应该设置 streaming 为 false")
        void shouldSetStreamingFalseWhenLastClientStops() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.startLogStream("client-2", "info");
            
            logStreamManager.stopLogStream("client-1");
            assertThat(logStreamManager.isStreaming()).isTrue();
            
            logStreamManager.stopLogStream("client-2");
            assertThat(logStreamManager.isStreaming()).isFalse();
        }

        @Test
        @DisplayName("停止不存在的客户端不应抛出异常")
        void shouldHandleStoppingNonExistentClient() {
            logStreamManager.stopLogStream("non-existent-client");
            // 不应该抛出异常
            assertThat(logStreamManager.getSubscriberCount()).isZero();
        }
    }

    // ==================== pauseLogStream / resumeLogStream 测试 ====================
    @Nested
    @DisplayName("pauseLogStream 和 resumeLogStream 测试")
    class PauseResumeTests {

        @Test
        @DisplayName("暂停应该将客户端状态设置为 false")
        void pauseShouldSetClientStateToFalse() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.pauseLogStream("client-1");

            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients.get("client-1")).isFalse();
        }

        @Test
        @DisplayName("恢复应该将客户端状态设置为 true")
        void resumeShouldSetClientStateToTrue() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.pauseLogStream("client-1");
            logStreamManager.resumeLogStream("client-1");

            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients.get("client-1")).isTrue();
        }

        @Test
        @DisplayName("恢复应该确保 streaming 为 true")
        void resumeShouldEnsureStreamingIsTrue() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            getStreamingState().set(false); // 手动设置为 false
            
            logStreamManager.resumeLogStream("client-1");

            assertThat(logStreamManager.isStreaming()).isTrue();
        }
    }

    // ==================== sendCustomLog 测试 ====================
    @Nested
    @DisplayName("sendCustomLog 测试")
    class SendCustomLogTests {

        @Test
        @DisplayName("logTransmitter 为 null 时不应抛出异常")
        void shouldNotThrowWhenLogTransmitterIsNull() {
            logStreamManager.sendCustomLog("info", "Test message", "test-source");
            // 不应该抛出异常
        }

        @Test
        @DisplayName("应该调用 logTransmitter 发送日志")
        void shouldCallLogTransmitter() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            logStreamManager.sendCustomLog("info", "Test message", "test-source");

            verify(mockTransmitter).sendLog("info", "Test message", "test-source", null);
        }

        @Test
        @DisplayName("应该支持不同的日志级别")
        void shouldSupportDifferentLogLevels() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            logStreamManager.sendCustomLog("warning", "Warning message", "source1");
            logStreamManager.sendCustomLog("error", "Error message", "source2");
            logStreamManager.sendCustomLog("debug", "Debug message", "source3");

            verify(mockTransmitter).sendLog("warning", "Warning message", "source1", null);
            verify(mockTransmitter).sendLog("error", "Error message", "source2", null);
            verify(mockTransmitter).sendLog("debug", "Debug message", "source3", null);
        }
    }

    // ==================== sendPlayerEventLog 测试 ====================
    @Nested
    @DisplayName("sendPlayerEventLog 测试")
    class SendPlayerEventLogTests {

        @Test
        @DisplayName("应该格式化并发送玩家事件日志")
        void shouldFormatAndSendPlayerEventLog() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            logStreamManager.sendPlayerEventLog("JOIN", "PlayerName", "玩家加入服务器");

            verify(mockTransmitter).sendLog(
                eq("info"), 
                contains("[玩家事件] JOIN: PlayerName - 玩家加入服务器"),
                eq("plugin:UltiTools"),
                any()
            );
        }

        @Test
        @DisplayName("应该支持不同的事件类型")
        void shouldSupportDifferentEventTypes() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            logStreamManager.sendPlayerEventLog("JOIN", "Player1", "加入");
            logStreamManager.sendPlayerEventLog("QUIT", "Player2", "退出");
            logStreamManager.sendPlayerEventLog("CHAT", "Player3", "聊天");

            verify(mockTransmitter, times(3)).sendLog(anyString(), anyString(), anyString(), any());
        }
    }

    // ==================== sendPluginActionLog 测试 ====================
    @Nested
    @DisplayName("sendPluginActionLog 测试")
    class SendPluginActionLogTests {

        @Test
        @DisplayName("应该格式化并发送插件操作日志")
        void shouldFormatAndSendPluginActionLog() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            logStreamManager.sendPluginActionLog("ENABLE", "插件已启用");

            verify(mockTransmitter).sendLog(
                eq("info"),
                contains("[插件操作] ENABLE: 插件已启用"),
                eq("plugin:UltiTools"),
                any()
            );
        }
    }

    // ==================== Bukkit 事件处理器测试 ====================
    @Nested
    @DisplayName("Bukkit 事件处理器测试")
    class BukkitEventHandlerTests {

        @Test
        @DisplayName("onPlayerJoin 应该发送玩家加入日志")
        void onPlayerJoinShouldSendLog() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getName()).thenReturn("TestPlayer");
            
            PlayerJoinEvent event = new PlayerJoinEvent(mockPlayer, "TestPlayer joined");
            logStreamManager.onPlayerJoin(event);

            verify(mockTransmitter).sendLog(
                eq("info"),
                contains("JOIN"),
                anyString(),
                any()
            );
        }

        @Test
        @DisplayName("onPlayerQuit 应该发送玩家离开日志")
        void onPlayerQuitShouldSendLog() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getName()).thenReturn("TestPlayer");
            
            PlayerQuitEvent event = new PlayerQuitEvent(mockPlayer, "TestPlayer left");
            logStreamManager.onPlayerQuit(event);

            verify(mockTransmitter).sendLog(
                eq("info"),
                contains("QUIT"),
                anyString(),
                any()
            );
        }

        @Test
        @DisplayName("onServerLoad 应该发送服务器加载日志")
        void onServerLoadShouldSendLog() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            ServerLoadEvent event = new ServerLoadEvent(ServerLoadEvent.LoadType.STARTUP);
            logStreamManager.onServerLoad(event);

            verify(mockTransmitter).sendLog(
                eq("info"),
                contains("服务器加载完成"),
                eq("server"),
                any()
            );
        }
    }

    // ==================== shutdown 测试 ====================
    @Nested
    @DisplayName("shutdown 测试")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown 应该清除所有订阅客户端")
        void shouldClearAllSubscribedClients() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.startLogStream("client-2", "info");
            
            logStreamManager.shutdown();

            assertThat(logStreamManager.getSubscriberCount()).isZero();
        }

        @Test
        @DisplayName("shutdown 应该设置 streaming 为 false")
        void shouldSetStreamingToFalse() {
            logStreamManager.startLogStream("client-1", "info");
            
            logStreamManager.shutdown();

            assertThat(logStreamManager.isStreaming()).isFalse();
        }

        @Test
        @DisplayName("shutdown 应该关闭 logTransmitter")
        void shouldShutdownLogTransmitter() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);

            logStreamManager.shutdown();

            verify(mockTransmitter).shutdown();
        }

        @Test
        @DisplayName("shutdown 时 logTransmitter 为 null 不应抛出异常")
        void shouldNotThrowWhenLogTransmitterIsNull() {
            logStreamManager.shutdown();
            // 不应该抛出异常
        }
    }

    // ==================== isStreaming / getSubscriberCount 测试 ====================
    @Nested
    @DisplayName("状态查询方法测试")
    class StatusQueryTests {

        @Test
        @DisplayName("isStreaming 应该返回正确状态")
        void isStreamingShouldReturnCorrectState() {
            assertThat(logStreamManager.isStreaming()).isFalse();
            
            logStreamManager.startLogStream("client-1", "info");
            assertThat(logStreamManager.isStreaming()).isTrue();
            
            logStreamManager.stopLogStream("client-1");
            assertThat(logStreamManager.isStreaming()).isFalse();
        }

        @Test
        @DisplayName("getSubscriberCount 应该返回正确数量")
        void getSubscriberCountShouldReturnCorrectCount() {
            assertThat(logStreamManager.getSubscriberCount()).isZero();
            
            logStreamManager.startLogStream("client-1", "info");
            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(1);
            
            logStreamManager.startLogStream("client-2", "info");
            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(2);
            
            logStreamManager.stopLogStream("client-1");
            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(1);
        }
    }

    // ==================== getServerId 测试 ====================
    @Nested
    @DisplayName("getServerId 测试")
    class GetServerIdTests {

        @Test
        @DisplayName("应该能通过反射调用 getServerId")
        void shouldBeAbleToCallViaReflection() throws Exception {
            Method method = LogStreamManager.class.getDeclaredMethod("getServerId");
            method.setAccessible(true);
            
            String result = (String) method.invoke(logStreamManager);
            
            assertThat(result).isNotNull();
        }
    }

    // ==================== sendStreamResponse 测试 ====================
    @Nested
    @DisplayName("sendStreamResponse 私有方法测试")
    class SendStreamResponseTests {

        @Test
        @DisplayName("WebSocket 为 null 时不应发送消息")
        void shouldNotSendWhenWebSocketIsNull() throws Exception {
            Method method = LogStreamManager.class.getDeclaredMethod(
                "sendStreamResponse", String.class, String.class, String.class);
            method.setAccessible(true);
            
            method.invoke(logStreamManager, "client-1", "started", "Test message");
            
            // webSocketClient 为 null，不应抛出异常
        }

        @Test
        @DisplayName("WebSocket 已连接时应该发送消息")
        void shouldSendWhenWebSocketIsConnected() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            Method method = LogStreamManager.class.getDeclaredMethod(
                "sendStreamResponse", String.class, String.class, String.class);
            method.setAccessible(true);
            
            method.invoke(logStreamManager, "client-1", "started", "Test message");

            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("发送异常时不应抛出")
        void shouldNotThrowOnSendException() throws Exception {
            doThrow(new RuntimeException("Send failed")).when(mockWebSocketClient).sendMessage(any(JsonObject.class));
            setWebSocketClient(mockWebSocketClient);
            
            Method method = LogStreamManager.class.getDeclaredMethod(
                "sendStreamResponse", String.class, String.class, String.class);
            method.setAccessible(true);
            
            // 不应该抛出异常
            method.invoke(logStreamManager, "client-1", "started", "Test message");
        }
    }

    // ==================== sendErrorResponse 测试 ====================
    @Nested
    @DisplayName("sendErrorResponse 私有方法测试")
    class SendErrorResponseTests {

        @Test
        @DisplayName("WebSocket 为 null 时不应发送错误消息")
        void shouldNotSendWhenWebSocketIsNull() throws Exception {
            Method method = LogStreamManager.class.getDeclaredMethod(
                "sendErrorResponse", String.class, String.class);
            method.setAccessible(true);
            
            method.invoke(logStreamManager, "client-1", "Error message");
            
            // 不应该抛出异常
        }

        @Test
        @DisplayName("WebSocket 已连接时应该发送错误消息")
        void shouldSendErrorWhenConnected() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            Method method = LogStreamManager.class.getDeclaredMethod(
                "sendErrorResponse", String.class, String.class);
            method.setAccessible(true);
            
            method.invoke(logStreamManager, "client-1", "Error message");

            verify(mockWebSocketClient).sendMessage(any(JsonObject.class));
        }
    }

    // ==================== sendStreamStatus 测试 ====================
    @Nested
    @DisplayName("sendStreamStatus 私有方法测试")
    class SendStreamStatusTests {

        @Test
        @DisplayName("应该发送包含状态信息的消息")
        void shouldSendStatusMessage() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            logStreamManager.startLogStream("client-1", "info");
            
            Method method = LogStreamManager.class.getDeclaredMethod("sendStreamStatus", String.class);
            method.setAccessible(true);
            
            method.invoke(logStreamManager, "client-1");

            verify(mockWebSocketClient, atLeast(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("状态消息应该包含正确的字段")
        void statusMessageShouldContainCorrectFields() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            logStreamManager.startLogStream("client-1", "info");
            reset(mockWebSocketClient);
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            // 捕获发送的消息
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            Method method = LogStreamManager.class.getDeclaredMethod("sendStreamStatus", String.class);
            method.setAccessible(true);
            method.invoke(logStreamManager, "client-1");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject sentMessage = captor.getValue();
            assertThat(sentMessage.get("type").getAsString()).isEqualTo("log_stream");
            assertThat(sentMessage.has("timestamp")).isTrue();
            assertThat(sentMessage.has("serverId")).isTrue();
            assertThat(sentMessage.has("data")).isTrue();
            
            JsonObject data = sentMessage.getAsJsonObject("data");
            assertThat(data.get("action").getAsString()).isEqualTo("status");
            assertThat(data.has("streaming")).isTrue();
            assertThat(data.has("subscriberCount")).isTrue();
        }
    }

    // ==================== loadBatchConfiguration 测试 ====================
    @Nested
    @DisplayName("loadBatchConfiguration 测试")
    class LoadBatchConfigurationTests {

        @Test
        @DisplayName("logTransmitter 为 null 时应该直接返回")
        void shouldReturnEarlyWhenLogTransmitterIsNull() throws Exception {
            Method method = LogStreamManager.class.getDeclaredMethod("loadBatchConfiguration");
            method.setAccessible(true);
            
            method.invoke(logStreamManager);
            
            // 不应该抛出异常
        }

        @Test
        @DisplayName("应该从配置加载批量发送设置")
        void shouldLoadBatchConfigFromConfig() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);
            
            when(mockConfig.contains("ultipanel.logging.batch.enabled")).thenReturn(true);
            when(mockConfig.getBoolean("ultipanel.logging.batch.enabled", true)).thenReturn(false);
            
            when(mockConfig.contains("ultipanel.logging.batch.size")).thenReturn(true);
            when(mockConfig.getInt("ultipanel.logging.batch.size", 10)).thenReturn(25);
            
            when(mockConfig.contains("ultipanel.logging.batch.interval")).thenReturn(true);
            when(mockConfig.getInt("ultipanel.logging.batch.interval", 5000)).thenReturn(3000);
            
            when(mockTransmitter.isBatchEnabled()).thenReturn(false);
            when(mockTransmitter.getBatchSize()).thenReturn(25);
            when(mockTransmitter.getIntervalMs()).thenReturn(3000);
            
            Method method = LogStreamManager.class.getDeclaredMethod("loadBatchConfiguration");
            method.setAccessible(true);
            method.invoke(logStreamManager);

            verify(mockTransmitter).setBatchEnabled(false);
            verify(mockTransmitter).setBatchSize(25);
            verify(mockTransmitter).setIntervalMs(3000);
        }

        @Test
        @DisplayName("配置加载异常时应该使用默认配置")
        void shouldUseDefaultConfigOnException() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);
            
            when(mockConfig.contains("ultipanel.logging.batch.enabled")).thenThrow(new RuntimeException("Config error"));
            
            Method method = LogStreamManager.class.getDeclaredMethod("loadBatchConfiguration");
            method.setAccessible(true);
            
            method.invoke(logStreamManager);

            verify(mockLogger).warning(contains("加载批量发送配置失败"));
        }
    }

    // ==================== handleConfigUpdate 测试 ====================
    @Nested
    @DisplayName("handleConfigUpdate 测试")
    class HandleConfigUpdateTests {

        @Test
        @DisplayName("更新配置异常时应该发送错误响应")
        void shouldSendErrorOnUpdateException() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);
            
            doThrow(new RuntimeException("Update failed")).when(mockTransmitter).setBatchEnabled(anyBoolean());
            
            JsonObject batchConfig = new JsonObject();
            batchConfig.addProperty("enabled", true);
            
            JsonObject data = new JsonObject();
            data.add("batchConfig", batchConfig);
            
            Method method = LogStreamManager.class.getDeclaredMethod(
                "handleConfigUpdate", JsonObject.class, String.class);
            method.setAccessible(true);
            
            method.invoke(logStreamManager, data, "client-1");

            verify(mockLogger).warning(contains("更新配置失败"));
        }
    }

    // ==================== 字段测试 ====================
    @Nested
    @DisplayName("字段访问测试")
    class FieldAccessTests {

        @Test
        @DisplayName("webSocketClient 字段应该存在")
        void webSocketClientFieldShouldExist() throws Exception {
            Field field = LogStreamManager.class.getDeclaredField("webSocketClient");
            assertThat(field).isNotNull();
            assertThat(field.getType()).isEqualTo(UltiPanelWebSocketClient.class);
        }

        @Test
        @DisplayName("logTransmitter 字段应该存在")
        void logTransmitterFieldShouldExist() throws Exception {
            Field field = LogStreamManager.class.getDeclaredField("logTransmitter");
            assertThat(field).isNotNull();
            assertThat(field.getType()).isEqualTo(UltiPanelLogTransmitter.class);
        }

        @Test
        @DisplayName("systemLogHandler 字段应该存在")
        void systemLogHandlerFieldShouldExist() throws Exception {
            Field field = LogStreamManager.class.getDeclaredField("systemLogHandler");
            assertThat(field).isNotNull();
        }

        @Test
        @DisplayName("streaming 字段应该是 AtomicBoolean")
        void streamingFieldShouldBeAtomicBoolean() throws Exception {
            Field field = LogStreamManager.class.getDeclaredField("streaming");
            assertThat(field).isNotNull();
            assertThat(field.getType()).isEqualTo(AtomicBoolean.class);
        }

        @Test
        @DisplayName("subscribedClients 字段应该是 ConcurrentHashMap")
        void subscribedClientsFieldShouldBeConcurrentHashMap() throws Exception {
            Field field = LogStreamManager.class.getDeclaredField("subscribedClients");
            assertThat(field).isNotNull();
            assertThat(field.getType()).isEqualTo(ConcurrentHashMap.class);
        }
    }

    // ==================== 并发安全测试 ====================
    @Nested
    @DisplayName("并发安全测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程同时启动日志流应该是线程安全的")
        void shouldBeThreadSafeForMultipleStarts() throws Exception {
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            
            for (int i = 0; i < threadCount; i++) {
                final int clientNum = i;
                executor.submit(() -> {
                    try {
                        logStreamManager.startLogStream("client-" + clientNum, "info");
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            
            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("streaming 状态应该使用 AtomicBoolean 保证线程安全")
        void streamingStateShouldBeAtomic() throws Exception {
            AtomicBoolean streaming = getStreamingState();
            assertThat(streaming).isInstanceOf(AtomicBoolean.class);
        }
    }

    // ==================== 边界条件测试 ====================
    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("重复启动同一客户端应该更新状态")
        void shouldUpdateStateOnDuplicateStart() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.startLogStream("client-1", "debug");
            
            // 应该只有一个客户端
            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(1);
            
            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients.get("client-1")).isTrue();
        }

        @Test
        @DisplayName("空字符串 clientId 应该被接受")
        void shouldAcceptEmptyStringClientId() throws Exception {
            logStreamManager.startLogStream("", "info");
            
            ConcurrentHashMap<String, Boolean> clients = getSubscribedClients();
            assertThat(clients).containsKey("");
        }

        @Test
        @DisplayName("null level 应该使用默认值")
        void shouldUseDefaultLevelWhenNull() throws Exception {
            logStreamManager.startLogStream("client-1", null);
            
            // 不应该抛出异常
            assertThat(logStreamManager.getSubscriberCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("连续暂停恢复应该正确处理")
        void shouldHandleMultiplePauseResume() throws Exception {
            logStreamManager.startLogStream("client-1", "info");
            
            for (int i = 0; i < 5; i++) {
                logStreamManager.pauseLogStream("client-1");
                assertThat(getSubscribedClients().get("client-1")).isFalse();
                
                logStreamManager.resumeLogStream("client-1");
                assertThat(getSubscribedClients().get("client-1")).isTrue();
            }
        }
    }

    // ==================== 消息格式验证测试 (参考后端协议) ====================
    @Nested
    @DisplayName("消息格式验证测试 - 参考 UltiPanel WebSocket 协议")
    class MessageFormatTests {

        @Test
        @DisplayName("响应消息应该包含 type: log_stream")
        void responseMessageShouldHaveCorrectType() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            logStreamManager.startLogStream("client-1", "info");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject message = captor.getValue();
            assertThat(message.get("type").getAsString()).isEqualTo("log_stream");
        }

        @Test
        @DisplayName("响应消息应该包含 serverId")
        void responseMessageShouldHaveServerId() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            logStreamManager.startLogStream("client-1", "info");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject message = captor.getValue();
            assertThat(message.has("serverId")).isTrue();
        }

        @Test
        @DisplayName("响应消息应该包含 timestamp")
        void responseMessageShouldHaveTimestamp() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            logStreamManager.startLogStream("client-1", "info");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject message = captor.getValue();
            assertThat(message.has("timestamp")).isTrue();
            assertThat(message.get("timestamp").getAsLong()).isGreaterThan(0);
        }

        @Test
        @DisplayName("响应消息 data 应该包含 status 字段")
        void responseDataShouldHaveStatusField() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            logStreamManager.startLogStream("client-1", "info");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject message = captor.getValue();
            JsonObject data = message.getAsJsonObject("data");
            assertThat(data.has("status")).isTrue();
            assertThat(data.get("status").getAsString()).isEqualTo("started");
        }

        @Test
        @DisplayName("停止响应 status 应该是 stopped")
        void stopResponseStatusShouldBeStopped() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            logStreamManager.startLogStream("client-1", "info");
            reset(mockWebSocketClient);
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            logStreamManager.stopLogStream("client-1");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject message = captor.getValue();
            JsonObject data = message.getAsJsonObject("data");
            assertThat(data.get("status").getAsString()).isEqualTo("stopped");
        }

        @Test
        @DisplayName("暂停响应 status 应该是 paused")
        void pauseResponseStatusShouldBePaused() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            logStreamManager.startLogStream("client-1", "info");
            reset(mockWebSocketClient);
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            logStreamManager.pauseLogStream("client-1");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject message = captor.getValue();
            JsonObject data = message.getAsJsonObject("data");
            assertThat(data.get("status").getAsString()).isEqualTo("paused");
        }

        @Test
        @DisplayName("恢复响应 status 应该是 resumed")
        void resumeResponseStatusShouldBeResumed() throws Exception {
            setWebSocketClient(mockWebSocketClient);
            logStreamManager.startLogStream("client-1", "info");
            logStreamManager.pauseLogStream("client-1");
            reset(mockWebSocketClient);
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            
            logStreamManager.resumeLogStream("client-1");

            verify(mockWebSocketClient).sendMessage(captor.capture());
            
            JsonObject message = captor.getValue();
            JsonObject data = message.getAsJsonObject("data");
            assertThat(data.get("status").getAsString()).isEqualTo("resumed");
        }
    }

    // ==================== getLogTransmitter 测试 ====================
    @Nested
    @DisplayName("getLogTransmitter 测试")
    class GetLogTransmitterTests {

        @Test
        @DisplayName("初始化前应该返回 null")
        void shouldReturnNullBeforeInitialization() {
            UltiPanelLogTransmitter transmitter = logStreamManager.getLogTransmitter();
            assertThat(transmitter).isNull();
        }

        @Test
        @DisplayName("设置后应该返回正确的实例")
        void shouldReturnCorrectInstanceAfterSet() throws Exception {
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);
            
            assertThat(logStreamManager.getLogTransmitter()).isSameAs(mockTransmitter);
        }
    }

    // ==================== initialize 方法测试 ====================
    @Nested
    @DisplayName("initialize 方法测试")
    class InitializeTests {

        @Test
        @DisplayName("initialize 应该设置 webSocketClient")
        void shouldSetWebSocketClient() throws Exception {
            // Arrange - 配置 mock 避免 NPE
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            when(mockWebSocketClient.getServerId()).thenReturn("test-server");
            
            // 模拟配置返回值
            when(mockConfig.contains(anyString())).thenReturn(false);
            
            // Act
            logStreamManager.initialize(mockWebSocketClient);
            
            // Assert
            Field wsField = LogStreamManager.class.getDeclaredField("webSocketClient");
            wsField.setAccessible(true);
            assertThat(wsField.get(logStreamManager)).isSameAs(mockWebSocketClient);
        }

        @Test
        @DisplayName("initialize 应该创建 logTransmitter")
        void shouldCreateLogTransmitter() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            when(mockWebSocketClient.getServerId()).thenReturn("test-server");
            when(mockConfig.contains(anyString())).thenReturn(false);
            
            // Act
            logStreamManager.initialize(mockWebSocketClient);
            
            // Assert
            assertThat(logStreamManager.getLogTransmitter()).isNotNull();
        }

        @Test
        @DisplayName("initialize 应该创建 systemLogHandler")
        void shouldCreateSystemLogHandler() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            when(mockWebSocketClient.getServerId()).thenReturn("test-server");
            when(mockConfig.contains(anyString())).thenReturn(false);
            
            // Act
            logStreamManager.initialize(mockWebSocketClient);
            
            // Assert
            Field field = LogStreamManager.class.getDeclaredField("systemLogHandler");
            field.setAccessible(true);
            assertThat(field.get(logStreamManager)).isNotNull();
        }

        @Test
        @DisplayName("initialize 应该启动日志流")
        void shouldStartLogStream() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            when(mockWebSocketClient.getServerId()).thenReturn("test-server");
            when(mockConfig.contains(anyString())).thenReturn(false);
            
            // Act
            logStreamManager.initialize(mockWebSocketClient);
            
            // Assert - 日志流应该已启动
            assertThat(logStreamManager.isStreaming()).isTrue();
        }

        @Test
        @DisplayName("initialize 应该记录初始化日志")
        void shouldLogInitialization() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            when(mockWebSocketClient.getServerId()).thenReturn("test-server");
            when(mockConfig.contains(anyString())).thenReturn(false);
            
            // Act
            logStreamManager.initialize(mockWebSocketClient);
            
            // Assert
            verify(mockLogger).info(contains("initialized"));
        }

        @Test
        @DisplayName("initialize 应该加载批量配置")
        void shouldLoadBatchConfiguration() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            when(mockWebSocketClient.getServerId()).thenReturn("test-server");
            when(mockConfig.contains("ultipanel.logging.batch.enabled")).thenReturn(true);
            when(mockConfig.getBoolean("ultipanel.logging.batch.enabled", true)).thenReturn(false);
            
            // Act
            logStreamManager.initialize(mockWebSocketClient);
            
            // Assert
            UltiPanelLogTransmitter transmitter = logStreamManager.getLogTransmitter();
            assertThat(transmitter.isBatchEnabled()).isFalse();
        }
    }

    // ==================== sendInitializationLogs 方法测试 ====================
    @Nested
    @DisplayName("sendInitializationLogs 方法测试")
    class SendInitializationLogsTests {

        @Test
        @DisplayName("应该发送初始化日志")
        void shouldSendInitializationLogs() throws Exception {
            // Arrange
            UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
            setLogTransmitter(mockTransmitter);
            
            SystemLogHandler mockHandler = mock(SystemLogHandler.class);
            when(mockHandler.getConfigurationInfo()).thenReturn("test config info");
            setSystemLogHandler(mockHandler);
            
            // Act - 调用 sendInitializationLogs
            Method method = LogStreamManager.class.getDeclaredMethod("sendInitializationLogs");
            method.setAccessible(true);
            method.invoke(logStreamManager);
            
            // Assert
            verify(mockTransmitter, atLeast(1)).info(anyString(), anyString());
        }
    }
}
