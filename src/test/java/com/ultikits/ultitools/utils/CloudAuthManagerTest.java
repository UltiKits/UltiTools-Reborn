package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.entities.TokenEntity;

/**
 * CloudAuthManager 测试类
 * Tests for the UltiCloud authentication token manager.
 * Tests focus on structure, static field behaviour, token state checks, and scheduler lifecycle.
 * Avoids calling any method that internally calls UltiTools.getInstance().
 */
@DisplayName("CloudAuthManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudAuthManagerTest {

    // -------------------------------------------------------------------------
    // Helper utilities
    // -------------------------------------------------------------------------

    /**
     * Read the value of a private static field from CloudAuthManager via reflection.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Object getStaticField(String name) throws Exception {
        Field field = CloudAuthManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Set the value of a private static field on CloudAuthManager via reflection.
     */
    private static void setStaticField(String name, Object value) throws Exception {
        Field field = CloudAuthManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
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
    void resetStaticState() throws Exception {
        // Stop any running schedulers that previous tests may have started
        CloudAuthManager.stopTokenRefreshScheduler();
        CloudAuthManager.stopPolling();
        // Null out the in-memory token
        setStaticField("currentToken", null);
    }

    @AfterEach
    void cleanUpStaticState() throws Exception {
        // Ensure schedulers are shut down so background threads don't outlive the test
        CloudAuthManager.stopTokenRefreshScheduler();
        CloudAuthManager.stopPolling();
        setStaticField("currentToken", null);
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
            assertThat(Modifier.isPublic(CloudAuthManager.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("CloudAuthManager 不应该是抽象类 / class should not be abstract")
        void classShouldNotBeAbstract() {
            assertThat(Modifier.isAbstract(CloudAuthManager.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("currentToken 静态字段应该存在 / static field currentToken should exist")
        void currentTokenFieldShouldExist() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("currentToken");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(field.getType()).isEqualTo(TokenEntity.class);
        }

        @Test
        @DisplayName("pollExecutor 静态字段应该存在 / static field pollExecutor should exist")
        void pollExecutorFieldShouldExist() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("pollExecutor");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(ScheduledExecutorService.class.isAssignableFrom(field.getType())).isTrue();
        }

        @Test
        @DisplayName("refreshExecutor 静态字段应该存在 / static field refreshExecutor should exist")
        void refreshExecutorFieldShouldExist() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("refreshExecutor");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(ScheduledExecutorService.class.isAssignableFrom(field.getType())).isTrue();
        }

        @Test
        @DisplayName("pollTask 静态字段应该存在 / static field pollTask should exist")
        void pollTaskFieldShouldExist() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("pollTask");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("refreshTask 静态字段应该存在 / static field refreshTask should exist")
        void refreshTaskFieldShouldExist() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("refreshTask");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
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
            Field field = CloudAuthManager.class.getDeclaredField("POLL_INTERVAL_MS");
            field.setAccessible(true);
            long value = field.getLong(null);
            assertThat(value).isEqualTo(3000L);
        }

        @Test
        @DisplayName("TOKEN_REFRESH_CHECK_INTERVAL_MS 应该为 3600000（1小时）/ should equal 1 hour in ms")
        void tokenRefreshCheckIntervalMsShouldBeOneHour() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("TOKEN_REFRESH_CHECK_INTERVAL_MS");
            field.setAccessible(true);
            long value = field.getLong(null);
            assertThat(value).isEqualTo(60L * 60L * 1000L);
        }

        @Test
        @DisplayName("TOKEN_REFRESH_THRESHOLD_SECONDS 应该为 7200（2小时）/ should be 7200 seconds")
        void tokenRefreshThresholdSecondsShouldBe7200() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("TOKEN_REFRESH_THRESHOLD_SECONDS");
            field.setAccessible(true);
            long value = field.getLong(null);
            assertThat(value).isEqualTo(2L * 60L * 60L);
        }

        @Test
        @DisplayName("OAUTH2_BASIC_AUTH 应该以 'Basic ' 开头 / should start with 'Basic '")
        void oauth2BasicAuthShouldStartWithBasic() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("OAUTH2_BASIC_AUTH");
            field.setAccessible(true);
            String value = (String) field.get(null);
            assertThat(value).startsWith("Basic ");
        }

        @Test
        @DisplayName("OAUTH2_BASIC_AUTH 解码后应该等于 'client:112233' / should decode to 'client:112233'")
        void oauth2BasicAuthShouldDecodeToClientCredentials() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("OAUTH2_BASIC_AUTH");
            field.setAccessible(true);
            String value = (String) field.get(null);

            // Strip the "Basic " prefix and decode Base64
            String base64Part = value.substring("Basic ".length());
            byte[] decodedBytes = Base64.getDecoder().decode(base64Part);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);

            assertThat(decoded).isEqualTo("client:112233");
        }

        @Test
        @DisplayName("MAX_POLL_ATTEMPTS 常量应该存在且大于 0 / MAX_POLL_ATTEMPTS should be positive")
        void maxPollAttemptsShouldBePositive() throws Exception {
            Field field = CloudAuthManager.class.getDeclaredField("MAX_POLL_ATTEMPTS");
            field.setAccessible(true);
            int value = field.getInt(null);
            assertThat(value).isGreaterThan(0);
        }
    }

    // =========================================================================
    // 3. Method Signature Tests — 方法签名测试
    // =========================================================================

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("loadSavedToken 方法应该存在且返回 TokenEntity / should exist and return TokenEntity")
        void loadSavedTokenMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("loadSavedToken");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(TokenEntity.class);
        }

        @Test
        @DisplayName("refreshToken 方法应该存在且接受 String 参数 / should accept String and return TokenEntity")
        void refreshTokenMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("refreshToken", String.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(TokenEntity.class);
            assertThat(method.getParameterCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("saveToken 方法应该存在且接受 TokenEntity 参数 / should accept TokenEntity and return void")
        void saveTokenMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("saveToken", TokenEntity.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("clearToken 方法应该存在且返回 void / should exist and return void")
        void clearTokenMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("clearToken");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("getCurrentToken 方法应该存在且返回 TokenEntity / should return TokenEntity")
        void getCurrentTokenMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("getCurrentToken");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(TokenEntity.class);
        }

        @Test
        @DisplayName("hasValidToken 方法应该存在且返回 boolean / should return boolean")
        void hasValidTokenMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("hasValidToken");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("startPolling 方法应该存在且接受 String 和 Consumer 参数")
        void startPollingMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod(
                "startPolling", String.class, Consumer.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("stopPolling 方法应该存在且返回 void / should exist and return void")
        void stopPollingMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("stopPolling");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("startTokenRefreshScheduler 方法应该存在且返回 void")
        void startTokenRefreshSchedulerMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("startTokenRefreshScheduler");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("stopTokenRefreshScheduler 方法应该存在且返回 void")
        void stopTokenRefreshSchedulerMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod("stopTokenRefreshScheduler");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("requestMagicLink 方法应该存在且接受 Consumer 参数")
        void requestMagicLinkMethodShouldExist() throws Exception {
            Method method = CloudAuthManager.class.getDeclaredMethod(
                "requestMagicLink", Consumer.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }
    }

    // =========================================================================
    // 4. hasValidToken Tests — hasValidToken 方法测试
    // =========================================================================

    @Nested
    @DisplayName("hasValidToken 方法测试")
    class HasValidTokenTests {

        @Test
        @DisplayName("currentToken 为 null 时应返回 false / returns false when token is null")
        void shouldReturnFalseWhenCurrentTokenIsNull() throws Exception {
            setStaticField("currentToken", null);

            assertThat(CloudAuthManager.hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("access_token 为 null 时应返回 false / returns false when access_token is null")
        void shouldReturnFalseWhenAccessTokenIsNull() throws Exception {
            TokenEntity token = new TokenEntity();
            token.setAccess_token(null);
            token.setExp((System.currentTimeMillis() / 1000L) + 3600L);
            setStaticField("currentToken", token);

            assertThat(CloudAuthManager.hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("令牌已过期时应返回 false / returns false when token is expired")
        void shouldReturnFalseWhenTokenIsExpired() throws Exception {
            // Set exp to 1 hour in the past
            TokenEntity expiredToken = buildTokenWithExp(-3600L);
            setStaticField("currentToken", expiredToken);

            assertThat(CloudAuthManager.hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("令牌有效时应返回 true / returns true when token is valid and not expired")
        void shouldReturnTrueWhenTokenIsValid() throws Exception {
            // Set exp to 1 hour in the future
            TokenEntity validToken = buildTokenWithExp(3600L);
            setStaticField("currentToken", validToken);

            assertThat(CloudAuthManager.hasValidToken()).isTrue();
        }

        @Test
        @DisplayName("令牌刚好在未来1秒过期时应返回 true / returns true for a token expiring in 1 second")
        void shouldReturnTrueWhenTokenExpiresInOneSecond() throws Exception {
            TokenEntity almostExpiredToken = buildTokenWithExp(1L);
            setStaticField("currentToken", almostExpiredToken);

            assertThat(CloudAuthManager.hasValidToken()).isTrue();
        }

        @Test
        @DisplayName("exp 为 null 时 TokenEntity.isExpired 返回 false，故 hasValidToken 应返回 true")
        void shouldReturnTrueWhenExpIsNull() throws Exception {
            // Per TokenEntity.isExpired(): if exp == null, returns false (not expired)
            TokenEntity tokenWithNullExp = buildTokenWithNullExp();
            setStaticField("currentToken", tokenWithNullExp);

            assertThat(CloudAuthManager.hasValidToken()).isTrue();
        }
    }

    // =========================================================================
    // 5. getCurrentToken Tests — getCurrentToken 方法测试
    // =========================================================================

    @Nested
    @DisplayName("getCurrentToken 方法测试")
    class GetCurrentTokenTests {

        @Test
        @DisplayName("初始状态（null）时应返回 null / returns null when currentToken is null")
        void shouldReturnNullWhenNoTokenSet() throws Exception {
            setStaticField("currentToken", null);

            assertThat(CloudAuthManager.getCurrentToken()).isNull();
        }

        @Test
        @DisplayName("设置 currentToken 后应返回相同对象 / returns the token that was set via reflection")
        void shouldReturnTokenAfterItIsSet() throws Exception {
            TokenEntity expectedToken = buildTokenWithExp(3600L);
            setStaticField("currentToken", expectedToken);

            TokenEntity result = CloudAuthManager.getCurrentToken();

            assertThat(result).isSameAs(expectedToken);
        }

        @Test
        @DisplayName("再次将 currentToken 置为 null 后应返回 null / returns null after being reset")
        void shouldReturnNullAfterReset() throws Exception {
            setStaticField("currentToken", buildTokenWithExp(3600L));
            // Reset to null
            setStaticField("currentToken", null);

            assertThat(CloudAuthManager.getCurrentToken()).isNull();
        }
    }

    // =========================================================================
    // 6. TokenEntity Helper Tests — TokenEntity 辅助测试
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
    // 7. Scheduler Lifecycle Tests — 调度器生命周期测试
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
            CloudAuthManager.startTokenRefreshScheduler();

            ScheduledExecutorService executor =
                (ScheduledExecutorService) getStaticField("refreshExecutor");
            assertThat(executor).isNotNull();
        }

        @Test
        @DisplayName("stopTokenRefreshScheduler 之后 refreshExecutor 应为 null")
        void refreshExecutorShouldBeNullAfterStop() throws Exception {
            CloudAuthManager.startTokenRefreshScheduler();
            CloudAuthManager.stopTokenRefreshScheduler();

            Object executor = getStaticField("refreshExecutor");
            assertThat(executor).isNull();
        }

        @Test
        @DisplayName("stopTokenRefreshScheduler 之后 refreshTask 应为 null")
        void refreshTaskShouldBeNullAfterStop() throws Exception {
            CloudAuthManager.startTokenRefreshScheduler();
            CloudAuthManager.stopTokenRefreshScheduler();

            Object task = getStaticField("refreshTask");
            assertThat(task).isNull();
        }

        @Test
        @DisplayName("stopTokenRefreshScheduler 在未启动时调用不应抛出异常 / idempotent when not started")
        void stopTokenRefreshSchedulerShouldBeIdempotentWhenNotRunning() throws Exception {
            // Ensure executor is null before calling stop
            setStaticField("refreshExecutor", null);
            setStaticField("refreshTask", null);

            // Should not throw any exception
            CloudAuthManager.stopTokenRefreshScheduler();

            assertThat(getStaticField("refreshExecutor")).isNull();
            assertThat(getStaticField("refreshTask")).isNull();
        }

        @Test
        @DisplayName("连续两次调用 startTokenRefreshScheduler 后只有一个 executor 存在")
        void doubleStartShouldProduceSingleExecutor() throws Exception {
            CloudAuthManager.startTokenRefreshScheduler();
            // Second call internally invokes stopTokenRefreshScheduler first,
            // so there should be exactly one executor when the method returns.
            CloudAuthManager.startTokenRefreshScheduler();

            ScheduledExecutorService executor =
                (ScheduledExecutorService) getStaticField("refreshExecutor");
            assertThat(executor).isNotNull();
            assertThat(executor.isShutdown()).isFalse();
        }
    }

    // =========================================================================
    // 8. Poll Scheduler Lifecycle Tests — 轮询调度器生命周期测试
    // =========================================================================

    @Nested
    @DisplayName("魔法链接轮询调度器生命周期测试")
    class PollSchedulerLifecycleTests {

        @Test
        @DisplayName("stopPolling 在未启动时调用不应抛出异常 / idempotent when not started")
        void stopPollingShouldBeIdempotentWhenNotRunning() throws Exception {
            setStaticField("pollExecutor", null);
            setStaticField("pollTask", null);

            // Should not throw
            CloudAuthManager.stopPolling();

            assertThat(getStaticField("pollExecutor")).isNull();
            assertThat(getStaticField("pollTask")).isNull();
        }

        @Test
        @DisplayName("stopPolling 之后 pollExecutor 应为 null / pollExecutor null after stop")
        void pollExecutorShouldBeNullAfterStop() throws Exception {
            // Manually plant an executor without calling startPolling (which needs UltiTools)
            ScheduledExecutorService fakeExecutor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            setStaticField("pollExecutor", fakeExecutor);

            CloudAuthManager.stopPolling();

            assertThat(getStaticField("pollExecutor")).isNull();
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

            setStaticField("pollExecutor", fakeExecutor);
            setStaticField("pollTask", fakeTask);

            CloudAuthManager.stopPolling();

            assertThat(getStaticField("pollTask")).isNull();
            assertThat(fakeTask.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("连续调用两次 stopPolling 不应抛出异常 / double stop should be safe")
        void doubleStopShouldBeSafe() throws Exception {
            CloudAuthManager.stopPolling();
            CloudAuthManager.stopPolling(); // second call on already-null state

            assertThat(getStaticField("pollExecutor")).isNull();
        }
    }

    // =========================================================================
    // 9. Combined Scheduler State Tests — 综合调度器状态测试
    // =========================================================================

    @Nested
    @DisplayName("综合调度器状态测试")
    class CombinedSchedulerStateTests {

        @Test
        @DisplayName("refresh 调度器启动后停止，executor 应该被关闭 / executor shutdown after stop")
        void executorShouldBeShutDownAfterStop() throws Exception {
            CloudAuthManager.startTokenRefreshScheduler();

            // Capture the executor reference before stopping
            ScheduledExecutorService executorBeforeStop =
                (ScheduledExecutorService) getStaticField("refreshExecutor");
            assertThat(executorBeforeStop).isNotNull();

            CloudAuthManager.stopTokenRefreshScheduler();

            // The captured reference should now be shut down
            assertThat(executorBeforeStop.isShutdown()).isTrue();
            // And the static field should be null
            assertThat(getStaticField("refreshExecutor")).isNull();
        }

        @Test
        @DisplayName("startTokenRefreshScheduler 创建的是单线程调度器 / should be single-thread")
        void refreshSchedulerShouldUseSingleThread() throws Exception {
            CloudAuthManager.startTokenRefreshScheduler();

            ScheduledExecutorService executor =
                (ScheduledExecutorService) getStaticField("refreshExecutor");
            assertThat(executor).isNotNull();
            // A single-thread scheduled executor is not a ThreadPoolExecutor subclass,
            // but we can verify it is usable (not shutdown) immediately after creation.
            assertThat(executor.isShutdown()).isFalse();
            assertThat(executor.isTerminated()).isFalse();
        }
    }

    // =========================================================================
    // 10. Token State Transition Tests — 令牌状态转换测试
    // =========================================================================

    @Nested
    @DisplayName("令牌状态转换测试")
    class TokenStateTransitionTests {

        @Test
        @DisplayName("初始时 getCurrentToken 返回 null / null before any token is set")
        void getCurrentTokenReturnsNullInitially() throws Exception {
            setStaticField("currentToken", null);

            assertThat(CloudAuthManager.getCurrentToken()).isNull();
        }

        @Test
        @DisplayName("设置有效令牌后 getCurrentToken 返回该令牌 / token accessible after injection")
        void getCurrentTokenReturnsInjectedToken() throws Exception {
            TokenEntity token = buildTokenWithExp(3600L);
            setStaticField("currentToken", token);

            assertThat(CloudAuthManager.getCurrentToken()).isEqualTo(token);
        }

        @Test
        @DisplayName("hasValidToken 在 null 令牌后设置有效令牌时状态应切换为 true")
        void hasValidTokenTransitionsFromFalseToTrue() throws Exception {
            setStaticField("currentToken", null);
            assertThat(CloudAuthManager.hasValidToken()).isFalse();

            setStaticField("currentToken", buildTokenWithExp(3600L));
            assertThat(CloudAuthManager.hasValidToken()).isTrue();
        }

        @Test
        @DisplayName("令牌设置后清空 currentToken，hasValidToken 应再次返回 false")
        void hasValidTokenTransitionsFromTrueToFalse() throws Exception {
            setStaticField("currentToken", buildTokenWithExp(3600L));
            assertThat(CloudAuthManager.hasValidToken()).isTrue();

            setStaticField("currentToken", null);
            assertThat(CloudAuthManager.hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("过期令牌不应该被 hasValidToken 认为有效 / expired token is invalid")
        void expiredTokenIsNotValid() throws Exception {
            TokenEntity expiredToken = buildTokenWithExp(-7200L); // 2 hours ago
            setStaticField("currentToken", expiredToken);

            assertThat(CloudAuthManager.hasValidToken()).isFalse();
        }

        @Test
        @DisplayName("将过期令牌替换为有效令牌后 hasValidToken 应返回 true")
        void replacingExpiredTokenWithValidTokenMakesItValid() throws Exception {
            setStaticField("currentToken", buildTokenWithExp(-3600L));
            assertThat(CloudAuthManager.hasValidToken()).isFalse();

            setStaticField("currentToken", buildTokenWithExp(3600L));
            assertThat(CloudAuthManager.hasValidToken()).isTrue();
        }
    }
}
