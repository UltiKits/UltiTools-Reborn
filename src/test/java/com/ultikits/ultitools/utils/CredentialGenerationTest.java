package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
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

    /**
     * The exact temporary-file name {@link CredentialStore} always creates for a write before
     * atomically renaming it onto {@code data.json} -- see {@code CredentialStore.TEMP_FILE_NAME},
     * which is private and so cannot be referenced symbolically from this package-mate.
     */
    private static final String TEMP_FILE_NAME = "data.json.tmp";

    /**
     * Registers an OS-level watch on {@code dir} for {@code ENTRY_CREATE} events. Used to count
     * {@link CredentialStore} writes independently of the call sites that trigger them: every
     * {@code CredentialStore} write creates {@value #TEMP_FILE_NAME} fresh (it is renamed away by
     * the following atomic move), so counting its creations counts real writes -- "observing the
     * file's modification sequence", per this task's own instructions, rather than trusting a
     * self-reported call count or inferring anything from elapsed time.
     */
    private static WatchService newTempFileWatcher(File dir) throws Exception {
        WatchService watchService = FileSystems.getDefault().newWatchService();
        dir.toPath().register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        return watchService;
    }

    /**
     * Drains {@code watchService} for up to {@code timeout}, counting {@code ENTRY_CREATE} events
     * whose file name is {@value #TEMP_FILE_NAME}. All writes in every test below happen
     * synchronously, strictly before this is called, so the events are already queued by the
     * kernel -- {@code timeout} only bounds how long this waits for them to be delivered and
     * drained, it is not a sleep-and-hope for an async operation to finish.
     */
    private static int countTempFileCreations(WatchService watchService, Duration timeout) throws InterruptedException {
        int count = 0;
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            WatchKey key = watchService.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (key == null) {
                break;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    Object context = event.context();
                    if (context != null && context.toString().equals(TEMP_FILE_NAME)) {
                        count++;
                    }
                }
            }
            if (!key.reset()) {
                break;
            }
        }
        return count;
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

    /**
     * D-14: one named, deterministic test per timing from issue #298's "already fixed" table
     * (five rows). Each stages its interleaving with explicit, controllable call ordering --
     * never a sleep -- and asserts both the final {@code data.json} content and the number of
     * writes {@link CredentialStore} actually performed, observed at the filesystem level via
     * {@link #newTempFileWatcher(File)}/{@link #countTempFileCreations(WatchService, Duration)}
     * so a failure names which timing broke rather than reporting a generic race. This is
     * explicitly not the shape D-14 rules out
     * ({@code DataStoreManagerTest#concurrentReadWriteShouldBeSafe}: threads plus a latch plus no
     * assertion) -- every test here ends with assertions on content and write count.
     */
    @Nested
    @DisplayName("issue #298's five named timings (D-14)")
    class FiveNamedTimingsFromIssue298 {

        @Test
        @DisplayName("Timing 1 (#298 row 1): a refresh in flight when logout happens must not write its late result")
        void refreshInFlightWhenLogoutHappens_lateCommitRejectedNoExtraWrite() throws Exception {
            // #298 row 1: refreshToken() saves before returning, and stopTokenRefreshScheduler()'s
            // cancel(false) does not interrupt an in-flight refresh -- the fix is the generation
            // guard alone, since the scheduler cannot be relied on to stop the in-flight call.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                long generationAtRefreshStart = CloudAuthManager.currentCredentialGeneration();

                // logout happens while the refresh HTTP call is "in flight"
                CloudAuthManager.invalidateCredentialOperations();
                CloudAuthManager.clearToken(); // the only write this scenario should perform

                // the refresh call "returns" only now, carrying the generation captured before logout
                boolean committed = CloudAuthManager.commitTokenIfCurrent(someToken(), generationAtRefreshStart);

                int writes = countTempFileCreations(watcher, Duration.ofMillis(800));

                assertThat(committed)
                        .as("a refresh started before logout must be rejected once it lands after logout")
                        .isFalse();
                assertThat(writes)
                        .as("only clearToken()'s own write may have happened -- the rejected commit must write nothing")
                        .isEqualTo(1);
                CredentialStore.ReadResult result = CredentialStore.read();
                assertThat(result.isParsed()).isTrue();
                assertThat(result.data()).as("no cloud_token key may survive").doesNotContainKey("cloud_token");
            }
        }

        @Test
        @DisplayName("Timing 2 (#298 row 2): a poll completion arriving after logout is rejected even without an explicit poll-stop call")
        void pollCompletionAfterLogoutWithoutExplicitStop_commitRejected() throws Exception {
            // #298 row 2: disableCloud() never called stopPolling(), and the poller's completed
            // branch reconnects on its own. This proves the file-write guard is sufficient defense
            // in depth for the credential-write half of that bug even when no stop call is made at
            // all -- this scenario deliberately never calls stopPolling().
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                long generationAtRequestStart = CloudAuthManager.currentCredentialGeneration();

                CloudAuthManager.invalidateCredentialOperations();
                CloudAuthManager.clearToken(); // the only write this scenario should perform

                boolean committed = CloudAuthManager.commitTokenIfCurrent(someToken(), generationAtRequestStart);

                int writes = countTempFileCreations(watcher, Duration.ofMillis(800));

                assertThat(committed)
                        .as("a poll result captured before logout must be rejected even without an explicit stopPolling() call")
                        .isFalse();
                assertThat(writes).as("only the logout write may have happened").isEqualTo(1);
                CredentialStore.ReadResult result = CredentialStore.read();
                assertThat(result.isParsed()).isTrue();
                assertThat(result.data()).doesNotContainKey("cloud_token");
            }
        }

        @Test
        @DisplayName("Timing 3 (#298 row 3): a login that lands after teardown completes is not wiped by the earlier logout")
        void loginAfterTeardownCompletes_isNotClearedByLogout() throws Exception {
            // #298 row 3: the old code snapshotted the credential BEFORE teardown, so a credential
            // committed during teardown was not seen by the snapshot and survived clearing. The
            // read-modify-write is now atomic under one lock (CredentialStore), so this proves the
            // mirror-image positive case: a legitimate login that starts only after teardown has
            // fully finished must not be collateral damage from the teardown's own write.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                CloudAuthManager.invalidateCredentialOperations();
                CloudAuthManager.clearToken(); // write 1 -- teardown, nothing to clear yet

                long generationAfterTeardown = CloudAuthManager.currentCredentialGeneration();
                boolean committed = CloudAuthManager.commitTokenIfCurrent(someToken(), generationAfterTeardown); // write 2

                int writes = countTempFileCreations(watcher, Duration.ofMillis(800));

                assertThat(committed)
                        .as("a login started after teardown completed must succeed")
                        .isTrue();
                assertThat(writes).isEqualTo(2);
                CredentialStore.ReadResult result = CredentialStore.read();
                assertThat(result.isParsed()).isTrue();
                assertThat(result.data()).containsKey("cloud_token");
                assertThat(CloudAuthManager.getCurrentToken()).isNotNull();
            }
        }

        @Test
        @DisplayName("Timing 4 (#298 row 4): a generation captured before a blocking call is honored, not silently re-read mid-flight")
        void generationCapturedBeforeBlockingCall_isHonoredNotReReadMidFlight() throws Exception {
            // #298 row 4: the generation used to be read in-place inside startPolling(), which
            // itself runs after a blocking POST -- so a logout landing during that round trip made
            // the login look "current" by the time the generation was actually read. The fix is
            // that the caller (requestMagicLink()) captures the generation BEFORE its own blocking
            // call and passes it through explicitly. This proves commitTokenIfCurrent() honors
            // whatever value it is given rather than re-reading a fresher one, in both directions:
            // a stale pre-blocking-call value is rejected, and a fresh post-logout value is not.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                long generationBeforeBlockingCall = CloudAuthManager.currentCredentialGeneration();

                CloudAuthManager.invalidateCredentialOperations();
                CloudAuthManager.clearToken(); // write 1
                long generationAfterLogout = CloudAuthManager.currentCredentialGeneration();
                assertThat(generationAfterLogout).isGreaterThan(generationBeforeBlockingCall);

                boolean committedWithStaleGeneration =
                        CloudAuthManager.commitTokenIfCurrent(someToken(), generationBeforeBlockingCall);
                boolean committedWithCurrentGeneration =
                        CloudAuthManager.commitTokenIfCurrent(someToken(), generationAfterLogout); // write 2

                int writes = countTempFileCreations(watcher, Duration.ofMillis(800));

                assertThat(committedWithStaleGeneration)
                        .as("a generation captured before the blocking call must be rejected once it is stale")
                        .isFalse();
                assertThat(committedWithCurrentGeneration)
                        .as("a generation captured after logout must still be accepted -- the guard compares "
                                + "whatever value it is given, it is not a blanket denial after any logout")
                        .isTrue();
                assertThat(writes).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("Timing 5 (#298 row 5): a logout squeezed between commit success and activation start aborts activation")
        void logoutBetweenCommitAndActivationStart_abortsActivation() throws Exception {
            // #298 row 5: the activation sequence (enableCloud + initWebsocket +
            // startTokenRefreshScheduler) was not atomic with the generation check, so a logout
            // squeezed in between "commit succeeded" and "activation starts" got reverted.
            // activateCloudIfCurrent() re-checks the generation before touching any of those three
            // steps -- this proves the re-check catches exactly this window and that no cloud
            // state changes when it does.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                long generation = CloudAuthManager.currentCredentialGeneration();
                boolean committed = CloudAuthManager.commitTokenIfCurrent(someToken(), generation); // write 1
                assertThat(committed).isTrue();

                // logout is squeezed in between "commit succeeded" and "activation starts"
                CloudAuthManager.invalidateCredentialOperations();
                CloudAuthManager.clearToken(); // write 2

                boolean cloudEnabledBeforeActivation = PluginInitiationUtils.isCloudEnabled();
                boolean activated = PluginInitiationUtils.activateCloudIfCurrent(generation);

                int writes = countTempFileCreations(watcher, Duration.ofMillis(800));

                assertThat(activated)
                        .as("activation must abort when a logout landed between commit success and activation start")
                        .isFalse();
                assertThat(PluginInitiationUtils.isCloudEnabled())
                        .as("an aborted activation must never reach enableCloud()")
                        .isEqualTo(cloudEnabledBeforeActivation);
                assertThat(writes).isEqualTo(2);
                CredentialStore.ReadResult result = CredentialStore.read();
                assertThat(result.isParsed()).isTrue();
                assertThat(result.data()).doesNotContainKey("cloud_token");
            }
        }
    }
}
