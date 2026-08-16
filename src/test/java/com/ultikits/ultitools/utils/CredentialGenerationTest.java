package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;

/**
 * 凭证代际：让在途的异步凭证操作在 logout 之后无法把凭证写回来。
 *
 * <p>问题的根子是「取消 ≠ 失效」。{@code stopTokenRefreshScheduler()} 用的是
 * {@code cancel(false)} 加 {@code shutdown()}，两者都只承诺不再调度新的执行，对一个
 * 已经进入 HTTP 请求的刷新任务毫无约束；而 {@code refreshToken()} 在返回**之前**就
 * {@code saveToken()} 写盘。于是这样的时序完全成立：
 *
 * <pre>
 *   1. 刷新任务发出 HTTP 请求（网络往返，秒级）
 *   2. 管理员 /ulticloud logout → 停调度器 → clearToken() 清掉 data.json
 *   3. HTTP 返回 → saveToken() 把新凭证写回 data.json
 *   4. 重启服务器 → 读到有效凭证 → 自动登录
 * </pre>
 *
 * <p>结果是 logout 这条命令没有效果，而它恰恰是一条安全语义的命令。magic-link
 * 轮询有完全相同的形状，而且更彻底——它还会 {@code enableCloud()} 加
 * {@code initWebsocket()}，直接把服务器登回去。
 */
@DisplayName("凭证代际（在途操作的失效判据）")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // 需反射复位 UltiTools 单例（#250）
class CredentialGenerationTest {

    @TempDir
    File dataFolder;

    @BeforeEach
    void setUp() {
        Logger mockLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getDataFolder()).thenReturn(dataFolder);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private TokenEntity someToken() {
        TokenEntity token = new TokenEntity();
        token.setAccess_token("late-arriving-access-token");
        token.setRefresh_token("late-arriving-refresh-token");
        token.setExp((System.currentTimeMillis() / 1000) + 3600);
        return token;
    }

    @Nested
    @DisplayName("拆线之后到达的结果必须被丢弃")
    class LateResultsAreDiscarded {

        @Test
        @DisplayName("代际未变时，提交成功")
        void commitSucceedsWhenGenerationUnchanged() throws Exception {
            long generation = CloudAuthManager.currentCredentialGeneration();

            boolean committed = CloudAuthManager.commitTokenIfCurrent(someToken(), generation);

            assertThat(committed).isTrue();
            assertThat(CloudAuthManager.getCurrentToken()).isNotNull();
        }

        @Test
        @DisplayName("代际已被 invalidate 递增时，提交必须被拒绝且不落盘")
        void commitIsRejectedAfterInvalidation() throws Exception {
            // 在途操作出发时记下的代际
            long generationAtStart = CloudAuthManager.currentCredentialGeneration();

            // logout 期间：拆线路径让一切在途凭证操作作废
            CloudAuthManager.invalidateCredentialOperations();
            CloudAuthManager.clearToken();

            // HTTP 请求这时才返回
            boolean committed = CloudAuthManager.commitTokenIfCurrent(someToken(), generationAtStart);

            assertThat(committed)
                    .as("迟到的刷新结果不得把凭证写回来——否则 logout 等于没执行")
                    .isFalse();
            assertThat(CloudAuthManager.getCurrentToken())
                    .as("内存中的凭证必须仍是空的")
                    .isNull();
            assertThat(new File(dataFolder, "data.json"))
                    .as("磁盘上不该留下可用于重启后自动重连的凭证")
                    .satisfiesAnyOf(
                            f -> assertThat(f).doesNotExist(),
                            f -> assertThat(f).content().doesNotContain("late-arriving-access-token"));
        }

        @Test
        @DisplayName("每次 invalidate 都推进代际，多次 logout 不会让旧结果重新变得有效")
        void generationIsMonotonic() {
            long first = CloudAuthManager.currentCredentialGeneration();
            CloudAuthManager.invalidateCredentialOperations();
            long second = CloudAuthManager.currentCredentialGeneration();
            CloudAuthManager.invalidateCredentialOperations();
            long third = CloudAuthManager.currentCredentialGeneration();

            assertThat(second).isGreaterThan(first);
            assertThat(third).isGreaterThan(second);
        }
    }
}
