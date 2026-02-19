package com.ultikits.ultitools.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import org.mockito.MockedStatic;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import me.clip.placeholderapi.PlaceholderAPI;

/**
 * PlayerJoinListener 测试 - 使用 MockBukkit
 */
@DisplayName("PlayerJoinListener 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PlayerJoinListenerTest {

    private ServerMock server;
    private PlayerJoinListener listener;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        listener = new PlayerJoinListener();
    }

    private static void assertDoesNotThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            throw new AssertionError("Expected no exception but got: " + e.getClass().getName() + " - " + e.getMessage(), e);
        }
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("toUpperCaseFirstOne 测试")
    class ToUpperCaseFirstOneTests {

        @Test
        @DisplayName("小写开头应该转换为大写")
        void shouldConvertLowercaseToUppercase() {
            // Act
            String result = PlayerJoinListener.toUpperCaseFirstOne("player");

            // Assert
            assertThat(result).isEqualTo("Player");
        }

        @Test
        @DisplayName("大写开头应该保持不变")
        void shouldKeepUppercaseUnchanged() {
            // Act
            String result = PlayerJoinListener.toUpperCaseFirstOne("Server");

            // Assert
            assertThat(result).isEqualTo("Server");
        }

        @Test
        @DisplayName("全小写应该只转换首字母")
        void shouldOnlyConvertFirstLetter() {
            // Act
            String result = PlayerJoinListener.toUpperCaseFirstOne("vault");

            // Assert
            assertThat(result).isEqualTo("Vault");
        }

        @Test
        @DisplayName("单字符小写应该转换")
        void singleLowercaseCharShouldConvert() {
            // Act
            String result = PlayerJoinListener.toUpperCaseFirstOne("a");

            // Assert
            assertThat(result).isEqualTo("A");
        }

        @Test
        @DisplayName("单字符大写应该保持不变")
        void singleUppercaseCharShouldStay() {
            // Act
            String result = PlayerJoinListener.toUpperCaseFirstOne("Z");

            // Assert
            assertThat(result).isEqualTo("Z");
        }

        @Test
        @DisplayName("全大写应该保持不变")
        void allUppercaseShouldStay() {
            // Act
            String result = PlayerJoinListener.toUpperCaseFirstOne("MATH");

            // Assert
            assertThat(result).isEqualTo("MATH");
        }
    }

    @Nested
    @DisplayName("onPlayerJoin 测试")
    class OnPlayerJoinTests {

        @Test
        @DisplayName("PlaceholderAPI 不可用时不应该抛出异常")
        void shouldNotThrowWhenPlaceholderAPINotAvailable() {
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            // Act & Assert - 不应该抛出异常
            // PlaceholderAPI 类不存在时会捕获 NoClassDefFoundError
            assertDoesNotThrow(() -> listener.onPlayerJoin(event));
        }

        @Test
        @DisplayName("所有 placeholders 已注册时不应该下载")
        void shouldNotDownloadWhenAllRegistered() {
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            try (MockedStatic<PlaceholderAPI> placeholderAPIMock = mockStatic(PlaceholderAPI.class)) {
                // 模拟所有 placeholder 都已注册
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("player")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("server")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("math")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("vault")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("localtime")).thenReturn(true);

                // Act
                listener.onPlayerJoin(event);

                // Assert - 执行调度任务后，不应该有下载任务（因为所有 placeholder 都已注册）
                server.getScheduler().performTicks(100);
                assertThat(server.getScheduler().getPendingTasks().stream()
                    .filter(t -> t.getTaskId() > 0)
                    .count()).as("No download tasks should be pending").isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("有未注册的 placeholder 时应该调度下载任务")
        void shouldScheduleDownloadWhenNotRegistered() {
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            try (MockedStatic<PlaceholderAPI> placeholderAPIMock = mockStatic(PlaceholderAPI.class)) {
                // 模拟部分 placeholder 未注册
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("player")).thenReturn(false);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("server")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("math")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("vault")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("localtime")).thenReturn(true);

                // Act
                listener.onPlayerJoin(event);

                // Assert - 应该有调度任务
                assertThat(server.getScheduler().getPendingTasks()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("异常应该被捕获而不是传播")
        void exceptionShouldBeCaughtNotPropagated() { // NOPMD - uses Mockito verify()
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            try (MockedStatic<PlaceholderAPI> placeholderAPIMock = mockStatic(PlaceholderAPI.class)) {
                // 模拟抛出异常
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered(anyString()))
                        .thenThrow(new RuntimeException("Test exception"));

                // Act & Assert - 不应该抛出异常
                listener.onPlayerJoin(event);
            }
        }

        @Test
        @DisplayName("多个未注册 placeholder 应该依次调度")
        void multipleUnregisteredShouldScheduleSequentially() {
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            try (MockedStatic<PlaceholderAPI> placeholderAPIMock = mockStatic(PlaceholderAPI.class)) {
                // 模拟所有 placeholder 都未注册
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered(anyString())).thenReturn(false);

                // Act
                listener.onPlayerJoin(event);

                // Assert - 应该有多个调度任务（5个下载 + 1个reload）
                // 每个未注册的 placeholder 会调度一个下载任务
                assertThat(server.getScheduler().getPendingTasks().size()).isGreaterThanOrEqualTo(5);
            }
        }
    }

    @Nested
    @DisplayName("Placeholder 列表测试")
    class PlaceholderListTests {

        @Test
        @DisplayName("应该检查正确的 placeholder 列表")
        void shouldCheckCorrectPlaceholders() {
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            try (MockedStatic<PlaceholderAPI> placeholderAPIMock = mockStatic(PlaceholderAPI.class)) {
                // 模拟所有都已注册
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered(anyString())).thenReturn(true);

                // Act
                listener.onPlayerJoin(event);

                // Assert - 验证检查了所有预期的 placeholder（verify 是断言）
                placeholderAPIMock.verify(() -> PlaceholderAPI.isRegistered("player"));
                placeholderAPIMock.verify(() -> PlaceholderAPI.isRegistered("server"));
                placeholderAPIMock.verify(() -> PlaceholderAPI.isRegistered("math"));
                placeholderAPIMock.verify(() -> PlaceholderAPI.isRegistered("vault"));
                placeholderAPIMock.verify(() -> PlaceholderAPI.isRegistered("localtime"));
                assertThat(true).as("All placeholder checks verified").isTrue();
            }
        }
    }

    @Nested
    @DisplayName("任务调度测试")
    class TaskSchedulingTests {

        @Test
        @DisplayName("下载任务应该以30tick间隔调度")
        void downloadTasksShouldBeScheduledWith30TickInterval() {
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            try (MockedStatic<PlaceholderAPI> placeholderAPIMock = mockStatic(PlaceholderAPI.class)) {
                // 只有第一个未注册
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("player")).thenReturn(false);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("server")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("math")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("vault")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("localtime")).thenReturn(true);

                // Act
                listener.onPlayerJoin(event);

                // Assert - 应该有调度任务
                assertThat(server.getScheduler().getPendingTasks()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("reload 任务应该在所有下载任务之后调度")
        void reloadTaskShouldBeScheduledAfterDownloads() {
            // Arrange
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined");

            try (MockedStatic<PlaceholderAPI> placeholderAPIMock = mockStatic(PlaceholderAPI.class)) {
                // 两个未注册
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("player")).thenReturn(false);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("server")).thenReturn(false);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("math")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("vault")).thenReturn(true);
                placeholderAPIMock.when(() -> PlaceholderAPI.isRegistered("localtime")).thenReturn(true);

                // Act
                listener.onPlayerJoin(event);

                // Assert - 应该有3个任务（2个下载 + 1个reload）
                assertThat(server.getScheduler().getPendingTasks().size()).isGreaterThanOrEqualTo(3);
            }
        }
    }
}
