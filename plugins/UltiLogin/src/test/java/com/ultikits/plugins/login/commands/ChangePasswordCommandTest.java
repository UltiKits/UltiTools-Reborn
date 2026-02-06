package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ChangePasswordCommand Tests")
class ChangePasswordCommandTest {

    private LoginService loginService;
    private ChangePasswordCommand command;
    private Player player;
    private UUID playerUuid;
    private LoginConfig config;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();
        loginService = mock(LoginService.class);
        command = new ChangePasswordCommand(loginService);

        playerUuid = UUID.randomUUID();
        player = UltiLoginTestHelper.createMockPlayer("TestPlayer", playerUuid);

        config = UltiLoginTestHelper.createDefaultConfig();
        when(loginService.getConfig()).thenReturn(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiLoginTestHelper.tearDown();
    }

    @Nested
    @DisplayName("changePassword command")
    class ChangePasswordCmd {

        @Test
        @DisplayName("Should deny when not logged in")
        void notLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);

            command.changePassword(player, "old", "new", "new");

            verify(player).sendMessage(contains("请先登录"));
            verify(loginService, never()).changePassword(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should deny when new password invalid")
        void invalidNewPassword() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
            when(loginService.isPasswordValid("123")).thenReturn(false);
            when(loginService.getPasswordValidationError("123")).thenReturn("密码太短");

            command.changePassword(player, "oldPass", "123", "123");

            verify(player).sendMessage(contains("密码太短"));
            verify(loginService, never()).changePassword(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should deny when new passwords don't match")
        void newPasswordMismatch() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
            when(loginService.isPasswordValid(anyString())).thenReturn(true);

            command.changePassword(player, "old", "new1", "new2");

            verify(player).sendMessage(contains("不一致"));
            verify(loginService, never()).changePassword(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should fail when old password wrong")
        void wrongOldPassword() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
            when(loginService.isPasswordValid(anyString())).thenReturn(true);
            when(loginService.changePassword(playerUuid, "wrongOld", "newPass")).thenReturn(false);

            command.changePassword(player, "wrongOld", "newPass", "newPass");

            verify(player).sendMessage(contains("原密码错误"));
        }

        @Test
        @DisplayName("Should succeed when all checks pass")
        void successfulChange() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);
            when(loginService.isPasswordValid("newPass123")).thenReturn(true);
            when(loginService.changePassword(playerUuid, "oldPass", "newPass123")).thenReturn(true);

            command.changePassword(player, "oldPass", "newPass123", "newPass123");

            verify(loginService).changePassword(playerUuid, "oldPass", "newPass123");
            verify(player).sendMessage(contains("修改成功"));
        }
    }

    @Nested
    @DisplayName("help command")
    class HelpCmd {

        @Test
        @DisplayName("Should show command mode help")
        void commandModeHelp() {
            when(config.isGuiModeEnabled()).thenReturn(false);
            when(config.getMinPasswordLength()).thenReturn(6);
            when(config.getMaxPasswordLength()).thenReturn(32);

            command.help(player);

            verify(player).sendMessage(contains("/changepassword"));
            verify(player).sendMessage(contains("6-32"));
        }

        @Test
        @DisplayName("Should show GUI mode help")
        void guiModeHelp() {
            when(config.isGuiModeEnabled()).thenReturn(true);
            when(config.getGuiPasswordLength()).thenReturn(4);

            command.help(player);

            verify(player).sendMessage(contains("/changepassword"));
            verify(player).sendMessage(contains("4"));
        }
    }
}
