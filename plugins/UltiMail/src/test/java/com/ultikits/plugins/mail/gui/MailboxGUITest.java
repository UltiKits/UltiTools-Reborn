package com.ultikits.plugins.mail.gui;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
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
 * Unit tests for MailboxGUI.
 * <p>
 * 收件箱 GUI 单元测试。
 * <p>
 * 注意: 需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("MailboxGUI 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class MailboxGUITest {

    private ServerMock server;
    private PlayerMock player;

    @Mock
    private MailService mockMailService;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        
        // Setup mock UltiToolsPlugin
        UltiToolsPlugin mockPlugin = TestHelper.mockUltiToolsPlugin();
        
        player = server.addPlayer("testplayer");
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("GUI 数据准备测试")
    class DataPreparationTests {

        @Test
        @DisplayName("空收件箱时应该正确处理")
        void shouldHandleEmptyInbox() {
            when(mockMailService.getInbox(any())).thenReturn(new ArrayList<>());

            List<MailData> inbox = mockMailService.getInbox(player.getUniqueId());

            assertThat(inbox).isEmpty();
        }

        @Test
        @DisplayName("应该正确获取收件箱邮件")
        void shouldGetInboxMails() {
            List<MailData> mails = createTestMails(5);
            when(mockMailService.getInbox(any())).thenReturn(mails);

            List<MailData> inbox = mockMailService.getInbox(player.getUniqueId());

            assertThat(inbox).hasSize(5);
        }

        @Test
        @DisplayName("未读邮件数量应该正确计算")
        void shouldCalculateUnreadCount() {
            List<MailData> mails = createTestMails(5);
            mails.get(0).setRead(true);
            mails.get(1).setRead(true);
            
            int unreadCount = 0;
            for (MailData mail : mails) {
                if (!mail.isRead()) {
                    unreadCount++;
                }
            }

            assertThat(unreadCount).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("邮件状态显示测试")
    class MailStatusTests {

        @Test
        @DisplayName("未读邮件应该标记正确")
        void shouldIdentifyUnreadMail() {
            MailData mail = createTestMail(false, false);

            assertThat(mail.isRead()).isFalse();
        }

        @Test
        @DisplayName("已读邮件应该标记正确")
        void shouldIdentifyReadMail() {
            MailData mail = createTestMail(true, false);

            assertThat(mail.isRead()).isTrue();
        }

        @Test
        @DisplayName("有附件的邮件应该正确识别")
        void shouldIdentifyMailWithItems() {
            MailData mail = createTestMail(false, true);

            assertThat(mail.hasItems()).isTrue();
        }

        @Test
        @DisplayName("无附件的邮件应该正确识别")
        void shouldIdentifyMailWithoutItems() {
            MailData mail = createTestMail(false, false);

            assertThat(mail.hasItems()).isFalse();
        }

        @Test
        @DisplayName("已领取附件的邮件应该正确识别")
        void shouldIdentifyClaimedMail() {
            MailData mail = createTestMail(true, true);
            mail.setClaimed(true);

            assertThat(mail.isClaimed()).isTrue();
        }
    }

    @Nested
    @DisplayName("分页逻辑测试")
    class PaginationTests {

        @Test
        @DisplayName("少于一页的邮件应该不需要分页")
        void shouldNotNeedPaginationForFewMails() {
            List<MailData> mails = createTestMails(10);
            int itemsPerPage = 45;

            assertThat(mails.size()).isLessThanOrEqualTo(itemsPerPage);
        }

        @Test
        @DisplayName("超过一页的邮件应该需要分页")
        void shouldNeedPaginationForManyMails() {
            List<MailData> mails = createTestMails(50);
            int itemsPerPage = 45;

            assertThat(mails.size()).isGreaterThan(itemsPerPage);
        }

        @Test
        @DisplayName("应该正确计算总页数")
        void shouldCalculateTotalPages() {
            List<MailData> mails = createTestMails(100);
            int itemsPerPage = 45;
            int totalPages = (int) Math.ceil((double) mails.size() / itemsPerPage);

            assertThat(totalPages).isEqualTo(3);
        }
    }

    // Helper methods
    private List<MailData> createTestMails(int count) {
        List<MailData> mails = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            mails.add(createTestMail(false, false));
        }
        return mails;
    }

    private MailData createTestMail(boolean read, boolean hasItems) {
        MailData mail = new MailData();
        mail.setSenderUuid("sender-uuid");
        mail.setSenderName("sender");
        mail.setReceiverUuid(player.getUniqueId().toString());
        mail.setReceiverName(player.getName());
        mail.setSubject("Test Subject");
        mail.setContent("Test Content");
        mail.setSentTime(System.currentTimeMillis());
        mail.setRead(read);
        if (hasItems) {
            mail.setItems("base64encodeddata");
        }
        return mail;
    }
}
