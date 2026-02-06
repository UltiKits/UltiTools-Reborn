package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.entity.AccountData;
import com.ultikits.plugins.login.service.LoginService;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("LoginAdminCommand Tests")
class LoginAdminCommandTest {

    private LoginService loginService;
    private LoginAdminCommand command;
    private CommandSender sender;
    private LoginConfig config;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();
        loginService = mock(LoginService.class);
        command = new LoginAdminCommand(loginService);

        sender = mock(CommandSender.class);

        config = UltiLoginTestHelper.createDefaultConfig();
        when(loginService.getConfig()).thenReturn(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiLoginTestHelper.tearDown();
    }

    @Nested
    @DisplayName("resetPassword (random)")
    class ResetPasswordRandom {

        @Test
        @DisplayName("Should show error when account not found")
        void accountNotFound() {
            when(loginService.getAccountByName("Unknown")).thenReturn(null);

            command.resetPassword(sender, "Unknown");

            verify(sender).sendMessage(contains("尚未注册"));
            verify(loginService, never()).resetPassword(any(UUID.class));
        }

        @Test
        @DisplayName("Should reset password and show new password")
        void successfulReset() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);
            when(loginService.resetPassword(uuid)).thenReturn("newPass123");

            command.resetPassword(sender, "TestPlayer");

