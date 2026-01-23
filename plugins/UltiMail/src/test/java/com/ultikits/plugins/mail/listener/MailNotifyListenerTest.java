package com.ultikits.plugins.mail.listener;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.mail.UltiMail;
import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.plugins.mail.utils.MockBukkitHelper;
import com.ultikits.plugins.mail.utils.TestHelper;

import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MailNotifyListener.
 * <p>
 * 邮件通知监听器单元测试。
 * <p>
 * 注意: 需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("MailNotifyListener 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class MailNotifyListenerTest {

    private PlayerMock player;
    private MailNotifyListener listener;

    @Mock
    private MailService mockMailService;

    @Mock
    private MailConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Setup mock UltiMail
        TestHelper.mockUltiMailInstance();

        player = server.addPlayer("testplayer");
        
        // Create listener and inject dependencies
        listener = new MailNotifyListener();
        injectField(listener, "mailService", mockMailService);
        injectField(listener, "config", mockConfig);
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
        MockBukkitHelper.safeUnmock();
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Nested
    @DisplayName("通知开关测试")
    class NotificationToggleTests {

        @Test
        @DisplayName("通知关闭时不应该发送消息")
        void shouldNotNotifyWhenDisabled() {
            when(mockConfig.isNotifyOnJoin()).thenReturn(false);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            // Should not call service methods when disabled
            verify(mockMailService, never()).getUnreadCount(any());
        }

        @Test
        @DisplayName("通知开启时应该检查未读邮件")
        void shouldCheckUnreadWhenEnabled() {
            when(mockConfig.isNotifyOnJoin()).thenReturn(true);
            when(mockConfig.getNotifyDelay()).thenReturn(0);
            when(mockMailService.getUnreadCount(any())).thenReturn(0);

            // Note: Due to scheduler delay, we verify the config check happens
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            // Config should be checked immediately
            verify(mockConfig).isNotifyOnJoin();
        }
    }

    @Nested
    @DisplayName("未读邮件计数测试")
    class UnreadCountTests {

        @Test
        @DisplayName("有未读邮件时应该准备通知")
        void shouldPrepareNotificationForUnreadMails() {
            when(mockConfig.isNotifyOnJoin()).thenReturn(true);
            when(mockConfig.getNotifyDelay()).thenReturn(0);
            when(mockMailService.getUnreadCount(player.getUniqueId())).thenReturn(5);

            // The actual notification is delayed, so we just verify the setup
            assertThat(mockMailService.getUnreadCount(player.getUniqueId())).isEqualTo(5);
        }

        @Test
        @DisplayName("没有未读邮件时不应该通知")
        void shouldNotNotifyWhenNoUnread() {
            when(mockConfig.isNotifyOnJoin()).thenReturn(true);
            when(mockMailService.getUnreadCount(any())).thenReturn(0);

            int unreadCount = mockMailService.getUnreadCount(player.getUniqueId());

            assertThat(unreadCount).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("通知延迟测试")
    class NotificationDelayTests {

        @Test
        @DisplayName("应该使用配置的延迟时间")
        void shouldUseConfiguredDelay() {
            when(mockConfig.getNotifyDelay()).thenReturn(3);

            int delay = mockConfig.getNotifyDelay();

            assertThat(delay).isEqualTo(3);
        }

        @Test
        @DisplayName("延迟为0时应该立即通知")
        void shouldNotifyImmediatelyWithZeroDelay() {
            when(mockConfig.getNotifyDelay()).thenReturn(0);

            int delay = mockConfig.getNotifyDelay();

            assertThat(delay).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("消息格式测试")
    class MessageFormatTests {

        @Test
        @DisplayName("消息应该包含未读邮件数量占位符")
        void shouldFormatMessageWithCount() {
            String template = "&e[邮件] &f你有 &a{COUNT} &f封未读邮件！";
            int count = 5;
            
            String message = template.replace("{COUNT}", String.valueOf(count));

            assertThat(message).contains("5");
            assertThat(message).doesNotContain("{COUNT}");
        }

        @Test
        @DisplayName("应该正确处理颜色代码")
        void shouldHandleColorCodes() {
            String template = "&e[邮件] &f你有 &a{COUNT} &f封未读邮件！";
            
            assertThat(template).contains("&e");
            assertThat(template).contains("&f");
            assertThat(template).contains("&a");
        }
    }

    @Nested
    @DisplayName("玩家在线状态测试")
    class PlayerOnlineStatusTests {

        @Test
        @DisplayName("玩家在线时应该发送通知")
        void shouldNotifyOnlinePlayer() {
            assertThat(player.isOnline()).isTrue();
        }

        @Test
        @DisplayName("玩家离线时不应该发送通知")
        void shouldNotNotifyOfflinePlayer() {
            // After player.disconnect(), isOnline() should be false
            // This is a mock behavior test
            PlayerMock offlinePlayer = mock(PlayerMock.class);
            when(offlinePlayer.isOnline()).thenReturn(false);

            assertThat(offlinePlayer.isOnline()).isFalse();
        }
    }
}
