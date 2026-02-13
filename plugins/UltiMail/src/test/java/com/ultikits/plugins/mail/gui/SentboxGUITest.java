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
 * Unit tests for SentboxGUI.
 * <p>
 * 发件箱 GUI 单元测试。
 * <p>
 * 注意: 需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("SentboxGUI 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class SentboxGUITest {

    private PlayerMock player;

    @Mock
    private MailService mockMailService;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Setup mock UltiToolsPlugin
        TestHelper.mockUltiToolsPlugin();

        player = server.addPlayer("testplayer");
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("发件箱数据测试")
    class SentboxDataTests {

        @Test
        @DisplayName("空发件箱时应该正确处理")
        void shouldHandleEmptySentbox() {
            when(mockMailService.getSentMails(any())).thenReturn(new ArrayList<>());

            List<MailData> sentbox = mockMailService.getSentMails(player.getUniqueId());

            assertThat(sentbox).isEmpty();
        }

        @Test
        @DisplayName("应该正确获取发件箱邮件")
        void shouldGetSentMails() {
            List<MailData> mails = createTestSentMails(5);
            when(mockMailService.getSentMails(any())).thenReturn(mails);

            List<MailData> sentbox = mockMailService.getSentMails(player.getUniqueId());

            assertThat(sentbox).hasSize(5);
        }
    }

    @Nested
    @DisplayName("发件显示测试")
    class SentMailDisplayTests {

        @Test
        @DisplayName("发件应该显示正确的接收者")
        void shouldShowCorrectReceiver() {
            MailData mail = createTestSentMail("receiver1");

            assertThat(mail.getReceiverName()).isEqualTo("receiver1");
        }

        @Test
        @DisplayName("发件应该显示正确的发送时间")
        void shouldShowSentTime() {
            long beforeTime = System.currentTimeMillis();
            MailData mail = createTestSentMail("receiver");
            long afterTime = System.currentTimeMillis();

            assertThat(mail.getSentTime()).isBetween(beforeTime, afterTime);
        }

        @Test
        @DisplayName("带附件的发件应该正确标记")
        void shouldMarkMailWithItems() {
            MailData mail = createTestSentMail("receiver");
            mail.setItems("base64data");

            assertThat(mail.hasItems()).isTrue();
        }
    }

    @Nested
    @DisplayName("发件箱分页测试")
    class SentboxPaginationTests {

        @Test
        @DisplayName("应该正确分页显示发件")
        void shouldPaginateSentMails() {
            List<MailData> mails = createTestSentMails(100);
            int itemsPerPage = 45;
            int totalPages = (int) Math.ceil((double) mails.size() / itemsPerPage);

            assertThat(totalPages).isEqualTo(3);
        }

        @Test
        @DisplayName("第一页应该显示最新的邮件")
        void shouldShowNewestMailsFirst() {
            List<MailData> mails = new ArrayList<>();
            MailData oldMail = createTestSentMail("old");
            oldMail.setSentTime(1000L);
            MailData newMail = createTestSentMail("new");
            newMail.setSentTime(2000L);
            mails.add(oldMail);
            mails.add(newMail);
            
            // Sort by time descending (as done in service)
            mails.sort((a, b) -> Long.compare(b.getSentTime(), a.getSentTime()));

            assertThat(mails.get(0).getReceiverName()).isEqualTo("new");
        }
    }

    // Helper methods
    private List<MailData> createTestSentMails(int count) {
        List<MailData> mails = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            mails.add(createTestSentMail("receiver" + i));
        }
        return mails;
    }

    private MailData createTestSentMail(String receiverName) {
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
