package com.ultikits.ultitools.commands;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.utils.CloudAuthManager;

/**
 * {@code /ulticloud login} and {@code /ulticloud status}'s rendered message strings, byte-for-byte
 * (WR-08, 16-REVIEW-cloud.md).
 * <p>
 * 16-09-SUMMARY.md's own {@code coverage} block disclosed this gap directly: only {@code logout()}'s
 * three branches were asserted by a rewritten test ({@code CloudLogoutCommandTest}); {@code login()}'s
 * five branches (already-logged-in, rate-limited, requesting, success/link-block, error) and
 * {@code status()}'s three branches were verified only by direct visual comparison against
 * {@code origin/alpha} during authoring -- confirmed byte-identical at review time, but with no
 * regression test that would catch a FUTURE accidental change to any of these eight message strings,
 * several of which are pinned verbatim by {@code UAT-CHECKLIST.md} rows.
 * <p>
 * Mirrors {@code CloudLogoutCommandTest}'s shape exactly: {@code CloudAuthManager} is mocked
 * statically, each of its callback-shaped or return-shaped outcomes is driven directly, and every
 * resulting {@code sendMessage} call is asserted with its exact {@link ChatColor} prefix and text.
 */
@DisplayName("ulticloud login / status 命令层：渲染字符串逐字断言（WR-08）")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudLoginCommandTest {

    @Nested
    @DisplayName("login 的五个分支")
    class LoginBranches {

        @Test
        @DisplayName("已登录：只打印一条黄色提示")
        void alreadyLoggedIn() {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(() -> CloudAuthManager.login(any(), any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            Runnable onAlreadyLoggedIn = invocation.getArgument(0);
                            onAlreadyLoggedIn.run();
                            return null;
                        });

                new CloudLoginCommand().login(sender);

                verify(sender).sendMessage(ChatColor.YELLOW
                        + "Already logged in to UltiCloud. Use /ulticloud logout first to re-login.");
            }
        }

        @Test
        @DisplayName("限流：打印一条红色提示，携带剩余秒数")
        void rateLimited() {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(() -> CloudAuthManager.login(any(), any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            Consumer<Long> onRateLimited = invocation.getArgument(1);
                            onRateLimited.accept(42L);
                            return null;
                        });

                new CloudLoginCommand().login(sender);

                verify(sender).sendMessage(ChatColor.RED + "Please wait 42 seconds before trying again.");
            }
        }

        @Test
        @DisplayName("请求中：打印一条青色的「正在请求」提示")
        void requesting() {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(() -> CloudAuthManager.login(any(), any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            Runnable onRequesting = invocation.getArgument(2);
                            onRequesting.run();
                            return null;
                        });

                new CloudLoginCommand().login(sender);

                verify(sender).sendMessage(ChatColor.AQUA + "Requesting login link from UltiCloud...");
            }
        }

        @Test
        @DisplayName("请求成功（魔法链接区块）：六行边框+链接+过期提示+等待提示，逐字校验")
        void successPrintsTheFullMagicLinkBlock() {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(() -> CloudAuthManager.login(any(), any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            Consumer<String> onSuccess = invocation.getArgument(3);
                            onSuccess.accept("https://ultikits.example/auth/synthetic-test-token");
                            return null;
                        });

                new CloudLoginCommand().login(sender);

                // The top and bottom border are the same string, printed twice.
                verify(sender, org.mockito.Mockito.times(2))
                        .sendMessage(ChatColor.GREEN + "========================================");
                verify(sender).sendMessage(ChatColor.GREEN + " Open this URL in your browser to login:");
                verify(sender).sendMessage(ChatColor.AQUA + " https://ultikits.example/auth/synthetic-test-token");
                verify(sender).sendMessage(ChatColor.GRAY + "The link will expire in 5 minutes.");
                verify(sender).sendMessage(ChatColor.GRAY + "Waiting for authentication...");
            }
        }

        @Test
        @DisplayName("请求失败：打印一条红色的错误提示，携带错误信息")
        void error() {
            CommandSender sender = mock(CommandSender.class);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(() -> CloudAuthManager.login(any(), any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            Consumer<String> onError = invocation.getArgument(4);
                            onError.accept("API URL not configured");
                            return null;
                        });

                new CloudLoginCommand().login(sender);

                verify(sender).sendMessage(ChatColor.RED + "Failed to get login link: API URL not configured");
            }
        }
    }

    @Nested
    @DisplayName("status 的三个分支")
    class StatusBranches {

        @Test
        @DisplayName("已连接且带过期时间：绿色用户名 + 灰色过期时间，两行都逐字校验")
        void connectedWithExpiry() {
            CommandSender sender = mock(CommandSender.class);
            Date expiration = new Date(0L); // fixed, deterministic instant -- no wall-clock dependency
            CloudAuthManager.CloudStatus status = buildStatus(true, "synthetic-user", expiration);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::status).thenReturn(status);

                new CloudLoginCommand().status(sender);

                verify(sender).sendMessage(ChatColor.GREEN + "UltiCloud: Connected as synthetic-user");
                verify(sender).sendMessage(ChatColor.GRAY + "Token expires: " + expiration.toString());
            }
        }

        @Test
        @DisplayName("已连接但不带过期时间：只打印用户名那一行，过期时间那一行不打印")
        void connectedWithoutExpiry() {
            CommandSender sender = mock(CommandSender.class);
            CloudAuthManager.CloudStatus status = buildStatus(true, "synthetic-user", null);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::status).thenReturn(status);

                new CloudLoginCommand().status(sender);

                verify(sender).sendMessage(ChatColor.GREEN + "UltiCloud: Connected as synthetic-user");
                verify(sender, org.mockito.Mockito.never())
                        .sendMessage(org.mockito.ArgumentMatchers.startsWith(ChatColor.GRAY + "Token expires:"));
            }
        }

        @Test
        @DisplayName("未连接：黄色「未连接」+灰色「去登录」提示，两行都逐字校验")
        void notConnected() {
            CommandSender sender = mock(CommandSender.class);
            CloudAuthManager.CloudStatus status = buildStatus(false, null, null);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::status).thenReturn(status);

                new CloudLoginCommand().status(sender);

                verify(sender).sendMessage(ChatColor.YELLOW + "UltiCloud: Not connected");
                verify(sender).sendMessage(ChatColor.GRAY + "Use /ulticloud login to authenticate.");
            }
        }

        /**
         * {@link CloudAuthManager.CloudStatus}'s constructor is private -- this reaches it via
         * reflection rather than adding a package-visible test seam to a class this deliberately
         * narrow (D-18's whole point is that this type carries no more surface than
         * {@code /ulticloud status} itself needs).
         */
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        private CloudAuthManager.CloudStatus buildStatus(boolean connected, String userName, Date expirationDate) {
            try {
                java.lang.reflect.Constructor<CloudAuthManager.CloudStatus> ctor =
                        CloudAuthManager.CloudStatus.class.getDeclaredConstructor(
                                boolean.class, String.class, Date.class);
                ctor.setAccessible(true);
                return ctor.newInstance(connected, userName, expirationDate);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to construct CloudStatus via reflection", e);
            }
        }
    }
}
