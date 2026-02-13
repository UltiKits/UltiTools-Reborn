package com.ultikits.plugins.login.service;

import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.config.EmailConfig;
import com.ultikits.plugins.login.entity.AccountData;
import com.ultikits.ultitools.context.ContextHolder;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;
import com.ultikits.ultitools.services.EmailService;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("EmailVerificationService Tests")
class EmailVerificationServiceTest {

    private EmailVerificationService service;
    private EmailConfig emailConfig;
    private LoginService loginService;
    private EmailService emailService;

    @SuppressWarnings("unchecked")
    private DataOperator<AccountData> dataOperator = mock(DataOperator.class);
    @SuppressWarnings("unchecked")
    private Query<AccountData> mockQuery = mock(Query.class);

    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();

        emailConfig = createDefaultEmailConfig();
        loginService = mock(LoginService.class);
        emailService = mock(EmailService.class);

        // Set up Bukkit.server
        Server mockServer = mock(Server.class);
        PluginManager mockPm = mock(PluginManager.class);
        Plugin mockBukkitPlugin = mock(Plugin.class);
        BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
        when(mockServer.getPluginManager()).thenReturn(mockPm);
        when(mockPm.getPlugin("UltiTools")).thenReturn(mockBukkitPlugin);
        when(mockServer.getScheduler()).thenReturn(mockScheduler);
        when(mockServer.getName()).thenReturn("TestServer");

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true); // NOPMD
        serverField.set(null, mockServer);

        // Set up ContextHolder with EmailService
        SimpleContainer mockContext = mock(SimpleContainer.class);
        when(mockContext.getBean(EmailService.class)).thenReturn(emailService);
        ContextHolder.setContext(mockContext);

        // Set up DataOperator mock
        when(UltiLoginTestHelper.getMockPlugin().getDataOperator(AccountData.class)).thenReturn(dataOperator);

        // Set up Query DSL mock
        when(dataOperator.query()).thenReturn(mockQuery);
        when(mockQuery.where(anyString())).thenReturn(mockQuery);
        when(mockQuery.and(anyString())).thenReturn(mockQuery);
        when(mockQuery.eq(any())).thenReturn(mockQuery);
        when(mockQuery.ne(any())).thenReturn(mockQuery);
        when(mockQuery.list()).thenReturn(Collections.emptyList());
        when(mockQuery.first()).thenReturn(null);
        when(mockQuery.exists()).thenReturn(false);
        when(mockQuery.count()).thenReturn(0L);

        // Default EmailService behavior
        when(emailService.isEnabled()).thenReturn(true);
        when(emailService.generateVerificationCode(anyInt())).thenReturn("123456");
        when(emailService.sendVerificationCodeEmail(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(true);

        // Default LoginService behavior
        when(loginService.getPlayerIp(any(Player.class))).thenReturn("127.0.0.1");

        // Create service
        service = new EmailVerificationService(
                UltiLoginTestHelper.getMockPlugin(), emailConfig, loginService);

        // Inject emailService via reflection (lazy init field)
        Field emailSvcField = EmailVerificationService.class.getDeclaredField("emailService");
        emailSvcField.setAccessible(true); // NOPMD
        emailSvcField.set(service, emailService);

        // Create player
        playerUuid = UUID.randomUUID();
        player = UltiLoginTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiLoginTestHelper.tearDown();
        ContextHolder.clear();
    }

    // ==================== Email Bind Flow ====================

    @Nested
    @DisplayName("requestEmailBind")
    class RequestEmailBind {

        @Test
        @DisplayName("Should succeed with valid email")
        void happyPath() {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@example.com");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessageKey()).isEqualTo("email_bind_prompt");
            assertThat(result.getReplacements()).contains("user@example.com");

            verify(emailService).generateVerificationCode(6);
            verify(emailService).sendVerificationCodeEmail(
                    eq("user@example.com"), eq("123456"), anyString(), eq(5));
        }

        @Test
        @DisplayName("Should fail when email service not enabled")
        void emailNotEnabled() {
            when(emailService.isEnabled()).thenReturn(false);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@example.com");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_not_enabled");
        }

        @Test
        @DisplayName("Should fail when player already has verified email")
        void alreadyBound() {
            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            account.setEmail("old@example.com");
            account.setEmailVerified(true);
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "new@example.com");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_already_bound");
            assertThat(result.getReplacements()).contains("old@example.com");
        }

        @Test
        @DisplayName("Should fail with invalid email format")
        void invalidFormat() {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "not-an-email");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_invalid_format");
        }

        @Test
        @DisplayName("Should fail with invalid email format - no domain")
        void invalidFormatNoDomain() {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_invalid_format");
        }

        @Test
        @DisplayName("Should fail with blacklisted domain")
        void domainBlocked() {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@mailinator.com");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_domain_blocked");
        }

        @Test
        @DisplayName("Should fail with blacklisted domain case-insensitive")
        void domainBlockedCaseInsensitive() {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@MAILINATOR.COM");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_domain_blocked");
        }

        @Test
        @DisplayName("Should fail when max accounts per email reached")
        void maxAccountsReached() {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            AccountData existingAccount = UltiLoginTestHelper.createSampleAccount(
                    UUID.randomUUID(), "OtherPlayer", "hash", "salt");
            when(mockQuery.list()).thenReturn(Collections.singletonList(existingAccount));

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@example.com");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_max_accounts");
        }

        @Test
        @DisplayName("Should fail during cooldown")
        void cooldown() throws Exception {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            // Set lastSendTime to recent
            Field lastSendField = EmailVerificationService.class.getDeclaredField("lastSendTime");
            lastSendField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastSend = (Map<UUID, Long>) lastSendField.get(service);
            lastSend.put(playerUuid, System.currentTimeMillis());

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@example.com");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_cooldown");
        }

        @Test
        @DisplayName("Should fail when email send fails")
        void sendFailed() {
            when(loginService.getAccount(playerUuid)).thenReturn(null);
            when(emailService.sendVerificationCodeEmail(anyString(), anyString(), anyString(), anyInt()))
                    .thenReturn(false);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@example.com");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_send_failed");
        }

        @Test
        @DisplayName("Should allow resend after cooldown expires")
        void resendAfterCooldown() throws Exception {
            when(loginService.getAccount(playerUuid)).thenReturn(null);

            // Set lastSendTime to beyond cooldown
            Field lastSendField = EmailVerificationService.class.getDeclaredField("lastSendTime");
            lastSendField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastSend = (Map<UUID, Long>) lastSendField.get(service);
            lastSend.put(playerUuid, System.currentTimeMillis() - 61_000);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@example.com");

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should allow bind when account exists but has no verified email")
        void accountWithoutVerifiedEmail() {
            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            account.setEmail(null);
            account.setEmailVerified(false);
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.BindResult result =
                    service.requestEmailBind(player, "user@example.com");

            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ==================== Verify Email Bind ====================

    @Nested
    @DisplayName("verifyEmailBind")
    class VerifyEmailBind {

        @Test
        @DisplayName("Should succeed with correct code")
        void happyPath() throws Exception {
            // Set up pending bind
            addPendingBind(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "123456");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessageKey()).isEqualTo("email_bind_success");
            assertThat(account.getEmail()).isEqualTo("user@example.com");
            assertThat(account.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("Should succeed with case-insensitive code")
        void caseInsensitive() throws Exception {
            addPendingBind(playerUuid, "user@example.com", "ABC123", System.currentTimeMillis());

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "abc123");

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should fail when no pending request")
        void noPending() {
            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "123456");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_no_pending");
        }

        @Test
        @DisplayName("Should fail when code expired")
        void expired() throws Exception {
            long pastTime = System.currentTimeMillis() - (301 * 1000L); // 301 seconds ago
            addPendingBind(playerUuid, "user@example.com", "123456", pastTime);

            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "123456");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_code_expired");
        }

        @Test
        @DisplayName("Should fail with wrong code and show remaining attempts")
        void wrongCode() throws Exception {
            addPendingBind(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "000000");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_code_invalid");
            assertThat(result.getReplacements()).contains("2"); // 3 max - 1 attempt = 2 remaining
        }

        @Test
        @DisplayName("Should fail when max attempts reached")
        void maxAttempts() throws Exception {
            addPendingBind(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            // Use up all attempts
            service.verifyEmailBind(player, "000001");
            service.verifyEmailBind(player, "000002");
            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "000003");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_code_max_attempts");

            // Pending should be removed
            EmailVerificationService.VerifyResult afterMax =
                    service.verifyEmailBind(player, "123456");
            assertThat(afterMax.getMessageKey()).isEqualTo("email_no_pending");
        }

        @Test
        @DisplayName("Should execute rewards when enabled")
        void rewardExecution() throws Exception {
            emailConfig.setRewardEnabled(true);
            emailConfig.setRewardCommands(Arrays.asList("give %player% diamond 1"));

            addPendingBind(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "123456");

            assertThat(result.isSuccess()).isTrue();
            // Reward dispatch is done via scheduler — verify scheduler interaction
            verify(Bukkit.getScheduler()).runTask(any(Plugin.class), any(Runnable.class));
        }

        @Test
        @DisplayName("Should not execute rewards when disabled")
        void noRewardsWhenDisabled() throws Exception {
            emailConfig.setRewardEnabled(false);

            addPendingBind(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            service.verifyEmailBind(player, "123456");

            verify(Bukkit.getScheduler(), never()).runTask(any(Plugin.class), any(Runnable.class));
        }

        @Test
        @DisplayName("Should update account even when dataOperator throws")
        void updateThrows() throws Exception {
            addPendingBind(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccount(playerUuid)).thenReturn(account);
            doThrow(new IllegalAccessException("test")).when(dataOperator).update(any(AccountData.class));

            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "123456");

            // Should still succeed (just log the error)
            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ==================== Password Recovery Flow ====================

    @Nested
    @DisplayName("requestPasswordRecovery")
    class RequestPasswordRecovery {

        @Test
        @DisplayName("Should succeed for registered player with verified email")
        void happyPath() {
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            account.setEmail("user@example.com");
            account.setEmailVerified(true);
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.RecoverResult result =
                    service.requestPasswordRecovery(player);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessageKey()).isEqualTo("recover_email_sent");
            verify(emailService).sendVerificationCodeEmail(
                    eq("user@example.com"), eq("123456"), anyString(), eq(5));
        }

        @Test
        @DisplayName("Should fail when email service not enabled")
        void emailNotEnabled() {
            when(emailService.isEnabled()).thenReturn(false);

            EmailVerificationService.RecoverResult result =
                    service.requestPasswordRecovery(player);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_not_enabled");
        }

        @Test
        @DisplayName("Should fail when player not registered")
        void notRegistered() {
            when(loginService.isRegistered(playerUuid)).thenReturn(false);

            EmailVerificationService.RecoverResult result =
                    service.requestPasswordRecovery(player);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("recover_not_registered");
        }

        @Test
        @DisplayName("Should fail when player has no bound email")
        void noEmail() {
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            account.setEmail(null);
            account.setEmailVerified(false);
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.RecoverResult result =
                    service.requestPasswordRecovery(player);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("recover_no_email");
        }

        @Test
        @DisplayName("Should fail when email not verified")
        void emailNotVerified() {
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            account.setEmail("user@example.com");
            account.setEmailVerified(false);
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            EmailVerificationService.RecoverResult result =
                    service.requestPasswordRecovery(player);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("recover_no_email");
        }

        @Test
        @DisplayName("Should fail during cooldown")
        void cooldown() throws Exception {
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            account.setEmail("user@example.com");
            account.setEmailVerified(true);
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            // Set lastSendTime to recent
            Field lastSendField = EmailVerificationService.class.getDeclaredField("lastSendTime");
            lastSendField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastSend = (Map<UUID, Long>) lastSendField.get(service);
            lastSend.put(playerUuid, System.currentTimeMillis());

            EmailVerificationService.RecoverResult result =
                    service.requestPasswordRecovery(player);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_cooldown");
        }

        @Test
        @DisplayName("Should fail when send fails")
        void sendFailed() {
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            AccountData account = UltiLoginTestHelper.createSampleAccount(
                    playerUuid, "TestPlayer", "hash", "salt");
            account.setEmail("user@example.com");
            account.setEmailVerified(true);
            when(loginService.getAccount(playerUuid)).thenReturn(account);

            when(emailService.sendVerificationCodeEmail(anyString(), anyString(), anyString(), anyInt()))
                    .thenReturn(false);

            EmailVerificationService.RecoverResult result =
                    service.requestPasswordRecovery(player);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_send_failed");
        }
    }

    // ==================== Verify Recovery Code ====================

    @Nested
    @DisplayName("verifyRecoveryCode")
    class VerifyRecoveryCode {

        @Test
        @DisplayName("Should succeed with correct code")
        void happyPath() throws Exception {
            addPendingRecovery(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            EmailVerificationService.RecoverVerifyResult result =
                    service.verifyRecoveryCode(player, "123456");

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should fail when no pending recovery")
        void noPending() {
            EmailVerificationService.RecoverVerifyResult result =
                    service.verifyRecoveryCode(player, "123456");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_no_pending");
        }

        @Test
        @DisplayName("Should fail when code expired")
        void expired() throws Exception {
            long pastTime = System.currentTimeMillis() - (301 * 1000L);
            addPendingRecovery(playerUuid, "user@example.com", "123456", pastTime);

            EmailVerificationService.RecoverVerifyResult result =
                    service.verifyRecoveryCode(player, "123456");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_code_expired");
        }

        @Test
        @DisplayName("Should fail with wrong code")
        void wrongCode() throws Exception {
            addPendingRecovery(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            EmailVerificationService.RecoverVerifyResult result =
                    service.verifyRecoveryCode(player, "000000");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_code_invalid");
        }

        @Test
        @DisplayName("Should fail when max attempts reached")
        void maxAttempts() throws Exception {
            addPendingRecovery(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            service.verifyRecoveryCode(player, "000001");
            service.verifyRecoveryCode(player, "000002");
            EmailVerificationService.RecoverVerifyResult result =
                    service.verifyRecoveryCode(player, "000003");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("email_code_max_attempts");
        }

        @Test
        @DisplayName("Should store verified IP")
        void storesVerifiedIp() throws Exception {
            addPendingRecovery(playerUuid, "user@example.com", "123456", System.currentTimeMillis());
            when(loginService.getPlayerIp(player)).thenReturn("192.168.1.1");

            service.verifyRecoveryCode(player, "123456");

            Field verifiedField = EmailVerificationService.class.getDeclaredField("recoveryVerified");
            verifiedField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, String> verified = (Map<UUID, String>) verifiedField.get(service);
            assertThat(verified.get(playerUuid)).isEqualTo("192.168.1.1");
        }
    }

    // ==================== Reset Password After Recovery ====================

    @Nested
    @DisplayName("resetPasswordAfterRecovery")
    class ResetPasswordAfterRecovery {

        @Test
        @DisplayName("Should succeed with matching IP")
        void happyPath() throws Exception {
            // Set up recovery verified state
            Field verifiedField = EmailVerificationService.class.getDeclaredField("recoveryVerified");
            verifiedField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, String> verified = (Map<UUID, String>) verifiedField.get(service);
            verified.put(playerUuid, "127.0.0.1");

            when(loginService.getPlayerIp(player)).thenReturn("127.0.0.1");
            when(loginService.resetPassword(playerUuid, "newpass123")).thenReturn(true);

            boolean result = service.resetPasswordAfterRecovery(player, "newpass123");

            assertThat(result).isTrue();
            verify(loginService).resetPassword(playerUuid, "newpass123");
            assertThat(verified).doesNotContainKey(playerUuid);
        }

        @Test
        @DisplayName("Should fail when not verified")
        void notVerified() {
            boolean result = service.resetPasswordAfterRecovery(player, "newpass123");

            assertThat(result).isFalse();
            verify(loginService, never()).resetPassword(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("Should fail when IP does not match")
        void ipMismatch() throws Exception {
            Field verifiedField = EmailVerificationService.class.getDeclaredField("recoveryVerified");
            verifiedField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, String> verified = (Map<UUID, String>) verifiedField.get(service);
            verified.put(playerUuid, "192.168.1.1");

            when(loginService.getPlayerIp(player)).thenReturn("10.0.0.1");

            boolean result = service.resetPasswordAfterRecovery(player, "newpass123");

            assertThat(result).isFalse();
            verify(loginService, never()).resetPassword(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("Should return false when loginService.resetPassword fails")
        void resetFails() throws Exception {
            Field verifiedField = EmailVerificationService.class.getDeclaredField("recoveryVerified");
            verifiedField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, String> verified = (Map<UUID, String>) verifiedField.get(service);
            verified.put(playerUuid, "127.0.0.1");

            when(loginService.getPlayerIp(player)).thenReturn("127.0.0.1");
            when(loginService.resetPassword(playerUuid, "newpass123")).thenReturn(false);

            boolean result = service.resetPasswordAfterRecovery(player, "newpass123");

            assertThat(result).isFalse();
        }
    }

    // ==================== Cleanup ====================

    @Nested
    @DisplayName("cleanupExpired")
    class Cleanup {

        @Test
        @DisplayName("Should remove expired pending binds")
        void removesExpiredBinds() throws Exception {
            long pastTime = System.currentTimeMillis() - (301 * 1000L);
            addPendingBind(playerUuid, "user@example.com", "123456", pastTime);

            service.cleanupExpired();

            // Verify bind was removed
            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "123456");
            assertThat(result.getMessageKey()).isEqualTo("email_no_pending");
        }

        @Test
        @DisplayName("Should remove expired pending recoveries")
        void removesExpiredRecoveries() throws Exception {
            long pastTime = System.currentTimeMillis() - (301 * 1000L);
            addPendingRecovery(playerUuid, "user@example.com", "123456", pastTime);

            service.cleanupExpired();

            EmailVerificationService.RecoverVerifyResult result =
                    service.verifyRecoveryCode(player, "123456");
            assertThat(result.getMessageKey()).isEqualTo("email_no_pending");
        }

        @Test
        @DisplayName("Should keep non-expired pending binds")
        void keepsValidBinds() throws Exception {
            addPendingBind(playerUuid, "user@example.com", "123456", System.currentTimeMillis());

            service.cleanupExpired();

            // Should still be there
            EmailVerificationService.VerifyResult result =
                    service.verifyEmailBind(player, "123456");
            assertThat(result.getMessageKey()).isNotEqualTo("email_no_pending");
        }

        @Test
        @DisplayName("Should remove stale lastSendTime entries")
        void removesStaleLastSendTime() throws Exception {
            Field lastSendField = EmailVerificationService.class.getDeclaredField("lastSendTime");
            lastSendField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastSend = (Map<UUID, Long>) lastSendField.get(service);

            UUID staleUuid = UUID.randomUUID();
            lastSend.put(staleUuid, System.currentTimeMillis() - (11 * 60 * 1000L)); // 11 min ago
            lastSend.put(playerUuid, System.currentTimeMillis()); // fresh

            service.cleanupExpired();

            assertThat(lastSend).doesNotContainKey(staleUuid);
            assertThat(lastSend).containsKey(playerUuid);
        }
    }

    // ==================== Result Classes ====================

    @Nested
    @DisplayName("Result Classes")
    class ResultClasses {

        @Test
        @DisplayName("BindResult should store fields correctly")
        void bindResult() {
            EmailVerificationService.BindResult result =
                    new EmailVerificationService.BindResult(true, "key", "arg1", "arg2");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessageKey()).isEqualTo("key");
            assertThat(result.getReplacements()).containsExactly("arg1", "arg2");
        }

        @Test
        @DisplayName("BindResult with no replacements")
        void bindResultNoReplacements() {
            EmailVerificationService.BindResult result =
                    new EmailVerificationService.BindResult(false, "error_key");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getReplacements()).isEmpty();
        }

        @Test
        @DisplayName("VerifyResult should store fields correctly")
        void verifyResult() {
            EmailVerificationService.VerifyResult result =
                    new EmailVerificationService.VerifyResult(true, "key", "val");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessageKey()).isEqualTo("key");
            assertThat(result.getReplacements()).containsExactly("val");
        }

        @Test
        @DisplayName("RecoverResult should store fields correctly")
        void recoverResult() {
            EmailVerificationService.RecoverResult result =
                    new EmailVerificationService.RecoverResult(false, "err", "detail");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessageKey()).isEqualTo("err");
            assertThat(result.getReplacements()).containsExactly("detail");
        }

        @Test
        @DisplayName("RecoverVerifyResult should store fields correctly")
        void recoverVerifyResult() {
            EmailVerificationService.RecoverVerifyResult result =
                    new EmailVerificationService.RecoverVerifyResult(true, "ok");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMessageKey()).isEqualTo("ok");
        }
    }

    // ==================== Helpers ====================

    private EmailConfig createDefaultEmailConfig() {
        EmailConfig config = new EmailConfig();
        // Use defaults — they are already set in the constructor
        return config;
    }

    @SuppressWarnings("unchecked")
    private void addPendingBind(UUID uuid, String email, String code, long createdAt) throws Exception {
        Field pendingField = EmailVerificationService.class.getDeclaredField("pendingBinds");
        pendingField.setAccessible(true); // NOPMD
        Map<UUID, Object> pendingMap = (Map<UUID, Object>) pendingField.get(service);

        // Create PendingVerification via reflection (private inner class)
        Class<?> pvClass = Class.forName(
                "com.ultikits.plugins.login.service.EmailVerificationService$PendingVerification");
        java.lang.reflect.Constructor<?> ctor = pvClass.getDeclaredConstructor(
                String.class, String.class, long.class);
        ctor.setAccessible(true); // NOPMD
        Object pv = ctor.newInstance(email, code, createdAt);

        pendingMap.put(uuid, pv);
    }

    @SuppressWarnings("unchecked")
    private void addPendingRecovery(UUID uuid, String email, String code, long createdAt) throws Exception {
        Field pendingField = EmailVerificationService.class.getDeclaredField("pendingRecoveries");
        pendingField.setAccessible(true); // NOPMD
        Map<UUID, Object> pendingMap = (Map<UUID, Object>) pendingField.get(service);

        Class<?> pvClass = Class.forName(
                "com.ultikits.plugins.login.service.EmailVerificationService$PendingVerification");
        java.lang.reflect.Constructor<?> ctor = pvClass.getDeclaredConstructor(
                String.class, String.class, long.class);
        ctor.setAccessible(true); // NOPMD
        Object pv = ctor.newInstance(email, code, createdAt);

        pendingMap.put(uuid, pv);
    }
}
