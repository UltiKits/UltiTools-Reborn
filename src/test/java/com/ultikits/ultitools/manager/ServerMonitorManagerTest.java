package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * ServerMonitorManager 测试
 */
@DisplayName("ServerMonitorManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class ServerMonitorManagerTest {

    private ServerMonitorManager serverMonitorManager;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Mock logger
        mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        serverMonitorManager = new ServerMonitorManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
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
    @DisplayName("sampleTpsAndCpu 测试")
    class SampleTpsAndCpuTests {

        @Test
        @DisplayName("应该在主线程上采样一次 TPS 与 CPU")
        void shouldSampleWithoutStartingV1Monitoring() throws Exception {
            // Arrange
            Field indexField = ServerMonitorManager.class.getDeclaredField("historyIndex");
            indexField.setAccessible(true);
            int initialIndex = indexField.getInt(serverMonitorManager);

            // Act
            boolean sampled = serverMonitorManager.sampleTpsAndCpu();

            // Assert
            assertThat(sampled).isTrue();
            assertThat(indexField.getInt(serverMonitorManager)).isEqualTo(initialIndex + 1);
        }

        @Test
        @DisplayName("非主线程调用应该被拒绝且不记录新的 TPS")
        void shouldRefuseOffTheMainThread() throws Exception {
            // Arrange
            Field indexField = ServerMonitorManager.class.getDeclaredField("historyIndex");
            indexField.setAccessible(true);
            int initialIndex = indexField.getInt(serverMonitorManager);
            ExecutorService executor = Executors.newSingleThreadExecutor();

            try {
                // Act
                Future<Boolean> sampled = executor.submit(serverMonitorManager::sampleTpsAndCpu);

                // Assert
                assertThat(sampled.get(10, TimeUnit.SECONDS)).isFalse();
            } finally {
                executor.shutdownNow();
            }
            assertThat(indexField.getInt(serverMonitorManager)).isEqualTo(initialIndex);
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
}
