package com.ultikits.ultitools.commands;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.utils.CloudAuthManager;

/**
 * {@code /ulticloud logout} 的拆线语义.
 *
 * <p>核心命题只有一条：<b>凭证的有效性不能作为生命周期拆解的门禁。</b>
 * access token 过期（例如主动刷新反复失败）之后，WebSocket、监控任务、日志 handler
 * 与玩家监听器可能全都还在跑，而 logout 是操作员唯一的停止手段——那正是最需要它
 * 生效的时刻，却恰恰是旧实现拒绝执行的时刻。
 *
 * <p>Plan 16-09 (D-17/D-18) folded the "tear down, then read, then clear" sequence this class used
 * to assert step-by-step (via {@code PluginInitiationUtils.disableCloud()} +
 * {@code CloudAuthManager.getCurrentToken()} + {@code CloudAuthManager.clearToken()}) into a single
 * {@code CloudAuthManager.logout()} entry point — only three public statics remain on that class,
 * and none of them accepts or returns a {@code TokenEntity} (D-18). This class now tests only the
 * command layer: which message branch {@code CloudLoginCommand.logout(CommandSender)} prints for
 * each of {@code logout()}'s two outcomes, and that an exception from {@code logout()} still
 * produces the failure message rather than propagating. The "tear down before reading, read after
 * teardown, tear down even when there is nothing to clear" semantics this class used to assert
 * directly now live inside {@code CloudAuthManager.logout()} itself and are covered there (see
 * {@code CloudAuthManagerTest}'s {@code LogoutTests} nested class).
 */
@DisplayName("ulticloud logout 命令层：按 CloudAuthManager.logout() 的返回值分支")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudLogoutCommandTest {

    @Nested
    @DisplayName("按 logout() 返回值选择消息分支")
    class MessageBranchByLogoutResult {

        @Test
        @DisplayName("logout() 返回 true（原有凭证被清除）时打印成功消息")
        void logoutReturningTruePrintsSuccessMessages() throws Exception {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::logout).thenReturn(true);

                new CloudLoginCommand().logout(sender);

                auth.verify(CloudAuthManager::logout, times(1));
                verify(sender).sendMessage(ChatColor.GREEN
                    + "Successfully logged out of UltiCloud. Cloud features are now disabled.");
                verify(sender).sendMessage(ChatColor.GRAY + "Use /ulticloud login to re-authenticate.");
            }
        }

        @Test
        @DisplayName("logout() 返回 false（本来就没有凭证）时打印“未登录”消息，拆线仍然发生")
        void logoutReturningFalsePrintsNotLoggedInMessage() throws Exception {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::logout).thenReturn(false);

                new CloudLoginCommand().logout(sender);

                // 拆线本身发生在 CloudAuthManager.logout() 内部（本类已不再直接调用
                // PluginInitiationUtils.disableCloud()）；这里只断言命令层收到 false
                // 之后走的是「未登录」分支，而不是把它当异常处理。
                auth.verify(CloudAuthManager::logout, times(1));
                verify(sender).sendMessage(ChatColor.YELLOW + "Not currently logged in to UltiCloud.");
                verify(sender).sendMessage(ChatColor.GRAY + "Cloud features have been stopped regardless.");
            }
        }

        @Test
        @DisplayName("logout() 抛出异常时打印失败消息，而不是让异常向上传播")
        void logoutThrowingPrintsFailureMessage() throws Exception {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::logout).thenThrow(new java.io.IOException("disk full"));

                new CloudLoginCommand().logout(sender);

                verify(sender).sendMessage(ChatColor.RED + "Failed to logout: disk full");
            }
        }
    }
}
