package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * 会话身份取代凭证代际：让在途的异步凭证操作在 logout 之后无法把凭证写回来。
 *
 * <p>issue #298 的根子是「取消 ≠ 失效」，这一点没有变；变的是拿什么来判定「失效」。6.3.0 之前
 * 用一个 {@code AtomicLong} 代际计数器：{@code stopTokenRefreshScheduler()} 用的是
 * {@code cancel(false)} 加 {@code shutdown()}，两者都只承诺不再调度新的执行，对一个已经进入
 * HTTP 请求的刷新任务毫无约束；每个在途操作记下开始时的代际，提交前再比一次。计数器能告诉一个迟到
 * 的结果「你迟到了」，但拦不住调度器、WebSocket 客户端本身继续活着——teardown 顺序仍然是正确性的
 * 前提。D-16/D-18 用 {@link CloudSession} 的对象身份取代了这个计数器：一个会话拥有令牌、两个调度器
 * 和（自 16-08 Task 2 起）WebSocket 客户端与重连退避状态；{@code logout} 换上一个新会话并
 * {@link CloudSession#invalidate()} 旧的，旧会话上的一切在途操作从此单靠自己的
 * {@code invalidated} 标记就会被拒绝——不需要再有任何 teardown 顺序保证。
 */
@DisplayName("会话身份取代凭证代际（在途操作的失效判据）")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // 需反射复位 UltiTools 单例（#250）
class CredentialGenerationTest {

    @TempDir
    File dataFolder;

    /**
     * The exact temporary-file name {@link CredentialStore} always creates for a write before
     * atomically renaming it onto {@code credentials.json} -- see {@code CredentialStore.TEMP_FILE_NAME},
     * which is private and so cannot be referenced symbolically from this package-mate.
     */
    private static final String TEMP_FILE_NAME = "credentials.json.tmp";

    @BeforeEach
    void setUp() {
        Logger mockLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getDataFolder()).thenReturn(dataFolder);
        });
        // Plan 08-15 moved CredentialStore's production target outside the plugin data folder
        // (<server root>/.ultikits/credentials.json). This class's whole method -- watching
        // dataFolder at the filesystem level for temp-file creations -- needs writes to keep
        // landing in dataFolder regardless of that move, so both locations are pinned here
        // explicitly rather than relying on the (now different) production default. The old
        // location is pinned to a path that never gets a real file written to it in this class,
        // so migrate() is always a fast, inert no-op for every test below.
        CredentialStore.setTargetPathForTesting(dataFolder.toPath().resolve("credentials.json"));
        CredentialStore.setOldLocationForTesting(dataFolder.toPath().resolve("pre-migration-data.json"));
        // Every test below constructs and tears down its own CloudSession instances, but
        // CloudSession.current is a JVM-wide static (surefire runs this module with no forkCount,
        // per issue #250) -- without resetting it here, whichever test ran last in this class
        // (or in CloudAuthManagerTest / CloudReconnectStateMachineTest, sharing the same JVM)
        // leaks its session into this one.
        CloudSession.resetForTesting();
    }

    @AfterEach
    void tearDown() throws Exception {
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        CloudSession.resetForTesting();
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
    // StandardWatchEventKinds constants are singletons whose implementing class
    // (java.nio.file.StandardWatchEventKinds$StdWatchEventKind) never overrides equals()/
    // hashCode() -- `==` is reference identity here, which is exactly what the JDK's own
    // WatchService documentation uses to compare a WatchEvent's kind.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
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
        @DisplayName("会话仍是当前会话时，提交成功")
        void commitSucceedsOnACurrentSession() throws Exception {
            CloudSession session = new CloudSession();

            boolean committed = session.commit(someToken());

            assertThat(committed).isTrue();
            assertThat(session.getToken()).isNotNull();
        }

        @Test
        @DisplayName("会话已被 invalidate 之后，提交必须被拒绝且不落盘")
        void commitIsRejectedAfterInvalidation() throws Exception {
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                CloudSession session = new CloudSession();

                // logout 期间：拆线路径让这个会话作废
                session.invalidate();

                // HTTP 请求这时才返回
                boolean committed = session.commit(someToken());

                int writes = countTempFileCreations(watcher, Duration.ofMillis(800));

                assertThat(committed)
                        .as("迟到的刷新结果不得把凭证写回来——否则 logout 等于没执行")
                        .isFalse();
                assertThat(session.getToken())
                        .as("内存中的凭证必须仍是空的")
                        .isNull();
                assertThat(writes).as("失效之后的提交不应有任何落盘发生").isZero();
            }
        }

        @Test
        @DisplayName("多次 invalidate 不会让一个已作废的会话重新变得有效")
        void multipleInvalidationsNeverRevalidateASession() {
            CloudSession session = new CloudSession();

            session.invalidate();
            assertThat(session.isCurrent()).isFalse();

            session.invalidate();
            assertThat(session.isCurrent())
                    .as("反复 invalidate 不会把一个会话变回当前——不存在能让它复活的第二次调用")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("两个空态：一个还没排上任何调度的会话，和一个提交前就已失效的会话")
    class EmptySessionBehaviours {

        @Test
        @DisplayName("一个刚构造、还没有任何调度器的会话可以直接 invalidate，不抛异常")
        void freshSessionAcceptsInvalidateWithoutThrowing() {
            CloudSession session = new CloudSession();

            assertThatCode(session::invalidate).doesNotThrowAnyException();
            assertThat(session.isCurrent()).isFalse();
        }

        @Test
        @DisplayName("一个在提交任何东西之前就已失效的会话，仍然报告自己失效并拒绝提交")
        void sessionInvalidatedBeforeAnyWorkStillReportsInvalidAndRefusesCommit() throws Exception {
            CloudSession session = new CloudSession();

            session.invalidate();

            assertThat(session.isCurrent()).isFalse();
            assertThat(session.commit(someToken()))
                    .as("从未提交过任何东西的会话，失效之后第一次提交也必须被拒绝")
                    .isFalse();
        }
    }

    /**
     * D-14/D-16/D-18: one named, deterministic test per timing from issue #298's "already fixed"
     * table (five rows), now driven through {@link CloudSession} instances instead of a shared
     * generation counter. Each stages its interleaving with explicit, controllable call ordering --
     * never a sleep -- and asserts both the final {@code credentials.json} content and the number of
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
            // cancel(false) does not interrupt an in-flight refresh -- the fix is the session
            // identity guard alone, since the scheduler cannot be relied on to stop the in-flight
            // call. The refresh task holds a reference to the session it started on; that
            // reference's own invalidated flag is the only thing that matters.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                CloudSession sessionAtRefreshStart = CloudSession.current();

                // logout happens while the refresh HTTP call is "in flight"
                CloudAuthManager.clearToken(); // the only write this scenario should perform

                // the refresh call "returns" only now, carrying the session captured before logout
                boolean committed = sessionAtRefreshStart.commit(someToken());

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
            // branch reconnects on its own. This proves the session-identity guard is sufficient
            // defense in depth for the credential-write half of that bug even when no stop call is
            // made at all -- this scenario deliberately never calls stopPolling().
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                CloudSession sessionAtRequestStart = CloudSession.current();

                CloudAuthManager.clearToken(); // the only write this scenario should perform

                boolean committed = sessionAtRequestStart.commit(someToken());

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
            // read-modify-write is atomic under one lock (CredentialStore) regardless, so this
            // proves the mirror-image positive case: a legitimate login that starts only after
            // teardown has fully finished -- against the brand-new session teardown installed --
            // must not be collateral damage from the teardown's own write.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                CloudAuthManager.clearToken(); // write 1 -- teardown, nothing to clear yet; installs a fresh session

                CloudSession sessionAfterTeardown = CloudSession.current();
                boolean committed = sessionAfterTeardown.commit(someToken()); // write 2

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
            // #298 row 4: the credential-identity check used to be read in-place inside
            // startPolling(), which itself runs after a blocking POST -- so a logout landing
            // during that round trip made the login look "current" by the time the check was
            // actually made. The fix is that the caller (requestMagicLink()) captures its own
            // session BEFORE its own blocking call and keeps using that same reference throughout.
            // This proves commit() honors whichever session instance it is called on rather than
            // re-reading CloudSession.current() fresh, in both directions: a session captured
            // before the blocking call and since superseded is rejected, and a freshly-current
            // session is not.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                CloudSession sessionBeforeBlockingCall = CloudSession.current();

                CloudAuthManager.clearToken(); // write 1
                CloudSession sessionAfterLogout = CloudSession.current();
                assertThat(sessionAfterLogout).isNotSameAs(sessionBeforeBlockingCall);
                assertThat(sessionBeforeBlockingCall.isCurrent()).isFalse();

                boolean committedWithStaleSession = sessionBeforeBlockingCall.commit(someToken());
                boolean committedWithCurrentSession = sessionAfterLogout.commit(someToken()); // write 2

                int writes = countTempFileCreations(watcher, Duration.ofMillis(800));

                assertThat(committedWithStaleSession)
                        .as("a session reference captured before the blocking call must be rejected once it is stale")
                        .isFalse();
                assertThat(committedWithCurrentSession)
                        .as("the session captured after logout must still be accepted -- the guard checks "
                                + "whatever instance it is given, it is not a blanket denial after any logout")
                        .isTrue();
                assertThat(writes).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("Timing 5 (#298 row 5): a logout squeezed between commit success and activation start aborts activation")
        void logoutBetweenCommitAndActivationStart_abortsActivation() throws Exception {
            // #298 row 5: the activation sequence (enableCloud + initWebsocket +
            // startTokenRefreshScheduler) was not atomic with the credential-identity check, so a
            // logout squeezed in between "commit succeeded" and "activation starts" got reverted.
            // activateCloudIfCurrent(session) re-checks session.isCurrent() before touching any of
            // those three steps -- this proves the re-check catches exactly this window and that no
            // cloud state changes when it does.
            try (WatchService watcher = newTempFileWatcher(dataFolder)) {
                CloudSession session = CloudSession.current();
                boolean committed = session.commit(someToken()); // write 1
                assertThat(committed).isTrue();

                // logout is squeezed in between "commit succeeded" and "activation starts"
                CloudAuthManager.clearToken(); // write 2

                boolean cloudEnabledBeforeActivation = PluginInitiationUtils.isCloudEnabled();
                boolean activated = PluginInitiationUtils.activateCloudIfCurrent(session);

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
