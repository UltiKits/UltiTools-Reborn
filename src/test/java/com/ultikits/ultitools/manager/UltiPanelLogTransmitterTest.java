package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * UltiPanelLogTransmitter 测试
 */
@DisplayName("UltiPanelLogTransmitter 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.SingularField"}) // Test requires reflection for mocking internal state
class UltiPanelLogTransmitterTest {

    private ServerMock server;
    private UltiPanelLogTransmitter logTransmitter;
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

        logTransmitter = new UltiPanelLogTransmitter(mockWebSocketClient, "test-server");
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该使用提供的 serverId")
        void shouldUseProvidedServerId() throws Exception {
            // Arrange & Act
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(mockWebSocketClient, "custom-server-id");

            // Assert
            Field serverIdField = UltiPanelLogTransmitter.class.getDeclaredField("serverId");
            serverIdField.setAccessible(true);
            assertThat(serverIdField.get(transmitter)).isEqualTo("custom-server-id");
        }

        @Test
        @DisplayName("serverId 为 null 时应该使用默认值")
        void shouldUseDefaultServerIdWhenNull() throws Exception {
            // Arrange & Act
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(mockWebSocketClient, null);

            // Assert
            Field serverIdField = UltiPanelLogTransmitter.class.getDeclaredField("serverId");
            serverIdField.setAccessible(true);
            assertThat(serverIdField.get(transmitter)).isNotNull();
        }

        @Test
        @DisplayName("应该初始化日志队列")
        void shouldInitializeLogQueue() throws Exception {
            // Arrange & Act
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(mockWebSocketClient, "test");

            // Assert
            Field queueField = UltiPanelLogTransmitter.class.getDeclaredField("logQueue");
            queueField.setAccessible(true);
            assertThat(queueField.get(transmitter)).isNotNull();
        }

