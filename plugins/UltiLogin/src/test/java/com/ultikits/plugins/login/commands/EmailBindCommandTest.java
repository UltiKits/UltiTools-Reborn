package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.UltiLogin;
import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.service.EmailVerificationService;
import com.ultikits.plugins.login.service.EmailVerificationService.BindResult;
import com.ultikits.plugins.login.service.EmailVerificationService.VerifyResult;
import com.ultikits.plugins.login.service.LoginService;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("EmailBindCommand Tests")
class EmailBindCommandTest {

    private UltiLogin plugin;
    private EmailVerificationService emailVerificationService;
    private LoginService loginService;
    private EmailBindCommand command;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();
        plugin = UltiLoginTestHelper.getMockPlugin();
        emailVerificationService = mock(EmailVerificationService.class);
        loginService = mock(LoginService.class);
        command = new EmailBindCommand(plugin, emailVerificationService, loginService);

        playerUuid = UUID.randomUUID();
        player = UltiLoginTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiLoginTestHelper.tearDown();
    }

    private BindResult mockBindResult(boolean success, String messageKey, String[] replacements) {
        BindResult result = mock(BindResult.class);
        when(result.isSuccess()).thenReturn(success);
        when(result.getMessageKey()).thenReturn(messageKey);
        when(result.getReplacements()).thenReturn(replacements);
        return result;
    }

    private VerifyResult mockVerifyResult(boolean success, String messageKey, String[] replacements) {
        VerifyResult result = mock(VerifyResult.class);
        when(result.isSuccess()).thenReturn(success);
        when(result.getMessageKey()).thenReturn(messageKey);
        when(result.getReplacements()).thenReturn(replacements);
        return result;
    }

    @Nested
    @DisplayName("Not logged in")
    class NotLoggedIn {

        @Test
        @DisplayName("Should block email binding when not logged in")
        void blockEmailBindWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            command.handleCommand(player, "test@example.com");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("please_login_first");
            verify(emailVerificationService, never()).requestEmailBind(any(), anyString());
        }

        @Test
        @DisplayName("Should block code verification when not logged in")
        void blockCodeVerifyWhenNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            command.handleCommand(player, "123456");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("please_login_first");
            verify(emailVerificationService, never()).verifyEmailBind(any(), anyString());
        }
    }

    @Nested
    @DisplayName("Email binding")
    class EmailBinding {

        @BeforeEach
        void setLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
        }

        @Test
        @DisplayName("Should call requestEmailBind for email argument")
        void callRequestEmailBind() {
            when(plugin.i18n("email_bind_prompt")).thenReturn("Verification sent to {EMAIL}");
            BindResult result = mockBindResult(true, "email_bind_prompt", new String[]{"{EMAIL}", "test@example.com"});
            when(emailVerificationService.requestEmailBind(player, "test@example.com")).thenReturn(result);

            command.handleCommand(player, "test@example.com");

            verify(emailVerificationService).requestEmailBind(player, "test@example.com");
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("test@example.com");
        }

        @Test
        @DisplayName("Should send error for invalid email format")
        void invalidEmailFormat() {
            BindResult result = mockBindResult(false, "email_invalid_format", null);
            when(emailVerificationService.requestEmailBind(player, "bad@format.com")).thenReturn(result);

            command.handleCommand(player, "bad@format.com");

            verify(emailVerificationService).requestEmailBind(player, "bad@format.com");
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_invalid_format");
        }

        @Test
        @DisplayName("Should send error when already bound")
        void alreadyBound() {
            when(plugin.i18n("email_already_bound")).thenReturn("Email already bound: {EMAIL}");
            BindResult result = mockBindResult(false, "email_already_bound", new String[]{"{EMAIL}", "existing@example.com"});
            when(emailVerificationService.requestEmailBind(player, "new@example.com")).thenReturn(result);

            command.handleCommand(player, "new@example.com");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("existing@example.com");
        }

        @Test
        @DisplayName("Should send error when domain is blocked")
        void domainBlocked() {
            BindResult result = mockBindResult(false, "email_domain_blocked", null);
            when(emailVerificationService.requestEmailBind(player, "test@tempmail.com")).thenReturn(result);

            command.handleCommand(player, "test@tempmail.com");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_domain_blocked");
        }

        @Test
        @DisplayName("Should send error when max accounts reached")
        void maxAccountsReached() {
            when(plugin.i18n("email_max_accounts")).thenReturn("Max {MAX} accounts per email");
            BindResult result = mockBindResult(false, "email_max_accounts", new String[]{"{MAX}", "1"});
            when(emailVerificationService.requestEmailBind(player, "shared@example.com")).thenReturn(result);

            command.handleCommand(player, "shared@example.com");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("1");
        }

        @Test
        @DisplayName("Should send error when on cooldown")
        void onCooldown() {
            when(plugin.i18n("email_cooldown")).thenReturn("Please wait {TIME} seconds");
            BindResult result = mockBindResult(false, "email_cooldown", new String[]{"{TIME}", "45"});
            when(emailVerificationService.requestEmailBind(player, "test@example.com")).thenReturn(result);

            command.handleCommand(player, "test@example.com");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("45");
        }

        @Test
        @DisplayName("Should send error when email send fails")
        void emailSendFailed() {
            BindResult result = mockBindResult(false, "email_send_failed", null);
            when(emailVerificationService.requestEmailBind(player, "test@example.com")).thenReturn(result);

            command.handleCommand(player, "test@example.com");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_send_failed");
        }

        @Test
        @DisplayName("Should handle result with no replacements")
        void noReplacements() {
            BindResult result = mockBindResult(true, "email_bind_success", null);
            when(emailVerificationService.requestEmailBind(player, "test@example.com")).thenReturn(result);

            command.handleCommand(player, "test@example.com");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_bind_success");
        }
    }

    @Nested
    @DisplayName("Code verification")
    class CodeVerification {

        @BeforeEach
        void setLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
        }

        @Test
        @DisplayName("Should call verifyEmailBind for code argument")
        void callVerifyEmailBind() {
            VerifyResult result = mockVerifyResult(true, "email_bind_success", null);
            when(emailVerificationService.verifyEmailBind(player, "123456")).thenReturn(result);

            command.handleCommand(player, "123456");

            verify(emailVerificationService).verifyEmailBind(player, "123456");
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_bind_success");
        }

        @Test
        @DisplayName("Should send error for invalid code")
        void invalidCode() {
            when(plugin.i18n("email_code_invalid")).thenReturn("Invalid code, {COUNT} attempts remaining");
            VerifyResult result = mockVerifyResult(false, "email_code_invalid", new String[]{"{COUNT}", "2"});
            when(emailVerificationService.verifyEmailBind(player, "000000")).thenReturn(result);

            command.handleCommand(player, "000000");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("2");
        }

        @Test
        @DisplayName("Should send error for expired code")
        void expiredCode() {
            VerifyResult result = mockVerifyResult(false, "email_code_expired", null);
            when(emailVerificationService.verifyEmailBind(player, "123456")).thenReturn(result);

            command.handleCommand(player, "123456");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_code_expired");
        }

        @Test
        @DisplayName("Should send error when no pending request")
        void noPending() {
            VerifyResult result = mockVerifyResult(false, "email_no_pending", null);
            when(emailVerificationService.verifyEmailBind(player, "123456")).thenReturn(result);

            command.handleCommand(player, "123456");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_no_pending");
        }

        @Test
        @DisplayName("Should send error when max attempts reached")
        void maxAttempts() {
            VerifyResult result = mockVerifyResult(false, "email_code_max_attempts", null);
            when(emailVerificationService.verifyEmailBind(player, "999999")).thenReturn(result);

            command.handleCommand(player, "999999");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_code_max_attempts");
        }

        @Test
        @DisplayName("Should handle reward message")
        void rewardMessage() {
            VerifyResult result = mockVerifyResult(true, "email_bind_reward", null);
            when(emailVerificationService.verifyEmailBind(player, "123456")).thenReturn(result);

            command.handleCommand(player, "123456");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_bind_reward");
        }
    }

    @Nested
    @DisplayName("Help command")
    class HelpCmd {

        @Test
        @DisplayName("Should show help messages")
        void showHelp() {
            command.help(player);

            verify(player, times(2)).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("Argument detection")
    class ArgumentDetection {

        @BeforeEach
        void setLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
        }

        @Test
        @DisplayName("Should detect email by @ symbol")
        void detectEmail() {
            BindResult result = mockBindResult(true, "email_bind_prompt", null);
            when(emailVerificationService.requestEmailBind(player, "user@domain.com")).thenReturn(result);

            command.handleCommand(player, "user@domain.com");

            verify(emailVerificationService).requestEmailBind(player, "user@domain.com");
            verify(emailVerificationService, never()).verifyEmailBind(any(), anyString());
        }

        @Test
        @DisplayName("Should detect code by absence of @ symbol")
        void detectCode() {
            VerifyResult result = mockVerifyResult(true, "email_bind_success", null);
            when(emailVerificationService.verifyEmailBind(player, "654321")).thenReturn(result);

            command.handleCommand(player, "654321");

            verify(emailVerificationService).verifyEmailBind(player, "654321");
            verify(emailVerificationService, never()).requestEmailBind(any(), anyString());
        }
    }
}