            verify(loginService).resetPassword(uuid);
            verify(sender).sendMessage(contains("newPass123"));
        }

        @Test
        @DisplayName("Should show failure when reset returns null")
        void resetFails() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);
            when(loginService.resetPassword(uuid)).thenReturn(null);

            command.resetPassword(sender, "TestPlayer");

            verify(sender).sendMessage(contains("失败"));
        }
    }

    @Nested
    @DisplayName("resetPassword (with password)")
    class ResetPasswordWithValue {

        @Test
        @DisplayName("Should show error when account not found")
        void accountNotFound() {
            when(loginService.getAccountByName("Unknown")).thenReturn(null);

            command.resetPasswordWithValue(sender, "Unknown", "newPass");

            verify(sender).sendMessage(contains("尚未注册"));
        }

        @Test
        @DisplayName("Should show error when password invalid")
        void invalidPassword() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);
            when(loginService.isPasswordValid("123")).thenReturn(false);
            when(loginService.getPasswordValidationError("123")).thenReturn("密码太短");

            command.resetPasswordWithValue(sender, "TestPlayer", "123");

            verify(sender).sendMessage(contains("密码太短"));
            verify(loginService, never()).resetPassword(any(UUID.class), anyString());
        }

        @Test
        @DisplayName("Should reset with specific password")
        void successfulReset() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);
            when(loginService.isPasswordValid("newPass123")).thenReturn(true);
            when(loginService.resetPassword(uuid, "newPass123")).thenReturn(true);

            command.resetPasswordWithValue(sender, "TestPlayer", "newPass123");

            verify(loginService).resetPassword(uuid, "newPass123");
            verify(sender).sendMessage(contains("newPass123"));
        }

        @Test
        @DisplayName("Should show failure when reset with specific password fails")
        void resetFails() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);
            when(loginService.isPasswordValid("newPass123")).thenReturn(true);
            when(loginService.resetPassword(uuid, "newPass123")).thenReturn(false);

            command.resetPasswordWithValue(sender, "TestPlayer", "newPass123");

            verify(sender).sendMessage(contains("失败"));
        }
    }

    @Nested
    @DisplayName("forceLogin")
    class ForceLogin {

        @Test
        @DisplayName("Should show error when player not online")
        void playerNotOnline() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer("Offline")).thenReturn(null);

                command.forceLogin(sender, "Offline");

                verify(sender).sendMessage(contains("找不到玩家"));
            }
        }

        @Test
        @DisplayName("Should show error when player not registered")
        void notRegistered() {
            UUID uuid = UUID.randomUUID();
            Player player = UltiLoginTestHelper.createMockPlayer("TestPlayer", uuid);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer("TestPlayer")).thenReturn(player);
                when(loginService.isRegistered(uuid)).thenReturn(false);

                command.forceLogin(sender, "TestPlayer");

                verify(sender).sendMessage(contains("尚未注册"));
            }
        }

        @Test
        @DisplayName("Should show message when already logged in")
        void alreadyLoggedIn() {
            UUID uuid = UUID.randomUUID();
            Player player = UltiLoginTestHelper.createMockPlayer("TestPlayer", uuid);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer("TestPlayer")).thenReturn(player);
                when(loginService.isRegistered(uuid)).thenReturn(true);
                when(loginService.isLoggedIn(uuid)).thenReturn(true);

                command.forceLogin(sender, "TestPlayer");

                verify(sender).sendMessage(contains("已经登录"));
            }
        }

        @Test
        @DisplayName("Should force login when checks pass")
        void successfulForceLogin() {
            UUID uuid = UUID.randomUUID();
            Player player = UltiLoginTestHelper.createMockPlayer("TestPlayer", uuid);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer("TestPlayer")).thenReturn(player);
                when(loginService.isRegistered(uuid)).thenReturn(true);
                when(loginService.isLoggedIn(uuid)).thenReturn(false);
                when(loginService.forceLogin(player)).thenReturn(true);

                command.forceLogin(sender, "TestPlayer");

                verify(loginService).forceLogin(player);
                verify(sender).sendMessage(contains("强制登录"));
                verify(player).sendMessage(anyString());
            }
        }

        @Test
        @DisplayName("Should show failure when forceLogin returns false")
        void forceLoginFails() {
            UUID uuid = UUID.randomUUID();
            Player player = UltiLoginTestHelper.createMockPlayer("TestPlayer", uuid);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer("TestPlayer")).thenReturn(player);
                when(loginService.isRegistered(uuid)).thenReturn(true);
                when(loginService.isLoggedIn(uuid)).thenReturn(false);
                when(loginService.forceLogin(player)).thenReturn(false);

                command.forceLogin(sender, "TestPlayer");

                verify(sender).sendMessage(contains("失败"));
            }
        }
    }

    @Nested
    @DisplayName("unregister")
    class Unregister {

        @Test
        @DisplayName("Should show error when account not found")
        void accountNotFound() {
            when(loginService.getAccountByName("Unknown")).thenReturn(null);

            command.unregister(sender, "Unknown");

            verify(sender).sendMessage(contains("尚未注册"));
        }

        @Test
        @DisplayName("Should unregister when account exists")
        void successfulUnregister() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);
            when(loginService.unregister(uuid)).thenReturn(true);

            command.unregister(sender, "TestPlayer");

            verify(loginService).unregister(uuid);
            verify(sender).sendMessage(contains("已删除"));
        }

        @Test
        @DisplayName("Should show failure when unregister fails")
        void unregisterFails() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);
            when(loginService.unregister(uuid)).thenReturn(false);

            command.unregister(sender, "TestPlayer");

            verify(sender).sendMessage(contains("失败"));
        }
    }

    @Nested
    @DisplayName("showInfo")
    class ShowInfo {

        @Test
        @DisplayName("Should show error when account not found")
        void accountNotFound() {
            when(loginService.getAccountByName("Unknown")).thenReturn(null);

            command.showInfo(sender, "Unknown");

            verify(sender).sendMessage(contains("尚未注册"));
        }

        @Test
        @DisplayName("Should display account info")
        void displayInfo() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            account.setEmail("test@example.com");
            account.setEmailVerified(true);
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);

            command.showInfo(sender, "TestPlayer");

            verify(sender, atLeastOnce()).sendMessage(anyString());
            verify(sender).sendMessage(contains("TestPlayer"));
            verify(sender).sendMessage(contains(uuid.toString()));
            verify(sender).sendMessage(contains("test@example.com"));
        }

        @Test
        @DisplayName("Should show unbound when email is null")
        void emailUnbound() {
            UUID uuid = UUID.randomUUID();
            AccountData account = UltiLoginTestHelper.createSampleAccount(uuid, "TestPlayer", "hash", "salt");
            account.setEmail(null);
            when(loginService.getAccountByName("TestPlayer")).thenReturn(account);

            command.showInfo(sender, "TestPlayer");

            verify(sender).sendMessage(contains("未绑定"));
        }
    }

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("Should show all admin commands")
        void showHelp() {
            command.help(sender);

            verify(sender).sendMessage(contains("reset"));
            verify(sender).sendMessage(contains("forcelogin"));
            verify(sender).sendMessage(contains("unregister"));
            verify(sender).sendMessage(contains("info"));
        }
    }
}
