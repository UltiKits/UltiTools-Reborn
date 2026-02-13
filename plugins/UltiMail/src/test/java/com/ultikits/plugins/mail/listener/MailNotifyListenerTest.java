package com.ultikits.plugins.mail.listener;

import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.plugins.mail.utils.TestHelper;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MailNotifyListener.
 * <p>
 * Uses pure Mockito (no MockBukkit) for maximum compatibility.
 */
@DisplayName("MailNotifyListener 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class MailNotifyListenerTest {

    private MailNotifyListener listener;

    @Mock
    private MailService mockMailService;

    private MailConfig config;

    @Mock
    private Player player;

    @Mock
    private BukkitScheduler mockScheduler;

    @Mock
    private BukkitTask mockTask;

    @Mock
    private Player.Spigot mockSpigot;

    private MockedStatic<Bukkit> mockedBukkit;

    private UUID playerUuid;

    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() throws Exception {
        // Setup mock UltiToolsPlugin
        mockPlugin = TestHelper.mockUltiToolsPlugin();

        // Use real config
        config = new MailConfig();

        playerUuid = UUID.randomUUID();
        lenient().when(player.getUniqueId()).thenReturn(playerUuid);
        lenient().when(player.getName()).thenReturn("TestPlayer");
        lenient().when(player.isOnline()).thenReturn(true);
        lenient().when(player.spigot()).thenReturn(mockSpigot);

        // Mock Bukkit static
        mockedBukkit = mockStatic(Bukkit.class);
        mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

        // Mock scheduler to capture and run the delayed task immediately
        lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
            .thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(1);
                runnable.run();
                return mockTask;
            });

        // Create listener and inject dependencies
        listener = new MailNotifyListener();
        TestHelper.injectField(listener, "mailService", mockMailService);
        TestHelper.injectField(listener, "config", config);
        TestHelper.injectField(listener, "plugin", mockPlugin);
    }

    @AfterEach
    void tearDown() {
        mockedBukkit.close();
        TestHelper.cleanupMocks();
    }


    // ==================== Notification Toggle Tests ====================

    @Nested
    @DisplayName("通知开关测试")
    class NotificationToggleTests {

        @Test
        @DisplayName("通知关闭时不应该调用邮件服务")
        void shouldNotNotifyWhenDisabled() {
            config.setNotifyOnJoin(false);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            verify(mockMailService, never()).getUnreadCount(any());
        }

        @Test
        @DisplayName("通知开启时应该检查未读邮件")
        void shouldCheckUnreadWhenEnabled() {
            config.setNotifyOnJoin(true);
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(0);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            verify(mockMailService).getUnreadCount(playerUuid);
        }
    }

    // ==================== Unread Count Tests ====================

    @Nested
    @DisplayName("未读邮件通知测试")
    class UnreadCountTests {

        @Test
        @DisplayName("有未读邮件时应该发送通知")
        void shouldNotifyWhenHasUnreadMails() {
            config.setNotifyOnJoin(true);
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(5);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            // Notification is sent via spigot API (sendMessage with TextComponents)
            verify(mockSpigot).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent[].class));
        }

        @Test
        @DisplayName("没有未读邮件时不应该通知")
        void shouldNotNotifyWhenNoUnread() {
            config.setNotifyOnJoin(true);
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(0);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            // No spigot message should be sent
            verify(mockSpigot, never()).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent[].class));
        }
    }

    // ==================== Player Online Status Tests ====================

    @Nested
    @DisplayName("玩家在线状态测试")
    class PlayerOnlineStatusTests {

        @Test
        @DisplayName("玩家离线时不应发送通知")
        void shouldNotNotifyIfPlayerWentOffline() {
            config.setNotifyOnJoin(true);
            when(player.isOnline()).thenReturn(false);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            verify(mockMailService, never()).getUnreadCount(any());
        }
    }

    // ==================== Delay Tests ====================

    @Nested
    @DisplayName("通知延迟测试")
    class NotificationDelayTests {

        @Test
        @DisplayName("应该使用配置的延迟时间")
        void shouldUseConfiguredDelay() {
            config.setNotifyOnJoin(true);
            config.setNotifyDelay(5);
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(1);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            // Verify runTaskLater was called with delay of 5 * 20 = 100 ticks
            verify(mockScheduler).runTaskLater(any(), any(Runnable.class), eq(100L));
        }

        @Test
        @DisplayName("延迟为0时应使用0 ticks")
        void shouldUseZeroTicksForZeroDelay() {
            config.setNotifyOnJoin(true);
            config.setNotifyDelay(0);
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(1);

            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            listener.onPlayerJoin(event);

            verify(mockScheduler).runTaskLater(any(), any(Runnable.class), eq(0L));
        }
    }

    // ==================== Annotation Tests ====================

    @Nested
    @DisplayName("注解配置测试")
    class AnnotationTests {

        @Test
        @DisplayName("类应该有 @EventListener 注解")
        void shouldHaveEventListenerAnnotation() {
            assertThat(MailNotifyListener.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.EventListener.class
            )).isTrue();
        }

        @Test
        @DisplayName("应该实现 Bukkit Listener 接口")
        void shouldImplementListener() {
            assertThat(org.bukkit.event.Listener.class)
                .isAssignableFrom(MailNotifyListener.class);
        }

        @Test
        @DisplayName("onPlayerJoin 应该有 @EventHandler 注解")
        void shouldHaveEventHandlerAnnotation() throws Exception {
            boolean hasAnnotation = MailNotifyListener.class
                .getMethod("onPlayerJoin", PlayerJoinEvent.class)
                .isAnnotationPresent(org.bukkit.event.EventHandler.class);

            assertThat(hasAnnotation).isTrue();
        }
    }
}
