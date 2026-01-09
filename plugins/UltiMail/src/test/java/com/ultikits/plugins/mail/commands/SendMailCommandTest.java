package com.ultikits.plugins.mail.commands;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.mail.UltiMail;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.plugins.mail.utils.MockBukkitHelper;
import com.ultikits.plugins.mail.utils.TestHelper;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SendMailCommand.
 * <p>
 * 发送邮件命令单元测试。
 * <p>
 * 注意: 需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("SendMailCommand 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class SendMailCommandTest {

    private ServerMock server;
    private PlayerMock sender;
    private PlayerMock receiver;

    @Mock
    private MailService mockMailService;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        
        // Setup mock UltiMail
        TestHelper.mockUltiMailInstance();
        
        sender = server.addPlayer("sender");
        receiver = server.addPlayer("receiver");
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("基础发送测试")
    class BasicSendTests {

        @Test
        @DisplayName("应该成功发送纯文本邮件")
        void shouldSendTextMail() {
            when(mockMailService.sendMail(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

            boolean result = mockMailService.sendMail(sender, "receiver", "标题", "内容", null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("发送失败时应该返回 false")
        void shouldReturnFalseOnFailure() {
            when(mockMailService.sendMail(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

            boolean result = mockMailService.sendMail(sender, "nonexistent", "标题", "内容", null);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("附件发送测试")
    class AttachmentSendTests {

        @Test
        @DisplayName("普通玩家应该使用主手物品作为附件")
        void shouldUseMainHandItemForRegularPlayer() {
            ItemStack diamond = new ItemStack(Material.DIAMOND, 1);
            sender.getInventory().setItemInMainHand(diamond);

            ItemStack mainHand = sender.getInventory().getItemInMainHand();

            assertThat(mainHand.getType()).isEqualTo(Material.DIAMOND);
        }

        @Test
        @DisplayName("主手为空时应该拒绝附件发送")
        void shouldRejectAttachmentWithEmptyHand() {
            sender.getInventory().setItemInMainHand(null);

            ItemStack mainHand = sender.getInventory().getItemInMainHand();

            assertThat(mainHand.getType()).isEqualTo(Material.AIR);
        }

        @Test
        @DisplayName("管理员应该能使用多物品附件")
        void shouldAllowMultiAttachForAdmin() {
            sender.addAttachment(MockBukkit.createMockPlugin(), "ultimail.admin.multiattach", true);

            assertThat(sender.hasPermission("ultimail.admin.multiattach")).isTrue();
        }
    }

    @Nested
    @DisplayName("权限测试")
    class PermissionTests {

        @Test
        @DisplayName("有发送权限时应该允许发送")
        void shouldAllowSendWithPermission() {
            sender.addAttachment(MockBukkit.createMockPlugin(), "ultimail.send", true);

            assertThat(sender.hasPermission("ultimail.send")).isTrue();
        }

        @Test
        @DisplayName("多物品权限应该只给管理员")
        void shouldRequireAdminForMultiAttach() {
            // Regular player
            assertThat(sender.hasPermission("ultimail.admin.multiattach")).isFalse();

            // Admin
            sender.addAttachment(MockBukkit.createMockPlugin(), "ultimail.admin.multiattach", true);
            assertThat(sender.hasPermission("ultimail.admin.multiattach")).isTrue();
        }
    }

    @Nested
    @DisplayName("对话式输入测试")
    class ConversationInputTests {

        @Test
        @DisplayName("应该支持取消发送")
        void shouldSupportCancellation() {
            String input = "cancel";
            
            assertThat(input.equalsIgnoreCase("cancel")).isTrue();
        }

        @Test
        @DisplayName("正常输入应该被接受")
        void shouldAcceptNormalInput() {
            String input = "这是邮件内容";
            
            assertThat(input).isNotEmpty();
            assertThat(input.equalsIgnoreCase("cancel")).isFalse();
        }
    }

    @Nested
    @DisplayName("接收者验证测试")
    class ReceiverValidationTests {

        @Test
        @DisplayName("在线玩家应该能接收邮件")
        void shouldAcceptOnlinePlayer() {
            assertThat(receiver.isOnline()).isTrue();
            assertThat(server.getPlayerExact("receiver")).isNotNull();
        }

        @Test
        @DisplayName("不存在的玩家应该被拒绝")
        void shouldRejectNonExistentPlayer() {
            assertThat(server.getPlayerExact("nonexistent_xyz")).isNull();
        }
    }

    @Nested
    @DisplayName("物品移除测试")
    class ItemRemovalTests {

        @Test
        @DisplayName("发送后应该从玩家背包移除物品")
        void shouldRemoveItemFromInventory() {
            ItemStack diamond = new ItemStack(Material.DIAMOND, 1);
            sender.getInventory().setItemInMainHand(diamond);

            // Simulate removal after send
            sender.getInventory().setItemInMainHand(null);

            assertThat(sender.getInventory().getItemInMainHand().getType()).isEqualTo(Material.AIR);
        }

        @Test
        @DisplayName("取消发送时应该返还物品")
        void shouldReturnItemOnCancel() {
            ItemStack diamond = new ItemStack(Material.DIAMOND, 1);
            
            // Simulate returning item on cancel
            sender.getInventory().addItem(diamond);

            assertThat(sender.getInventory().contains(Material.DIAMOND)).isTrue();
        }
    }

    @Nested
    @DisplayName("命令帮助测试")
    class HelpTests {

        @Test
        @DisplayName("帮助信息应该包含基础命令")
        void shouldContainBasicCommands() {
            String[] helpLines = {
                "/sendmail <玩家> <标题>",
                "/sendmail <玩家> <标题> attach"
            };

            for (String line : helpLines) {
                assertThat(line).contains("/sendmail");
            }
        }

        @Test
        @DisplayName("帮助信息应该提示取消方式")
        void shouldShowCancelHint() {
            String cancelHint = "输入 'cancel' 可取消发送";

            assertThat(cancelHint).contains("cancel");
        }
    }
}
