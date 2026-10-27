package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.entities.TokenEntity;

/**
 * CloudAuthManager 测试类
 * Tests for the UltiCloud authentication facade and the {@link CloudSession} it delegates to.
 * <p>
 * As of 6.3.0 plan 16-08 (D-16/D-17/D-18) {@code CloudAuthManager} owns no state of its own --
 * every field this class used to reflect into directly (the in-memory token, the poll/refresh
 * executors and their scheduled tasks, the timing constants) now lives on {@link CloudSession}.
 * Plan 16-09 (D-17/D-18) went further and removed every fine-grained public static this class used
 * to expose: only {@link CloudAuthManager#login}, {@link CloudAuthManager#logout} and
 * {@link CloudAuthManager#status} remain public, and none of the three accepts or returns a
 * {@link TokenEntity} or a generation. The former {@code MethodSignatureTests} nested class, which
 * asserted the now-removed statics' signatures, is gone with them --
 * {@code CredentialStaticSurfaceInvariantTest} (buildtools) is the structural test that now proves
 * the surface stays this narrow. Every other nested class below reaches {@link CloudSession}'s
 * instance state directly (via {@link CloudSession#current()}), since that is where it actually
 * lives; {@link LoginTests}, {@link LogoutTests} and {@link StatusTests} are new, exercising the
 * three surviving entry points directly.
 * <p>
 * Plan 22-24 (CLOUD-02) deleted the 6.3.0 user-token state of {@link CloudSession} (the token, its
 * store, its refresh scheduler and the OAuth constants) together with the 29 cases that pinned it:
 * the token, refresh-executor and refresh-task field pins, the four token-refresh constants,
 * {@code hasValidToken} (6), {@code getToken} (3), the refresh-scheduler lifecycle (5), the combined
 * scheduler state (2) and the token state transitions (6). The cases that describe 6.3.1 behaviour of the public
 * members are kept; the logout cases that pinned the token-era disk clear now pin the same properties
 * on the protocol v2 credential (revoke, then compare-and-delete by credential id).
 */
