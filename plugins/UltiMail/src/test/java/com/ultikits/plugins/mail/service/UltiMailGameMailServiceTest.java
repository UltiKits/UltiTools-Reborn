package com.ultikits.plugins.mail.service;

import com.ultikits.plugins.mail.utils.TestHelper;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UltiMailGameMailService.
 * <p>
 * Uses pure Mockito (no MockBukkit) for maximum compatibility.
 */
@DisplayName("UltiMailGameMailService 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiMailGameMailServiceTest {

    private UltiMailGameMailService gameMailService;

    @Mock
    private MailService mockMailService;

    @Mock
    private Player player;

    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        // Setup mock UltiMail
        TestHelper.mockUltiMailInstance();

        playerUuid = UUID.randomUUID();
        lenient().when(player.getUniqueId()).thenReturn(playerUuid);
        lenient().when(player.getName()).thenReturn("TestPlayer");

        // Create service and inject
        gameMailService = new UltiMailGameMailService();
        injectField(gameMailService, "mailService", mockMailService);
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== Service Info Tests ====================

    @Nested
    @DisplayName("服务信息测试")
    class ServiceInfoTests {

        @Test
        @DisplayName("getName 应该返回正确的服务名称")
        void shouldReturnCorrectServiceName() {
            assertThat(gameMailService.getName()).isEqualTo("UltiMailGameMailService");
        }

        @Test
        @DisplayName("getAuthor 应该返回正确的作者")
        void shouldReturnCorrectAuthor() {
            assertThat(gameMailService.getAuthor()).isEqualTo("UltiTools");
        }

        @Test
        @DisplayName("getVersion 应该返回版本号")
        void shouldReturnVersionNumber() {
            assertThat(gameMailService.getVersion()).isEqualTo(1);
        }
    }

    // ==================== sendMail without items Tests ====================

    @Nested
    @DisplayName("sendMail 无物品测试")
    class SendMailWithoutItemsTests {

        @Test
        @DisplayName("应该成功发送纯文本邮件")
        void shouldSendTextMail() {
            UUID senderUuid = UUID.randomUUID();
            when(mockMailService.sendMailInternal(any(), anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(true);

            boolean result = gameMailService.sendMail(senderUuid, "sender", "receiver", "标题", "内容");

            assertThat(result).isTrue();
            verify(mockMailService).sendMailInternal(eq(senderUuid), eq("sender"), eq("receiver"),
                eq("标题"), eq("内容"), isNull());
        }

        @Test
        @DisplayName("接收者不存在时应该返回 false")
        void shouldReturnFalseForNonExistentReceiver() {
            UUID senderUuid = UUID.randomUUID();
            when(mockMailService.sendMailInternal(any(), anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(false);

            boolean result = gameMailService.sendMail(senderUuid, "sender", "nonexistent", "标题", "内容");

            assertThat(result).isFalse();
        }
    }

    // ==================== sendMail with items Tests ====================

    @Nested
    @DisplayName("sendMail 带物品测试")
    class SendMailWithItemsTests {

        @Test
        @DisplayName("应该成功发送带物品的邮件")
        void shouldSendMailWithItems() {
            UUID senderUuid = UUID.randomUUID();
            ItemStack[] items = new ItemStack[]{mock(ItemStack.class)};
            when(mockMailService.sendMailInternal(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

            boolean result = gameMailService.sendMail(senderUuid, "sender", "receiver", "标题", "内容", items);

            assertThat(result).isTrue();
            verify(mockMailService).sendMailInternal(eq(senderUuid), eq("sender"), eq("receiver"),
                eq("标题"), eq("内容"), eq(items));
        }

        @Test
        @DisplayName("null 物品数组应该正确传递")
        void shouldHandleNullItems() {
            UUID senderUuid = UUID.randomUUID();
            when(mockMailService.sendMailInternal(any(), anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(true);

            boolean result = gameMailService.sendMail(senderUuid, "sender", "receiver", "标题", "内容", null);

            assertThat(result).isTrue();
        }
    }

    // ==================== sendSystemMail Tests ====================

    @Nested
    @DisplayName("sendSystemMail 测试")
    class SendSystemMailTests {

        @Test
        @DisplayName("应该使用 null UUID 发送系统邮件")
        void shouldSendWithNullUuid() {
            when(mockMailService.sendMailInternal(isNull(), eq("System"), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(true);

            boolean result = gameMailService.sendSystemMail("receiver", "系统通知", "内容");

            assertThat(result).isTrue();
            verify(mockMailService).sendMailInternal(isNull(), eq("System"), eq("receiver"),
                eq("系统通知"), eq("内容"), isNull());
        }

        @Test
        @DisplayName("系统邮件不应带物品")
        void shouldNotIncludeItems() {
            when(mockMailService.sendMailInternal(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

            gameMailService.sendSystemMail("receiver", "标题", "内容");

            verify(mockMailService).sendMailInternal(any(), anyString(), anyString(), anyString(), anyString(), isNull());
        }
    }

    // ==================== getUnreadCount Tests ====================

    @Nested
    @DisplayName("getUnreadCount 测试")
    class GetUnreadCountTests {

        @Test
        @DisplayName("应该返回未读邮件数量")
        void shouldReturnUnreadCount() {
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(5);

            int count = gameMailService.getUnreadCount(playerUuid);

            assertThat(count).isEqualTo(5);
        }

        @Test
        @DisplayName("没有未读邮件时应该返回 0")
        void shouldReturnZeroWhenNoUnread() {
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(0);

            int count = gameMailService.getUnreadCount(playerUuid);

            assertThat(count).isEqualTo(0);
        }
    }

    // ==================== isAvailable Tests ====================

    @Nested
    @DisplayName("isAvailable 测试")
    class IsAvailableTests {

        @Test
        @DisplayName("mailService 不为 null 时应该返回 true")
        void shouldReturnTrueWhenAvailable() {
            assertThat(gameMailService.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("mailService 为 null 时应该返回 false")
        void shouldReturnFalseWhenNull() throws Exception {
            injectField(gameMailService, "mailService", null);

            assertThat(gameMailService.isAvailable()).isFalse();
        }
    }

    // ==================== notifyNewMail Tests ====================

    @Nested
    @DisplayName("notifyNewMail 测试")
    class NotifyNewMailTests {

        @Test
        @DisplayName("有未读邮件时应该通知玩家")
        void shouldNotifyWhenHasUnread() {
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(3);

            gameMailService.notifyNewMail(player);

            verify(player).sendMessage(ArgumentMatchers.<String>argThat(msg -> msg.contains("3")));
        }

        @Test
        @DisplayName("没有未读邮件时不应该通知")
        void shouldNotNotifyWhenNoUnread() {
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(0);

            gameMailService.notifyNewMail(player);

            verify(player, never()).sendMessage(any(String.class));
        }

        @Test
        @DisplayName("消息应该包含UltiMail标识")
        void shouldContainUltiMailLabel() {
            when(mockMailService.getUnreadCount(playerUuid)).thenReturn(1);

            gameMailService.notifyNewMail(player);

            verify(player).sendMessage(ArgumentMatchers.<String>argThat(msg -> msg.contains("UltiMail")));
        }
    }

    // ==================== Annotation Tests ====================

    @Nested
    @DisplayName("注解配置测试")
    class AnnotationTests {

        @Test
        @DisplayName("类应该有 @Service 注解")
        void shouldHaveServiceAnnotation() {
            assertThat(UltiMailGameMailService.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.Service.class
            )).isTrue();
        }

        @Test
        @DisplayName("@Service 应该有正确的优先级")
        void shouldHaveCorrectPriority() {
            com.ultikits.ultitools.annotations.Service annotation =
                UltiMailGameMailService.class.getAnnotation(
                    com.ultikits.ultitools.annotations.Service.class
                );

            assertThat(annotation.priority()).isEqualTo(100);
        }

        @Test
        @DisplayName("应该实现 GameMailService 接口")
        void shouldImplementGameMailService() {
            assertThat(com.ultikits.ultitools.services.GameMailService.class)
                .isAssignableFrom(UltiMailGameMailService.class);
        }
    }
}
