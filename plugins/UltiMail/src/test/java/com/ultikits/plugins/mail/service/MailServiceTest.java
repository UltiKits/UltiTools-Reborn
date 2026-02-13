package com.ultikits.plugins.mail.service;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.utils.TestHelper;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
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
 * Uses pure Mockito (no MockBukkit) for maximum compatibility.
 */
@DisplayName("MailService 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MailServiceTest {

    private MailService mailService;

    @Mock
    private DataOperator<MailData> mockDataOperator;

    @Mock
    private Query<MailData> mockQueryBuilder;

    private MailConfig config;

    @Mock
    private Player sender;

    @Mock
    private Player receiver;

    @Mock
    private PlayerInventory senderInventory;

    @Mock
    private PlayerInventory receiverInventory;

    private MockedStatic<Bukkit> mockedBukkit;

    private UUID senderUuid;
    private UUID receiverUuid;

    @BeforeEach
    void setUp() throws Exception {
        // Setup mock UltiToolsPlugin
        UltiToolsPlugin mockPlugin = TestHelper.mockUltiToolsPlugin();
        when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mockDataOperator);

        // Setup Query DSL chain
        when(mockDataOperator.query()).thenReturn(mockQueryBuilder);
        when(mockQueryBuilder.where(anyString())).thenReturn(mockQueryBuilder);
        when(mockQueryBuilder.eq(any())).thenReturn(mockQueryBuilder);

        // Use real MailConfig with default values
        config = new MailConfig();

        // Setup UUIDs
        senderUuid = UUID.randomUUID();
        receiverUuid = UUID.randomUUID();

        // Setup sender mock
        lenient().when(sender.getUniqueId()).thenReturn(senderUuid);
        lenient().when(sender.getName()).thenReturn("SenderPlayer");
        lenient().when(sender.getInventory()).thenReturn(senderInventory);
        lenient().when(sender.isOnline()).thenReturn(true);

        // Setup receiver mock
        lenient().when(receiver.getUniqueId()).thenReturn(receiverUuid);
        lenient().when(receiver.getName()).thenReturn("ReceiverPlayer");
        lenient().when(receiver.getInventory()).thenReturn(receiverInventory);
        lenient().when(receiver.isOnline()).thenReturn(true);

        // Mock Bukkit static methods
        mockedBukkit = mockStatic(Bukkit.class);
        mockedBukkit.when(() -> Bukkit.getPlayerExact("ReceiverPlayer")).thenReturn(receiver);
        mockedBukkit.when(() -> Bukkit.getPlayerExact("SenderPlayer")).thenReturn(sender);

        // Create service and inject dependencies
        mailService = new MailService();
        injectField(mailService, "config", config);
        injectField(mailService, "dataOperator", mockDataOperator);
        injectField(mailService, "plugin", mockPlugin);
    }

    @AfterEach
    void tearDown() {
        mockedBukkit.close();
        TestHelper.cleanupMocks();
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Long> getCooldownMap() throws Exception {
        Field field = MailService.class.getDeclaredField("sendCooldowns");
        field.setAccessible(true);
        return (Map<UUID, Long>) field.get(mailService);
    }

    // ==================== sendMail Tests ====================

    @Nested
    @DisplayName("sendMail 方法测试")
    class SendMailTests {

        @Test
        @DisplayName("应该成功发送纯文本邮件")
        void shouldSendTextMail() {
            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "测试标题", "测试内容", null);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(any(MailData.class));
        }

        @Test
        @DisplayName("标题过长时应该返回 false")
        void shouldRejectLongSubject() {
            String longSubject = String.join("", Collections.nCopies(100, "a"));

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", longSubject, "内容", null);

            assertThat(result).isFalse();
            verify(mockDataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("内容过长时应该返回 false")
        void shouldRejectLongContent() {
            String longContent = String.join("", Collections.nCopies(1000, "a"));

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", longContent, null);

            assertThat(result).isFalse();
            verify(mockDataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("接收者不存在时应该返回 false")
        void shouldRejectNonExistentReceiver() {
            // Player not found online
            mockedBukkit.when(() -> Bukkit.getPlayerExact("nonexistent")).thenReturn(null);
            // Player not found offline
            OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            when(offlinePlayer.isOnline()).thenReturn(false);
            mockedBukkit.when(() -> Bukkit.getOfflinePlayer("nonexistent")).thenReturn(offlinePlayer);

            boolean result = mailService.sendMail(sender, "nonexistent", "标题", "内容", null);

            assertThat(result).isFalse();
            verify(mockDataOperator, never()).insert(any());
        }

        @Test
        @DisplayName("应该成功发送带命令的邮件")
        void shouldSendMailWithCommands() {
            List<String> commands = Arrays.asList("give %player% diamond 1", "console:eco give %player% 100");

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null, commands);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(argThat(mail ->
                mail.getCommands() != null && mail.getCommands().contains("diamond")
            ));
        }

        @Test
        @DisplayName("冷却期间应该拒绝发送")
        void shouldRejectWhenOnCooldown() throws Exception {
            // Send first mail to set cooldown
            boolean first = mailService.sendMail(sender, "ReceiverPlayer", "标题1", "内容1", null);
            assertThat(first).isTrue();

            // Second should be on cooldown
            boolean second = mailService.sendMail(sender, "ReceiverPlayer", "标题2", "内容2", null);
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("冷却过期后应该允许发送")
        void shouldAllowSendAfterCooldownExpires() throws Exception {
            // Set cooldown far in the past
            getCooldownMap().put(senderUuid, System.currentTimeMillis() - 60000L);

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("发送成功后应该通知在线接收者")
        void shouldNotifyOnlineReceiver() {
            mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            verify(receiver).sendMessage(any(String.class));
        }

        @Test
        @DisplayName("接收者离线时不应发送通知")
        void shouldNotNotifyOfflineReceiver() {
            // Make receiver offline
            mockedBukkit.when(() -> Bukkit.getPlayerExact("ReceiverPlayer")).thenReturn(null)
                .thenReturn(null);
            // But they exist as offline player for UUID lookup
            OfflinePlayer offlineReceiver = mock(OfflinePlayer.class);
            when(offlineReceiver.hasPlayedBefore()).thenReturn(true);
            when(offlineReceiver.getUniqueId()).thenReturn(receiverUuid);
            mockedBukkit.when(() -> Bukkit.getOfflinePlayer("ReceiverPlayer")).thenReturn(offlineReceiver);

            mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            // receiver.sendMessage should not be called since receiver is not online
            verify(receiver, never()).sendMessage(any(String.class));
        }

        @Test
        @DisplayName("通知消息应包含发送者名字")
        void shouldIncludeSenderNameInNotification() {
            mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            verify(receiver).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("SenderPlayer")
            ));
        }

        @Test
        @DisplayName("发送成功后应该设置冷却")
        void shouldSetCooldownAfterSending() throws Exception {
            mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            Map<UUID, Long> cooldowns = getCooldownMap();
            assertThat(cooldowns).containsKey(senderUuid);
        }

        @Test
        @DisplayName("空物品数组应该被忽略")
        void shouldIgnoreEmptyItemsArray() {
            ItemStack[] items = new ItemStack[0];

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", items);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(argThat(mail ->
                mail.getItems() == null
            ));
        }

        @Test
        @DisplayName("全AIR物品数组应该被忽略")
        void shouldIgnoreAllAirItems() {
            ItemStack airItem = mock(ItemStack.class);
            when(airItem.getType()).thenReturn(Material.AIR);
            ItemStack[] items = new ItemStack[]{airItem};

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", items);

            assertThat(result).isTrue();
            // Mail should be inserted without items
            verify(mockDataOperator).insert(any(MailData.class));
        }

        @Test
        @DisplayName("null项在物品数组中应该被过滤")
        void shouldFilterNullItems() {
            ItemStack[] items = new ItemStack[]{null, null};

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", items);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("空命令列表不应设置commands字段")
        void shouldNotSetCommandsWhenEmpty() {
            List<String> commands = Collections.emptyList();

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null, commands);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(argThat(mail ->
                mail.getCommands() == null
            ));
        }

        @Test
        @DisplayName("邮件数据应包含正确的发送者信息")
        void shouldContainCorrectSenderInfo() {
            mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            verify(mockDataOperator).insert(argThat(mail ->
                mail.getSenderUuid().equals(senderUuid.toString()) &&
                mail.getSenderName().equals("SenderPlayer")
            ));
        }

        @Test
        @DisplayName("邮件数据应包含正确的接收者信息")
        void shouldContainCorrectReceiverInfo() {
            mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            verify(mockDataOperator).insert(argThat(mail ->
                mail.getReceiverUuid().equals(receiverUuid.toString()) &&
                mail.getReceiverName().equals("ReceiverPlayer")
            ));
        }

        @Test
        @DisplayName("邮件数据应包含正确的主题和内容")
        void shouldContainCorrectSubjectAndContent() {
            mailService.sendMail(sender, "ReceiverPlayer", "测试标题", "测试内容", null);

            verify(mockDataOperator).insert(argThat(mail ->
                "测试标题".equals(mail.getSubject()) &&
                "测试内容".equals(mail.getContent())
            ));
        }

        @Test
        @DisplayName("邮件数据应设置发送时间")
        void shouldSetSentTime() {
            long before = System.currentTimeMillis();
            mailService.sendMail(sender, "ReceiverPlayer", "标题", "内容", null);

            verify(mockDataOperator).insert(argThat(mail ->
                mail.getSentTime() >= before && mail.getSentTime() <= System.currentTimeMillis()
            ));
        }

        @Test
        @DisplayName("刚好在长度限制内的标题应该被接受")
        void shouldAcceptSubjectAtMaxLength() {
            String exactLengthSubject = String.join("", Collections.nCopies(50, "a"));

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", exactLengthSubject, "内容", null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("刚好在长度限制内的内容应该被接受")
        void shouldAcceptContentAtMaxLength() {
            String exactLengthContent = String.join("", Collections.nCopies(500, "a"));

            boolean result = mailService.sendMail(sender, "ReceiverPlayer", "标题", exactLengthContent, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("通过离线玩家UUID查找接收者")
        void shouldFindReceiverByOfflineUuid() {
            // Clear online lookup
            mockedBukkit.when(() -> Bukkit.getPlayerExact("OfflineGuy")).thenReturn(null);
            OfflinePlayer offlineReceiver = mock(OfflinePlayer.class);
            when(offlineReceiver.hasPlayedBefore()).thenReturn(true);
            when(offlineReceiver.getUniqueId()).thenReturn(receiverUuid);
            mockedBukkit.when(() -> Bukkit.getOfflinePlayer("OfflineGuy")).thenReturn(offlineReceiver);

            boolean result = mailService.sendMail(sender, "OfflineGuy", "标题", "内容", null);

            assertThat(result).isTrue();
        }
    }

    // ==================== getInbox Tests ====================

    @Nested
    @DisplayName("getInbox 方法测试")
    class GetInboxTests {

        @Test
        @DisplayName("应该返回玩家的收件箱")
        void shouldReturnInbox() {
            List<MailData> mails = new ArrayList<>();
            mails.add(createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer"));
            mails.add(createTestMail("s2", "sender2", receiverUuid.toString(), "ReceiverPlayer"));
            when(mockQueryBuilder.list()).thenReturn(mails);

            List<MailData> result = mailService.getInbox(receiverUuid);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("应该过滤掉已删除的邮件")
        void shouldFilterDeletedMails() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            MailData mail2 = createTestMail("s2", "sender2", receiverUuid.toString(), "ReceiverPlayer");
            mail2.setDeletedByReceiver(true);
            mails.add(mail1);
            mails.add(mail2);
            when(mockQueryBuilder.list()).thenReturn(mails);

            List<MailData> result = mailService.getInbox(receiverUuid);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("应该按时间降序排序")
        void shouldSortByTimeDescending() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail1.setSentTime(1000L);
            MailData mail2 = createTestMail("s2", "sender2", receiverUuid.toString(), "ReceiverPlayer");
            mail2.setSentTime(2000L);
            mails.add(mail1);
            mails.add(mail2);
            when(mockQueryBuilder.list()).thenReturn(mails);

            List<MailData> result = mailService.getInbox(receiverUuid);

            assertThat(result.get(0).getSentTime()).isGreaterThan(result.get(1).getSentTime());
        }

        @Test
        @DisplayName("空收件箱应该返回空列表")
        void shouldReturnEmptyForEmptyInbox() {
            when(mockQueryBuilder.list()).thenReturn(new ArrayList<>());

            List<MailData> result = mailService.getInbox(receiverUuid);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("所有邮件都被删除时应返回空列表")
        void shouldReturnEmptyWhenAllDeleted() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setDeletedByReceiver(true);
            mails.add(mail);
            when(mockQueryBuilder.list()).thenReturn(mails);

            List<MailData> result = mailService.getInbox(receiverUuid);

            assertThat(result).isEmpty();
        }
    }

    // ==================== getSentMails Tests ====================

    @Nested
    @DisplayName("getSentMails 方法测试")
    class GetSentMailsTests {

        @Test
        @DisplayName("应该返回玩家的发件箱")
        void shouldReturnSentMails() {
            List<MailData> mails = new ArrayList<>();
            mails.add(createTestMail(senderUuid.toString(), "SenderPlayer", "r1", "receiver1"));
            when(mockQueryBuilder.list()).thenReturn(mails);

            List<MailData> result = mailService.getSentMails(senderUuid);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("应该过滤掉发件人已删除的邮件")
        void shouldFilterDeletedBySender() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail(senderUuid.toString(), "SenderPlayer", "r1", "receiver1");
            mail.setDeletedBySender(true);
            mails.add(mail);
            when(mockQueryBuilder.list()).thenReturn(mails);

            List<MailData> result = mailService.getSentMails(senderUuid);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("应该按时间降序排序")
        void shouldSortSentMailsByTimeDescending() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail(senderUuid.toString(), "SenderPlayer", "r1", "receiver1");
            mail1.setSentTime(1000L);
            MailData mail2 = createTestMail(senderUuid.toString(), "SenderPlayer", "r2", "receiver2");
            mail2.setSentTime(3000L);
            mails.add(mail1);
            mails.add(mail2);
            when(mockQueryBuilder.list()).thenReturn(mails);

            List<MailData> result = mailService.getSentMails(senderUuid);

            assertThat(result.get(0).getSentTime()).isGreaterThan(result.get(1).getSentTime());
        }
    }

    // ==================== getUnreadCount Tests ====================

    @Nested
    @DisplayName("getUnreadCount 方法测试")
    class GetUnreadCountTests {

        @Test
        @DisplayName("应该返回正确的未读邮件数")
        void shouldReturnUnreadCount() {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail1.setRead(false);
            MailData mail2 = createTestMail("s2", "sender2", receiverUuid.toString(), "ReceiverPlayer");
            mail2.setRead(true);
            MailData mail3 = createTestMail("s3", "sender3", receiverUuid.toString(), "ReceiverPlayer");
            mail3.setRead(false);
            mails.add(mail1);
            mails.add(mail2);
            mails.add(mail3);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.getUnreadCount(receiverUuid);

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("没有未读邮件时应返回 0")
        void shouldReturnZeroWhenNoUnread() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setRead(true);
            mails.add(mail);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.getUnreadCount(receiverUuid);

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("空收件箱应返回 0")
        void shouldReturnZeroForEmptyInbox() {
            when(mockQueryBuilder.list()).thenReturn(new ArrayList<>());

            int count = mailService.getUnreadCount(receiverUuid);

            assertThat(count).isEqualTo(0);
        }
    }

    // ==================== markAsRead Tests ====================

    @Nested
    @DisplayName("markAsRead 方法测试")
    class MarkAsReadTests {

        @Test
        @DisplayName("应该将邮件标记为已读")
        void shouldMarkMailAsRead() throws Exception {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            assertThat(mail.isRead()).isFalse();

            mailService.markAsRead(mail);

            assertThat(mail.isRead()).isTrue();
            verify(mockDataOperator).update(mail);
        }

        @Test
        @DisplayName("update失败时应该记录错误")
        void shouldLogErrorOnUpdateFailure() throws Exception {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            doThrow(new IllegalAccessException("test error")).when(mockDataOperator).update(mail);

            // Should not throw exception
            mailService.markAsRead(mail);

            assertThat(mail.isRead()).isTrue();
        }
    }

    // ==================== claimItems Tests ====================

    @Nested
    @DisplayName("claimItems 方法测试")
    class ClaimItemsTests {

        @Test
        @DisplayName("已领取的邮件应返回空数组")
        void shouldReturnEmptyForClaimedMail() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setClaimed(true);

            ItemStack[] result = mailService.claimItems(mail, receiver);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("没有附件的邮件应返回空数组")
        void shouldReturnEmptyForNoItems() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setItems(null);

            ItemStack[] result = mailService.claimItems(mail, receiver);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("空字符串附件应返回空数组")
        void shouldReturnEmptyForEmptyStringItems() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setItems("");

            ItemStack[] result = mailService.claimItems(mail, receiver);

            assertThat(result).isEmpty();
        }
    }

    // ==================== getItemCount Tests ====================

    @Nested
    @DisplayName("getItemCount 方法测试")
    class GetItemCountTests {

        @Test
        @DisplayName("没有附件时应返回 0")
        void shouldReturnZeroForNoItems() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setItems(null);

            int count = mailService.getItemCount(mail);

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("空字符串时应返回 0")
        void shouldReturnZeroForEmptyString() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setItems("");

            int count = mailService.getItemCount(mail);

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("无效的Base64数据应返回 0")
        void shouldReturnZeroForInvalidBase64() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setItems("not-valid-base64");

            int count = mailService.getItemCount(mail);

            assertThat(count).isEqualTo(0);
        }
    }

    // ==================== executeMailCommands Tests ====================

    @Nested
    @DisplayName("executeMailCommands 方法测试")
    class ExecuteMailCommandsTests {

        @Test
        @DisplayName("无命令时不应执行任何操作")
        void shouldDoNothingWhenNoCommands() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands(null);

            mailService.executeMailCommands(receiver, mail);

            // No interactions expected
            verify(receiver, never()).performCommand(anyString());
        }

        @Test
        @DisplayName("命令已执行时不应重复执行")
        void shouldNotExecuteAlreadyExecutedCommands() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands("[\"give %player% diamond 1\"]");
            mail.setCommandsExecuted(true);

            mailService.executeMailCommands(receiver, mail);

            verify(receiver, never()).performCommand(anyString());
        }

        @Test
        @DisplayName("应该执行普通命令作为玩家")
        void shouldExecutePlayerCommand() throws Exception {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands("[\"give ReceiverPlayer diamond 1\"]");

            mailService.executeMailCommands(receiver, mail);

            verify(receiver).performCommand("give ReceiverPlayer diamond 1");
            assertThat(mail.isCommandsExecuted()).isTrue();
        }

        @Test
        @DisplayName("应该执行console:前缀命令作为控制台")
        void shouldExecuteConsoleCommand() throws Exception {
            ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
            mockedBukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);
            mockedBukkit.when(() -> Bukkit.dispatchCommand(eq(consoleSender), anyString())).thenReturn(true);

            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands("[\"console:eco give ReceiverPlayer 100\"]");

            mailService.executeMailCommands(receiver, mail);

            mockedBukkit.verify(() -> Bukkit.dispatchCommand(eq(consoleSender), eq("eco give ReceiverPlayer 100")));
            assertThat(mail.isCommandsExecuted()).isTrue();
        }

        @Test
        @DisplayName("应该替换%player%占位符")
        void shouldReplacePlayerPlaceholder() throws Exception {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands("[\"give %player% diamond 1\"]");

            mailService.executeMailCommands(receiver, mail);

            verify(receiver).performCommand("give ReceiverPlayer diamond 1");
        }

        @Test
        @DisplayName("执行后应标记为已执行")
        void shouldMarkCommandsAsExecuted() throws Exception {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands("[\"test\"]");

            mailService.executeMailCommands(receiver, mail);

            assertThat(mail.isCommandsExecuted()).isTrue();
            verify(mockDataOperator).update(mail);
        }

        @Test
        @DisplayName("空命令列表不应执行")
        void shouldNotExecuteEmptyCommandList() {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands("[]");

            mailService.executeMailCommands(receiver, mail);

            verify(receiver, never()).performCommand(anyString());
        }

        @Test
        @DisplayName("应该执行多个命令")
        void shouldExecuteMultipleCommands() throws Exception {
            ConsoleCommandSender consoleSender = mock(ConsoleCommandSender.class);
            mockedBukkit.when(Bukkit::getConsoleSender).thenReturn(consoleSender);
            mockedBukkit.when(() -> Bukkit.dispatchCommand(any(), anyString())).thenReturn(true);

            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setCommands("[\"give ReceiverPlayer diamond 1\",\"console:eco give ReceiverPlayer 100\"]");

            mailService.executeMailCommands(receiver, mail);

            verify(receiver).performCommand("give ReceiverPlayer diamond 1");
            mockedBukkit.verify(() -> Bukkit.dispatchCommand(eq(consoleSender), eq("eco give ReceiverPlayer 100")));
        }
    }

    // ==================== deleteMail Tests ====================

    @Nested
    @DisplayName("deleteMail 方法测试")
    class DeleteMailTests {

        @Test
        @DisplayName("接收者删除应标记 deletedByReceiver")
        void shouldMarkDeletedByReceiver() throws Exception {
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");

            mailService.deleteMail(mail, receiverUuid);

            assertThat(mail.isDeletedByReceiver()).isTrue();
            verify(mockDataOperator).update(mail);
        }

        @Test
        @DisplayName("发件者删除应标记 deletedBySender")
        void shouldMarkDeletedBySender() throws Exception {
            MailData mail = createTestMail(senderUuid.toString(), "SenderPlayer", "r1", "receiver1");

            mailService.deleteMail(mail, senderUuid);

            assertThat(mail.isDeletedBySender()).isTrue();
            verify(mockDataOperator).update(mail);
        }

        @Test
        @DisplayName("双方都删除时应真正删除")
        void shouldReallyDeleteWhenBothDeleted() {
            MailData mail = createTestMail(senderUuid.toString(), "SenderPlayer",
                receiverUuid.toString(), "ReceiverPlayer");
            mail.setDeletedBySender(true);
            mail.setId("123");

            mailService.deleteMail(mail, receiverUuid);

            verify(mockDataOperator).delById("123");
        }

        @Test
        @DisplayName("无关玩家删除不应改变状态")
        void shouldNotChangeStateForUnrelatedPlayer() throws Exception {
            UUID unrelatedUuid = UUID.randomUUID();
            MailData mail = createTestMail(senderUuid.toString(), "SenderPlayer",
                receiverUuid.toString(), "ReceiverPlayer");

            mailService.deleteMail(mail, unrelatedUuid);

            assertThat(mail.isDeletedBySender()).isFalse();
            assertThat(mail.isDeletedByReceiver()).isFalse();
        }
    }

    // ==================== deleteAllByReceiver Tests ====================

    @Nested
    @DisplayName("deleteAllByReceiver 方法测试")
    class DeleteAllByReceiverTests {

        @Test
        @DisplayName("应该删除所有无附件的邮件")
        void shouldDeleteMailsWithoutItems() throws Exception {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            MailData mail2 = createTestMail("s2", "sender2", receiverUuid.toString(), "ReceiverPlayer");
            mails.add(mail1);
            mails.add(mail2);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.deleteAllByReceiver(receiverUuid);

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("应该跳过有未领取附件的邮件")
        void shouldSkipMailsWithUnclaimedItems() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setItems("someItems");
            mail.setClaimed(false);
            mails.add(mail);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.deleteAllByReceiver(receiverUuid);

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("已领取附件的邮件应该被删除")
        void shouldDeleteClaimedItemsMails() throws Exception {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setItems("someItems");
            mail.setClaimed(true);
            mails.add(mail);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.deleteAllByReceiver(receiverUuid);

            assertThat(count).isEqualTo(1);
        }
    }

    // ==================== deleteReadByReceiver Tests ====================

    @Nested
    @DisplayName("deleteReadByReceiver 方法测试")
    class DeleteReadByReceiverTests {

        @Test
        @DisplayName("应该只删除已读邮件")
        void shouldDeleteOnlyReadMails() throws Exception {
            List<MailData> mails = new ArrayList<>();
            MailData mail1 = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail1.setRead(true);
            MailData mail2 = createTestMail("s2", "sender2", receiverUuid.toString(), "ReceiverPlayer");
            mail2.setRead(false);
            mails.add(mail1);
            mails.add(mail2);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.deleteReadByReceiver(receiverUuid);

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("已读但有未领取附件的邮件不应删除")
        void shouldNotDeleteReadMailsWithUnclaimedItems() {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setRead(true);
            mail.setItems("someItems");
            mail.setClaimed(false);
            mails.add(mail);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.deleteReadByReceiver(receiverUuid);

            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("已读且已领取附件的邮件应删除")
        void shouldDeleteReadAndClaimedMails() throws Exception {
            List<MailData> mails = new ArrayList<>();
            MailData mail = createTestMail("s1", "sender1", receiverUuid.toString(), "ReceiverPlayer");
            mail.setRead(true);
            mail.setItems("someItems");
            mail.setClaimed(true);
            mails.add(mail);
            when(mockQueryBuilder.list()).thenReturn(mails);

            int count = mailService.deleteReadByReceiver(receiverUuid);

            assertThat(count).isEqualTo(1);
        }
    }

    // ==================== getMail Tests ====================

    @Nested
    @DisplayName("getMail 方法测试")
    class GetMailTests {

        @Test
        @DisplayName("应该通过ID获取邮件")
        void shouldGetMailById() {
            MailData expectedMail = createTestMail("s1", "sender1", "r1", "receiver1");
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

    // ==================== sendMailInternal Tests ====================

    @Nested
    @DisplayName("sendMailInternal 方法测试")
    class SendMailInternalTests {

        @Test
        @DisplayName("应该成功发送内部邮件")
        void shouldSendInternalMail() {
            boolean result = mailService.sendMailInternal(
                senderUuid, "SenderPlayer", "ReceiverPlayer", "标题", "内容", null);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(any(MailData.class));
        }

        @Test
        @DisplayName("接收者不存在时应返回 false")
        void shouldReturnFalseWhenReceiverNotFound() {
            mockedBukkit.when(() -> Bukkit.getPlayerExact("nonexistent")).thenReturn(null);
            OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);
            when(offlinePlayer.isOnline()).thenReturn(false);
            mockedBukkit.when(() -> Bukkit.getOfflinePlayer("nonexistent")).thenReturn(offlinePlayer);

            boolean result = mailService.sendMailInternal(
                senderUuid, "SenderPlayer", "nonexistent", "标题", "内容", null);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("应该支持 null UUID (系统邮件)")
        void shouldSupportNullUuidForSystemMail() {
            boolean result = mailService.sendMailInternal(
                null, "System", "ReceiverPlayer", "系统通知", "内容", null);

            assertThat(result).isTrue();
            verify(mockDataOperator).insert(argThat(mail ->
                mail.getSenderUuid() == null
            ));
        }
    }

    // ==================== getConfig Tests ====================

    @Nested
    @DisplayName("getConfig 方法测试")
    class GetConfigTests {

        @Test
        @DisplayName("应该返回注入的配置")
        void shouldReturnInjectedConfig() {
            MailConfig result = mailService.getConfig();

            assertThat(result).isSameAs(config);
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
