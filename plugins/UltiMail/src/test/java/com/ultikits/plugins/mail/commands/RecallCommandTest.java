package com.ultikits.plugins.mail.commands;

import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.plugins.mail.utils.TestHelper;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RecallCommand.
 * <p>
 * Uses pure Mockito (no MockBukkit) for maximum compatibility.
 * Tests permission checks, command mapping, placeholder replacement,
 * internal methods via reflection, and async execution patterns.
 */
@DisplayName("RecallCommand 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class RecallCommandTest {

    private RecallCommand recallCommand;

    private MailConfig config;

    private UltiToolsPlugin mockPlugin;

    @Mock
    private MailService mockMailService;

    @Mock
    private Player player;

    @Mock
    private Player adminPlayer;

    @Mock
    private BukkitScheduler mockScheduler;

    @Mock
    private BukkitTask mockTask;

    private MockedStatic<Bukkit> mockedBukkit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // Setup mock UltiToolsPlugin
        mockPlugin = TestHelper.mockUltiToolsPlugin();
        DataOperator<MailData> mockDataOperator = mock(DataOperator.class);
        when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mockDataOperator);

        // Use real MailConfig with defaults
        config = new MailConfig();

        // Setup players
        lenient().when(player.getName()).thenReturn("NormalPlayer");
        lenient().when(player.isOp()).thenReturn(false);
        lenient().when(player.hasPermission("ultimail.recall.admin")).thenReturn(false);

        lenient().when(adminPlayer.getName()).thenReturn("AdminPlayer");
        lenient().when(adminPlayer.isOp()).thenReturn(true);
        lenient().when(adminPlayer.hasPermission("ultimail.recall.admin")).thenReturn(true);

        // Mock Bukkit static
        mockedBukkit = mockStatic(Bukkit.class);
        mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
        mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[0]);

        // Mock scheduler to capture and run runnables
        lenient().when(mockScheduler.runTaskAsynchronously(any(), any(Runnable.class)))
            .thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(1);
                runnable.run();
                return mockTask;
            });
        lenient().when(mockScheduler.runTask(any(), any(Runnable.class)))
            .thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(1);
                runnable.run();
                return mockTask;
            });

        // Create command and inject dependencies
        recallCommand = new RecallCommand();
        TestHelper.injectField(recallCommand, "config", config);
        TestHelper.injectField(recallCommand, "mailService", mockMailService);
        TestHelper.injectField(recallCommand, "plugin", mockPlugin);
    }

    @AfterEach
    void tearDown() {
        mockedBukkit.close();
        TestHelper.cleanupMocks();
    }

    // ==================== Permission Tests ====================

    @Nested
    @DisplayName("权限检查测试")
    class PermissionTests {

        @Test
        @DisplayName("无权限玩家应该被拒绝")
        void shouldDenyNormalPlayer() {
            recallCommand.sendRecallWithMessage(player, null);

            verify(player).sendMessage(ArgumentMatchers.<String>argThat(msg -> msg.contains("没有权限")));
        }

        @Test
        @DisplayName("OP 玩家应该被允许")
        void shouldAllowOpPlayer() {
            recallCommand.sendRecallWithMessage(adminPlayer, null);

            // Should not send permission denied message
            verify(adminPlayer, never()).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("没有权限")
            ));
            // Should send "正在发送" message
            verify(adminPlayer).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("正在发送")
            ));
        }

        @Test
        @DisplayName("有 recall.admin 权限的玩家应该被允许")
        void shouldAllowPlayerWithAdminPermission() {
            when(player.hasPermission("ultimail.recall.admin")).thenReturn(true);

            recallCommand.sendRecallWithMessage(player, null);

            verify(player, never()).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("没有权限")
            ));
        }

        @Test
        @DisplayName("控制台命令发送者应该被允许（isOp returns true）")
        void shouldAllowConsoleSender() {
            CommandSender console = mock(CommandSender.class);
            when(console.isOp()).thenReturn(true);
            when(console.getName()).thenReturn("CONSOLE");

            recallCommand.sendRecallWithMessage(console, null);

            verify(console, never()).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("没有权限")
            ));
        }
    }

    // ==================== sendRecall (no-arg) Tests ====================

    @Nested
    @DisplayName("sendRecall 无参数命令测试")
    class SendRecallNoArgTests {

        @Test
        @DisplayName("无参数版本应该委托给 sendRecallWithMessage(null)")
        void shouldDelegateToSendRecallWithMessage() {
            recallCommand.sendRecall(adminPlayer);

            // Should still send the "正在发送" message
            verify(adminPlayer).sendMessage(ArgumentMatchers.<String>argThat(msg -> msg.contains("正在发送")));
        }
    }

    // ==================== sendRecallWithMessage Tests ====================

    @Nested
    @DisplayName("sendRecallWithMessage 命令测试")
    class SendRecallWithMessageTests {

        @Test
        @DisplayName("应该显示发送中消息")
        void shouldShowSendingMessage() {
            recallCommand.sendRecallWithMessage(adminPlayer, null);

            verify(adminPlayer).sendMessage(ArgumentMatchers.<String>argThat(msg -> msg.contains("正在发送")));
        }

        @Test
        @DisplayName("应该显示完成消息")
        void shouldShowCompletionMessage() {
            recallCommand.sendRecallWithMessage(adminPlayer, null);

            // Should show "发送完成"
            verify(adminPlayer, atLeast(1)).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("发送完成")
            ));
        }

        @Test
        @DisplayName("无离线玩家时应该显示 0 注册玩家")
        void shouldShowZeroPlayersWhenNoOfflinePlayers() {
            mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[0]);

            recallCommand.sendRecallWithMessage(adminPlayer, null);

            verify(adminPlayer, atLeast(1)).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("0")
            ));
        }

        @Test
        @DisplayName("email未启用时不应显示电子邮件统计")
        void shouldNotShowEmailStatsWhenDisabled() {
            config.setEmailEnabled(false);

            recallCommand.sendRecallWithMessage(adminPlayer, null);

            // Should NOT show email count line
            verify(adminPlayer, never()).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("电子邮件")
            ));
        }

        @Test
        @DisplayName("email启用时应显示电子邮件统计")
        void shouldShowEmailStatsWhenEnabled() {
            config.setEmailEnabled(true);

            recallCommand.sendRecallWithMessage(adminPlayer, null);

            verify(adminPlayer, atLeast(1)).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("电子邮件")
            ));
        }
    }

    // ==================== sendGameMail (private) Tests ====================

    @Nested
    @DisplayName("sendGameMail 私有方法测试")
    class SendGameMailTests {

        @Test
        @DisplayName("应该正确创建游戏邮件")
        void shouldCreateGameMail() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailDataOperator = mock(DataOperator.class);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailDataOperator);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendGameMail", String.class, String.class, String.class, String.class);
            method.setAccessible(true); // NOPMD

            method.invoke(recallCommand, "uuid-123", "TestPlayer", "AdminPlayer", null);

            verify(mailDataOperator).insert(argThat(mail -> {
                MailData m = (MailData) mail;
                return "SYSTEM".equals(m.getSenderUuid()) &&
                       "uuid-123".equals(m.getReceiverUuid()) &&
                       "TestPlayer".equals(m.getReceiverName());
            }));
        }

        @Test
        @DisplayName("使用自定义消息时应替换默认内容")
        void shouldUseCustomMessage() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailDataOperator = mock(DataOperator.class);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailDataOperator);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendGameMail", String.class, String.class, String.class, String.class);
            method.setAccessible(true); // NOPMD

            method.invoke(recallCommand, "uuid-123", "TestPlayer", "Admin", "自定义召回消息");

            verify(mailDataOperator).insert(argThat(mail -> {
                MailData m = (MailData) mail;
                return m.getContent().contains("自定义召回消息");
            }));
        }

        @Test
        @DisplayName("邮件主题应替换SERVER占位符")
        void shouldReplaceServerPlaceholderInSubject() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailDataOperator = mock(DataOperator.class);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailDataOperator);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendGameMail", String.class, String.class, String.class, String.class);
            method.setAccessible(true); // NOPMD

            method.invoke(recallCommand, "uuid-123", "TestPlayer", "Admin", null);

            verify(mailDataOperator).insert(argThat(mail -> {
                MailData m = (MailData) mail;
                return m.getSubject().contains(config.getServerName()) &&
                       !m.getSubject().contains("{SERVER}");
            }));
        }

        @Test
        @DisplayName("邮件内容应替换SENDER占位符")
        void shouldReplaceSenderPlaceholderInContent() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailDataOperator = mock(DataOperator.class);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailDataOperator);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendGameMail", String.class, String.class, String.class, String.class);
            method.setAccessible(true); // NOPMD

            method.invoke(recallCommand, "uuid-123", "TestPlayer", "TheAdmin", null);

            verify(mailDataOperator).insert(argThat(mail -> {
                MailData m = (MailData) mail;
                return m.getContent().contains("TheAdmin") &&
                       !m.getContent().contains("{SENDER}");
            }));
        }

        @Test
        @DisplayName("发送者UUID应该是SYSTEM")
        void shouldUseSYSTEMasSenderUuid() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailDataOperator = mock(DataOperator.class);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailDataOperator);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendGameMail", String.class, String.class, String.class, String.class);
            method.setAccessible(true); // NOPMD

            method.invoke(recallCommand, "uuid-123", "TestPlayer", "Admin", null);

            verify(mailDataOperator).insert(argThat(mail -> {
                MailData m = (MailData) mail;
                return "SYSTEM".equals(m.getSenderUuid());
            }));
        }

        @Test
        @DisplayName("发送者名称应该是服务器名称")
        void shouldUseServerNameAsSenderName() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailDataOperator = mock(DataOperator.class);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailDataOperator);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendGameMail", String.class, String.class, String.class, String.class);
            method.setAccessible(true); // NOPMD

            method.invoke(recallCommand, "uuid-123", "TestPlayer", "Admin", null);

            verify(mailDataOperator).insert(argThat(mail -> {
                MailData m = (MailData) mail;
                return config.getServerName().equals(m.getSenderName());
            }));
        }
    }

    // ==================== getAllRegisteredPlayers (private) Tests ====================

    @Nested
    @DisplayName("getAllRegisteredPlayers 私有方法测试")
    class GetAllRegisteredPlayersTests {

        @Test
        @DisplayName("无UltiLogin时应回退到离线玩家")
        void shouldFallbackToOfflinePlayers() throws Exception {
            UUID uuid1 = UUID.randomUUID();
            OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);
            when(offlinePlayer.getUniqueId()).thenReturn(uuid1);
            when(offlinePlayer.getName()).thenReturn("OfflineGuy");
            mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[]{offlinePlayer});

            // The mail data operator returns empty (no existing mails)
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailOp = mock(DataOperator.class);
            when(mailOp.getAll()).thenReturn(new ArrayList<>());
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailOp);

            Method method = RecallCommand.class.getDeclaredMethod("getAllRegisteredPlayers");
            method.setAccessible(true); // NOPMD

            @SuppressWarnings("unchecked")
            List<?> players = (List<?>) method.invoke(recallCommand);

            assertThat(players).hasSize(1);
        }

        @Test
        @DisplayName("回退到邮件数据时应收集唯一接收者")
        void shouldCollectUniqueReceiversFromMailData() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailOp = mock(DataOperator.class);
            List<MailData> mails = new ArrayList<>();

            MailData mail1 = new MailData();
            mail1.setReceiverUuid("uuid-1");
            mail1.setReceiverName("Player1");
            mails.add(mail1);

            MailData mail2 = new MailData();
            mail2.setReceiverUuid("uuid-1"); // Duplicate
            mail2.setReceiverName("Player1");
            mails.add(mail2);

            MailData mail3 = new MailData();
            mail3.setReceiverUuid("uuid-2");
            mail3.setReceiverName("Player2");
            mails.add(mail3);

            when(mailOp.getAll()).thenReturn(mails);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailOp);
            mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[0]);

            Method method = RecallCommand.class.getDeclaredMethod("getAllRegisteredPlayers");
            method.setAccessible(true); // NOPMD

            @SuppressWarnings("unchecked")
            List<?> players = (List<?>) method.invoke(recallCommand);

            // Should have 2 unique players (uuid-1 and uuid-2)
            assertThat(players).hasSize(2);
        }

        @Test
        @DisplayName("应该排除SYSTEM UUID")
        void shouldExcludeSystemUuid() throws Exception {
            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailOp = mock(DataOperator.class);
            List<MailData> mails = new ArrayList<>();

            MailData mail = new MailData();
            mail.setReceiverUuid("SYSTEM");
            mail.setReceiverName("System");
            mails.add(mail);

            when(mailOp.getAll()).thenReturn(mails);
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailOp);
            mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[0]);

            Method method = RecallCommand.class.getDeclaredMethod("getAllRegisteredPlayers");
            method.setAccessible(true); // NOPMD

            @SuppressWarnings("unchecked")
            List<?> players = (List<?>) method.invoke(recallCommand);

            assertThat(players).isEmpty();
        }

        @Test
        @DisplayName("应该去重离线玩家和邮件数据中的玩家")
        void shouldDeduplicatePlayers() throws Exception {
            UUID uuid1 = UUID.randomUUID();

            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailOp = mock(DataOperator.class);
            List<MailData> mails = new ArrayList<>();
            MailData mail = new MailData();
            mail.setReceiverUuid(uuid1.toString());
            mail.setReceiverName("SamePlayer");
            mails.add(mail);
            when(mailOp.getAll()).thenReturn(mails);

            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailOp);

            // Same player also in offline list
            OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);
            when(offlinePlayer.getUniqueId()).thenReturn(uuid1);
            lenient().when(offlinePlayer.getName()).thenReturn("SamePlayer");
            mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[]{offlinePlayer});

            Method method = RecallCommand.class.getDeclaredMethod("getAllRegisteredPlayers");
            method.setAccessible(true); // NOPMD

            @SuppressWarnings("unchecked")
            List<?> players = (List<?>) method.invoke(recallCommand);

            // Should not duplicate
            assertThat(players).hasSize(1);
        }
    }

    // ==================== sendRecallNotifications (private) Tests ====================

    @Nested
    @DisplayName("sendRecallNotifications 私有方法测试")
    class SendRecallNotificationsTests {

        @Test
        @DisplayName("应该跳过在线玩家")
        void shouldSkipOnlinePlayers() throws Exception {
            UUID onlineUuid = UUID.randomUUID();
            Player onlinePlayer = mock(Player.class);
            lenient().when(onlinePlayer.getUniqueId()).thenReturn(onlineUuid);
            mockedBukkit.when(() -> Bukkit.getPlayer(onlineUuid)).thenReturn(onlinePlayer);

            // Setup offline players list that includes the online player
            OfflinePlayer offlineOnline = mock(OfflinePlayer.class);
            when(offlineOnline.getUniqueId()).thenReturn(onlineUuid);
            when(offlineOnline.getName()).thenReturn("OnlineGuy");
            mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[]{offlineOnline});

            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailOp = mock(DataOperator.class);
            when(mailOp.getAll()).thenReturn(new ArrayList<>());
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailOp);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendRecallNotifications", String.class, String.class);
            method.setAccessible(true); // NOPMD

            int[] results = (int[]) method.invoke(recallCommand, "Admin", null);

            // Total = 1 (one offline player found), gameMails = 0 (skipped because online)
            assertThat(results[0]).isEqualTo(1); // total
            assertThat(results[1]).isEqualTo(0); // gameMails (skipped online)
        }

        @Test
        @DisplayName("应该返回正确的统计数组")
        void shouldReturnCorrectResultArray() throws Exception {
            mockedBukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[0]);

            @SuppressWarnings("unchecked")
            DataOperator<MailData> mailOp = mock(DataOperator.class);
            when(mailOp.getAll()).thenReturn(new ArrayList<>());
            when(mockPlugin.getDataOperator(MailData.class)).thenReturn(mailOp);

            Method method = RecallCommand.class.getDeclaredMethod(
                "sendRecallNotifications", String.class, String.class);
            method.setAccessible(true); // NOPMD

            int[] results = (int[]) method.invoke(recallCommand, "Admin", null);

            assertThat(results).hasSize(4);
            assertThat(results[0]).isEqualTo(0); // total
            assertThat(results[1]).isEqualTo(0); // gameMails
            assertThat(results[2]).isEqualTo(0); // emails
            assertThat(results[3]).isEqualTo(0); // failed
        }
    }

    // ==================== handleHelp Tests ====================

    @Nested
    @DisplayName("帮助命令测试")
    class HandleHelpTests {

        @Test
        @DisplayName("帮助应该显示命令说明")
        void shouldShowHelpMessages() {
            CommandSender sender = mock(CommandSender.class);

            // handleHelp is protected - invoke via reflection
            try {
                Method helpMethod = RecallCommand.class.getDeclaredMethod("handleHelp", CommandSender.class);
                helpMethod.setAccessible(true); // NOPMD
                helpMethod.invoke(recallCommand, sender);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            verify(sender, atLeast(2)).sendMessage(any(String.class));
        }

        @Test
        @DisplayName("帮助应该包含 /recall 命令")
        void shouldContainRecallCommand() {
            CommandSender sender = mock(CommandSender.class);

            try {
                Method helpMethod = RecallCommand.class.getDeclaredMethod("handleHelp", CommandSender.class);
                helpMethod.setAccessible(true); // NOPMD
                helpMethod.invoke(recallCommand, sender);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            verify(sender, atLeast(1)).sendMessage(ArgumentMatchers.<String>argThat(msg ->
                msg.contains("/recall")
            ));
        }
    }

    // ==================== Annotation Tests ====================

    @Nested
    @DisplayName("注解配置测试")
    class AnnotationConfigTests {

        @Test
        @DisplayName("类应该有 @CmdExecutor 注解")
        void shouldHaveCmdExecutorAnnotation() {
            assertThat(RecallCommand.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.command.CmdExecutor.class
            )).isTrue();
        }

        @Test
        @DisplayName("@CmdExecutor 应该有正确的别名")
        void shouldHaveCorrectAliases() {
            com.ultikits.ultitools.annotations.command.CmdExecutor annotation =
                RecallCommand.class.getAnnotation(
                    com.ultikits.ultitools.annotations.command.CmdExecutor.class
                );

            assertThat(annotation.alias()).contains("recall", "callback");
        }

        @Test
        @DisplayName("@CmdExecutor 应该有正确的权限")
        void shouldHaveCorrectPermission() {
            com.ultikits.ultitools.annotations.command.CmdExecutor annotation =
                RecallCommand.class.getAnnotation(
                    com.ultikits.ultitools.annotations.command.CmdExecutor.class
                );

            assertThat(annotation.permission()).isEqualTo("ultimail.recall");
        }

        @Test
        @DisplayName("@CmdExecutor 应该有描述")
        void shouldHaveDescription() {
            com.ultikits.ultitools.annotations.command.CmdExecutor annotation =
                RecallCommand.class.getAnnotation(
                    com.ultikits.ultitools.annotations.command.CmdExecutor.class
                );

            assertThat(annotation.description()).isNotEmpty();
        }

        @Test
        @DisplayName("应该继承 BaseCommandExecutor")
        void shouldExtendBaseCommandExecutor() {
            assertThat(com.ultikits.ultitools.abstracts.command.BaseCommandExecutor.class)
                .isAssignableFrom(RecallCommand.class);
        }
    }

    // ==================== Placeholder Tests ====================

    @Nested
    @DisplayName("占位符替换测试")
    class PlaceholderTests {

        @Test
        @DisplayName("游戏邮件主题应该替换 SERVER 占位符")
        void shouldReplaceServerPlaceholderInSubject() {
            String subject = config.getRecallSubject();
            String replaced = subject.replace("{SERVER}", config.getServerName());

            assertThat(replaced).contains(config.getServerName());
            assertThat(replaced).doesNotContain("{SERVER}");
        }

        @Test
        @DisplayName("游戏邮件内容应该替换 SERVER 和 SENDER 占位符")
        void shouldReplacePlaceholdersInContent() {
            String content = config.getRecallContent();
            String replaced = content.replace("{SERVER}", "TestServer")
                                    .replace("{SENDER}", "Admin");

            assertThat(replaced).contains("TestServer");
            assertThat(replaced).contains("Admin");
            assertThat(replaced).doesNotContain("{SERVER}");
            assertThat(replaced).doesNotContain("{SENDER}");
        }

        @Test
        @DisplayName("电子邮件内容应该替换 PLAYER 占位符")
        void shouldReplacePlayerPlaceholderInEmail() {
            String content = config.getRecallEmailContent();
            String replaced = content.replace("{PLAYER}", "TestPlayer")
                                    .replace("{SERVER}", "TestServer")
                                    .replace("{SENDER}", "Admin");

            assertThat(replaced).contains("TestPlayer");
            assertThat(replaced).doesNotContain("{PLAYER}");
        }
    }

    // ==================== PlayerInfo Tests ====================

    @Nested
    @DisplayName("PlayerInfo 内部类测试")
    class PlayerInfoTests {

        @Test
        @DisplayName("PlayerInfo 应该正确存储玩家信息")
        void shouldStorePlayerInfo() throws Exception {
            Class<?> playerInfoClass = Class.forName(
                "com.ultikits.plugins.mail.commands.RecallCommand$PlayerInfo");
            Object playerInfo = playerInfoClass.getDeclaredConstructor(
                    String.class, String.class, String.class)
                .newInstance("uuid-123", "TestPlayer", "test@example.com");

            Field uuidField = playerInfoClass.getDeclaredField("uuid");
            uuidField.setAccessible(true); // NOPMD
            Field nameField = playerInfoClass.getDeclaredField("name");
            nameField.setAccessible(true); // NOPMD
            Field emailField = playerInfoClass.getDeclaredField("email");
            emailField.setAccessible(true); // NOPMD

            assertThat(uuidField.get(playerInfo)).isEqualTo("uuid-123");
            assertThat(nameField.get(playerInfo)).isEqualTo("TestPlayer");
            assertThat(emailField.get(playerInfo)).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("PlayerInfo 应该允许 null email")
        void shouldAllowNullEmail() throws Exception {
            Class<?> playerInfoClass = Class.forName(
                "com.ultikits.plugins.mail.commands.RecallCommand$PlayerInfo");
            Object playerInfo = playerInfoClass.getDeclaredConstructor(
                    String.class, String.class, String.class)
                .newInstance("uuid-123", "TestPlayer", null);

            Field emailField = playerInfoClass.getDeclaredField("email");
            emailField.setAccessible(true); // NOPMD

            assertThat(emailField.get(playerInfo)).isNull();
        }
    }

    // ==================== Command Format Tests ====================

    @Nested
    @DisplayName("命令格式测试")
    class CommandFormatTests {

        @Test
        @DisplayName("应该支持无参数的召回命令")
        void shouldSupportNoArgumentFormat() throws Exception {
            RecallCommand.class.getDeclaredMethod("sendRecall", CommandSender.class);
        }

        @Test
        @DisplayName("应该支持带自定义消息的召回命令")
        void shouldSupportCustomMessageFormat() throws Exception {
            RecallCommand.class.getDeclaredMethod("sendRecallWithMessage", CommandSender.class, String.class);
        }
    }
}
