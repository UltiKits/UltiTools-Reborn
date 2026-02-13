package com.ultikits.plugins.mail.commands;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.plugins.mail.utils.TestHelper;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SendMailCommand.
 * <p>
 * Uses pure Mockito (no MockBukkit) for maximum compatibility.
 * Tests focus on command structure, permissions, and item handling logic.
 */
@DisplayName("SendMailCommand 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SendMailCommandTest {

    private SendMailCommand command;

    @Mock
    private MailService mockMailService;

    @Mock
    private Plugin mockPlugin;

    @Mock
    private Player sender;

    @Mock
    private PlayerInventory senderInventory;

    private UUID senderUuid;

    @BeforeEach
    void setUp() throws Exception {
        // Setup mock UltiToolsPlugin
        UltiToolsPlugin mockUltiPlugin = TestHelper.mockUltiToolsPlugin();

        senderUuid = UUID.randomUUID();
        lenient().when(sender.getUniqueId()).thenReturn(senderUuid);
        lenient().when(sender.getName()).thenReturn("SenderPlayer");
        lenient().when(sender.getInventory()).thenReturn(senderInventory);
        lenient().when(sender.isOnline()).thenReturn(true);

        // Create command and inject dependencies
        command = new SendMailCommand(mockMailService, mockPlugin);
        TestHelper.injectField(command, "ultiPlugin", mockUltiPlugin);
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
    }

    // ==================== sendMailWithItems Tests ====================

    @Nested
    @DisplayName("附件发送测试")
    class AttachmentSendTests {

        @Test
        @DisplayName("普通玩家主手为空时应该显示错误")
        void shouldShowErrorWhenMainHandEmpty() {
            when(sender.hasPermission("ultimail.admin.multiattach")).thenReturn(false);
            ItemStack airItem = mock(ItemStack.class);
            when(airItem.getType()).thenReturn(Material.AIR);
            when(senderInventory.getItemInMainHand()).thenReturn(airItem);

            command.sendMailWithItems(sender, "receiver", "subject");

            verify(sender).sendMessage(ArgumentMatchers.<String>argThat(msg -> msg.contains("[error_no_item_in_hand]")));
        }

        @Test
        @DisplayName("普通玩家应该使用主手物品并清空主手")
        void shouldUseMainHandItemAndClearHand() {
            when(sender.hasPermission("ultimail.admin.multiattach")).thenReturn(false);
            ItemStack diamond = mock(ItemStack.class);
            when(diamond.getType()).thenReturn(Material.DIAMOND);
            ItemStack clonedDiamond = mock(ItemStack.class);
            when(diamond.clone()).thenReturn(clonedDiamond);
            when(senderInventory.getItemInMainHand()).thenReturn(diamond);

            // This will try to start a conversation which will fail on mock plugin,
            // but we can verify item was taken
            try {
                command.sendMailWithItems(sender, "receiver", "subject");
            } catch (Exception e) {
                // Expected since ConversationFactory may not work with mock plugin
            }

            verify(senderInventory).setItemInMainHand(null);
        }
    }

    // ==================== help Tests ====================

    @Nested
    @DisplayName("帮助命令测试")
    class HelpTests {

        @Test
        @DisplayName("帮助命令应该显示信息")
        void shouldShowHelpInfo() {
            command.help(sender);

            verify(sender, atLeast(3)).sendMessage(any(String.class));
        }

        @Test
        @DisplayName("帮助应包含 sendmail 命令")
        void shouldContainSendmailCommand() {
            command.help(sender);

            verify(sender, atLeast(1)).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("/sendmail")
            ));
        }

        @Test
        @DisplayName("帮助应包含 attach 关键字")
        void shouldContainAttachKeyword() {
            command.help(sender);

            verify(sender, atLeast(1)).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("attach")
            ));
        }
    }

    // ==================== Annotation Tests ====================

    @Nested
    @DisplayName("注解配置测试")
    class AnnotationTests {

        @Test
        @DisplayName("类应该有 @CmdExecutor 注解")
        void shouldHaveCmdExecutorAnnotation() {
            assertThat(SendMailCommand.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.command.CmdExecutor.class
            )).isTrue();
        }

        @Test
        @DisplayName("@CmdExecutor 应该有正确的别名")
        void shouldHaveCorrectAliases() {
            com.ultikits.ultitools.annotations.command.CmdExecutor annotation =
                SendMailCommand.class.getAnnotation(
                    com.ultikits.ultitools.annotations.command.CmdExecutor.class
                );

            assertThat(annotation.alias()).contains("sendmail", "sm");
        }

        @Test
        @DisplayName("@CmdExecutor 应该有正确的权限")
        void shouldHaveCorrectPermission() {
            com.ultikits.ultitools.annotations.command.CmdExecutor annotation =
                SendMailCommand.class.getAnnotation(
                    com.ultikits.ultitools.annotations.command.CmdExecutor.class
                );

            assertThat(annotation.permission()).isEqualTo("ultimail.send");
        }

        @Test
        @DisplayName("类应该有 @CmdTarget(PLAYER) 注解")
        void shouldHavePlayerTarget() {
            com.ultikits.ultitools.annotations.command.CmdTarget annotation =
                SendMailCommand.class.getAnnotation(
                    com.ultikits.ultitools.annotations.command.CmdTarget.class
                );

            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo(
                com.ultikits.ultitools.annotations.command.CmdTarget.CmdTargetType.PLAYER
            );
        }

        @Test
        @DisplayName("应该继承 BaseCommandExecutor")
        void shouldExtendBaseCommandExecutor() {
            assertThat(com.ultikits.ultitools.abstracts.command.BaseCommandExecutor.class)
                .isAssignableFrom(SendMailCommand.class);
        }
    }

    // ==================== ContentPrompt Tests ====================

    @Nested
    @DisplayName("ContentPrompt 内部类测试")
    class ContentPromptTests {

        @Test
        @DisplayName("ContentPrompt 应该存在")
        void shouldExist() throws Exception {
            Class<?> contentPromptClass = Class.forName(
                "com.ultikits.plugins.mail.commands.SendMailCommand$ContentPrompt"
            );
            assertThat(contentPromptClass).isNotNull();
        }

        @Test
        @DisplayName("ContentPrompt 应该有正确的构造函数")
        void shouldHaveCorrectConstructor() throws Exception {
            Class<?> contentPromptClass = Class.forName(
                "com.ultikits.plugins.mail.commands.SendMailCommand$ContentPrompt"
            );
            contentPromptClass.getDeclaredConstructor(String.class, String.class);
        }

        @Test
        @DisplayName("ContentPrompt 应该实现 StringPrompt")
        void shouldExtendStringPrompt() throws Exception {
            Class<?> contentPromptClass = Class.forName(
                "com.ultikits.plugins.mail.commands.SendMailCommand$ContentPrompt"
            );
            assertThat(org.bukkit.conversations.StringPrompt.class)
                .isAssignableFrom(contentPromptClass);
        }
    }

    // ==================== Constructor Tests ====================

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该正确创建命令实例")
        void shouldCreateInstance() {
            SendMailCommand cmd = new SendMailCommand(mockMailService, mockPlugin);
            assertThat(cmd).isNotNull();
        }
    }

    // ==================== Permission Tests ====================

    @Nested
    @DisplayName("权限测试")
    class PermissionTests {

        @Test
        @DisplayName("ADMIN_PERMISSION 常量应该是 ultimail.admin.multiattach")
        void shouldHaveCorrectAdminPermission() throws Exception {
            java.lang.reflect.Field field = SendMailCommand.class.getDeclaredField("ADMIN_PERMISSION");
            field.setAccessible(true);
            String value = (String) field.get(null);

            assertThat(value).isEqualTo("ultimail.admin.multiattach");
        }
    }
}