@DisplayName("CloudAuthManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudAuthManagerTest {

    @TempDir
    File dataFolder;

    /** The plugin logger every test sees; the device-login polling tests verify its lines. */
    private Logger mockLogger;

    // -------------------------------------------------------------------------
    // Helper utilities
    // -------------------------------------------------------------------------

    /**
     * Read the value of a private instance field on {@link CloudSession#current()} via reflection.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Object getSessionField(String name) throws Exception {
        Field field = CloudSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(CloudSession.current());
    }

    /**
     * Set the value of a private instance field on {@link CloudSession#current()} via reflection.
     */
    private static void setSessionField(String name, Object value) throws Exception {
        Field field = CloudSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(CloudSession.current(), value);
    }

    /**
     * Read the value of a private static {@code long} field (a constant) on {@link CloudSession}.
     */
    private static long getSessionStaticLong(String name) throws Exception {
        Field field = CloudSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(null);
    }

    /**
     * Build a TokenEntity where the exp claim places expiry at the given offset
     * from the current time (in seconds).
     *
     * @param offsetSeconds positive = future expiry, negative = already expired
     */
    private static TokenEntity buildTokenWithExp(long offsetSeconds) {
        long expEpochSeconds = (System.currentTimeMillis() / 1000L) + offsetSeconds;
        TokenEntity token = new TokenEntity();
        token.setAccess_token("dummy.access.token");
        token.setRefresh_token("dummy-refresh-token");
        token.setExp(expEpochSeconds);
        return token;
    }

    /**
     * Build a TokenEntity with no exp field set (exp remains null).
     */
    private static TokenEntity buildTokenWithNullExp() {
        TokenEntity token = new TokenEntity();
        token.setAccess_token("dummy.access.token");
        token.setRefresh_token("dummy-refresh-token");
        // exp intentionally left null
        return token;
    }

    // -------------------------------------------------------------------------
    // Setup / teardown — reset all static state before and after each test
    // -------------------------------------------------------------------------

    @BeforeEach
    void resetStaticState() {
        // CloudSession.current is a JVM-wide static (surefire runs this module with no forkCount,
        // issue #250); a fresh, un-invalidated session with nothing scheduled replaces whatever a
        // previous test class left behind, including any leaked poll/refresh executors.
        CloudSession.resetForTesting();
        // login()'s not-already-logged-in path consults ApiRateLimiter; without resetting it here,
        // a rate-limit timestamp recorded by one test would leak into the next.
        ApiRateLimiter.resetAll();
        // login()'s magic-link request path reads this lazily; forcing it to a deliberately empty
        // value makes "API URL not configured" a deterministic, network-free branch instead of
        // depending on env.yml or an uninitialized static.
        HttpRequestUtils.setBaseUrlForTesting("");
        // LogoutTests' real (unmocked) logout() calls write through ServerCredentialStore/CredentialStore
        // -- pin both write locations to the temp dir so nothing
        // lands under this repository's real data folder or ~/.ultikits (mirrors
        // CredentialGenerationTest's setup).
        CredentialStore.setTargetPathForTesting(dataFolder.toPath().resolve("credentials.json"));
        CredentialStore.setOldLocationForTesting(dataFolder.toPath().resolve("pre-migration-data.json"));
        // LogoutTests' one un-mocked disableCloud() call reaches UltiTools.getInstance().getLogger()
        // from several teardown steps; every manager getter besides that defaults to Mockito's null,
        // which doDisableCloud()'s own null-checks and catch-and-log-at-FINE already tolerate.
        mockLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(mockLogger));
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the UltiTools singleton mocked in setUp
    void cleanUpStaticState() throws Exception {
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        HttpRequestUtils.resetBaseUrl();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        Field instanceField = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    // =========================================================================
    // 1. Class Structure Tests — 类结构测试
    // =========================================================================

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("CloudAuthManager 应该是 public 类 / class should be public")
        void classShouldBePublic() {
            assertThat(java.lang.reflect.Modifier.isPublic(CloudAuthManager.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("CloudAuthManager 不应该是抽象类 / class should not be abstract")
        void classShouldNotBeAbstract() {
            assertThat(java.lang.reflect.Modifier.isAbstract(CloudAuthManager.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("CloudSession 应该拥有 pollExecutor 实例字段 / instance field pollExecutor should exist")
        void pollExecutorFieldShouldExist() throws Exception {
            Field field = CloudSession.class.getDeclaredField("pollExecutor");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isFalse();
            assertThat(ScheduledExecutorService.class.isAssignableFrom(field.getType())).isTrue();
        }

        @Test
        @DisplayName("CloudSession 应该拥有 pollTask 实例字段 / instance field pollTask should exist")
        void pollTaskFieldShouldExist() throws Exception {
            Field field = CloudSession.class.getDeclaredField("pollTask");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("plan 22-24: CloudSession holds no 6.3.0 user-token state any more")
        void sessionHoldsNoUserTokenState() {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Field field : CloudSession.class.getDeclaredFields()) {
                names.add(field.getName());
                assertThat(field.getType()).as(field.getName()).isNotEqualTo(TokenEntity.class);
            }
            assertThat(names).doesNotContain("token", "predecessorToken", "tokenStore", "refreshExecutor",
                    "refreshTask", "TOKEN_REFRESH_CHECK_INTERVAL_MS", "TOKEN_REFRESH_THRESHOLD_SECONDS",
                    "OAUTH2_BASIC_AUTH");
            assertThat(names).as("control: the reflection sees the session's fields")
                    .contains("credential", "pollExecutor", "pollTask");
        }
    }

    // =========================================================================
    // 2. Constant Validation Tests — 常量验证测试
    // =========================================================================

    @Nested
    @DisplayName("常量验证测试")
    class ConstantValidationTests {

        @Test
        @DisplayName("DEVICE_SLOW_DOWN_INCREMENT_MS 为 5000（RFC 8628 §3.5）/ slow_down adds 5 s")
        void deviceSlowDownIncrementIsFiveSeconds() throws Exception {
            long value = getSessionStaticLong("DEVICE_SLOW_DOWN_INCREMENT_MS");
            assertThat(value).isEqualTo(5000L);
        }
    }

    // =========================================================================
    // 5. TokenEntity Helper Tests — TokenEntity 辅助测试
    // =========================================================================

    @Nested
    @DisplayName("TokenEntity 过期状态测试")
    class TokenEntityExpiryTests {

        @Test
        @DisplayName("exp 在未来时 isExpired 应返回 false / isExpired returns false for future exp")
        void isExpiredShouldReturnFalseForFutureExp() {
            TokenEntity token = buildTokenWithExp(7200L); // 2 hours from now

            assertThat(token.isExpired()).isFalse();
        }

        @Test
        @DisplayName("exp 在过去时 isExpired 应返回 true / isExpired returns true for past exp")
        void isExpiredShouldReturnTrueForPastExp() {
            TokenEntity token = buildTokenWithExp(-1L); // 1 second ago

            assertThat(token.isExpired()).isTrue();
        }

        @Test
        @DisplayName("exp 为 null 时 isExpired 应返回 false / isExpired returns false when exp is null")
        void isExpiredShouldReturnFalseWhenExpIsNull() {
            TokenEntity token = buildTokenWithNullExp();

            assertThat(token.isExpired()).isFalse();
        }

        @Test
        @DisplayName("过期了1小时的令牌 isExpired 应为 true / 1-hour-old token should be expired")
        void isExpiredShouldBeTrueForOneHourOldToken() {
            TokenEntity token = buildTokenWithExp(-3600L);

            assertThat(token.isExpired()).isTrue();
        }

        @Test
        @DisplayName("访问令牌为 null 时 getAccess_token 为 null / a null access_token stays null")
        void nullAccessTokenMeansInvalid() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token(null);
            token.setExp((System.currentTimeMillis() / 1000L) + 3600L);

            assertThat(token.getAccess_token()).isNull();
        }

        @Test
        @DisplayName("TokenEntity 的 getExpirationDate 在 exp 不为 null 时应返回非 null Date")
        void getExpirationDateShouldReturnNonNullWhenExpSet() {
            TokenEntity token = buildTokenWithExp(3600L);

            assertThat(token.getExpirationDate()).isNotNull();
        }

        @Test
        @DisplayName("TokenEntity 的 getExpirationDate 在 exp 为 null 时应返回 null")
        void getExpirationDateShouldReturnNullWhenExpNotSet() {
            TokenEntity token = buildTokenWithNullExp();

            assertThat(token.getExpirationDate()).isNull();
        }
    }

    // =========================================================================
    // 7. Poll Scheduler Lifecycle Tests — 轮询调度器生命周期测试（CloudSession 实例方法）
    // =========================================================================

    @Nested
    @DisplayName("设备码轮询调度器生命周期测试 / device-code poll scheduler lifecycle")
    class PollSchedulerLifecycleTests {

        @Test
        @DisplayName("stopPolling 在未启动时调用不应抛出异常 / idempotent when not started")
        void stopPollingShouldBeIdempotentWhenNotRunning() throws Exception {
            setSessionField("pollExecutor", null);
            setSessionField("pollTask", null);

            // Should not throw
            CloudSession.current().stopPolling();

            assertThat(getSessionField("pollExecutor")).isNull();
            assertThat(getSessionField("pollTask")).isNull();
        }

        @Test
        @DisplayName("stopPolling 之后 pollExecutor 应为 null / pollExecutor null after stop")
        void pollExecutorShouldBeNullAfterStop() throws Exception {
            // Manually plant an executor without calling startPolling (which needs UltiTools)
            ScheduledExecutorService fakeExecutor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            setSessionField("pollExecutor", fakeExecutor);

            CloudSession.current().stopPolling();

            assertThat(getSessionField("pollExecutor")).isNull();
            assertThat(fakeExecutor.isShutdown()).isTrue();
        }

        @Test
        @DisplayName("stopPolling 之后 pollTask 应为 null / pollTask null after stop")
        void pollTaskShouldBeNullAfterStop() throws Exception {
            // Simulate a task that is already present
            ScheduledExecutorService fakeExecutor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            java.util.concurrent.ScheduledFuture<?> fakeTask =
                fakeExecutor.schedule(() -> { }, 1, TimeUnit.HOURS);

            setSessionField("pollExecutor", fakeExecutor);
            setSessionField("pollTask", fakeTask);

            CloudSession.current().stopPolling();

            assertThat(getSessionField("pollTask")).isNull();
            assertThat(fakeTask.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("连续调用两次 stopPolling 不应抛出异常 / double stop should be safe")
        void doubleStopShouldBeSafe() throws Exception {
            CloudSession.current().stopPolling();
            CloudSession.current().stopPolling(); // second call on already-null state

            assertThat(getSessionField("pollExecutor")).isNull();
        }
    }

    // =========================================================================
    // 10. login() Tests — 新的三个命令入口之一（plan 16-09）
    // =========================================================================

    @Nested
    @DisplayName("login 方法测试（三个命令入口之一，D-17/D-18）")
    class LoginTests {

        @Test
        @DisplayName("已绑定（会话持有服务器凭据）时只调用 onAlreadyLoggedIn，不发起请求")
        void alreadyLoggedInSkipsEverythingElse() throws Exception {
            // Protocol v2 (plan 22-01): "already logged in" is "already linked" -- a server
            // credential, not the 6.3.0 user token.
            CloudSession.current().commitCredential(DeviceLoginTestSupport.credential("Survival"));

            AtomicBoolean alreadyLoggedInCalled = new AtomicBoolean(false);
            AtomicBoolean anythingElseCalled = new AtomicBoolean(false);

            CloudAuthManager.login(
                () -> alreadyLoggedInCalled.set(true),
                remaining -> anythingElseCalled.set(true),
                () -> anythingElseCalled.set(true),
                url -> anythingElseCalled.set(true),
                error -> anythingElseCalled.set(true));

            assertThat(alreadyLoggedInCalled).isTrue();
            assertThat(anythingElseCalled)
                    .as("已登录分支必须短路——不应触碰限流、请求或回调的任何其它分支")
                    .isFalse();
        }

        @Test
        @DisplayName("限流命中时调用 onRateLimited，剩余秒数大于 0")
        void rateLimitedReportsPositiveRemaining() throws Exception {
            // First call consumes the rate-limit window (network-free: base URL is forced empty).
            CloudAuthManager.login(() -> { }, remaining -> { }, () -> { }, url -> { }, error -> { });

            // Second call, immediately after, must be rate-limited.
            AtomicLong remainingSeconds = new AtomicLong(-1);
            AtomicBoolean requestingCalled = new AtomicBoolean(false);

            CloudAuthManager.login(
                () -> { },
                remainingSeconds::set,
                () -> requestingCalled.set(true),
                url -> { },
                error -> { });

            assertThat(remainingSeconds.get()).isGreaterThan(0);
            assertThat(requestingCalled)
                    .as("限流命中时不应继续走到 onRequesting")
                    .isFalse();
        }

        @Test
        @DisplayName("未登录且未限流时：先 onRequesting，再因 API URL 未配置调用 onError")
        void notLoggedInAndNotRateLimitedRequestsThenReportsMisconfiguration() throws Exception {
            AtomicBoolean requestingCalled = new AtomicBoolean(false);
            AtomicReference<String> errorMessage = new AtomicReference<>();
            AtomicBoolean successCalled = new AtomicBoolean(false);
            CountDownLatch errorReported = new CountDownLatch(1);

            CloudAuthManager.login(
                () -> { },
                remaining -> { },
                () -> requestingCalled.set(true),
                url -> successCalled.set(true),
                error -> {
                    errorMessage.set(error);
                    errorReported.countDown();
                });

            assertThat(requestingCalled).isTrue();
            // Protocol v2 (plan 22-01): the device-code request runs on the new session's executor,
            // so the failure arrives after login() returned.
            assertThat(errorReported.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(errorMessage.get()).isEqualTo("API URL not configured");
            assertThat(successCalled).isFalse();
        }

        @Test
        @DisplayName("Round 1 外部评审：登录会先完整拆掉当前（已过期）会话的全局管理器，再换上新会话")
        // PMD.JUnitTestsShouldIncludeAssert: 本用例唯一的断言是下面的 Mockito verify()，PMD 不认得
        // 这种断言形式（与本仓库 CLAUDE.md「Suppressing PMD in tests」一节记录的既有情形相同）。
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void loginFullyTearsDownTheStaleSessionsGlobalManagersBeforeReplacingIt() throws Exception {
            // 未绑定——不会走「已登录」短路分支，但这个会话此前可能仍有一整套云生命周期在跑。
            CloudSession sessionBeforeLogin = CloudSession.current();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                init.when(() -> PluginInitiationUtils.disableCloud(any(CloudSession.class)))
                        .thenReturn(sessionBeforeLogin);

                CloudAuthManager.login(() -> { }, remaining -> { }, () -> { }, url -> { }, error -> { });

                // 必须拆的是登录发生时那个（已过期）会话本身，而不是随便一个会话；且必须发生在
                // startNew() 换上新会话之前——sessionBeforeLogin 在这一刻还是 current()。
                init.verify(() -> PluginInitiationUtils.disableCloud(sessionBeforeLogin), times(1));
            }
        }

        @Test
        @DisplayName("Round 1 外部评审（第三轮）：两次判断之间若已有一次在途的设备码轮询悄悄把凭据落地，login 不能把它撤销掉再开一次新的登录流程")
        void loginDoesNotTearDownASessionThatBecameValidBetweenItsOwnTwoChecks() throws Exception {
            CloudSession sessionAtCheck = CloudSession.current();

            try (MockedStatic<ApiRateLimiter> rate = mockStatic(ApiRateLimiter.class)) {
                rate.when(ApiRateLimiter::isLoginAllowed).thenAnswer(invocation -> {
                    // 模拟：正是在「已绑定」判断和这次限流判断之间，一个更早发起、仍在轮询的
                    // 设备码登录完成了，把服务器凭据提交到了同一个（此刻仍是 current 的）会话上——
                    // 设备码能撑 10 分钟，远比这里 1 分钟的登录冷却长。
                    sessionAtCheck.commitCredential(DeviceLoginTestSupport.credential("Survival"));
                    return true;
                });

                AtomicBoolean alreadyLoggedInCalled = new AtomicBoolean(false);
                AtomicBoolean anythingElseCalled = new AtomicBoolean(false);

                CloudAuthManager.login(
                    () -> alreadyLoggedInCalled.set(true),
                    remaining -> anythingElseCalled.set(true),
                    () -> anythingElseCalled.set(true),
                    url -> anythingElseCalled.set(true),
                    error -> anythingElseCalled.set(true));

                assertThat(alreadyLoggedInCalled)
                        .as("两次判断之间已经变得有效的会话必须被当成「已登录」，不能被拆掉重新走一遍登录流程")
                        .isTrue();
                assertThat(anythingElseCalled)
                        .as("走「已登录」分支就必须整体短路，不能再碰限流之后的任何步骤")
                        .isFalse();
            }

            assertThat(sessionAtCheck.isCurrent())
                    .as("刚刚才拿到有效令牌的会话不该被这次 login() 调用拆掉")
                    .isTrue();
        }

        @Test
        @DisplayName("#466（16-10 补丁）：login 已经判定「未登录」并开始拆线之后，一个仍在追赶的并发提交"
                + "必须要么在拆线开始前就已经完整落地，要么被拆线之后彻底拒绝——不允许出现"
                + "「提交报告成功，但这份凭证已经没有会话再认得」的幽灵状态")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void concurrentPollCommitCannotSlipBetweenLoginsRecheckAndItsTeardown() throws Exception {
            CloudSession sessionAtCheck = CloudSession.current();

            // Mockito 的静态 mock 是线程限定的（只拦截创建它的那个线程发起的调用），所以桩必须挂
            // 在真正调用 login() 的这个（主测试）线程上——模拟并发提交的那个线程反而是从桩自己的
            // Answer 内部现造出来的。login() 只在这一个点调用 PluginInitiationUtils 的静态方法：
            // 桩先把真正调用真实实现之前的这段窗口，撑成一个受控的、可确定性观察的宽度——起一个
            // 独立线程尝试并发提交，给它一个公平的调度机会，再调用 callRealMethod() 落实真正的
            // 拆线。全程不靠 sleep，只靠 CountDownLatch 与有界 join。
            AtomicBoolean commitSucceeded = new AtomicBoolean(false);
            AtomicReference<Thread> pollThreadRef = new AtomicReference<>();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                init.when(() -> PluginInitiationUtils.disableCloud(sessionAtCheck)).thenAnswer(invocation -> {
                    // 此刻：调用方（login()）自己的复检已经跑完并返回 false，正准备真正拆线。
                    // 一次并发的轮询在这个窗口尝试提交，模拟一次更早发起、仍在轮询、此刻恰好收到
                    // 授权结果的设备码登录（协议 v2，plan 22-01：提交走 commitCredential）。
                    CountDownLatch commitAttempted = new CountDownLatch(1);
                    Thread pollThread = new Thread(() -> {
                        commitAttempted.countDown();
                        try {
                            commitSucceeded.set(sessionAtCheck.commitCredential(
                                    DeviceLoginTestSupport.credential("Survival")));
                        } catch (Exception ignored) {
                            // 本用例只关心提交成功与否，不关心磁盘写入本身的异常路径
                        }
                    }, "poll-466-worker");
                    pollThread.setDaemon(true);
                    pollThreadRef.set(pollThread);
                    pollThread.start();
                    assertThat(commitAttempted.await(5, TimeUnit.SECONDS)).isTrue();

                    // 给轮询线程一个公平的机会真正跑到 commit() 内部——修复之后调用方这个线程
                    // 此刻仍持有会话锁（Mockito 拦截是纯方法级替换，不释放调用线程已经持有的任何
                    // 内在锁），轮询线程会被同一把锁挡住；300ms 足够任何调度延迟证明这一点。
                    // 修复之前调用方此刻不持有任何锁，轮询线程早就已经跑完了。这不是一个计时
                    // 假设，只是给调度器一个公平的机会——真正的判定来自下面调用返回之后对
                    // pollThread 的完整 join 与最终状态断言。
                    pollThread.join(300);

                    return invocation.callRealMethod();
                });

                AtomicBoolean alreadyLoggedInCalled = new AtomicBoolean(false);
                CloudAuthManager.login(
                        () -> alreadyLoggedInCalled.set(true),
                        remaining -> { },
                        () -> { },
                        url -> { },
                        error -> { });

                assertThat(alreadyLoggedInCalled)
                        .as("这次 login 调用自己的复检发生在轮询提交之前，正确地判定为「未登录」并"
                                + "继续了原本的换会话流程——这与轮询自己的提交是否安全，是两件独立的事")
                        .isFalse();
            }

            Thread pollThread = pollThreadRef.get();
            assertThat(pollThread).as("前置条件：桩内部确实起了那个并发提交线程").isNotNull();
            pollThread.join(5000);
            assertThat(pollThread.isAlive()).as("轮询线程必须已经跑完").isFalse();

            assertThat(commitSucceeded)
                    .as("#466：一次在 login 已经判定「未登录」并开始拆线之后才追上来的提交，"
                            + "绝不能成功写盘——否则这份凭证就成了没有会话再认得的幽灵凭证。"
                            + "锁修复之前，login 在调用 disableCloud 的这段窗口不持有任何锁，"
                            + "这次提交会立刻成功，随后又被 login 自己的拆线甩在一边。")
                    .isFalse();

            assertThat(sessionAtCheck.isCurrent())
                    .as("前置条件：本用例这次 login 调用的拆线确实跑完了，不是被跳过")
                    .isFalse();
        }

        @Test
        @DisplayName("round-14 外部评审（P2）：两次几乎同时发起的 login 调用，后到的那一次不得撤销"
                + "先到的那次刚刚装好、已经在轮询的新会话")
        void secondOverlappingLoginDoesNotInvalidateTheFirstOnesFreshSession() throws Exception {
            CloudSession sessionAtCheck = CloudSession.current();

            // 复现评审给出的确切场景：ApiRateLimiter.isAllowed() 自己的 check-then-set
            // 不是原子的（分开的 ConcurrentHashMap get + put），所以两次几乎同时发起的
            // @RunAsync login() 调用可能都基于同一个 sessionAtCheck 通过限流检查。这里不去
            // 复现限流器本身的那道竞争（真要在两个真实线程之间跨线程打桩 ApiRateLimiter 这个
            // 静态方法，会撞上 Mockito 静态 mock 线程限定的限制——本文件其它用例已经踩过这个坑），
            // 而是直接复现两次调用「都基于同一个 sessionAtCheck 认为自己可以继续」这个前提本身：
            // T1 卡在持锁区域内部（借 onRequesting 钩子），T2 在 T1 released 之前就已经真的
            // 排队等在同一把锁外面。
            CountDownLatch t1ReachedLock = new CountDownLatch(1);
            CountDownLatch t2Attempted = new CountDownLatch(1);
            CountDownLatch releaseT1 = new CountDownLatch(1);

            AtomicReference<String> t1ErrorMessage = new AtomicReference<>();
            Thread t1 = new Thread(() -> CloudAuthManager.login(
                    () -> { },
                    remaining -> { },
                    () -> {
                        // 此刻 T1 已经拿到 CloudSession.class + sessionAtCheck 这两把锁。
                        t1ReachedLock.countDown();
                        try {
                            assertThat(releaseT1.await(5, TimeUnit.SECONDS))
                                    .as("本用例必须主动放行 T1，不能超时")
                                    .isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    url -> { },
                    t1ErrorMessage::set),
                    "login-round14-t1");
            t1.setDaemon(true);
            t1.start();

            assertThat(t1ReachedLock.await(5, TimeUnit.SECONDS))
                    .as("T1 必须先真的拿到锁、卡在 onRequesting 钩子里")
                    .isTrue();

            // T1 自己那次 isLoginAllowed() 调用已经消耗掉了限流令牌——T2 若不重置，会在还没碰到
            // 锁之前就先被限流短路掉，测的就成了限流器而不是这里要补的那个洞。真实场景里 T2 能
            // 绕过限流，靠的是 isAllowed() 自己那个非原子的 check-then-set 竞争；这里直接重置，
            // 等价地制造出「T2 也认为自己被放行了」这个前提，不去复现限流器内部的竞争本身。
            ApiRateLimiter.resetAll();

            AtomicBoolean t2AlreadyLoggedInCalled = new AtomicBoolean(false);
            AtomicReference<String> t2ErrorMessage = new AtomicReference<>();
            Thread t2 = new Thread(() -> {
                t2Attempted.countDown();
                CloudAuthManager.login(
                        () -> t2AlreadyLoggedInCalled.set(true),
                        remaining -> { },
                        () -> { },
                        url -> { },
                        t2ErrorMessage::set);
            }, "login-round14-t2");
            t2.setDaemon(true);
            t2.start();
            assertThat(t2Attempted.await(5, TimeUnit.SECONDS)).isTrue();
            // 给 T2 一个公平的机会真正排队到同一把锁外面——它此刻必须被 T1 挡住，进不去。
            t2.join(300);
            assertThat(t2.isAlive())
                    .as("前置条件：T2 此刻必须还卡在锁外面，还没跑完")
                    .isTrue();

            releaseT1.countDown();
            t1.join(5000);
            assertThat(t1.isAlive()).as("T1 必须已经跑完").isFalse();

            CloudSession sessionAfterT1 = CloudSession.current();
            assertThat(sessionAfterT1)
                    .as("前置条件：T1 必须已经真的换上了它自己的新会话")
                    .isNotSameAs(sessionAtCheck);

            t2.join(5000);
            assertThat(t2.isAlive()).as("T2 必须已经跑完").isFalse();

            assertThat(t2AlreadyLoggedInCalled)
                    .as("T2 自己的复检（针对它自己那份、早已过期的 sessionAtCheck 快照）"
                            + "正确地判定为「未登录」——这与它最终该不该继续替换会话是两件事")
                    .isFalse();
            assertThat(t2ErrorMessage.get())
                    .as("round-14：T2 发现自己的 sessionAtCheck 早已不是 current()，"
                            + "必须安全地报错退出，而不是继续往下走")
                    .isNotNull();

            assertThat(CloudSession.current())
                    .as("round-14：T2 绝不能撤销 T1 刚刚装好的新会话——这正是评审要补的那个洞")
                    .isSameAs(sessionAfterT1);
            assertThat(sessionAfterT1.isCurrent())
                    .as("round-14：T1 的新会话必须仍然有效，没有被 T2 误伤")
                    .isTrue();
        }
    }

    // =========================================================================
    // 11. logout() Tests — 新的三个命令入口之一（plan 16-09）
    // =========================================================================

    @Nested
    @DisplayName("logout 方法测试（三个命令入口之一，D-17/D-18）")
    class LogoutTests {

        /**
         * Runs {@link CloudAuthManager#logout()} with Bukkit's scheduler replaced: the asynchronous
         * revoke-and-delete it schedules is captured in {@code revoke} instead of run, so a test decides
         * when it runs. Running it with the empty base URL of {@code resetStaticState()} makes the revoke
         * fail fast without a network ({@code DELETED_LOCALLY_REVOKE_FAILED}); the local delete still runs.
         */
        private boolean logoutCapturingRevoke(AtomicReference<Runnable> revoke) throws Exception {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
                revoke.set(invocation.getArgument(1));
                return null;
            });
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                return CloudAuthManager.logout();
            }
        }

        private ServerCredential credentialWithId(String credentialId) {
            return new ServerCredential("ucs_" + credentialId.toUpperCase(java.util.Locale.ROOT), credentialId,
                    "01JTESTSERVERID0000000000", 43L, "Survival", 1_700_000_000_000L);
        }

        @Test
        @DisplayName("有凭据时返回 true；拆线之后这个（同一个、现已失效的）会话在吊销与删除之后不再持有该凭据")
        void linkedCredentialReportsHadCredentialAndIsDeletedAfterTheRevoke() throws Exception {
            // Plan 22-24: was "an expired or valid token still reports a credential"; protocol v2 holds
            // a non-expiring server credential, and logout does not gate on connection state either.
            CloudSession sessionBeforeLogout = CloudSession.current();
            sessionBeforeLogout.commitCredential(credentialWithId("cred-own"));
            AtomicReference<Runnable> revoke = new AtomicReference<>();

            boolean hadCredential = logoutCapturingRevoke(revoke);

            assertThat(hadCredential).isTrue();
            // CR-01 fix: logout() does not call CloudSession.startNew() itself -- it clears the
            // captured session in place. current() therefore stays this SAME (now-invalidated)
            // session object; the next real login is what installs a fresh one.
            assertThat(CloudSession.current())
                    .as("logout 不再自行 startNew()——清掉的是拆线返回的那个会话本身")
                    .isSameAs(sessionBeforeLogout);
            assertThat(revoke.get()).as("the revoke and delete run on an asynchronous task").isNotNull();
            revoke.get().run();
            assertThat(sessionBeforeLogout.getCredential()).as("logout 之后这个会话不应再持有旧凭据").isNull();
            assertThat(ServerCredentialStore.load()).as("and the stored credential is deleted").isNull();
        }

        @Test
        @DisplayName("从未登录过时返回 false，但拆线仍然无条件发生")
        void neverLoggedInReturnsFalseButTeardownStillRuns() throws Exception {
            CloudSession session = CloudSession.current();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                // logout() calls the CloudSession-returning overload (CR-01) -- the mock must honour
                // that contract, or logout()'s read of the torn-down session below NPEs.
                init.when(() -> PluginInitiationUtils.disableCloud(session)).thenReturn(session);

                boolean hadCredential = CloudAuthManager.logout();

                init.verify(() -> PluginInitiationUtils.disableCloud(session), times(1));
                assertThat(hadCredential)
                        .as("没有凭据就没什么可清的，但 disableCloud() 的每一步都必须照跑")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("凭据必须在拆线之后读：拆线期间落地的凭据仍会被看到、吊销并删除")
        void credentialCommittedDuringTeardownIsStillSeenAndDeleted() throws Exception {
            CloudSession session = CloudSession.current();
            AtomicReference<Runnable> revoke = new AtomicReference<>();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                // Simulate an in-flight device-code poll committing successfully WHILE disableCloud() is
                // (nominally) running -- exactly the window logout() must still catch by reading the
                // credential only after teardown returns. Per the CR-01 fix, disableCloud() returns the
                // session it tore down -- here, the same session the poll committed onto.
                init.when(() -> PluginInitiationUtils.disableCloud(session)).thenAnswer(invocation -> {
                    session.commitCredential(credentialWithId("cred-mid-teardown"));
                    return session;
                });

                boolean hadCredential = logoutCapturingRevoke(revoke);

                assertThat(hadCredential)
                        .as("若沿用拆线前的快照，这里会是 false——凭据必须在拆线之后才读")
                        .isTrue();
            }
            // WR-04 (16-REVIEW-cloud.md): an independent disk-state assertion, not only the return value.
            assertThat(revoke.get()).isNotNull();
            revoke.get().run();
            assertThat(CredentialStore.read().data())
                    .as("拆线期间落地的凭据必须真的从磁盘上被删除，不能只是返回值凑巧对了")
                    .doesNotContainKey(ServerCredentialStore.KEY);
        }

        @Test
        @DisplayName("CR-01：即便有并发登录在拆线期间安装了新会话，logout 删掉的仍是它实际拆掉的那个会话的凭据")
        void logoutClearsTheCapturedSessionEvenWhenALoginInstallsANewSessionMidTeardown() throws Exception {
            // sBefore is the session /ulticloud logout is actually acting on -- give it a real,
            // committed credential on disk, the way a genuine prior login would have.
            CloudSession sBefore = CloudSession.current();
            sBefore.commitCredential(credentialWithId("cred-before"));
            assertThat(CredentialStore.read().data())
                    .as("前置条件：磁盘上得先真的有这份凭据")
                    .containsKey(ServerCredentialStore.KEY);
            AtomicReference<Runnable> revoke = new AtomicReference<>();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                // CR-01's exact race as an injected hook: disableCloud() returns the session it tore
                // down (sBefore), but a concurrent /ulticloud login lands before it returns and installs
                // a brand-new session as CloudSession.current(). A second, independent current() read
                // taken after this would see sNew's (always null) credential and conclude there was
                // nothing to clear.
                init.when(() -> PluginInitiationUtils.disableCloud(sBefore)).thenAnswer(invocation -> {
                    CloudSession.startNew(); // the concurrent login racing in
                    return sBefore;          // the FIXED contract: return what was actually torn down
                });

                boolean hadCredential = logoutCapturingRevoke(revoke);

                assertThat(hadCredential)
                        .as("即便并发登录已经把 current() 换掉，被真正拆线的会话（sBefore）原本持有凭据这件事必须被看到")
                        .isTrue();
            }

            CloudSession sNew = CloudSession.current();
            assertThat(sNew).isNotSameAs(sBefore);
            assertThat(sNew.getCredential()).as("并发登录安装的新会话不该被这次 logout 调用碰到").isNull();
            revoke.get().run();
            assertThat(CredentialStore.read().data())
                    .as("旧会话的磁盘凭据必须被删掉，不能因为 current() 已经指向别处而被跳过")
                    .doesNotContainKey(ServerCredentialStore.KEY);
        }

        @Test
        @DisplayName("Round 1 外部评审：拆线期间若新会话已经落地了自己的凭据，logout 的删除动作绝不能把它冲掉")
        void logoutDoesNotClearACredentialAFreshLoginAlreadyCommittedDuringTeardown() throws Exception {
            // sBefore -- the session /ulticloud logout is actually acting on -- has its own,
            // now-stale credential on disk.
            CloudSession sBefore = CloudSession.current();
            sBefore.commitCredential(credentialWithId("cred-stale"));
            AtomicReference<Runnable> revoke = new AtomicReference<>();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                // While sBefore's teardown is (nominally) still in flight, a concurrent /ulticloud login
                // installs a brand-new session AND its device login completes, committing a fresh
                // credential (a different credential id) to the SAME credentials.json document.
                init.when(() -> PluginInitiationUtils.disableCloud(sBefore)).thenAnswer(invocation -> {
                    CloudSession concurrentLogin = CloudSession.startNew();
                    concurrentLogin.commitCredential(credentialWithId("cred-fresh"));
                    return sBefore;
                });

                boolean hadCredential = logoutCapturingRevoke(revoke);

                assertThat(hadCredential)
                        .as("sBefore 自己原本确实持有凭据，这一半判断不受这条 fix 影响")
                        .isTrue();
            }

            revoke.get().run();
            ServerCredential survivor = ServerCredentialStore.load();
            assertThat(survivor).as("并发登录已经落地的凭据必须原样保留，不能被这次 logout 的删除动作冲掉").isNotNull();
            assertThat(survivor.getCredentialId())
                    .as("留在磁盘上的必须是并发登录自己的凭据，不是 sBefore 的")
                    .isEqualTo("cred-fresh");
        }

        @Test
        @DisplayName("round-13 外部评审（P1）：当前会话自己从没加载过凭据时，logout 仍必须吊销并删除磁盘上的那份凭据")
        void logoutDeletesAStoredCredentialTheCurrentSessionNeverLoaded() throws Exception {
            // Plan 22-24: was "an exhausted predecessor's token is still cleared"; on protocol v2 the
            // logout reads the stored credential whenever the session holds none
            // (CloudAuthManager#linkedCredential), which covers a predecessor's credential, one stored
            // while cloud features were off, and one written before this session existed.
            ServerCredentialStore.save(credentialWithId("cred-unloaded"));
            assertThat(CloudSession.current().getCredential())
                    .as("前置条件：当前会话此刻确实没有加载这份凭据")
                    .isNull();
            AtomicReference<Runnable> revoke = new AtomicReference<>();

            boolean hadCredential = logoutCapturingRevoke(revoke);

            assertThat(hadCredential)
                    .as("round-13：只要磁盘上还有一份没被清掉的凭据，这次 logout 就必须报告「确实清掉了」")
                    .isTrue();
            revoke.get().run();
            assertThat(CredentialStore.read().data())
                    .as("round-13：这份凭据必须被这次 logout 真正删掉，不能在重启之后被悄悄重新加载并重连")
                    .doesNotContainKey(ServerCredentialStore.KEY);
        }

        /**
         * Real-machine UAT fix, plan 16-08: {@code ultitools.ulticloud.logout.neg-mid-poll}.
         * <p>
         * Preconditions on the real server: not linked, {@code /ulticloud login}'s poll still in
         * flight (no credential committed yet). Observed on Paper 1.21.11 with the 6.3.0 magic-link
         * poll: {@code /ulticloud logout} printed the "not currently logged in" pair instead of the
         * success pair, because a cancelled-but-never-committed poll was indistinguishable from a
         * logout that cancelled nothing. The safety half (a stale approval after this logout must not
         * reactivate the server) is proven here on the protocol v2 commit,
         * {@link CloudSession#commitCredential(ServerCredential)}.
         */
        @Test
        @DisplayName("16-08 UAT 修复：登出发生在设备码轮询进行中时必须报告成功并真正取消轮询，"
                + "之后陈旧的批准结果不能落地凭据或重新激活")
        void logoutDuringInFlightMagicLinkPollReportsSuccessAndCancelsThePoll() throws Exception {
            // No credential has been committed yet -- login is still polling, exactly the checklist
            // row's precondition.
            CloudSession session = CloudSession.current();

            ScheduledExecutorService fakePollExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            java.util.concurrent.ScheduledFuture<?> fakePollTask = fakePollExecutor.schedule(() -> { }, 1, TimeUnit.HOURS);
            setSessionField("pollExecutor", fakePollExecutor);
            setSessionField("pollTask", fakePollTask);

            boolean hadCredential = CloudAuthManager.logout();

            assertThat(hadCredential)
                    .as("cancelling a real, in-flight authentication attempt is a genuine logout, "
                            + "not \"nothing to clear\" — the checklist row's expected success pair")
                    .isTrue();
            assertThat(fakePollTask.isCancelled())
                    .as("the in-flight poll must actually be cancelled by this logout, not merely "
                            + "reported as cancelled")
                    .isTrue();
            assertThat(getSessionField("pollTask"))
                    .as("teardown must clear the session's own poll-task reference")
                    .isNull();

            // The safety half: a stale grant from that same cancelled poll (the owner confirming the
            // old code after logout) must not reactivate the server or write a credential.
            boolean staleApprovalCommitted = session.commitCredential(credentialWithId("cred-stale-grant"));
            assertThat(staleApprovalCommitted)
                    .as("a poll result that lands after this logout must be rejected, not silently "
                            + "reactivate the server")
                    .isFalse();
            assertThat(CredentialStore.read().data())
                    .as("no credential may be written by that rejected, stale result")
                    .doesNotContainKey(ServerCredentialStore.KEY);
        }

        /**
         * The plain half of the same row's precondition space, restated here alongside the
         * mid-poll fix above so this class documents both outcomes of the same decision together:
         * {@link #neverLoggedInReturnsFalseButTeardownStillRuns()} already covers this scenario
         * (fresh session, no credential, no poll ever started) and must keep passing unchanged by the
         * 16-08 fix — {@code ultitools.ulticloud.logout.neg-not-logged-in}'s own expected "Not
         * currently logged in to UltiCloud." pair depends on it staying false when nothing was ever
         * pending.
         */
        @Test
        @DisplayName("对照组：没有凭据也没有轮询在飞时，logout 仍然报告「未登录」")
        void plainNoTokenAndNoPendingPollStillReportsNotCurrentlyLoggedIn() throws Exception {
            setSessionField("pollTask", null);
            setSessionField("pollExecutor", null);

            boolean hadCredential = CloudAuthManager.logout();

            assertThat(hadCredential)
                    .as("nothing was ever pending -- this must stay \"not currently logged in\"")
                    .isFalse();
        }
    }

    // =========================================================================
    // 11b. resumeSavedCredentialOnStartup() Tests — round-13 外部评审（16-10 补丁）
    // =========================================================================

    @Nested
    @DisplayName("resumeSavedCredentialOnStartup 方法测试（round-13 外部评审，P2）")
    class ResumeSavedCredentialOnStartupTests {

        @Test
        @DisplayName("round-13 外部评审（P2）：会话已因重连耗尽失效时（例如同一 classloader 内的"
                + " /reload），必须先换上新会话再加载磁盘凭证，不能加载到即将被替换掉的旧会话上")
        void replacesAnInvalidatedSessionBeforeLoadingTheSavedCredentialOntoIt() throws Exception {
            CloudSession old = CloudSession.current();
            old.commitCredential(DeviceLoginTestSupport.credential("Survival"));
            PluginInitiationUtils.disableCloud(old);
            assertThat(old.isCurrent()).isFalse();
            assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
            CloudSession fresh = CloudSession.current();
            assertThat(fresh).isNotSameAs(old);
            assertThat(fresh.isCurrent()).isTrue();
            assertThat(fresh.getCredential().getCredentialId()).isEqualTo(old.getCredential().getCredentialId());
        }

    }

    // =========================================================================
    // 12. status() Tests — 新的三个命令入口之一（plan 16-09）
    // =========================================================================

    @Nested
    @DisplayName("status 方法测试（三个命令入口之一，D-17/D-18）")
    class StatusTests {

        @Test
        @DisplayName("未连接时返回的快照 connected=false，且不携带用户名或过期时间")
        void notConnectedReportsDisconnectedSnapshot() throws Exception {
            CloudAuthManager.CloudStatus status = CloudAuthManager.status();

            assertThat(status.isConnected()).isFalse();
            assertThat(status.getUserName()).isNull();
            assertThat(status.getExpirationDate()).isNull();
        }

        @Test
        @DisplayName("已连接快照仅包含服务器身份，不再包含用户令牌信息")
        void connectedReportsServerIdentityWithoutUserToken() throws Exception {
            CloudReconnectStateMachineTest.AgentRuntime runtime = new CloudReconnectStateMachineTest.AgentRuntime();
            runtime.install();
            // Plan 22-20: connected means welcomed, and the welcome must name the linked server id
            // (a real ULID), so this test links one.
            CloudSession.current().commitCredential(new ServerCredential("ucs_TESTSECRET", "cred-1",
                    HandshakeEndToEndTest.SERVER_ID, 43L, "Survival", 1_700_000_000_000L));
            CloudSession.current().startConnection();
            runtime.runDue();
            runtime.last().accept();
            assertThat(CloudAuthManager.status().isConnected()).as("upgraded, not yet welcomed").isFalse();
            runtime.last().welcome();
            CloudAuthManager.CloudStatus status = CloudAuthManager.status();
            assertThat(status.isConnected()).isTrue();
            assertThat(status.isLinked()).isTrue();
            assertThat(status.getServerName()).isEqualTo("Survival");
            assertThat(status.getUserName()).isNull();
            assertThat(status.getExpirationDate()).isNull();
            assertThat(status.getConnectionDescription()).isEqualTo("connected");
        }

        @Test
        @DisplayName("未命名的已绑定服务器不等于已连接")
        void linkedWithoutServerNameReportsNotConnected() {
            CloudSession.current().commitCredential(DeviceLoginTestSupport.credential(null));
            CloudAuthManager.CloudStatus status = CloudAuthManager.status();
            assertThat(status.isLinked()).isTrue();
            assertThat(status.isConnected()).isFalse();
            assertThat(status.getServerName()).isNull();
            assertThat(status.getUserName()).isNull();
            assertThat(status.getConnectionDescription()).isEqualTo("not connected");
        }

    }

    // =========================================================================
    // 14. Device login polling (protocol v2, plan 22-01) — RFC 8628 语义与会话身份竞争
    // =========================================================================

    @Nested
    @DisplayName("设备码轮询：RFC 8628 语义、截止时间与会话身份竞争（plan 22-01）")
    class DeviceLoginPollingTests {

        private final DeviceLoginTestSupport.FakeClock clock = new DeviceLoginTestSupport.FakeClock();
        private final DeviceLoginTestSupport.FakeScheduler scheduler = new DeviceLoginTestSupport.FakeScheduler(clock);
        private final DeviceLoginTestSupport.RecordingListener listener = new DeviceLoginTestSupport.RecordingListener();

        @AfterEach
        void clearSeams() {
            CloudSession.setDeviceSeamsForTesting(null, null, null);
        }

        /** Installs {@code client}, runs login() and its authorize step; polling is then scheduled. */
        private void loginWith(DeviceLoginTestSupport.ScriptedClient client) {
            CloudSession.setDeviceSeamsForTesting(client, scheduler, clock);
            CloudAuthManager.login(listener);
            assertThat(scheduler.pending()).as("login() hands the authorize call to the session executor").isEqualTo(1);
            scheduler.runNext();
        }

        private DeviceLoginTestSupport.ScriptedClient grantedClient(long expiresIn, long interval) {
            return new DeviceLoginTestSupport.ScriptedClient(
                    DeviceAuthorizationClient.AuthorizeResult.granted(DeviceLoginTestSupport.grant(expiresIn, interval)));
        }

        private boolean credentialOnDisk() {
            CredentialStore.ReadResult result = CredentialStore.read();
            return result.isParsed() && result.data().containsKey("cloud_credential");
        }

        private DeviceAuthorizationClient.PollResult of(DeviceAuthorizationClient.PollResult.Kind kind) {
            return DeviceAuthorizationClient.PollResult.of(kind);
        }

        @Test
        @DisplayName("slow_down twice raises the interval to interval + 10 s, cumulatively")
        void slowDownTwiceAddsTenSeconds() {
            loginWith(grantedClient(600, 5)
                    .thenPoll(of(DeviceAuthorizationClient.PollResult.Kind.SLOW_DOWN))
                    .thenPoll(of(DeviceAuthorizationClient.PollResult.Kind.SLOW_DOWN)));

            scheduler.runNext();
            scheduler.runNext();

            assertThat(scheduler.delays()).containsExactly(0L, 5_000L, 10_000L, 15_000L);
            assertThat(credentialOnDisk()).isFalse();
        }

        @Test
        @DisplayName("PENDING until the deadline ends with onExpired, without another poll")
        void pendingUntilTheDeadlineExpires() {
            DeviceLoginTestSupport.ScriptedClient client = grantedClient(12, 5);
            loginWith(client);

            scheduler.runNext(); // t = 5 s, pending
            scheduler.runNext(); // t = 10 s, pending
            scheduler.runNext(); // t = 15 s >= 12 s deadline

            assertThat(client.pollCount()).isEqualTo(2);
            assertThat(listener.events).containsOnlyOnce("expired");
            assertThat(scheduler.pending()).isZero();
            verify(mockLogger).log(Level.WARNING,
                    "UltiCloud: the device code expired before it was confirmed. Run /ulticloud login to get a new code.");
            assertThat(credentialOnDisk()).isFalse();
        }

        @Test
        @DisplayName("EXPIRED from UltiCloud ends polling with onExpired")
        void expiredTokenEndsPolling() {
            loginWith(grantedClient(600, 5).thenPoll(of(DeviceAuthorizationClient.PollResult.Kind.EXPIRED)));

            scheduler.runNext();

            assertThat(listener.events).contains("expired");
            assertThat(scheduler.pending()).isZero();
            assertThat(credentialOnDisk()).isFalse();
        }

        @Test
        @DisplayName("DENIED ends polling with onDenied and the denied line")
        void deniedEndsPolling() {
            loginWith(grantedClient(600, 5).thenPoll(of(DeviceAuthorizationClient.PollResult.Kind.DENIED)));

            scheduler.runNext();

            assertThat(listener.events).contains("denied");
            assertThat(scheduler.pending()).isZero();
            verify(mockLogger).log(Level.WARNING,
                    "UltiCloud: linking was denied on the confirmation page. Run /ulticloud login to try again.");
            assertThat(credentialOnDisk()).isFalse();
        }

        @Test
        @DisplayName("REJECTED ends polling with onRejected(code, message) and the rejected line")
        void rejectedEndsPolling() {
            loginWith(grantedClient(600, 5)
                    .thenPoll(DeviceAuthorizationClient.PollResult.rejected("invalid_request", "deviceCode is malformed")));

            scheduler.runNext();

            assertThat(listener.events).contains("rejected:invalid_request:deviceCode is malformed");
            assertThat(scheduler.pending()).isZero();
            verify(mockLogger).log(Level.WARNING,
                    "UltiCloud: the device code request was rejected (invalid_request): deviceCode is malformed");
            assertThat(credentialOnDisk()).isFalse();
        }

        @Test
        @DisplayName("TRANSIENT keeps polling at the current interval; after a 429 it waits max(interval, Retry-After)")
        void transientKeepsPollingAndHonoursRetryAfter() {
            loginWith(grantedClient(600, 5)
                    .thenPoll(DeviceAuthorizationClient.PollResult.transientFailure(-1))
                    .thenPoll(DeviceAuthorizationClient.PollResult.transientFailure(30))
                    .thenPoll(DeviceAuthorizationClient.PollResult.transientFailure(2)));

            scheduler.runNext();
            scheduler.runNext();
            scheduler.runNext();

            assertThat(scheduler.delays()).containsExactly(0L, 5_000L, 5_000L, 30_000L, 5_000L);
            assertThat(listener.events).doesNotContain("expired", "denied");
            assertThat(credentialOnDisk()).isFalse();
        }

        @Test
        @DisplayName("GRANTED commits the credential, stops polling and logs the linked line")
        void grantedCommitsAndStops() {
            new CloudReconnectStateMachineTest.AgentRuntime().install();
            loginWith(grantedClient(600, 5)
                    .thenPoll(DeviceAuthorizationClient.PollResult.granted(DeviceLoginTestSupport.credential("Survival"))));

            scheduler.runNext();

            assertThat(credentialOnDisk()).isTrue();
            assertThat(listener.events).contains("linked:Survival");
            assertThat(scheduler.pending()).isZero();
            verify(mockLogger).log(Level.INFO,
                    "UltiCloud: this server is now linked as Survival (server ID 01JTESTSERVERID0000000000).");
        }

        @Test
        @DisplayName("a failed device-code request is reported and schedules no poll")
        void failedAuthorizeIsReported() {
            loginWith(new DeviceLoginTestSupport.ScriptedClient(
                    DeviceAuthorizationClient.AuthorizeResult.failed("UltiCloud answered HTTP 503", -1)));

            assertThat(listener.events).containsExactly("requesting", "failed:UltiCloud answered HTTP 503");
            assertThat(scheduler.pending()).isZero();
        }

        // ---- the magic-link session-identity races, ported to the device flow ----

        @Test
        @DisplayName("logout while a poll is in flight cancels it; the grant that poll returns is discarded, not written")
        void logoutWhilePollingDiscardsTheLateGrant() {
            AtomicBoolean logoutReported = new AtomicBoolean();
            loginWith(grantedClient(600, 5).thenPoll(() -> {
                try {
                    logoutReported.set(CloudAuthManager.logout());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                return DeviceAuthorizationClient.PollResult.granted(DeviceLoginTestSupport.credential("Survival"));
            }));

            scheduler.runNext();

            assertThat(logoutReported).as("cancelling a pending authentication is a real logout").isTrue();
            assertThat(credentialOnDisk()).as("the late grant must not reach credentials.json").isFalse();
            assertThat(listener.events).noneMatch(event -> event.startsWith("linked:"));
            assertThat(scheduler.pending()).isZero();
            verify(mockLogger, never()).log(org.mockito.ArgumentMatchers.eq(Level.INFO),
                    org.mockito.ArgumentMatchers.startsWith("UltiCloud: this server is now linked"));
        }

        @Test
        @DisplayName("a grant that arrives after a second login replaced the session is discarded")
        void grantAfterANewerLoginIsDiscarded() {
            DeviceLoginTestSupport.RecordingListener second = new DeviceLoginTestSupport.RecordingListener();
            AtomicReference<CloudSession> pollingSession = new AtomicReference<>();
            loginWith(grantedClient(600, 5).thenPoll(() -> {
                pollingSession.set(CloudSession.current());
                ApiRateLimiter.resetAll();
                CloudAuthManager.login(second);
                return DeviceAuthorizationClient.PollResult.granted(DeviceLoginTestSupport.credential("Survival"));
            }));

            scheduler.runNext();

            assertThat(CloudSession.current()).isNotSameAs(pollingSession.get());
            assertThat(pollingSession.get().isCurrent()).isFalse();
            assertThat(credentialOnDisk()).isFalse();
            assertThat(listener.events).noneMatch(event -> event.startsWith("linked:"));
            assertThat(second.events).containsExactly("requesting");
        }

        @Test
        @DisplayName("the 6.3.0 five-argument login still works: onSuccess receives the complete verification URI")
        void legacyOverloadReceivesTheCompleteUri() {
            CloudSession.setDeviceSeamsForTesting(grantedClient(600, 5), scheduler, clock);
            AtomicReference<String> url = new AtomicReference<>();
            AtomicBoolean requesting = new AtomicBoolean();

            CloudAuthManager.login(() -> { }, remaining -> { }, () -> requesting.set(true), url::set, error -> { });
            scheduler.runNext();

            assertThat(requesting).isTrue();
            assertThat(url.get()).isEqualTo("https://panel.ultikits.com/device?code=BCDF-GHJK");
            assertThat(scheduler.pending()).as("polling started").isEqualTo(1);
        }

        @Test
        @DisplayName("a stored credential makes login report already linked, with nothing requested")
        void storedCredentialIsAlreadyLinked() {
            ServerCredentialStore.save(DeviceLoginTestSupport.credential("Survival"));
            CloudSession.setDeviceSeamsForTesting(grantedClient(600, 5), scheduler, clock);

            CloudAuthManager.login(listener);

            assertThat(listener.events).containsExactly("alreadyLinked:Survival");
            assertThat(scheduler.pending()).isZero();
        }
    }
}
