package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.UltiLogin;
import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.service.EmailVerificationService;
import com.ultikits.plugins.login.service.EmailVerificationService.RecoverResult;
import com.ultikits.plugins.login.service.EmailVerificationService.RecoverVerifyResult;
import com.ultikits.plugins.login.service.LoginService;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RecoverCommand Tests")
class RecoverCommandTest {

    private UltiLogin plugin;
    private EmailVerificationService emailVerificationService;
    private LoginService loginService;
    private RecoverCommand command;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();
        plugin = UltiLoginTestHelper.getMockPlugin();
        emailVerificationService = mock(EmailVerificationService.class);
        loginService = mock(LoginService.class);
        command = new RecoverCommand(plugin, emailVerificationService, loginService);

        playerUuid = UUID.randomUUID();
        player = UltiLoginTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiLoginTestHelper.tearDown();
    }

    private RecoverResult mockRecoverResult(boolean success, String messageKey, String[] replacements) {
        RecoverResult result = mock(RecoverResult.class);
        when(result.isSuccess()).thenReturn(success);
        when(result.getMessageKey()).thenReturn(messageKey);
        when(result.getReplacements()).thenReturn(replacements);
        return result;
    }

    private RecoverVerifyResult mockRecoverVerifyResult(boolean success, String messageKey) {
        RecoverVerifyResult result = mock(RecoverVerifyResult.class);
        when(result.isSuccess()).thenReturn(success);
        when(result.getMessageKey()).thenReturn(messageKey);
        return result;
    }

    @Nested
    @DisplayName("Request recovery")
    class RequestRecovery {

        @Test
        @DisplayName("Should call requestPasswordRecovery when not logged in")
        void callRequestRecovery() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            RecoverResult result = mockRecoverResult(true, "recover_email_sent", null);
            when(emailVerificationService.requestPasswordRecovery(player)).thenReturn(result);

            command.requestRecovery(player);

            verify(emailVerificationService).requestPasswordRecovery(player);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("recover_email_sent");
        }

        @Test
        @DisplayName("Should send error when no email bound")
        void noEmailBound() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            RecoverResult result = mockRecoverResult(false, "recover_no_email", null);
            when(emailVerificationService.requestPasswordRecovery(player)).thenReturn(result);

            command.requestRecovery(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("recover_no_email");
        }

        @Test
        @DisplayName("Should send error when not registered")
        void notRegistered() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            RecoverResult result = mockRecoverResult(false, "recover_not_registered", null);
            when(emailVerificationService.requestPasswordRecovery(player)).thenReturn(result);

            command.requestRecovery(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("recover_not_registered");
        }

        @Test
        @DisplayName("Should send error on cooldown")
        void onCooldown() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(plugin.i18n("email_cooldown")).thenReturn("Please wait {TIME} seconds");
            RecoverResult result = mockRecoverResult(false, "email_cooldown", new String[]{"{TIME}", "30"});
            when(emailVerificationService.requestPasswordRecovery(player)).thenReturn(result);

            command.requestRecovery(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("30");
        }

        @Test
        @DisplayName("Should send error when email service not enabled")
        void emailNotEnabled() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            RecoverResult result = mockRecoverResult(false, "email_not_enabled", null);
            when(emailVerificationService.requestPasswordRecovery(player)).thenReturn(result);

            command.requestRecovery(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_not_enabled");
        }

        @Test
        @DisplayName("Should send error when email send fails")
        void emailSendFailed() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            RecoverResult result = mockRecoverResult(false, "email_send_failed", null);
            when(emailVerificationService.requestPasswordRecovery(player)).thenReturn(result);

            command.requestRecovery(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_send_failed");
        }
    }

    @Nested
    @DisplayName("Already logged in")
    class AlreadyLoggedIn {

        @BeforeEach
        void setLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
        }

        @Test
        @DisplayName("Should block requestRecovery when logged in")
        void blockRequestRecovery() {
            command.requestRecovery(player);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("recover_already_logged");
            verify(emailVerificationService, never()).requestPasswordRecovery(any());
        }

        @Test
        @DisplayName("Should block resetPassword when logged in")
        void blockResetPassword() {
            command.resetPassword(player, "123456", "newpass", "newpass");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("recover_already_logged");
            verify(emailVerificationService, never()).verifyRecoveryCode(any(), anyString());
        }
    }

    @Nested
    @DisplayName("Reset password")
    class ResetPasswordTests {

        @BeforeEach
        void setNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
        }

        @Test
        @DisplayName("Should complete full recovery flow")
        void fullRecoveryFlow() {
            when(loginService.isPasswordValid("newPassword1")).thenReturn(true);
            RecoverVerifyResult verifyResult = mockRecoverVerifyResult(true, null);
            when(emailVerificationService.verifyRecoveryCode(player, "123456")).thenReturn(verifyResult);
            when(emailVerificationService.resetPasswordAfterRecovery(player, "newPassword1")).thenReturn(true);

            command.resetPassword(player, "123456", "newPassword1", "newPassword1");

            verify(emailVerificationService).verifyRecoveryCode(player, "123456");
            verify(emailVerificationService).resetPasswordAfterRecovery(player, "newPassword1");
            verify(loginService).completeLogin(player);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("recover_success");
        }

        @Test
        @DisplayName("Should send error when reset fails")
        void resetFails() {
            when(loginService.isPasswordValid("newPassword1")).thenReturn(true);
            RecoverVerifyResult verifyResult = mockRecoverVerifyResult(true, null);
            when(emailVerificationService.verifyRecoveryCode(player, "123456")).thenReturn(verifyResult);
            when(emailVerificationService.resetPasswordAfterRecovery(player, "newPassword1")).thenReturn(false);

            command.resetPassword(player, "123456", "newPassword1", "newPassword1");

            verify(loginService, never()).completeLogin(any());
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("recover_failed");
        }

        @Test
        @DisplayName("Should send error when verification code is wrong")
        void wrongVerificationCode() {
            when(loginService.isPasswordValid("newPassword1")).thenReturn(true);
            RecoverVerifyResult verifyResult = mockRecoverVerifyResult(false, "email_code_invalid");
            when(emailVerificationService.verifyRecoveryCode(player, "000000")).thenReturn(verifyResult);

            command.resetPassword(player, "000000", "newPassword1", "newPassword1");

            verify(emailVerificationService, never()).resetPasswordAfterRecovery(any(), anyString());
            verify(loginService, never()).completeLogin(any());
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_code_invalid");
        }

        @Test
        @DisplayName("Should send error when code is expired")
        void expiredCode() {
            when(loginService.isPasswordValid("newPassword1")).thenReturn(true);
            RecoverVerifyResult verifyResult = mockRecoverVerifyResult(false, "email_code_expired");
            when(emailVerificationService.verifyRecoveryCode(player, "123456")).thenReturn(verifyResult);

            command.resetPassword(player, "123456", "newPassword1", "newPassword1");

            verify(emailVerificationService, never()).resetPasswordAfterRecovery(any(), anyString());
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_code_expired");
        }

        @Test
        @DisplayName("Should send error when no pending recovery")
        void noPendingRecovery() {
            when(loginService.isPasswordValid("newPassword1")).thenReturn(true);
            RecoverVerifyResult verifyResult = mockRecoverVerifyResult(false, "email_no_pending");
            when(emailVerificationService.verifyRecoveryCode(player, "123456")).thenReturn(verifyResult);

            command.resetPassword(player, "123456", "newPassword1", "newPassword1");

            verify(emailVerificationService, never()).resetPasswordAfterRecovery(any(), anyString());
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_no_pending");
        }

        @Test
        @DisplayName("Should send error when max attempts exceeded")
        void maxAttemptsExceeded() {
            when(loginService.isPasswordValid("newPassword1")).thenReturn(true);
            RecoverVerifyResult verifyResult = mockRecoverVerifyResult(false, "email_code_max_attempts");
            when(emailVerificationService.verifyRecoveryCode(player, "123456")).thenReturn(verifyResult);

            command.resetPassword(player, "123456", "newPassword1", "newPassword1");

            verify(emailVerificationService, never()).resetPasswordAfterRecovery(any(), anyString());
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("email_code_max_attempts");
        }
    }

    @Nested
    @DisplayName("Password validation")
    class PasswordValidation {

        @BeforeEach
        void setNotLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
        }

        @Test
        @DisplayName("Should send error when passwords do not match")
        void passwordMismatch() {
            command.resetPassword(player, "123456", "password1", "password2");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("password_mismatch");
            verify(emailVerificationService, never()).verifyRecoveryCode(any(), anyString());
        }

        @Test
        @DisplayName("Should send error when password is too short")
        void passwordTooShort() {
            when(loginService.isPasswordValid("abc")).thenReturn(false);
            when(loginService.getPasswordValidationError("abc")).thenReturn("&c密码太短！至少需要 6 个字符。");

            command.resetPassword(player, "123456", "abc", "abc");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("6");
            verify(emailVerificationService, never()).verifyRecoveryCode(any(), anyString());
        }

        @Test
        @DisplayName("Should send error when password is too long")
        void passwordTooLong() {
            String longPass = new String(new char[50]).replace('\0', 'a');
            when(loginService.isPasswordValid(longPass)).thenReturn(false);
            when(loginService.getPasswordValidationError(longPass)).thenReturn("&c密码太长！最多 32 个字符。");

            command.resetPassword(player, "123456", longPass, longPass);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("32");
            verify(emailVerificationService, never()).verifyRecoveryCode(any(), anyString());
        }

        @Test
        @DisplayName("Should send error for GUI mode invalid password")
        void guiModeInvalidPassword() {
            when(loginService.isPasswordValid("abcd")).thenReturn(false);
            when(loginService.getPasswordValidationError("abcd")).thenReturn("&c密码必须是 4 位数字！");

            command.resetPassword(player, "123456", "abcd", "abcd");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("4");
            verify(emailVerificationService, never()).verifyRecoveryCode(any(), anyString());
        }
    }

    @Nested
    @DisplayName("Help command")
    class HelpCmd {

        @Test
        @DisplayName("Should show help messages")
        void showHelp() {
            command.handleHelp(player);

            verify(player, times(2)).sendMessage(anyString());
        }
    }
}
