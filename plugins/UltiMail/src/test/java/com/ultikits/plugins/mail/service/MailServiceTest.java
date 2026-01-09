package com.ultikits.plugins.mail.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.mail.UltiMail;
import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.utils.MockBukkitHelper;
import com.ultikits.plugins.mail.utils.TestHelper;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.entities.WhereCondition;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MailService.
 * <p>
 * 邮件服务单元测试。
 * <p>
 * 注意: 这些测试需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("MailService 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class MailServiceTest {

    private ServerMock server;
    private PlayerMock sender;
    private PlayerMock receiver;
    private MailService mailService;
    
    @Mock
    private DataOperator<MailData> mockDataOperator;
    
    @Mock
    private MailConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        
        // Setup mock UltiMail
        UltiMail mockPlugin = TestHelper.mockUltiMailInstance();
        when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mockDataOperator);
        
        // Setup config
        mockConfig = TestHelper.createMockConfig();
        
        // Create players
        sender = server.addPlayer("sender");
        receiver = server.addPlayer("receiver");
        
        // Create service and inject dependencies
        mailService = new MailService();
        injectField(mailService, "config", mockConfig);
        injectField(mailService, "dataOperator", mockDataOperator);
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
    @DisplayName("sendMail 方法测试")
    class SendMailTests {

        @Test
        @DisplayName("应该成功发送纯文本邮件")
        void shouldSendTextMail() {
            boolean result = mailService.sendMail(sender, "receiver", "测试标题", "测试内容", null);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(any(MailData.class));
        }

        @Test
        @DisplayName("标题过长时应该返回 false")
        void shouldRejectLongSubject() {
            String longSubject = String.join("", Collections.nCopies(100, "a")); // 100 chars
            
            boolean result = mailService.sendMail(sender, "receiver", longSubject, "内容", null);

            assertThat(result).isFalse();
            verify(mockDataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("内容过长时应该返回 false")
        void shouldRejectLongContent() {
            String longContent = String.join("", Collections.nCopies(1000, "a")); // 1000 chars
            
            boolean result = mailService.sendMail(sender, "receiver", "标题", longContent, null);

            assertThat(result).isFalse();
            verify(mockDataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("接收者不存在时应该返回 false")
        void shouldRejectNonExistentReceiver() {
            boolean result = mailService.sendMail(sender, "nonexistent_player_xyz", "标题", "内容", null);

            assertThat(result).isFalse();
            verify(mockDataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("应该成功发送带物品附件的邮件")
        void shouldSendMailWithItems() {
            ItemStack[] items = new ItemStack[] { new ItemStack(Material.DIAMOND, 1) };
            
            boolean result = mailService.sendMail(sender, "receiver", "标题", "内容", items);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(argThat(mail -> 
                mail.getItems() != null && !mail.getItems().isEmpty()
            ));
        }

        @Test
        @DisplayName("物品数量超限时应该返回 false")
        void shouldRejectTooManyItems() {
            when(mockConfig.getMaxItems()).thenReturn(5);
            ItemStack[] items = new ItemStack[10];
            for (int i = 0; i < 10; i++) {
                items[i] = new ItemStack(Material.DIAMOND, 1);
            }
            
            boolean result = mailService.sendMail(sender, "receiver", "标题", "内容", items);

            assertThat(result).isFalse();
            verify(mockDataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("应该成功发送带命令的邮件")
        void shouldSendMailWithCommands() {
            List<String> commands = Arrays.asList("give %player% diamond 1", "console:eco give %player% 100");
            
            boolean result = mailService.sendMail(sender, "receiver", "标题", "内容", null, commands);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(argThat(mail -> 
                mail.getCommands() != null && mail.getCommands().contains("diamond")
            ));
        }
    }

    @Nested
    @DisplayName("getInbox 方法测试")
    class GetInboxTests {

        @Test
        @DisplayName("应该返回玩家的收件箱")
        void shouldReturnInbox() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("sender1", sender.getName(), receiver.getUniqueId().toString(), receiver.getName());
            MailData mail2 = createTestMail("sender2", "sender2", receiver.getUniqueId().toString(), receiver.getName());
            mails.add(mail1);
            mails.add(mail2);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            List<MailData> result = mailService.getInbox(receiver.getUniqueId());

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("应该过滤掉已删除的邮件")
        void shouldFilterDeletedMails() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            MailData mail2 = createTestMail("sender2", "sender2", receiver.getUniqueId().toString(), receiver.getName());
            mail2.setDeletedByReceiver(true);
            mails.add(mail1);
            mails.add(mail2);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            List<MailData> result = mailService.getInbox(receiver.getUniqueId());

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("应该按时间降序排序")
        void shouldSortByTimeDescending() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail1.setSentTime(1000L);
            MailData mail2 = createTestMail("sender2", "sender2", receiver.getUniqueId().toString(), receiver.getName());
            mail2.setSentTime(2000L);
            mails.add(mail1);
            mails.add(mail2);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            List<MailData> result = mailService.getInbox(receiver.getUniqueId());

            assertThat(result.get(0).getSentTime()).isGreaterThan(result.get(1).getSentTime());
        }
    }

    @Nested
    @DisplayName("getSentMails 方法测试")
    class GetSentMailsTests {

        @Test
        @DisplayName("应该返回玩家的发件箱")
        void shouldReturnSentMails() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail(sender.getUniqueId().toString(), sender.getName(), "receiver1", "receiver1");
            mails.add(mail);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            List<MailData> result = mailService.getSentMails(sender.getUniqueId());

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("应该过滤掉发件人已删除的邮件")
        void shouldFilterDeletedBySender() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail(sender.getUniqueId().toString(), sender.getName(), "receiver1", "receiver1");
            mail.setDeletedBySender(true);
            mails.add(mail);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            List<MailData> result = mailService.getSentMails(sender.getUniqueId());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getUnreadCount 方法测试")
    class GetUnreadCountTests {

        @Test
        @DisplayName("应该返回正确的未读邮件数")
        void shouldReturnUnreadCount() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail1.setRead(false);
            MailData mail2 = createTestMail("sender2", "sender2", receiver.getUniqueId().toString(), receiver.getName());
            mail2.setRead(true);
            MailData mail3 = createTestMail("sender3", "sender3", receiver.getUniqueId().toString(), receiver.getName());
            mail3.setRead(false);
            mails.add(mail1);
            mails.add(mail2);
            mails.add(mail3);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            int count = mailService.getUnreadCount(receiver.getUniqueId());

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("没有未读邮件时应返回 0")
        void shouldReturnZeroWhenNoUnread() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail.setRead(true);
            mails.add(mail);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            int count = mailService.getUnreadCount(receiver.getUniqueId());

            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("markAsRead 方法测试")
    class MarkAsReadTests {

        @Test
        @DisplayName("应该将邮件标记为已读")
        void shouldMarkMailAsRead() throws Exception {
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            assertThat(mail.isRead()).isFalse();

            mailService.markAsRead(mail);

            assertThat(mail.isRead()).isTrue();
            verify(mockDataOperator).update(mail);
        }
    }

    @Nested
    @DisplayName("claimItems 方法测试")
    class ClaimItemsTests {

        @Test
        @DisplayName("已领取的邮件应返回空数组")
        void shouldReturnEmptyForClaimedMail() {
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail.setClaimed(true);

            ItemStack[] result = mailService.claimItems(mail, receiver);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("没有附件的邮件应返回空数组")
        void shouldReturnEmptyForNoItems() {
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail.setItems(null);

            ItemStack[] result = mailService.claimItems(mail, receiver);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getItemCount 方法测试")
    class GetItemCountTests {

        @Test
        @DisplayName("没有附件时应返回 0")
        void shouldReturnZeroForNoItems() {
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail.setItems(null);

            int count = mailService.getItemCount(mail);

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("空字符串时应返回 0")
        void shouldReturnZeroForEmptyString() {
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail.setItems("");

            int count = mailService.getItemCount(mail);

            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("deleteMail 方法测试")
    class DeleteMailTests {

        @Test
        @DisplayName("接收者删除应标记 deletedByReceiver")
        void shouldMarkDeletedByReceiver() throws Exception {
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());

            mailService.deleteMail(mail, receiver.getUniqueId());

            assertThat(mail.isDeletedByReceiver()).isTrue();
            verify(mockDataOperator).update(mail);
        }

        @Test
        @DisplayName("发件者删除应标记 deletedBySender")
        void shouldMarkDeletedBySender() throws Exception {
            MailData mail = createTestMail(sender.getUniqueId().toString(), sender.getName(), "receiver1", "receiver1");

            mailService.deleteMail(mail, sender.getUniqueId());

            assertThat(mail.isDeletedBySender()).isTrue();
            verify(mockDataOperator).update(mail);
        }

        @Test
        @DisplayName("双方都删除时应真正删除")
        void shouldReallyDeleteWhenBothDeleted() {
            MailData mail = createTestMail(sender.getUniqueId().toString(), sender.getName(), 
                receiver.getUniqueId().toString(), receiver.getName());
            mail.setDeletedBySender(true);
            mail.setId(123);

            mailService.deleteMail(mail, receiver.getUniqueId());

            verify(mockDataOperator).delById("123");
        }
    }

    @Nested
    @DisplayName("deleteAllByReceiver 方法测试")
    class DeleteAllByReceiverTests {

        @Test
        @DisplayName("应该删除所有已领取附件的邮件")
        void shouldDeleteAllClaimedMails() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail1.setClaimed(true);
            MailData mail2 = createTestMail("sender2", "sender2", receiver.getUniqueId().toString(), receiver.getName());
            mail2.setClaimed(true);
            mails.add(mail1);
            mails.add(mail2);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            int count = mailService.deleteAllByReceiver(receiver.getUniqueId());

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("应该跳过有未领取附件的邮件")
        void shouldSkipMailsWithUnclaimedItems() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail.setItems("someItems");
            mail.setClaimed(false);
            mails.add(mail);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            int count = mailService.deleteAllByReceiver(receiver.getUniqueId());

            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("deleteReadByReceiver 方法测试")
    class DeleteReadByReceiverTests {

        @Test
        @DisplayName("应该只删除已读邮件")
        void shouldDeleteOnlyReadMails() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail1.setRead(true);
            MailData mail2 = createTestMail("sender2", "sender2", receiver.getUniqueId().toString(), receiver.getName());
            mail2.setRead(false);
            mails.add(mail1);
            mails.add(mail2);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            int count = mailService.deleteReadByReceiver(receiver.getUniqueId());

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("已读但有未领取附件的邮件不应删除")
        void shouldNotDeleteReadMailsWithUnclaimedItems() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("sender1", "sender1", receiver.getUniqueId().toString(), receiver.getName());
            mail.setRead(true);
            mail.setItems("someItems");
            mail.setClaimed(false);
            mails.add(mail);
            
            when(mockDataOperator.getAll(any(WhereCondition.class))).thenReturn(mails);

            int count = mailService.deleteReadByReceiver(receiver.getUniqueId());

            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("getMail 方法测试")
    class GetMailTests {

        @Test
        @DisplayName("应该通过ID获取邮件")
        void shouldGetMailById() {
            MailData expectedMail = createTestMail("sender1", "sender1", "receiver1", "receiver1");
            when(mockDataOperator.getById("123")).thenReturn(expectedMail);

            MailData result = mailService.getMail("123");

            assertThat(result).isEqualTo(expectedMail);
        }

        @Test
        @DisplayName("不存在的ID应返回 null")
        void shouldReturnNullForNonExistentId() {
            when(mockDataOperator.getById("999")).thenReturn(null);

            MailData result = mailService.getMail("999");

            assertThat(result).isNull();
        }
    }

    // Helper method
    private MailData createTestMail(String senderUuid, String senderName, String receiverUuid, String receiverName) {
        MailData mail = new MailData();
        mail.setSenderUuid(senderUuid);
        mail.setSenderName(senderName);
        mail.setReceiverUuid(receiverUuid);
        mail.setReceiverName(receiverName);
        mail.setSubject("Test Subject");
        mail.setContent("Test Content");
        mail.setSentTime(System.currentTimeMillis());
        return mail;
    }
}