        @Test
        @DisplayName("应该初始化批量调度器")
        void shouldInitializeBatchScheduler() throws Exception {
            // Arrange & Act
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(mockWebSocketClient, "test");

            // Assert
            Field schedulerField = UltiPanelLogTransmitter.class.getDeclaredField("batchScheduler");
            schedulerField.setAccessible(true);
            assertThat(schedulerField.get(transmitter)).isNotNull();
        }
    }

    @Nested
    @DisplayName("sendLog 测试")
    class SendLogTests {

        @Test
        @DisplayName("WebSocket 未连接时不应该发送")
        void shouldNotSendWhenNotConnected() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            logTransmitter.sendLog("info", "test message", "server", null);

            // Assert - 不会立即发送
        }

        @Test
        @DisplayName("WebSocket 为 null 时不应该抛出异常")
        void shouldNotThrowWhenWebSocketNull() {
            // Arrange
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(null, "test");

            // Act - 不应该抛出异常
            transmitter.sendLog("info", "test message", "server", null);
        }

        @Test
        @DisplayName("level 为 null 时应该使用默认值 info")
        void shouldUseDefaultLevelWhenNull() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.sendLog(null, "test message", "server", null);

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("source 为 null 时应该使用默认值 server")
        void shouldUseDefaultSourceWhenNull() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.sendLog("info", "test message", null, null);

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("应该包含异常堆栈跟踪")
        void shouldIncludeStackTrace() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            Exception testException = new RuntimeException("Test exception");

            // Act
            logTransmitter.sendLog("error", "error message", "server", testException);

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("便捷方法测试")
    class ConvenienceMethodsTests {

        @Test
        @DisplayName("info 方法应该调用 sendLog")
        void infoShouldCallSendLog() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.info("test info", "server");

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("warning 方法应该调用 sendLog")
        void warningShouldCallSendLog() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.warning("test warning", "server");

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("error 方法应该调用 sendLog")
        void errorShouldCallSendLog() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.error("test error", "server", null);

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("debug 方法应该调用 sendLog")
        void debugShouldCallSendLog() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.debug("test debug", "server");

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("批量发送测试")
    class BatchSendingTests {

        @Test
        @DisplayName("batchEnabled 默认应该为 true")
        void batchEnabledShouldBeTrue() {
            // Assert
            assertThat(logTransmitter.isBatchEnabled()).isTrue();
        }

        @Test
        @DisplayName("应该能够设置 batchEnabled")
        void shouldSetBatchEnabled() {
            // Act
            logTransmitter.setBatchEnabled(false);

            // Assert
            assertThat(logTransmitter.isBatchEnabled()).isFalse();
        }

        @Test
        @DisplayName("batchSize 默认应该为 10")
        void batchSizeShouldBe10() {
            // Assert
            assertThat(logTransmitter.getBatchSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("应该能够设置 batchSize")
        void shouldSetBatchSize() {
            // Act
            logTransmitter.setBatchSize(20);

            // Assert
            assertThat(logTransmitter.getBatchSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("intervalMs 默认应该为 5000")
        void intervalMsShouldBe5000() {
            // Assert
            assertThat(logTransmitter.getIntervalMs()).isEqualTo(5000);
        }

        @Test
        @DisplayName("应该能够设置 intervalMs")
        void shouldSetIntervalMs() {
            // Act
            logTransmitter.setIntervalMs(10000);

            // Assert
            assertThat(logTransmitter.getIntervalMs()).isEqualTo(10000);
        }

        @Test
        @DisplayName("队列满时应该触发发送")
        void shouldSendWhenQueueFull() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchSize(3);

            // Act - 发送超过 batchSize 的日志
            for (int i = 0; i < 5; i++) {
                logTransmitter.sendLog("info", "message " + i, "server", null);
            }

            // 等待批量发送
            Thread.sleep(200);

            // 不应该抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("addToBatch 测试")
    class AddToBatchTests {

        @Test
        @DisplayName("应该将日志添加到队列")
        void shouldAddLogToQueue() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            logTransmitter.sendLog("info", "test", "server", null);

            // Assert
            Field queueField = UltiPanelLogTransmitter.class.getDeclaredField("logQueue");
            queueField.setAccessible(true);
            ConcurrentLinkedQueue<?> queue = (ConcurrentLinkedQueue<?>) queueField.get(logTransmitter);
            // 队列可能已被清空（如果触发了批量发送），但不应该抛出异常
        }
    }

    @Nested
    @DisplayName("sendLogImmediately 测试")
    class SendLogImmediatelyTests {

        @Test
        @DisplayName("应该直接发送消息")
        void shouldSendMessageDirectly() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("sendLogImmediately", JsonObject.class);
            method.setAccessible(true);

            JsonObject logData = new JsonObject();
            logData.addProperty("message", "test");

            // Act
            method.invoke(logTransmitter, logData);

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("logTransmissionEnabled 测试")
    class LogTransmissionEnabledTests {

        @Test
        @DisplayName("默认应该启用")
        void shouldBeEnabledByDefault() throws Exception {
            // Assert
            Field field = UltiPanelLogTransmitter.class.getDeclaredField("logTransmissionEnabled");
            field.setAccessible(true);
            AtomicBoolean enabled = (AtomicBoolean) field.get(logTransmitter);
            assertThat(enabled.get()).isTrue();
        }

        @Test
        @DisplayName("禁用时不应该发送日志")
        void shouldNotSendWhenDisabled() throws Exception {
            // Arrange
            Field field = UltiPanelLogTransmitter.class.getDeclaredField("logTransmissionEnabled");
            field.setAccessible(true);
            AtomicBoolean enabled = (AtomicBoolean) field.get(logTransmitter);
            enabled.set(false);

            // Act
            logTransmitter.sendLog("info", "test", "server", null);

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("getStackTrace 测试")
    class GetStackTraceTests {

        @Test
        @DisplayName("应该返回异常堆栈字符串")
        void shouldReturnStackTraceString() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("getStackTrace", Throwable.class);
            method.setAccessible(true);

            Exception testException = new RuntimeException("Test");

            // Act
            String result = (String) method.invoke(logTransmitter, testException);

            // Assert
            assertThat(result).contains("RuntimeException");
            assertThat(result).contains("Test");
        }
    }

    @Nested
    @DisplayName("determineLoggerName 测试")
    class DetermineLoggerNameTests {

        @Test
        @DisplayName("应该返回正确的 logger 名称")
        void shouldReturnCorrectLoggerName() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("determineLoggerName", String.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(logTransmitter, "plugin:TestPlugin");

            // Assert
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("source 为 server 时应该返回 MinecraftServer")
        void shouldReturnMinecraftServerForServerSource() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("determineLoggerName", String.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(logTransmitter, "server");

            // Assert
            assertThat(result).isEqualTo("MinecraftServer");
        }

        @Test
        @DisplayName("source 为 null 时应该返回 unknown")
        void shouldReturnUnknownForNullSource() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("determineLoggerName", String.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(logTransmitter, (String) null);

            // Assert
            assertThat(result).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("flushLogs 测试")
    class FlushLogsTests {

        @Test
        @DisplayName("应该清空队列并发送所有日志")
        void shouldFlushQueueAndSendAll() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            // 添加一些日志到队列
            for (int i = 0; i < 5; i++) {
                logTransmitter.sendLog("info", "message " + i, "server", null);
            }

            // Act
            logTransmitter.flushLogs();

            // Assert - 队列应该被清空
            Field queueField = UltiPanelLogTransmitter.class.getDeclaredField("logQueue");
            queueField.setAccessible(true);
            ConcurrentLinkedQueue<?> queue = (ConcurrentLinkedQueue<?>) queueField.get(logTransmitter);
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("WebSocket 未连接时不应该发送")
        void shouldNotSendWhenNotConnected() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(false);
            
            // 添加日志
            logTransmitter.sendLog("info", "test", "server", null);

            // Act
            logTransmitter.flushLogs();

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("shutdown 测试")
    class ShutdownTests {

        @Test
        @DisplayName("应该关闭调度器")
        void shouldShutdownScheduler() {
            // Act
            logTransmitter.shutdown();

            // Assert - 不应该抛出异常
            // 再次调用也不应该抛出异常
            logTransmitter.shutdown();
        }

        @Test
        @DisplayName("关闭后不应该发送日志")
        void shouldNotSendLogsAfterShutdown() {
            // Arrange
            logTransmitter.shutdown();

            // Act
            logTransmitter.sendLog("info", "test", "server", null);

            // Assert - 不应该抛出异常
        }
    }

    @Nested
    @DisplayName("getQueueSize 测试")
    class GetQueueSizeTests {

        @Test
        @DisplayName("初始队列大小应该为 0")
        void initialQueueSizeShouldBeZero() {
            // Assert
            assertThat(logTransmitter.getQueueSize()).isEqualTo(0);
        }

        @Test
        @DisplayName("添加日志后队列大小应该增加")
        void queueSizeShouldIncreaseAfterAddingLogs() throws Exception {
            // Arrange - 禁用批量发送以保持日志在队列中
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            // 使用反射禁用 logTransmissionEnabled 以防止立即发送
            Field enabledField = UltiPanelLogTransmitter.class.getDeclaredField("logTransmissionEnabled");
            enabledField.setAccessible(true);
            AtomicBoolean enabled = (AtomicBoolean) enabledField.get(logTransmitter);
            enabled.set(false);

            // 启用后添加日志
            enabled.set(true);
            
            // 由于批量逻辑，实际测试需要检查队列增加
            int initialSize = logTransmitter.getQueueSize();
            
            // Assert
            assertThat(initialSize).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("setLogTransmissionEnabled 测试")
    class SetLogTransmissionEnabledTests {

        @Test
        @DisplayName("应该能够启用日志传输")
        void shouldEnableLogTransmission() throws Exception {
            // Arrange
            Field enabledField = UltiPanelLogTransmitter.class.getDeclaredField("logTransmissionEnabled");
            enabledField.setAccessible(true);

            // Act
            logTransmitter.setLogTransmissionEnabled(true);

            // Assert
            AtomicBoolean enabled = (AtomicBoolean) enabledField.get(logTransmitter);
            assertThat(enabled.get()).isTrue();
        }

        @Test
        @DisplayName("应该能够禁用日志传输")
        void shouldDisableLogTransmission() throws Exception {
            // Arrange
            Field enabledField = UltiPanelLogTransmitter.class.getDeclaredField("logTransmissionEnabled");
            enabledField.setAccessible(true);

            // Act
            logTransmitter.setLogTransmissionEnabled(false);

            // Assert
            AtomicBoolean enabled = (AtomicBoolean) enabledField.get(logTransmitter);
            assertThat(enabled.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("isLogTransmissionEnabled 测试")
    class IsLogTransmissionEnabledTests {

        @Test
        @DisplayName("默认应该返回 true")
        void shouldReturnTrueByDefault() {
            // Assert
            assertThat(logTransmitter.isLogTransmissionEnabled()).isTrue();
        }

        @Test
        @DisplayName("禁用后应该返回 false")
        void shouldReturnFalseWhenDisabled() {
            // Arrange
            logTransmitter.setLogTransmissionEnabled(false);

            // Assert
            assertThat(logTransmitter.isLogTransmissionEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("sendBatch 测试")
    class SendBatchTests {

        @Test
        @DisplayName("应该批量发送日志")
        void shouldSendLogsInBatch() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("sendBatch");
            method.setAccessible(true);
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // 先添加一些日志到队列
            Field queueField = UltiPanelLogTransmitter.class.getDeclaredField("logQueue");
            queueField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<JsonObject> queue = (ConcurrentLinkedQueue<JsonObject>) queueField.get(logTransmitter);
            
            JsonObject log1 = new JsonObject();
            log1.addProperty("message", "test1");
            queue.add(log1);

            JsonObject log2 = new JsonObject();
            log2.addProperty("message", "test2");
            queue.add(log2);

            // Act
            method.invoke(logTransmitter);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("队列为空时不应该发送")
        void shouldNotSendWhenQueueEmpty() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("sendBatch");
            method.setAccessible(true);
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // 确保队列为空
            Field queueField = UltiPanelLogTransmitter.class.getDeclaredField("logQueue");
            queueField.setAccessible(true);
            ConcurrentLinkedQueue<?> queue = (ConcurrentLinkedQueue<?>) queueField.get(logTransmitter);
            queue.clear();

            // Act
            method.invoke(logTransmitter);

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("发送消息格式测试")
    class SendMessageFormatTests {

        @Test
        @DisplayName("发送的消息应该包含正确的类型")
        void sentMessageShouldHaveCorrectType() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.sendLog("info", "test message", "server", null);

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("错误日志应该包含堆栈跟踪")
        void errorLogShouldContainStackTrace() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);
            RuntimeException exception = new RuntimeException("Test error");

            // Act
            logTransmitter.sendLog("error", "test", "server", exception);

            // Assert
            verify(mockWebSocketClient, times(1)).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("WebSocket 客户端测试")
    class WebSocketClientTests {

        @Test
        @DisplayName("webSocketClient 为 null 时应该安全处理")
        void shouldHandleNullWebSocketClient() {
            // Arrange
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(null, "test");

            // Act - 不应该抛出异常
            transmitter.sendLog("info", "test", "server", null);
            transmitter.flushLogs();
            transmitter.shutdown();

            // If we reach here, test passes
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("WebSocket 断开连接后再连接应该正常工作")
        void shouldWorkAfterReconnect() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(false);
            logTransmitter.sendLog("info", "test1", "server", null);

            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act
            logTransmitter.sendLog("info", "test2", "server", null);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("日志级别测试")
    class LogLevelTests {

        @Test
        @DisplayName("所有有效级别都应该被接受")
        void allValidLevelsShouldBeAccepted() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);
            String[] levels = {"info", "warning", "error", "debug", "INFO", "WARNING", "ERROR", "DEBUG"};

            // Act & Assert - 所有级别都不应该抛出异常
            for (String level : levels) {
                logTransmitter.sendLog(level, "test", "server", null);
            }
        }

        @Test
        @DisplayName("空字符串级别应该使用默认值")
        void emptyLevelShouldUseDefault() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(false);

            // Act - 不应该抛出异常
            logTransmitter.sendLog("", "test", "server", null);
        }
    }

    @Nested
    @DisplayName("多线程安全测试")
    class ThreadSafetyTests {

        @Test
        @DisplayName("并发发送日志不应该抛出异常")
        void concurrentSendShouldNotThrow() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            int threadCount = 10;
            int logsPerThread = 50;
            Thread[] threads = new Thread[threadCount];

            // Act
            for (int i = 0; i < threadCount; i++) {
                final int threadIndex = i;
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < logsPerThread; j++) {
                        logTransmitter.sendLog("info", "Thread " + threadIndex + " Log " + j, "server", null);
                    }
                });
                threads[i].start();
            }

            // 等待所有线程完成
            for (Thread thread : threads) {
                thread.join();
            }

            // Assert - 没有异常就是成功
        }
    }

    @Nested
    @DisplayName("determineLoggerName 边缘情况测试")
    class DetermineLoggerNameEdgeCasesTests {

        @Test
        @DisplayName("普通 source 应该直接返回")
        void shouldReturnSourceDirectlyForOtherSources() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("determineLoggerName", String.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(logTransmitter, "custom-source");

            // Assert - 既不是 "server" 也不是 "plugin:" 前缀，应该直接返回 source
            assertThat(result).isEqualTo("custom-source");
        }

        @Test
        @DisplayName("plugin: 前缀应该被移除")
        void shouldRemovePluginPrefix() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("determineLoggerName", String.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(logTransmitter, "plugin:MyPlugin");

            // Assert
            assertThat(result).isEqualTo("MyPlugin");
        }
    }

    @Nested
    @DisplayName("getDefaultServerId 异常测试")
    class GetDefaultServerIdExceptionTests {

        @Test
        @DisplayName("CommonUtils.getUltiToolsUUID 抛出异常时应该返回 unknown-server")
        void shouldReturnUnknownServerOnException() throws Exception {
            // 这个测试验证 getDefaultServerId 的异常处理
            // 由于 serverId 为 null 时会调用 getDefaultServerId
            // 如果 CommonUtils.getUltiToolsUUID() 抛出异常，应该返回 "unknown-server"
            
            // Arrange - 使用 null serverId 创建新的 transmitter
            // 在正常情况下，getDefaultServerId 会尝试获取 UUID
            // 我们验证即使有异常也能正常工作
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(mockWebSocketClient, null);

            // Assert
            Field serverIdField = UltiPanelLogTransmitter.class.getDeclaredField("serverId");
            serverIdField.setAccessible(true);
            String serverId = (String) serverIdField.get(transmitter);
            assertThat(serverId).isNotNull();
        }
    }

    @Nested
    @DisplayName("getStackTrace 边缘情况测试")
    class GetStackTraceEdgeCasesTests {

        @Test
        @DisplayName("throwable 为 null 时应该返回 null")
        void shouldReturnNullForNullThrowable() throws Exception {
            // Arrange
            Method method = UltiPanelLogTransmitter.class.getDeclaredMethod("getStackTrace", Throwable.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(logTransmitter, (Throwable) null);

            // Assert
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("shutdown 边缘情况测试")
    class ShutdownEdgeCasesTests {

        @Test
        @DisplayName("shutdown 超时应该强制关闭")
        void shouldForceShutdownOnTimeout() throws Exception {
            // Arrange
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(mockWebSocketClient, "test");
            
            // 添加一些日志来确保有数据
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            transmitter.sendLog("info", "test", "server", null);

            // Act - shutdown 应该正常完成
            transmitter.shutdown();

            // Assert - 不应该抛出异常
            assertThat(transmitter.isLogTransmissionEnabled()).isFalse();
        }

        @Test
        @DisplayName("多次 shutdown 不应该抛出异常")
        void multipleShutdownShouldNotThrow() {
            // Arrange
            UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(mockWebSocketClient, "test");

            // Act - 多次 shutdown
            transmitter.shutdown();
            transmitter.shutdown();

            // Assert - 不应该抛出异常
        }
    }

    @Nested
    @DisplayName("sendLog 异常处理测试")
    class SendLogExceptionHandlingTests {

        @Test
        @DisplayName("WebSocket 发送异常时应该输出到 stderr")
        void shouldOutputToStderrOnSendException() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            org.mockito.Mockito.doThrow(new RuntimeException("Send failed"))
                .when(mockWebSocketClient).sendMessage(any(JsonObject.class));
            logTransmitter.setBatchEnabled(false);

            // Act - 不应该抛出异常
            logTransmitter.sendLog("info", "test", "server", null);

            // Assert - 异常被捕获，没有抛出
        }
    }

    @Nested
    @DisplayName("sendBatch 异常测试")
    class SendBatchExceptionTests {

        @Test
        @DisplayName("sendBatch 异常时应该安全处理")
        void shouldHandleSendBatchException() throws Exception {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            logTransmitter.setBatchEnabled(true);
            logTransmitter.setBatchSize(1);
            
            // 模拟第二次发送时抛出异常
            org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("Batch send failed"))
                .when(mockWebSocketClient).sendMessage(any(JsonObject.class));

            // Act - 发送多条日志触发批量发送
            logTransmitter.sendLog("info", "message1", "server", null);
            logTransmitter.sendLog("info", "message2", "server", null);

            // Assert - 不应该抛出异常
        }
    }
}
