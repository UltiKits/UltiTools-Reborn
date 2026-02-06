package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RegisterCommand Tests")
class RegisterCommandTest {

    private LoginService loginService;
    private RegisterCommand command;
    private Player player;
    private UUID playerUuid;
    private LoginConfig config;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();
        loginService = mock(LoginService.class);
        command = new RegisterCommand(loginService);

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
    @DisplayName("register command")
    class RegisterCmd {

        @Test
        @DisplayName("Should deny when already logged in")
        void alreadyLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            command.register(player, "password", "password");

            verify(player).sendMessage(contains("已经登录"));
            verify(loginService, never()).register(any(), anyString());
        }

        @Test
        @DisplayName("Should deny when already registered")
        void alreadyRegistered() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(true);

            command.register(player, "password", "password");

            verify(player).sendMessage(contains("已经注册"));
            verify(loginService, never()).register(any(), anyString());
        }

        @Test
        @DisplayName("Should deny when password invalid")
        void invalidPassword() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(false);
            when(loginService.isPasswordValid("123")).thenReturn(false);
            when(loginService.getPasswordValidationError("123")).thenReturn("密码太短");

            command.register(player, "123", "123");

            verify(player).sendMessage(contains("密码太短"));
            verify(loginService, never()).register(any(), anyString());
        }

        @Test
        @DisplayName("Should deny when passwords don't match")
        void passwordMismatch() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(false);
            when(loginService.isPasswordValid(anyString())).thenReturn(true);

            command.register(player, "password1", "password2");

            verify(player).sendMessage(contains("不一致"));
            verify(loginService, never()).register(any(), anyString());
        }

        @Test
        @DisplayName("Should register when all checks pass")
        void successfulRegister() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(false);
            when(loginService.isPasswordValid("password123")).thenReturn(true);
            when(loginService.register(player, "password123")).thenReturn(true);

            command.register(player, "password123", "password123");

            verify(loginService).register(player, "password123");
            verify(player).sendMessage(contains("注册成功"));
        }

        @Test
        @DisplayName("Should not send success when register returns false")
        void registerReturnsFalse() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(false);
            when(loginService.isPasswordValid("password123")).thenReturn(true);
            when(loginService.register(player, "password123")).thenReturn(false);

            command.register(player, "password123", "password123");

            verify(loginService).register(player, "password123");
            // Should NOT send the success message since register returned false
            verify(player, never()).sendMessage(contains("注册成功"));
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

            verify(player).sendMessage(contains("/register"));
            verify(player).sendMessage(contains("6-32"));
        }

        @Test
        @DisplayName("Should show GUI mode help")
        void guiModeHelp() {
            when(config.isGuiModeEnabled()).thenReturn(true);
            when(config.getGuiPasswordLength()).thenReturn(4);

            command.help(player);

            verify(player).sendMessage(contains("/register"));
            verify(player).sendMessage(contains("4"));
        }
    }
}
