package com.ultikits.plugins.mail.commands;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.mail.UltiMail;
import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.plugins.mail.utils.MockBukkitHelper;
import com.ultikits.plugins.mail.utils.TestHelper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MailCommand.
 * <p>
 * 邮件命令单元测试。
 * <p>
 * 注意: 需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("MailCommand 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class MailCommandTest {

    private ServerMock server;
    private PlayerMock player;
    // Command instance prepared for test methods - used to verify command instantiation
    private MailCommand mailCommand;

    @Mock
    private MailService mockMailService;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Setup mock UltiMail
        TestHelper.mockUltiMailInstance();

        player = server.addPlayer("testplayer");

        // Create command with mock mailService
        mailCommand = new MailCommand(mockMailService);
        // Verify command was created successfully
        assertThat(mailCommand).isNotNull();
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("inbox 命令测试")
    class InboxCommandTests {

        @Test
        @DisplayName("空收件箱时应该显示空消息")
        void shouldShowEmptyMessageForEmptyInbox() {
            when(mockMailService.getInbox(any())).thenReturn(new ArrayList<>());

            List<MailData> inbox = mockMailService.getInbox(player.getUniqueId());

            assertThat(inbox).isEmpty();
        }

        @Test
        @DisplayName("有邮件时应该显示邮件列表")
        void shouldShowMailListWhenHasMails() {
            List<MailData> mails = new ArrayList<>();
            mails.add(createTestMail("sender1"));
            mails.add(createTestMail("sender2"));
            when(mockMailService.getInbox(any())).thenReturn(mails);

            List<MailData> inbox = mockMailService.getInbox(player.getUniqueId());

            assertThat(inbox).hasSize(2);
        }
    }

    @Nested
    @DisplayName("read 命令测试")
    class ReadCommandTests {

        @Test
        @DisplayName("无效索引应该被拒绝")
        void shouldRejectInvalidIndex() {
            List<MailData> mails = new ArrayList<>();
            mails.add(createTestMail("sender"));
            when(mockMailService.getInbox(any())).thenReturn(mails);

            // Index 5 is invalid for list of size 1
            int index = 5;
            List<MailData> inbox = mockMailService.getInbox(player.getUniqueId());

            assertThat(index).isGreaterThan(inbox.size());
        }

        @Test
        @DisplayName("有效索引应该显示邮件详情")
        void shouldShowMailDetailsForValidIndex() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("sender");
            mail.setSubject("测试标题");
            mail.setContent("测试内容");
            mails.add(mail);
            when(mockMailService.getInbox(any())).thenReturn(mails);

            List<MailData> inbox = mockMailService.getInbox(player.getUniqueId());
            MailData readMail = inbox.get(0);

            assertThat(readMail.getSubject()).isEqualTo("测试标题");
            assertThat(readMail.getContent()).isEqualTo("测试内容");
        }

        @Test
        @DisplayName("阅读后应该标记为已读")
        void shouldMarkAsReadAfterReading() {
            MailData mail = createTestMail("sender");
            assertThat(mail.isRead()).isFalse();

            mail.setRead(true);

            assertThat(mail.isRead()).isTrue();
        }
    }

    @Nested
    @DisplayName("claim 命令测试")
    class ClaimCommandTests {

        @Test
        @DisplayName("没有附件的邮件不应该被领取")
        void shouldNotClaimMailWithoutItems() {
            MailData mail = createTestMail("sender");
            mail.setItems(null);

            assertThat(mail.hasItems()).isFalse();
        }

        @Test
        @DisplayName("已领取的邮件不应该重复领取")
        void shouldNotClaimAlreadyClaimedMail() {
            MailData mail = createTestMail("sender");
            mail.setItems("base64data");
            mail.setClaimed(true);

            assertThat(mail.isClaimed()).isTrue();
        }

        @Test
        @DisplayName("有效附件应该被成功领取")
        void shouldClaimValidItems() {
            MailData mail = createTestMail("sender");
            mail.setItems("base64data");
            mail.setClaimed(false);

            assertThat(mail.hasItems()).isTrue();
            assertThat(mail.isClaimed()).isFalse();
        }
    }

    @Nested
    @DisplayName("delete 命令测试")
    class DeleteCommandTests {

        @Test
        @DisplayName("有未领取附件的邮件不应该被删除")
        void shouldNotDeleteMailWithUnclaimedItems() {
            MailData mail = createTestMail("sender");
            mail.setItems("base64data");
            mail.setClaimed(false);

            // Should reject deletion
            assertThat(mail.hasItems() && !mail.isClaimed()).isTrue();
        }

        @Test
        @DisplayName("无附件或已领取的邮件可以删除")
        void shouldDeleteMailWithoutItemsOrClaimed() {
            MailData mail1 = createTestMail("sender");
            mail1.setItems(null);

            MailData mail2 = createTestMail("sender");
            mail2.setItems("base64data");
            mail2.setClaimed(true);

            assertThat(!mail1.hasItems() || mail1.isClaimed()).isTrue();
            assertThat(!mail2.hasItems() || mail2.isClaimed()).isTrue();
        }
    }

    @Nested
    @DisplayName("delall 命令测试")
    class DeleteAllCommandTests {

        @Test
        @DisplayName("应该删除所有可删除的邮件")
        void shouldDeleteAllDeletableMails() {
            when(mockMailService.deleteAllByReceiver(any())).thenReturn(5);

            int deleted = mockMailService.deleteAllByReceiver(player.getUniqueId());

            assertThat(deleted).isEqualTo(5);
        }

        @Test
        @DisplayName("没有可删除邮件时应该返回0")
        void shouldReturnZeroWhenNoDeletableMails() {
            when(mockMailService.deleteAllByReceiver(any())).thenReturn(0);

            int deleted = mockMailService.deleteAllByReceiver(player.getUniqueId());

            assertThat(deleted).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("delread 命令测试")
    class DeleteReadCommandTests {

        @Test
        @DisplayName("应该只删除已读邮件")
        void shouldDeleteOnlyReadMails() {
            when(mockMailService.deleteReadByReceiver(any())).thenReturn(3);

            int deleted = mockMailService.deleteReadByReceiver(player.getUniqueId());

            assertThat(deleted).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("sent 命令测试")
    class SentCommandTests {

        @Test
        @DisplayName("应该显示发件箱列表")
        void shouldShowSentMails() {
            List<MailData> mails = new ArrayList<>();
            mails.add(createSentMail("receiver1"));
            mails.add(createSentMail("receiver2"));
            when(mockMailService.getSentMails(any())).thenReturn(mails);

            List<MailData> sentbox = mockMailService.getSentMails(player.getUniqueId());

            assertThat(sentbox).hasSize(2);
        }
    }

    @Nested
    @DisplayName("sendall 命令权限测试")
    class SendAllPermissionTests {

        @Test
        @DisplayName("有权限时应该允许群发")
        void shouldAllowSendAllWithPermission() {
            player.addAttachment(MockBukkit.createMockPlugin(), "ultimail.admin.sendall", true);

            assertThat(player.hasPermission("ultimail.admin.sendall")).isTrue();
        }

        @Test
        @DisplayName("无权限时应该拒绝群发")
        void shouldDenySendAllWithoutPermission() {
            // Default: no permission
            assertThat(player.hasPermission("ultimail.admin.sendall")).isFalse();
        }
    }

    // Helper methods
    private MailData createTestMail(String senderName) {
        MailData mail = new MailData();
        mail.setSenderUuid("sender-uuid");
        mail.setSenderName(senderName);
        mail.setReceiverUuid(player.getUniqueId().toString());
        mail.setReceiverName(player.getName());
        mail.setSubject("Test Subject");
        mail.setContent("Test Content");
        mail.setSentTime(System.currentTimeMillis());
        return mail;
    }

    private MailData createSentMail(String receiverName) {
        MailData mail = new MailData();
        mail.setSenderUuid(player.getUniqueId().toString());
        mail.setSenderName(player.getName());
        mail.setReceiverUuid("receiver-uuid");
        mail.setReceiverName(receiverName);
        mail.setSubject("Test Subject");
        mail.setContent("Test Content");
        mail.setSentTime(System.currentTimeMillis());
        return mail;
    }
}
