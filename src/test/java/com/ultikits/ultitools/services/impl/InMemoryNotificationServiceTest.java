package com.ultikits.ultitools.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.widgets.Toast;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;

/**
 * InMemoryNotificationService 测试
 */
@DisplayName("InMemoryNotificationService 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class InMemoryNotificationServiceTest {

    private ServerMock server;
    private InMemoryNotificationService notificationService;
    private Logger mockLogger;
    private VersionWrapper mockVersionWrapper;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        // Mock logger
        mockLogger = mock(Logger.class);
        when(UltiTools.getInstance().getLogger()).thenReturn(mockLogger);

        // Mock VersionWrapper
        mockVersionWrapper = mock(VersionWrapper.class);
        when(UltiTools.getInstance().getVersionWrapper()).thenReturn(mockVersionWrapper);

        notificationService = new InMemoryNotificationService();
        
        // 清空静态 Map
        clearStaticMaps();
    }

    @AfterEach
    void tearDown() {
        clearStaticMaps();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }
    
    @SuppressWarnings("unchecked")
    private void clearStaticMaps() {
        try {
            Field atedPlayerField = InMemoryNotificationService.class.getDeclaredField("atedPlayer");
            atedPlayerField.setAccessible(true);
            Map<UUID, BossBar> atedPlayer = (Map<UUID, BossBar>) atedPlayerField.get(null);
            atedPlayer.clear();
        } catch (Exception e) {
            // 忽略清理异常
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该成功创建实例")
        void shouldCreateInstance() {
            // Assert
            assertThat(notificationService).isNotNull();
        }
    }

    @Nested
    @DisplayName("getName 测试")
    class GetNameTests {

        @Test
        @DisplayName("应该返回正确的服务名称")
        void shouldReturnCorrectName() {
            // Act
            String name = notificationService.getName();

            // Assert
            assertThat(name).isEqualTo("InMemoryNotificationService");
        }
    }

    @Nested
    @DisplayName("getAuthor 测试")
    class GetAuthorTests {

        @Test
        @DisplayName("应该返回正确的作者")
        void shouldReturnCorrectAuthor() {
            // Act
            String author = notificationService.getAuthor();

            // Assert
            assertThat(author).isEqualTo("wisdomme");
        }
    }

    @Nested
    @DisplayName("getVersion 测试")
    class GetVersionTests {

        @Test
        @DisplayName("应该返回版本 1")
        void shouldReturnVersion1() {
            // Act
            int version = notificationService.getVersion();

            // Assert
            assertThat(version).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("sendMessageNotification 测试")
    class SendMessageNotificationTests {

        @Test
        @DisplayName("应该向玩家发送消息")
        void shouldSendMessageToPlayer() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Test message";

            // Act
            boolean result = notificationService.sendMessageNotification(player, message);

            // Assert
            assertThat(result).isFalse(); // 该方法返回 false
            assertThat(player.nextMessage()).isEqualTo(message);
        }

        @Test
        @DisplayName("带声音的消息通知")
        void shouldSendMessageWithSound() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Test message with sound";
            Sound sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;

            // Act
            boolean result = notificationService.sendMessageNotification(player, message, sound);

            // Assert
            assertThat(result).isFalse();
            assertThat(player.nextMessage()).isEqualTo(message);
        }

        @Test
        @DisplayName("null 声音时不应该抛出异常")
        void shouldNotThrowWhenSoundIsNull() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Test message";

            // Act - 不应该抛出异常
            boolean result = notificationService.sendMessageNotification(player, message, null);

            // Assert
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("sendTitleNotification 测试")
    class SendTitleNotificationTests {

        @Test
        @DisplayName("应该发送标题通知")
        void shouldSendTitleNotification() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String title = "Test Title";
            String subtitle = "Test Subtitle";

            // Act
            boolean result = notificationService.sendTitleNotification(player, title, subtitle);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("带声音的标题通知")
        void shouldSendTitleWithSound() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String title = "Test Title";
            String subtitle = "Test Subtitle";
            Sound sound = Sound.ENTITY_PLAYER_LEVELUP;

            // Act
            boolean result = notificationService.sendTitleNotification(player, title, subtitle, sound);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("带自定义时间的标题通知")
        void shouldSendTitleWithCustomTiming() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String title = "Test Title";
            String subtitle = "Test Subtitle";

            // Act
            boolean result = notificationService.sendTitleNotification(
                player, title, subtitle, null, 5, 100, 10);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("空标题应该正常工作")
        void emptyTitleShouldWork() {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act
            boolean result = notificationService.sendTitleNotification(player, "", "Subtitle only");

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("sendSubTitleNotification 测试")
    class SendSubTitleNotificationTests {

        @Test
        @DisplayName("应该发送副标题通知")
        void shouldSendSubtitleNotification() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String subtitle = "Test Subtitle";

            // Act
            boolean result = notificationService.sendSubTitleNotification(player, subtitle);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("带声音的副标题通知")
        void shouldSendSubtitleWithSound() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String subtitle = "Test Subtitle";
            Sound sound = Sound.BLOCK_NOTE_BLOCK_PLING;

            // Act
            boolean result = notificationService.sendSubTitleNotification(player, subtitle, sound);

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("sendBossBarNotification 测试")
    class SendBossBarNotificationTests {

        @Test
        @DisplayName("应该发送 BossBar 通知")
        void shouldSendBossBarNotification() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Test BossBar";

            // Act
            boolean result = notificationService.sendBossBarNotification(player, message);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("带时长的 BossBar 通知")
        void shouldSendBossBarWithDuration() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Test BossBar";
            int seconds = 10;

            // Act
            boolean result = notificationService.sendBossBarNotification(player, message, seconds);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("带声音的 BossBar 通知")
        void shouldSendBossBarWithSound() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Test BossBar";
            Sound sound = Sound.ENTITY_VILLAGER_YES;

            // Act
            boolean result = notificationService.sendBossBarNotification(player, message, 10, sound);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("自定义 BossBar 通知")
        void shouldSendCustomBossBar() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Test BossBar";
            BossBar bossBar = server.createBossBar("Custom", org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SOLID);

            // Act
            boolean result = notificationService.sendBossBarNotification(player, message, 10, bossBar);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("同一玩家多次发送应该复用 BossBar")
        @SuppressWarnings("unchecked")
        void shouldReuseBossBarForSamePlayer() throws Exception {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act
            notificationService.sendBossBarNotification(player, "First", 10);
            notificationService.sendBossBarNotification(player, "Second", 10);

            // Assert
            Field field = InMemoryNotificationService.class.getDeclaredField("atedPlayer");
            field.setAccessible(true);
            Map<UUID, BossBar> atedPlayer = (Map<UUID, BossBar>) field.get(null);
            assertThat(atedPlayer).containsKey(player.getUniqueId());
        }

        @Test
        @DisplayName("完整参数的 BossBar 通知")
        void shouldSendBossBarWithAllParams() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Full BossBar";
            BossBar bossBar = server.createBossBar("Custom", org.bukkit.boss.BarColor.BLUE, org.bukkit.boss.BarStyle.SEGMENTED_10);
            Sound sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;

            // Act
            boolean result = notificationService.sendBossBarNotification(player, message, 15, bossBar, sound);

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("sendActionBarNotification 测试")
    class SendActionBarNotificationTests {

        @Test
        @DisplayName("应该发送 ActionBar 通知")
        void shouldSendActionBarNotification() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String message = "Action Bar Message";

            // Act
            boolean result = notificationService.sendActionBarNotification(player, message);

            // Assert - 验证方法执行成功（ActionBar 现在通过 XVersionUtils 静态方法发送）
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("空消息应该正常工作")
        void emptyMessageShouldWork() {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act
            boolean result = notificationService.sendActionBarNotification(player, "");

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("sendToastNotification 测试")
    class SendToastNotificationTests {

        @Test
        @DisplayName("sendToastNotification 方法应该存在")
        void sendToastMethodShouldExist() throws Exception {
            // Assert - Toast 依赖 UltiTools.getInstance().getName()，在 MockBukkit 环境中无法完全测试
            // 这里验证方法存在
            java.lang.reflect.Method method = InMemoryNotificationService.class.getMethod(
                "sendToastNotification", Player.class, String.class, String.class, Toast.Style.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("Toast.Style 枚举应该包含所有样式")
        void toastStyleEnumShouldContainAllStyles() {
            // Assert
            assertThat(Toast.Style.values()).contains(
                Toast.Style.TASK, 
                Toast.Style.GOAL, 
                Toast.Style.CHALLENGE
            );
        }
    }

    @Nested
    @DisplayName("接口实现测试")
    class InterfaceImplementationTests {

        @Test
        @DisplayName("应该实现 NotificationService 接口")
        void shouldImplementNotificationService() {
            // Assert
            assertThat(notificationService).isInstanceOf(
                com.ultikits.ultitools.services.NotificationService.class);
        }

        @Test
        @DisplayName("应该有 @Service 注解")
        void shouldHaveServiceAnnotation() {
            // Assert
            assertThat(InMemoryNotificationService.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.Service.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("多玩家通知测试")
    class MultiPlayerNotificationTests {

        @Test
        @DisplayName("不同玩家应该有独立的 BossBar")
        @SuppressWarnings("unchecked")
        void differentPlayersShouldHaveIndependentBossBars() throws Exception {
            // Arrange
            PlayerMock player1 = server.addPlayer("Player1");
            PlayerMock player2 = server.addPlayer("Player2");

            // Act
            notificationService.sendBossBarNotification(player1, "Message 1", 10);
            notificationService.sendBossBarNotification(player2, "Message 2", 10);

            // Assert
            Field field = InMemoryNotificationService.class.getDeclaredField("atedPlayer");
            field.setAccessible(true);
            Map<UUID, BossBar> atedPlayer = (Map<UUID, BossBar>) field.get(null);
            
            assertThat(atedPlayer).containsKey(player1.getUniqueId());
            assertThat(atedPlayer).containsKey(player2.getUniqueId());
            assertThat(atedPlayer.get(player1.getUniqueId()))
                .isNotSameAs(atedPlayer.get(player2.getUniqueId()));
        }

        @Test
        @DisplayName("多个玩家同时收到消息通知")
        void multiplePlayersShouldReceiveMessageNotifications() {
            // Arrange
            PlayerMock player1 = server.addPlayer("Player1");
            PlayerMock player2 = server.addPlayer("Player2");
            PlayerMock player3 = server.addPlayer("Player3");

            // Act
            notificationService.sendMessageNotification(player1, "Message 1");
            notificationService.sendMessageNotification(player2, "Message 2");
            notificationService.sendMessageNotification(player3, "Message 3");

            // Assert
            assertThat(player1.nextMessage()).isEqualTo("Message 1");
            assertThat(player2.nextMessage()).isEqualTo("Message 2");
            assertThat(player3.nextMessage()).isEqualTo("Message 3");
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("非常长的消息应该正常处理")
        void veryLongMessageShouldBeHandled() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String longMessage = "A".repeat(1000);

            // Act
            boolean result = notificationService.sendMessageNotification(player, longMessage);

            // Assert
            assertThat(result).isFalse();
            assertThat(player.nextMessage()).isEqualTo(longMessage);
        }

        @Test
        @DisplayName("BossBar 持续时间为 0 应该正常工作")
        void bossBarWithZeroDurationShouldWork() {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act
            boolean result = notificationService.sendBossBarNotification(player, "Zero duration", 0);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("标题通知的 fadeIn/stay/fadeOut 为 0 应该正常工作")
        void titleWithZeroTimingShouldWork() {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act
            boolean result = notificationService.sendTitleNotification(
                player, "Title", "Subtitle", null, 0, 0, 0);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("特殊字符消息应该正常处理")
        void specialCharactersShouldBeHandled() {
            // Arrange
            PlayerMock player = server.addPlayer();
            String specialMessage = "§c红色 §l粗体 §n下划线 \n 换行 \t 制表符";

            // Act
            notificationService.sendMessageNotification(player, specialMessage);

            // Assert
            assertThat(player.nextMessage()).isEqualTo(specialMessage);
        }
    }

    @Nested
    @DisplayName("静态字段测试")
    class StaticFieldTests {

        @Test
        @DisplayName("atedPlayer 应该是 HashMap")
        void atedPlayerShouldBeHashMap() throws Exception {
            // Arrange
            Field field = InMemoryNotificationService.class.getDeclaredField("atedPlayer");
            field.setAccessible(true);

            // Assert
            assertThat(field.get(null)).isNotNull();
            assertThat(field.getType().getSimpleName()).isEqualTo("Map");
        }
    }

    @Nested
    @DisplayName("返回值测试")
    class ReturnValueTests {

        @Test
        @DisplayName("sendMessageNotification 应该返回 false")
        void sendMessageShouldReturnFalse() {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act & Assert
            assertThat(notificationService.sendMessageNotification(player, "test")).isFalse();
            assertThat(notificationService.sendMessageNotification(player, "test", null)).isFalse();
        }

        @Test
        @DisplayName("sendTitleNotification 应该返回 true")
        void sendTitleShouldReturnTrue() {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act & Assert
            assertThat(notificationService.sendTitleNotification(player, "t", "s")).isTrue();
            assertThat(notificationService.sendTitleNotification(player, "t", "s", null)).isTrue();
        }

        @Test
        @DisplayName("sendActionBarNotification 应该返回 true")
        void sendActionBarShouldReturnTrue() {
            // Arrange
            PlayerMock player = server.addPlayer();

            // Act & Assert
            assertThat(notificationService.sendActionBarNotification(player, "test")).isTrue();
        }

        @Test
        @DisplayName("sendToastNotification 方法存在且返回 boolean")
        void sendToastMethodShouldExist() throws Exception {
            // Assert - Toast 依赖 UltiTools.getInstance().getName()，无法在 MockBukkit 中测试
            java.lang.reflect.Method method = InMemoryNotificationService.class.getMethod(
                "sendToastNotification", Player.class, String.class, String.class, Toast.Style.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }
    }

    @Nested
    @DisplayName("processBossBarTick 方法测试 - 可测试的提取方法")
    class ProcessBossBarTickTests {

        @Test
        @DisplayName("进度大于0时应该减少进度并返回shouldCancel=false")
        void shouldDecreaseProgressWhenProgressGreaterThanZero() {
            // Arrange
            PlayerMock player = server.addPlayer();
            BossBar bossBar = Bukkit.createBossBar("Test", BarColor.GREEN, BarStyle.SOLID);
            bossBar.setProgress(1.0);
            bossBar.addPlayer(player);
            int seconds = 4; // 1.0 / (4 * 4) = 0.0625 per tick

            // Act
            InMemoryNotificationService.BossBarTickResult result = 
                InMemoryNotificationService.processBossBarTick(bossBar, player, seconds);

            // Assert
            assertThat(result.shouldCancel).isFalse();
            assertThat(result.newProgress).isLessThan(1.0);
            assertThat(result.newProgress).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("进度到达0时应该移除玩家并返回shouldCancel=true")
        void shouldRemovePlayerAndCancelWhenProgressReachesZero() {
            // Arrange
            PlayerMock player = server.addPlayer();
            BossBar bossBar = Bukkit.createBossBar("Test", BarColor.GREEN, BarStyle.SOLID);
            bossBar.setProgress(0.01); // 很小的进度
            bossBar.addPlayer(player);
            int seconds = 1; // 每tick减少 0.25

            // Act
            InMemoryNotificationService.BossBarTickResult result = 
                InMemoryNotificationService.processBossBarTick(bossBar, player, seconds);

            // Assert
            assertThat(result.shouldCancel).isTrue();
            assertThat(result.newProgress).isEqualTo(0);
        }

        @Test
        @DisplayName("不同秒数应该有不同的进度减少量")
        void differentSecondsShouldHaveDifferentDecrements() {
            // Arrange
            PlayerMock player1 = server.addPlayer("Player1");
            PlayerMock player2 = server.addPlayer("Player2");
            
            BossBar bossBar1 = Bukkit.createBossBar("Test1", BarColor.GREEN, BarStyle.SOLID);
            BossBar bossBar2 = Bukkit.createBossBar("Test2", BarColor.GREEN, BarStyle.SOLID);
            bossBar1.setProgress(1.0);
            bossBar2.setProgress(1.0);
            bossBar1.addPlayer(player1);
            bossBar2.addPlayer(player2);

            // Act
            InMemoryNotificationService.BossBarTickResult result1 = 
                InMemoryNotificationService.processBossBarTick(bossBar1, player1, 10);
            InMemoryNotificationService.BossBarTickResult result2 = 
                InMemoryNotificationService.processBossBarTick(bossBar2, player2, 20);

            // Assert - 更长的持续时间应该有更小的减少量
            double decrement1 = 1.0 - result1.newProgress;
            double decrement2 = 1.0 - result2.newProgress;
            assertThat(decrement1).isGreaterThan(decrement2);
        }

        @Test
        @DisplayName("多次tick后进度应该正确累积减少")
        void multipleTicksShouldAccumulateProgressReduction() {
            // Arrange
            PlayerMock player = server.addPlayer();
            BossBar bossBar = Bukkit.createBossBar("Test", BarColor.GREEN, BarStyle.SOLID);
            bossBar.setProgress(1.0);
            bossBar.addPlayer(player);
            int seconds = 4;

            // Act - 模拟多次tick
            InMemoryNotificationService.BossBarTickResult result1 = 
                InMemoryNotificationService.processBossBarTick(bossBar, player, seconds);
            InMemoryNotificationService.BossBarTickResult result2 = 
                InMemoryNotificationService.processBossBarTick(bossBar, player, seconds);
            InMemoryNotificationService.BossBarTickResult result3 = 
                InMemoryNotificationService.processBossBarTick(bossBar, player, seconds);

            // Assert
            assertThat(result1.newProgress).isGreaterThan(result2.newProgress);
            assertThat(result2.newProgress).isGreaterThan(result3.newProgress);
        }
    }

    @Nested
    @DisplayName("BossBar 缓存辅助方法测试")
    class BossBarCacheHelperTests {

        @Test
        @DisplayName("cacheBossBar 应该缓存 BossBar")
        void cacheBossBarShouldCache() {
            // Arrange
            UUID playerUUID = UUID.randomUUID();
            BossBar bossBar = Bukkit.createBossBar("Test", BarColor.GREEN, BarStyle.SOLID);

            // Act
            InMemoryNotificationService.cacheBossBar(playerUUID, bossBar);

            // Assert
            assertThat(InMemoryNotificationService.getCachedBossBar(playerUUID)).isEqualTo(bossBar);
        }

        @Test
        @DisplayName("getCachedBossBar 对于未缓存的玩家应返回null")
        void getCachedBossBarShouldReturnNullForUncachedPlayer() {
            // Arrange
            UUID playerUUID = UUID.randomUUID();

            // Act & Assert
            assertThat(InMemoryNotificationService.getCachedBossBar(playerUUID)).isNull();
        }

        @Test
        @DisplayName("clearBossBarCache 应该清除所有缓存")
        void clearBossBarCacheShouldClearAll() {
            // Arrange
            UUID playerUUID1 = UUID.randomUUID();
            UUID playerUUID2 = UUID.randomUUID();
            BossBar bossBar1 = Bukkit.createBossBar("Test1", BarColor.GREEN, BarStyle.SOLID);
            BossBar bossBar2 = Bukkit.createBossBar("Test2", BarColor.RED, BarStyle.SOLID);
            InMemoryNotificationService.cacheBossBar(playerUUID1, bossBar1);
            InMemoryNotificationService.cacheBossBar(playerUUID2, bossBar2);

            // Act
            InMemoryNotificationService.clearBossBarCache();

            // Assert
            assertThat(InMemoryNotificationService.getCachedBossBar(playerUUID1)).isNull();
            assertThat(InMemoryNotificationService.getCachedBossBar(playerUUID2)).isNull();
        }
    }
}
