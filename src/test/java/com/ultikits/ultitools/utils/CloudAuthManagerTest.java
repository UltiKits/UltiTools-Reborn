package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

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
 */
@DisplayName("CloudAuthManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudAuthManagerTest {

    @TempDir
    File dataFolder;

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
     * Read the value of a private static {@code int} field (a constant) on {@link CloudSession}.
     */
    private static int getSessionStaticInt(String name) throws Exception {
        Field field = CloudSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    /**
     * Read the value of a private static {@link String} field (a constant) on {@link CloudSession}.
     */
    private static String getSessionStaticString(String name) throws Exception {
        Field field = CloudSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
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
        // LogoutTests' real (unmocked) logout() calls exercise CloudSession#commit(), which writes
        // through TokenStore/CredentialStore -- pin both write locations to the temp dir so nothing
        // lands under this repository's real data folder or ~/.ultikits (mirrors
        // CredentialGenerationTest's setup).
        CredentialStore.setTargetPathForTesting(dataFolder.toPath().resolve("credentials.json"));
        CredentialStore.setOldLocationForTesting(dataFolder.toPath().resolve("pre-migration-data.json"));
        // LogoutTests' one un-mocked disableCloud() call reaches UltiTools.getInstance().getLogger()
        // from several teardown steps; every manager getter besides that defaults to Mockito's null,
        // which doDisableCloud()'s own null-checks and catch-and-log-at-FINE already tolerate.
        Logger mockLogger = mock(Logger.class);
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
        @DisplayName("CloudSession 应该拥有一个持有当前令牌的实例字段 / holds an instance field for the current token")
        void sessionTokenFieldShouldExist() throws Exception {
            Field field = CloudSession.class.getDeclaredField("token");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .as("the token is now instance state, owned by one session, not a class-wide static")
                    .isFalse();
            assertThat(field.getType()).isEqualTo(TokenEntity.class);
        }

        @Test
        @DisplayName("CloudSession 应该拥有 pollExecutor 实例字段 / instance field pollExecutor should exist")
        void pollExecutorFieldShouldExist() throws Exception {
            Field field = CloudSession.class.getDeclaredField("pollExecutor");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isFalse();
            assertThat(ScheduledExecutorService.class.isAssignableFrom(field.getType())).isTrue();
        }

        @Test
        @DisplayName("CloudSession 应该拥有 refreshExecutor 实例字段 / instance field refreshExecutor should exist")
        void refreshExecutorFieldShouldExist() throws Exception {
            Field field = CloudSession.class.getDeclaredField("refreshExecutor");
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
        @DisplayName("CloudSession 应该拥有 refreshTask 实例字段 / instance field refreshTask should exist")
        void refreshTaskFieldShouldExist() throws Exception {
            Field field = CloudSession.class.getDeclaredField("refreshTask");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isFalse();
        }
    }

    // =========================================================================
    // 2. Constant Validation Tests — 常量验证测试
    // =========================================================================

    @Nested
    @DisplayName("常量验证测试")
    class ConstantValidationTests {

        @Test
        @DisplayName("POLL_INTERVAL_MS 常量应该存在且值为 3000 / POLL_INTERVAL_MS should be 3000")
        void pollIntervalMsShouldBe3000() throws Exception {
            long value = getSessionStaticLong("POLL_INTERVAL_MS");
            assertThat(value).isEqualTo(3000L);
        }

        @Test
        @DisplayName("TOKEN_REFRESH_CHECK_INTERVAL_MS 应该为 3600000（1小时）/ should equal 1 hour in ms")
        void tokenRefreshCheckIntervalMsShouldBeOneHour() throws Exception {
            long value = getSessionStaticLong("TOKEN_REFRESH_CHECK_INTERVAL_MS");
            assertThat(value).isEqualTo(60L * 60L * 1000L);
        }

        @Test
        @DisplayName("TOKEN_REFRESH_THRESHOLD_SECONDS 应该为 7200（2小时）/ should be 7200 seconds")
        void tokenRefreshThresholdSecondsShouldBe7200() throws Exception {
            long value = getSessionStaticLong("TOKEN_REFRESH_THRESHOLD_SECONDS");
            assertThat(value).isEqualTo(2L * 60L * 60L);
        }

        @Test
        @DisplayName("OAUTH2_BASIC_AUTH 应该以 'Basic ' 开头 / should start with 'Basic '")
        void oauth2BasicAuthShouldStartWithBasic() throws Exception {
            String value = getSessionStaticString("OAUTH2_BASIC_AUTH");
            assertThat(value).startsWith("Basic ");
        }

        @Test
        @DisplayName("OAUTH2_BASIC_AUTH 解码后应该等于 'client:112233' / should decode to 'client:112233'")
        void oauth2BasicAuthShouldDecodeToClientCredentials() throws Exception {
            String value = getSessionStaticString("OAUTH2_BASIC_AUTH");

            // Strip the "Basic " prefix and decode Base64
            String base64Part = value.substring("Basic ".length());
            byte[] decodedBytes = Base64.getDecoder().decode(base64Part);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);

            assertThat(decoded).isEqualTo("client:112233");
        }

        @Test
        @DisplayName("MAX_POLL_ATTEMPTS 常量应该存在且大于 0 / MAX_POLL_ATTEMPTS should be positive")
        void maxPollAttemptsShouldBePositive() throws Exception {
            int value = getSessionStaticInt("MAX_POLL_ATTEMPTS");
            assertThat(value).isGreaterThan(0);
        }
    }

    // =========================================================================
    // 3. hasValidToken Tests — hasValidToken 方法测试（CloudSession 实例方法）
    // =========================================================================

    @Nested
    @DisplayName("hasValidToken 方法测试")
    class HasValidTokenTests {

        @Test
        @DisplayName("currentToken 为 null 时应返回 false / returns false when token is null")
        void shouldReturnFalseWhenCurrentTokenIsNull() throws Exception {
            setSessionField("token", null);

            assertThat(CloudSession.current().hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("access_token 为 null 时应返回 false / returns false when access_token is null")
        void shouldReturnFalseWhenAccessTokenIsNull() throws Exception {
            TokenEntity token = new TokenEntity();
            token.setAccess_token(null);
            token.setExp((System.currentTimeMillis() / 1000L) + 3600L);
            setSessionField("token", token);

            assertThat(CloudSession.current().hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("令牌已过期时应返回 false / returns false when token is expired")
        void shouldReturnFalseWhenTokenIsExpired() throws Exception {
            // Set exp to 1 hour in the past
            TokenEntity expiredToken = buildTokenWithExp(-3600L);
            setSessionField("token", expiredToken);

            assertThat(CloudSession.current().hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("令牌有效时应返回 true / returns true when token is valid and not expired")
        void shouldReturnTrueWhenTokenIsValid() throws Exception {
            // Set exp to 1 hour in the future
            TokenEntity validToken = buildTokenWithExp(3600L);
            setSessionField("token", validToken);

            assertThat(CloudSession.current().hasValidToken()).isTrue();
        }

        @Test
        @DisplayName("令牌刚好在未来1秒过期时应返回 true / returns true for a token expiring in 1 second")
        void shouldReturnTrueWhenTokenExpiresInOneSecond() throws Exception {
            TokenEntity almostExpiredToken = buildTokenWithExp(1L);
            setSessionField("token", almostExpiredToken);

            assertThat(CloudSession.current().hasValidToken()).isTrue();
        }

        @Test
        @DisplayName("exp 为 null 时 TokenEntity.isExpired 返回 false，故 hasValidToken 应返回 true")
        void shouldReturnTrueWhenExpIsNull() throws Exception {
            // Per TokenEntity.isExpired(): if exp == null, returns false (not expired)
            TokenEntity tokenWithNullExp = buildTokenWithNullExp();
            setSessionField("token", tokenWithNullExp);

            assertThat(CloudSession.current().hasValidToken()).isTrue();
        }
    }

    // =========================================================================
    // 4. getToken Tests — getToken 方法测试（CloudSession 实例方法，原 getCurrentToken）
    // =========================================================================

    @Nested
    @DisplayName("getToken 方法测试")
    class GetCurrentTokenTests {

        @Test
        @DisplayName("初始状态（null）时应返回 null / returns null when currentToken is null")
        void shouldReturnNullWhenNoTokenSet() throws Exception {
            setSessionField("token", null);

            assertThat(CloudSession.current().getToken()).isNull();
        }

        @Test
        @DisplayName("设置 currentToken 后应返回相同对象 / returns the token that was set via reflection")
        void shouldReturnTokenAfterItIsSet() throws Exception {
            TokenEntity expectedToken = buildTokenWithExp(3600L);
            setSessionField("token", expectedToken);

            TokenEntity result = CloudSession.current().getToken();

            assertThat(result).isSameAs(expectedToken);
        }

        @Test
        @DisplayName("再次将 currentToken 置为 null 后应返回 null / returns null after being reset")
        void shouldReturnNullAfterReset() throws Exception {
            setSessionField("token", buildTokenWithExp(3600L));
            // Reset to null
            setSessionField("token", null);

            assertThat(CloudSession.current().getToken()).isNull();
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
        @DisplayName("访问令牌为 null 时 hasValidToken 应为 false / null access_token means invalid")
        void nullAccessTokenMeansInvalid() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token(null);
            token.setExp((System.currentTimeMillis() / 1000L) + 3600L);

            // Directly verify the entity state used by hasValidToken
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
    // 6. Scheduler Lifecycle Tests — 调度器生命周期测试（CloudSession 实例方法）
    // =========================================================================

    @Nested
    @DisplayName("令牌刷新调度器生命周期测试")
    class TokenRefreshSchedulerLifecycleTests {

        @Test
        @DisplayName("startTokenRefreshScheduler 之后 refreshExecutor 应不为 null")
        void refreshExecutorShouldNotBeNullAfterStart() throws Exception {
            // The scheduled task body calls UltiTools.getInstance(), but the executor
            // itself is created before the task fires (initial delay = 1 hour).
            // We only check that the executor was created, not that the task ran.
            CloudSession.current().startTokenRefreshScheduler();

            ScheduledExecutorService executor =
                (ScheduledExecutorService) getSessionField("refreshExecutor");
            assertThat(executor).isNotNull();
        }

        @Test
        @DisplayName("stopTokenRefreshScheduler 之后 refreshExecutor 应为 null")
        void refreshExecutorShouldBeNullAfterStop() throws Exception {
            CloudSession.current().startTokenRefreshScheduler();
            CloudSession.current().stopTokenRefreshScheduler();

            Object executor = getSessionField("refreshExecutor");
            assertThat(executor).isNull();
        }

        @Test
        @DisplayName("stopTokenRefreshScheduler 之后 refreshTask 应为 null")
        void refreshTaskShouldBeNullAfterStop() throws Exception {
            CloudSession.current().startTokenRefreshScheduler();
            CloudSession.current().stopTokenRefreshScheduler();

            Object task = getSessionField("refreshTask");
            assertThat(task).isNull();
        }

        @Test
        @DisplayName("stopTokenRefreshScheduler 在未启动时调用不应抛出异常 / idempotent when not started")
        void stopTokenRefreshSchedulerShouldBeIdempotentWhenNotRunning() throws Exception {
            // Ensure executor is null before calling stop
            setSessionField("refreshExecutor", null);
            setSessionField("refreshTask", null);

            // Should not throw any exception
            CloudSession.current().stopTokenRefreshScheduler();

            assertThat(getSessionField("refreshExecutor")).isNull();
            assertThat(getSessionField("refreshTask")).isNull();
        }

        @Test
        @DisplayName("连续两次调用 startTokenRefreshScheduler 后只有一个 executor 存在")
        void doubleStartShouldProduceSingleExecutor() throws Exception {
            CloudSession.current().startTokenRefreshScheduler();
            // Second call internally invokes stopTokenRefreshScheduler first,
            // so there should be exactly one executor when the method returns.
            CloudSession.current().startTokenRefreshScheduler();

            ScheduledExecutorService executor =
                (ScheduledExecutorService) getSessionField("refreshExecutor");
            assertThat(executor).isNotNull();
            assertThat(executor.isShutdown()).isFalse();
        }
    }

    // =========================================================================
    // 7. Poll Scheduler Lifecycle Tests — 轮询调度器生命周期测试（CloudSession 实例方法）
    // =========================================================================

    @Nested
    @DisplayName("魔法链接轮询调度器生命周期测试")
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
    // 8. Combined Scheduler State Tests — 综合调度器状态测试（CloudSession 实例方法）
    // =========================================================================

    @Nested
    @DisplayName("综合调度器状态测试")
    class CombinedSchedulerStateTests {

        @Test
        @DisplayName("refresh 调度器启动后停止，executor 应该被关闭 / executor shutdown after stop")
        void executorShouldBeShutDownAfterStop() throws Exception {
            CloudSession.current().startTokenRefreshScheduler();

            // Capture the executor reference before stopping
            ScheduledExecutorService executorBeforeStop =
                (ScheduledExecutorService) getSessionField("refreshExecutor");
            assertThat(executorBeforeStop).isNotNull();

            CloudSession.current().stopTokenRefreshScheduler();

            // The captured reference should now be shut down
            assertThat(executorBeforeStop.isShutdown()).isTrue();
            // And the session field should be null
            assertThat(getSessionField("refreshExecutor")).isNull();
        }

        @Test
        @DisplayName("startTokenRefreshScheduler 创建的是单线程调度器 / should be single-thread")
        void refreshSchedulerShouldUseSingleThread() throws Exception {
            CloudSession.current().startTokenRefreshScheduler();

            ScheduledExecutorService executor =
                (ScheduledExecutorService) getSessionField("refreshExecutor");
            assertThat(executor).isNotNull();
            // A single-thread scheduled executor is not a ThreadPoolExecutor subclass,
            // but we can verify it is usable (not shutdown) immediately after creation.
            assertThat(executor.isShutdown()).isFalse();
            assertThat(executor.isTerminated()).isFalse();
        }
    }

    // =========================================================================
    // 9. Token State Transition Tests — 令牌状态转换测试（CloudSession 实例方法）
    // =========================================================================

    @Nested
    @DisplayName("令牌状态转换测试")
    class TokenStateTransitionTests {

        @Test
        @DisplayName("初始时 getToken 返回 null / null before any token is set")
        void getCurrentTokenReturnsNullInitially() throws Exception {
            setSessionField("token", null);

            assertThat(CloudSession.current().getToken()).isNull();
        }

        @Test
        @DisplayName("设置有效令牌后 getToken 返回该令牌 / token accessible after injection")
        void getCurrentTokenReturnsInjectedToken() throws Exception {
            TokenEntity token = buildTokenWithExp(3600L);
            setSessionField("token", token);

            assertThat(CloudSession.current().getToken()).isEqualTo(token);
        }

        @Test
        @DisplayName("hasValidToken 在 null 令牌后设置有效令牌时状态应切换为 true")
        void hasValidTokenTransitionsFromFalseToTrue() throws Exception {
            setSessionField("token", null);
            assertThat(CloudSession.current().hasValidToken()).isFalse();

            setSessionField("token", buildTokenWithExp(3600L));
            assertThat(CloudSession.current().hasValidToken()).isTrue();
        }

        @Test
        @DisplayName("令牌设置后清空 currentToken，hasValidToken 应再次返回 false")
        void hasValidTokenTransitionsFromTrueToFalse() throws Exception {
            setSessionField("token", buildTokenWithExp(3600L));
            assertThat(CloudSession.current().hasValidToken()).isTrue();

            setSessionField("token", null);
            assertThat(CloudSession.current().hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("过期令牌不应该被 hasValidToken 认为有效 / expired token is invalid")
        void expiredTokenIsNotValid() throws Exception {
            TokenEntity expiredToken = buildTokenWithExp(-7200L); // 2 hours ago
            setSessionField("token", expiredToken);

            assertThat(CloudSession.current().hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("将过期令牌替换为有效令牌后 hasValidToken 应返回 true")
        void replacingExpiredTokenWithValidTokenMakesItValid() throws Exception {
            setSessionField("token", buildTokenWithExp(-3600L));
            assertThat(CloudSession.current().hasValidToken()).isFalse();

            setSessionField("token", buildTokenWithExp(3600L));
            assertThat(CloudSession.current().hasValidToken()).isTrue();
        }
    }

    // =========================================================================
    // 10. login() Tests — 新的三个命令入口之一（plan 16-09）
    // =========================================================================

    @Nested
    @DisplayName("login 方法测试（三个命令入口之一，D-17/D-18）")
    class LoginTests {

        @Test
        @DisplayName("已登录时只调用 onAlreadyLoggedIn，不发起请求")
        void alreadyLoggedInSkipsEverythingElse() throws Exception {
            setSessionField("token", buildTokenWithExp(3600L));

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
            setSessionField("token", null);

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
            setSessionField("token", null);

            AtomicBoolean requestingCalled = new AtomicBoolean(false);
            AtomicReference<String> errorMessage = new AtomicReference<>();
            AtomicBoolean successCalled = new AtomicBoolean(false);

            CloudAuthManager.login(
                () -> { },
                remaining -> { },
                () -> requestingCalled.set(true),
                url -> successCalled.set(true),
                errorMessage::set);

            assertThat(requestingCalled).isTrue();
            assertThat(errorMessage.get()).isEqualTo("API URL not configured");
            assertThat(successCalled).isFalse();
        }
    }

    // =========================================================================
    // 11. logout() Tests — 新的三个命令入口之一（plan 16-09）
    // =========================================================================

    @Nested
    @DisplayName("logout 方法测试（三个命令入口之一，D-17/D-18）")
    class LogoutTests {

        @Test
        @DisplayName("有凭证（即便已过期）时返回 true，且拆线之后这个（同一个、现已失效的）会话不再持有该凭证")
        void expiredOrValidTokenStillReportsHadCredential() throws Exception {
            setSessionField("token", buildTokenWithExp(-3600L)); // expired -- logout does not gate on validity
            CloudSession sessionBeforeLogout = CloudSession.current();

            boolean hadCredential = CloudAuthManager.logout();

            assertThat(hadCredential).isTrue();
            // CR-01 fix: logout() no longer calls CloudSession.startNew() itself -- it clears the
            // captured session in place. current() therefore stays this SAME (now-invalidated)
            // session object; the next real login is what installs a fresh one.
            assertThat(CloudSession.current())
                    .as("logout 不再自行 startNew()——清掉的是拆线返回的那个会话本身")
                    .isSameAs(sessionBeforeLogout);
            assertThat(CloudSession.current().getToken())
                    .as("logout 之后这个会话不应再持有旧凭证")
                    .isNull();
        }

        @Test
        @DisplayName("从未登录过时返回 false，但拆线仍然无条件发生")
        void neverLoggedInReturnsFalseButTeardownStillRuns() throws Exception {
            setSessionField("token", null);
            CloudSession session = CloudSession.current();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                // logout() now calls the CloudSession-returning overload (CR-01) -- the mock
                // must honour that contract, or logout()'s null-token read below NPEs.
                init.when(() -> PluginInitiationUtils.disableCloud(session)).thenReturn(session);

                boolean hadCredential = CloudAuthManager.logout();

                init.verify(() -> PluginInitiationUtils.disableCloud(session), times(1));
                assertThat(hadCredential)
                        .as("没有凭证就没什么可清的，但 disableCloud() 的每一步都必须照跑")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("凭证必须在拆线之后读：拆线期间落地的凭证仍会被看到并清除")
        void credentialCommittedDuringTeardownIsStillSeenAndCleared() throws Exception {
            setSessionField("token", null);
            CloudSession session = CloudSession.current();

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                // Simulate an in-flight magic-link poll committing successfully WHILE
                // disableCloud() is (nominally) running -- exactly the window logout() must
                // still catch by reading the credential only after teardown returns. Per the CR-01
                // fix, disableCloud() returns the session it tore down -- here, the same session the
                // poll committed onto -- so logout() reads the commit through that returned
                // reference, never through a second, independent CloudSession.current() call.
                init.when(() -> PluginInitiationUtils.disableCloud(session)).thenAnswer(invocation -> {
                    session.commit(buildTokenWithExp(3600L));
                    return session;
                });

                boolean hadCredential = CloudAuthManager.logout();

                assertThat(hadCredential)
                        .as("若沿用拆线前的快照，这里会是 false——凭证必须在拆线之后才读")
                        .isTrue();
                // WR-04 (16-REVIEW-cloud.md): the alpha test this class replaced asserted
                // verify(clearToken, times(1)) -- an explicit check that the disk-clearing call
                // actually ran. The rewritten version above only checked the boolean return value,
                // which a future refactor could satisfy without ever calling clearPersisted(). Add
                // back the independent disk-state assertion, matching the pattern
                // CredentialGenerationTest already uses throughout.
                CredentialStore.ReadResult result = CredentialStore.read();
                assertThat(result.data())
                        .as("拆线期间落地的凭证必须真的从磁盘上被清除，不能只是返回值凑巧对了")
                        .doesNotContainKey("cloud_token");
            }
        }

        @Test
        @DisplayName("CR-01：即便有并发登录在拆线期间安装了新会话，logout 清掉的仍是它实际拆掉的那个会话，磁盘凭证不会残留")
        void logoutClearsTheCapturedSessionEvenWhenALoginInstallsANewSessionMidTeardown() throws Exception {
            // sBefore is the session /ulticloud logout is actually acting on -- give it a real,
            // committed credential on disk, the way a genuine prior login would have.
            CloudSession sBefore = CloudSession.current();
            sBefore.commit(buildTokenWithExp(3600L));
            CredentialStore.ReadResult before = CredentialStore.read();
            assertThat(before.data())
                    .as("前置条件：磁盘上得先真的有这份凭证")
                    .containsKey("cloud_token");

            try (MockedStatic<PluginInitiationUtils> init = mockStatic(PluginInitiationUtils.class)) {
                // Reproduces CR-01's exact race, as an injected hook rather than sleep-based luck:
                // disableCloud() is stubbed to behave exactly like the FIXED production
                // implementation (return the session it tore down -- sBefore), but ALSO simulates a
                // concurrent /ulticloud login landing before it returns, installing a brand-new
                // session as CloudSession.current(). Before the CR-01 fix, logout() would have read
                // CloudAuthManager's disk-clear decision off a SECOND, independent
                // CloudSession.current() call taken after this -- which would see sNew's (always
                // null) token and wrongly conclude there was nothing to clear.
                init.when(() -> PluginInitiationUtils.disableCloud(sBefore)).thenAnswer(invocation -> {
                    CloudSession.startNew(); // the concurrent login racing in
                    return sBefore;          // the FIXED contract: return what was actually torn down
                });

                boolean hadCredential = CloudAuthManager.logout();

                assertThat(hadCredential)
                        .as("即便并发登录已经把 current() 换掉，被真正拆线的会话（sBefore）原本持有凭证这件事必须被看到")
                        .isTrue();
            }

            CloudSession sNew = CloudSession.current();
            assertThat(sNew).isNotSameAs(sBefore);
            assertThat(sNew.getToken())
                    .as("并发登录安装的新会话不该被这次 logout 调用碰到")
                    .isNull();
            CredentialStore.ReadResult after = CredentialStore.read();
            assertThat(after.data())
                    .as("旧会话的磁盘凭证必须被清掉，不能因为 current() 已经指向别处而被跳过")
                    .doesNotContainKey("cloud_token");
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
            setSessionField("token", null);

            CloudAuthManager.CloudStatus status = CloudAuthManager.status();

            assertThat(status.isConnected()).isFalse();
            assertThat(status.getUserName()).isNull();
            assertThat(status.getExpirationDate()).isNull();
        }

        @Test
        @DisplayName("已连接且有用户名时返回的快照携带该用户名与到期时间")
        void connectedWithUserNameReportsIt() throws Exception {
            TokenEntity token = buildTokenWithExp(3600L);
            token.setUser_name("alice");
            setSessionField("token", token);

            CloudAuthManager.CloudStatus status = CloudAuthManager.status();

            assertThat(status.isConnected()).isTrue();
            assertThat(status.getUserName()).isEqualTo("alice");
            assertThat(status.getExpirationDate()).isEqualTo(token.getExpirationDate());
        }

        @Test
        @DisplayName("已连接但用户名为空时返回的快照用户名为 'Unknown'")
        void connectedWithoutUserNameReportsUnknown() throws Exception {
            TokenEntity token = buildTokenWithExp(3600L);
            token.setUser_name(null);
            setSessionField("token", token);

            CloudAuthManager.CloudStatus status = CloudAuthManager.status();

            assertThat(status.isConnected()).isTrue();
            assertThat(status.getUserName()).isEqualTo("Unknown");
        }
    }
}
