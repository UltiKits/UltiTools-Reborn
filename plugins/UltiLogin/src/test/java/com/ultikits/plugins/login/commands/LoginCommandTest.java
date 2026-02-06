package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.UltiLoginTestHelper;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("LoginCommand Tests")
class LoginCommandTest {

    private LoginService loginService;
    private LoginCommand command;
    private Player player;
    private UUID playerUuid;
    private LoginConfig config;

    @BeforeEach
    void setUp() throws Exception {
        UltiLoginTestHelper.setUp();
        loginService = mock(LoginService.class);
        command = new LoginCommand(loginService);

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
    @DisplayName("login command")
    class LoginCmd {

        @Test
        @DisplayName("Should deny when already logged in")
        void alreadyLoggedIn() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(true);

            command.login(player, "password");

            verify(player).sendMessage(contains("已经登录"));
            verify(loginService, never()).login(any(), anyString());
        }

        @Test
        @DisplayName("Should deny when not registered")
        void notRegistered() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(false);

            command.login(player, "password");

            verify(player).sendMessage(contains("未注册"));
            verify(loginService, never()).login(any(), anyString());
        }

        @Test
        @DisplayName("Should deny when locked")
        void locked() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(true);
            when(loginService.isLocked(player)).thenReturn(true);
            when(loginService.getRemainingLockoutTime(player)).thenReturn(600L);

            command.login(player, "password");

            verify(player).sendMessage(contains("600"));
            verify(loginService, never()).login(any(), anyString());
        }

        @Test
        @DisplayName("Should attempt login when checks pass")
        void attemptLogin() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(true);
            when(loginService.isLocked(player)).thenReturn(false);
            when(loginService.login(player, "password"))
                    .thenReturn(new LoginService.LoginResult(true, "登录成功"));

            command.login(player, "password");

            verify(loginService).login(player, "password");
            verify(player).sendMessage(contains("登录成功"));
        }

        @Test
        @DisplayName("Should show error when login fails")
        void loginFails() {
            when(loginService.isLoggedIn(playerUuid)).thenReturn(false);
            when(loginService.isRegistered(playerUuid)).thenReturn(true);
            when(loginService.isLocked(player)).thenReturn(false);
            when(loginService.login(player, "wrong"))
                    .thenReturn(new LoginService.LoginResult(false, "密码错误"));

            command.login(player, "wrong");

            verify(player).sendMessage(contains("密码错误"));
        }
    }

    @Nested
    @DisplayName("help command")
    class HelpCmd {

        @Test
        @DisplayName("Should show usage")
        void showUsage() {
            command.help(player);

            verify(player).sendMessage(contains("/login"));
        }
    }
}
