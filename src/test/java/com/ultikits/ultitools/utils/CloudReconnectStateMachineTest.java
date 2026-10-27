package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * 云连接重连状态机的行为：全局预算、logout 的终止语义、以及成功日志的位置——现在全部驱动在
 * {@link CloudSession} 实例上，而不是两个已删除的静态字段（{@code cloudEnabled}、
 * {@code cloudLifecycleLock}）之上。
 *
 * <p>在 issue #181 / #223 之前，有四个地方各自独立决定「要不要继续重连」而谁都不是所有者：
 * 客户端 {@code onClose} 按每实例 5 次算、{@code reinitWebSocket} 造新实例把计数清零、
 * {@code ulticloud logout} 只清凭证根本不碰状态机、只有 {@code onDisable} 拆得干净。
 * 于是 logout 之后插件仍在拿已作废的凭证持续敲面板。6.3.0（D-16/D-17/D-18，计划 16-08 Task 2）
 * 把 WebSocket 客户端、重连退避状态和面板管理器接线都挪到了 {@link CloudSession} 上：
 * 「云功能是否开启」不再是一个独立的布尔标志，而就是「当前会话是否仍然有效」；
 * 「接线与拆线互斥」不再靠一把全局锁，而是两者都在会话自己的监视器上做
 * {@code synchronized}。
 */
@DisplayName("云连接重连状态机")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // 测试需要反射读写内部状态，与仓库其它测试类一致
class CloudReconnectStateMachineTest {

    @TempDir
    Path tempDir;
    private final AgentRuntime runtime = new AgentRuntime();
    private Logger mockLogger;
    private ServerMonitorManager mockMonitor;

    @BeforeEach
    void setUp() {
        mockLogger = mock(Logger.class);
        mockMonitor = mock(ServerMonitorManager.class);
        // Consumer 重载：先打桩、后发布。见 TestHelper javadoc 与 issue #250。
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getServerMonitorManager()).thenReturn(mockMonitor);
            // 必须是惰性 answer，不能 thenReturn(LogStreamManager.getInstance())：
            // 这个回调跑在 mock 被发布**之前**（见 TestHelper javadoc），而
            // LogStreamManager 的构造函数会调 UltiTools.getInstance().getLogger()，
            // 此时静态字段还是 null。改成 answer 之后求值推迟到真正被调用时，那时已经发布完毕。
            lenient().when(ultiTools.getLogStreamManager())
                    .thenAnswer(invocation -> LogStreamManager.getInstance());
            // 16-10 补丁为 v1 的 wireManagers() 路径加的两个桩；22-24 删除了那段接线，桩保留无害。
            lenient().when(ultiTools.getCommandExecutionManager())
                    .thenReturn(mock(com.ultikits.ultitools.manager.CommandExecutionManager.class));
            lenient().when(ultiTools.getFileOperationManager())
                    .thenReturn(mock(com.ultikits.ultitools.manager.FileOperationManager.class));
        });
        // CloudSession.current 是 JVM 级静态（surefire 未配 forkCount，issue #250），每个用例
        // 从一个全新、未失效、没有任何调度或客户端的会话开始，不依赖上一个用例或上一个测试类
        // 留下的状态。
        CloudSession.resetForTesting();
        CredentialStore.setTargetPathForTesting(tempDir.resolve("credentials.json"));
        CredentialStore.setOldLocationForTesting(tempDir.resolve("absent.json"));
        runtime.install();
    }

    @AfterEach
    void tearDown() throws Exception {
        CloudSession.resetForTesting();
        removeLeakedSystemLogHandlers();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();

        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static void removeLeakedSystemLogHandlers() {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            if (handler instanceof SystemLogHandler) {
                rootLogger.removeHandler(handler);
            }
        }
    }

    /** 取出以指定级别记录的全部日志正文。 */
    private java.util.List<String> loggedAt(Level level) {
        // Both forms: the reconnect lines are panel-connection lines, logged as marked records.
        return com.ultikits.ultitools.testutil.LoggedLines.at(mockLogger, level);
    }

    @Nested
    @DisplayName("v2 单一重连所有者")
    class ReconnectOwner {
        @Test
        void repeatedInitKeepsOneOwnerAndOneRetry() throws Exception {
            startAgent();
            for (int i = 0; i < 20; i++) { PluginInitiationUtils.initWebsocket(); }
            runtime.runDue();
            assertThat(runtime.attempts).hasSize(1);
            assertThat(runtime.schedulers).hasSize(1);
            runtime.last().close(1011);
            // Plan 22-07 arms one more task on this same session-owned scheduler: the 30 s
            // state-flush tick. Together with the connection's one queued retry that is two, and
            // the twenty repeated init calls above added neither a second owner nor a second retry.
            assertThat(runtime.scheduler().pending()).isEqualTo(2);
            runtime.advance(1000);
            assertThat(runtime.attempts).hasSize(2);
        }

        @Test
        void successfulHandshakeResetsAttemptCount() throws Exception {
            startAgent();
            runtime.last().close(1011);
            runtime.advance(1000);
            assertThat(CloudSession.current().connectionDescription()).isEqualTo("connecting");
            runtime.last().accept();
            // Plan 22-20: the counters reset at welcome, not at the upgrade.
            runtime.last().welcome();
            runtime.last().close(1011);
            runtime.advance(1000);
            runtime.last().close(1011);
            assertThat(CloudSession.current().connectionDescription()).isEqualTo("reconnecting in 1 s (attempt 1)");
        }

        @Test
        void disableEnableStartsWithFreshCredentialAndAttemptCount() throws Exception {
            startAgent();
            CloudSession old = CloudSession.current();
            runtime.last().close(1011);
            runtime.advance(1000);
            PluginInitiationUtils.stopWebsocket();
            assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
            PluginInitiationUtils.initWebsocket();
            runtime.runDue();
            assertThat(CloudSession.current()).isNotSameAs(old);
            runtime.last().close(1011);
            assertThat(CloudSession.current().connectionDescription()).isEqualTo("reconnecting in 1 s (attempt 1)");
        }

        @Test
        void reinitStopsOldSchedulerAndInstallsOnlyOneFreshOwner() throws Exception {
            startAgent();
            AgentConnectionTest.ManualScheduler first = runtime.scheduler();
            AgentRuntime.Attempt old = runtime.last();
            old.close(1011);
            PluginInitiationUtils.reinitWebSocket();
            runtime.runDue();
            first.advance(60000);
            assertThat(first.isShutdown()).isTrue();
            assertThat(runtime.attempts).hasSize(2);
            runtime.last().close(1011);
            runtime.advance(1000);
            assertThat(runtime.attempts).hasSize(3);
        }

        @Test
        void successLineAppearsOnlyOncePerAcceptedConnection() throws Exception {
            startAgent();
            assertThat(loggedAt(Level.INFO)).doesNotContain("UltiCloud: connected.");
            AgentRuntime.Attempt first = runtime.last();
            first.accept();
            assertThat(loggedAt(Level.INFO)).as("upgraded is not connected (plan 22-20)").doesNotContain("UltiCloud: connected.");
            first.welcome();
            first.accept();
            assertThat(loggedAt(Level.INFO).stream().filter("UltiCloud: connected."::equals)).hasSize(1);
            first.close(1011);
            runtime.advance(1000);
            runtime.last().accept();
            runtime.last().welcome();
            assertThat(loggedAt(Level.INFO).stream().filter("UltiCloud: connected."::equals)).hasSize(2);
        }

        @Test
        void backoffStatusRoundsSecondsUp() throws Exception {
            startAgent();
            runtime.last().close(1011);
            runtime.advance(1);
            assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo("reconnecting in 1 s (attempt 1)");
            assertThat(CloudAuthManager.status().isConnected()).isFalse();
        }

        @Test
        void planStatusRoundsMinutesUp() throws Exception {
            startAgent();
            runtime.last().refuse(402);
            runtime.advance(1);
            assertThat(CloudAuthManager.status().getConnectionDescription()).contains("next in 15 min");
            runtime.advance(60000);
            assertThat(CloudAuthManager.status().getConnectionDescription()).contains("next in 14 min");
        }

        @Test
        void rateLimitBacksOffWithoutDeletingCredentialOrLoggingRejection() throws Exception {
            startAgent();
            runtime.last().refuse(429);
            assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.BACKOFF);
            assertThat(ServerCredentialStore.load()).isNotNull();
            assertThat(loggedAt(Level.WARNING)).isEmpty();
            runtime.advance(60000);
            assertThat(runtime.attempts).hasSize(2);
        }
    }

    @Nested
    @DisplayName("logout 终止状态机（#223）")
    class LogoutTearsDown {

        @Test
        @DisplayName("disableCloud 之后 reinitWebSocket 不再重建连接")
        void reinitIsNoOpAfterDisable() {
            PluginInitiationUtils.disableCloud();
            assertThat(PluginInitiationUtils.isCloudEnabled()).isFalse();

            // 这一步在加闸门之前会去刷新 token 并重建客户端——也就是 401 循环的来源
            assertThatCode(PluginInitiationUtils::reinitWebSocket).doesNotThrowAnyException();

            assertThat(PluginInitiationUtils.isCloudEnabled())
                    .as("logout 之后不得自我复活")
                    .isFalse();

            assertThat(loggedAt(Level.INFO))
                    .as("不应再打印任何重初始化的进行时日志")
                    .noneSatisfy(line -> assertThat(line).contains("Re-initializing"));
        }

        @Test
        @DisplayName("disableCloud 会摘掉 root logger 上的日志 handler")
        void disableCloudDetachesLogHandler() {
            PluginInitiationUtils.disableCloud();

            assertThat(countFrameworkHandlersOnRootLogger())
                    .as("「云功能已关闭」应当包含不再劫持 root logger")
                    .isZero();
        }

        @Test
        @DisplayName("WR-01：日志 handler 的摘除现在与会话失效共用同一把锁——持锁期间它不可能已经先跑完")
        void logStreamShutdownIsCoveredByTheSameSessionLockAsInvalidation() throws Exception {
            // 复现路径：disableCloud() 曾经先（不持锁）关掉 LogStreamManager，再（持锁）
            // invalidate() 会话。一次迟到的 onOpen 落在两步之间会看到会话仍然 current，
            // 把刚摘下来的日志 handler 原样装回去。16-10 把日志 handler 的摘除挪进了
            // CloudSession#invalidate() 内部，与其它拆线步骤共用会话自己的监视器——
            // 本用例要验的不是「disableCloud 最终会不会摘掉 handler」（disableCloudDetachesLogHandler
            // 已经验过），而是「主线程持着会话监视器的整段时间里，handler 是不是还没被摘」。
            CloudSession session = CloudSession.current();
            UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);
            try {
                LogStreamManager.getInstance().initialize(client);
            } catch (Exception ignored) {
                // 本测试环境没有真实的 Bukkit server（sendInitializationLogs 会因此抛），
                // 但 handler 的挂载已经在那之前发生——与 IdempotentLogStream 用例同样的容错。
            }
            assertThat(countFrameworkHandlersOnRootLogger())
                    .as("前置条件：日志 handler 必须先真的挂上去")
                    .isEqualTo(1);

            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
            Thread worker = new Thread(() -> {
                started.countDown();
                try {
                    PluginInitiationUtils.disableCloud();
                } catch (Exception ignored) {
                    // 本用例只关心锁的覆盖范围，不关心拆线其它步骤的结果
                } finally {
                    finished.countDown();
                }
            }, "wr-01-log-stream-lock-probe");
            worker.setDaemon(true);

            synchronized (session) {
                worker.start();
                assertThat(started.await(5, TimeUnit.SECONDS))
                        .as("探针线程本身要真的跑起来，否则下面那条断言是空转")
                        .isTrue();
                assertThat(finished.await(300, TimeUnit.MILLISECONDS))
                        .as("持有会话监视器期间，disableCloud 整体必须进不去")
                        .isFalse();
                assertThat(countFrameworkHandlersOnRootLogger())
                        .as("在修复之前，日志 handler 的摘除跑在锁外面，此刻已经没了——" +
                            "修复之后它现在也在锁里面，此刻必须还在")
                        .isEqualTo(1);
            }

            assertThat(finished.await(5, TimeUnit.SECONDS))
                    .as("释放锁之后应当立刻放行")
                    .isTrue();
            worker.join(1000);
            assertThat(countFrameworkHandlersOnRootLogger())
                    .as("放开锁之后，拆线跑完，handler 必须被摘掉")
                    .isZero();
        }

        @Test
        @DisplayName("disableCloud 会让在途的凭证操作作废")
        void disableCloudInvalidatesInFlightCredentialOperations() {
            // 只停调度器是不够的：stop* 用的都是 cancel(false)，拦不住一个已经进了 HTTP
            // 请求的刷新或轮询，而两者都会在返回前写 token 与 data.json。没有这一步，
            // logout 会被一个迟到几秒的刷新原地撤销。6.3.0 起判据不再是一个代际计数器，
            // 而是这次拆线之前那个会话对象自身的 invalidated 标记。
            CloudSession sessionBeforeDisable = CloudSession.current();

            PluginInitiationUtils.disableCloud();

            assertThat(sessionBeforeDisable.isCurrent())
                    .as("拆线必须让在途凭证操作所在的会话失效，否则迟到的结果仍会被提交")
                    .isFalse();
        }

        @Test
        @DisplayName("disableCloud 会停掉服务器监控")
        void disableCloudStopsServerMonitor() {
            // ServerMonitorManager 自带一个 ScheduledExecutorService 加两个主线程 Bukkit
            // 定时任务。在此之前 stopMonitoring() 在整个 src/main 里没有调用方——写好了、
            // 测过了、就是没接线，于是「云功能已关闭」之后主线程仍在按 5 秒遍历所有世界。
            // 这里验证的是接线；停下来之后到底停了什么由 ServerMonitorSnapshotTest 覆盖。
            PluginInitiationUtils.disableCloud();

            Mockito.verify(mockMonitor).stopMonitoring();
        }

        @Test
        @DisplayName("initWebsocket 自身不得把状态机置回启用态")
        void initWebsocketDoesNotResurrectDisabledState() throws Exception {
            // 回归测试，对应 PR 评审里的 P1：initWebsocket 曾经在开头把状态机直接置为启用。
            // 由于 reinitWebSocket 也复用它，一个正在途中的重连（与 logout 跑在不同线程，
            // 中间还隔着一次 token 刷新的网络调用）能把刚被关掉的状态机重新拉起来。
            PluginInitiationUtils.disableCloud();
            assertThat(PluginInitiationUtils.isCloudEnabled()).isFalse();

            // 直接调 initWebsocket：它会因为没有 token 而抛 IOException，
            // 但关键是——无论成败，它都不该让当前会话变回有效。
            assertThatCode(() -> {
                try {
                    PluginInitiationUtils.initWebsocket();
                } catch (Exception expected) {
                    // 没有 token，抛异常是预期的
                }
            }).doesNotThrowAnyException();

            assertThat(PluginInitiationUtils.isCloudEnabled())
                    .as("只有显式的 enableCloud 才允许开启状态机")
                    .isFalse();
        }

        @Test
        @DisplayName("logout 与进行中的 reinit 并发时，logout 必须赢")
        void concurrentLogoutBeatsInFlightReinit() throws Exception {
            // 真的并发跑，而不是靠顺序模拟：reinit 线程反复尝试重建，
            // 主线程在中途 disableCloud()。此后状态机不得再被拉起来。
            java.util.concurrent.atomic.AtomicBoolean stop =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread reinitThread = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        PluginInitiationUtils.reinitWebSocket();
                    } catch (Exception ignored) {
                        // 无 token 时的失败与本用例无关
                    }
                }
            }, "issue-223-reinit-loop");
            reinitThread.setDaemon(true);
            reinitThread.start();

            Thread.sleep(30);
            PluginInitiationUtils.disableCloud();
            Thread.sleep(60);   // 给在途的那一轮足够时间跑完

            stop.set(true);
            reinitThread.join(5000);

            assertThat(PluginInitiationUtils.isCloudEnabled())
                    .as("logout 之后，任何在途的重连都不得把状态机复活")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("会话身份隔离 v2 传输与回调")
    class SessionOwnsTransportAndBackoff {
        @Test
        void logoutClosesTheActiveConnectionAndSuppressesFurtherReconnectsOnTheOldSession() throws Exception {
            startAgent();
            CloudSession old = CloudSession.current();
            AgentRuntime.Attempt attempt = runtime.last();
            attempt.accept();
            org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
            List<Runnable> deferred = new ArrayList<>();
            Mockito.when(scheduler.runTaskAsynchronously(Mockito.any(org.bukkit.plugin.Plugin.class),
                    Mockito.any(Runnable.class))).thenAnswer(call -> {
                deferred.add(call.getArgument(1));
                return null;
            });
            CloudHttp.Response response = mock(CloudHttp.Response.class);
            Mockito.when(response.outcome()).thenReturn(CloudHttp.Outcome.IO_ERROR);
            try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = Mockito.mockStatic(org.bukkit.Bukkit.class);
                    org.mockito.MockedStatic<CloudHttp> http = Mockito.mockStatic(CloudHttp.class)) {
                bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
                http.when(() -> CloudHttp.postJson("/v1/agent/credential/revoke", null, "ucs_OLD"))
                        .thenReturn(response);
                CloudAuthManager.logout();
                assertThat(deferred).hasSize(1);
                http.verifyNoInteractions();
                assertThat(attempt.closedWith).isEqualTo(1000);
                assertThat(runtime.scheduler().isShutdown()).isTrue();
                PluginInitiationUtils.reinitWebSocket(old);
                runtime.advance(60000);
                assertThat(runtime.attempts).hasSize(1);
                assertThat(old.connectionState()).isEqualTo(AgentConnection.State.STOPPED);
                deferred.get(0).run();
                assertThat(ServerCredentialStore.load()).isNull();
                assertThat(old.getCredential()).isNull();
                runtime.advance(60000);
                assertThat(runtime.attempts).hasSize(1);
            }
        }

        @Test
        void replacedSessionCannotRebuildAConnection() throws Exception {
            startAgent();
            CloudSession old = CloudSession.current();
            CloudSession replacement = CloudSession.startNew();
            PluginInitiationUtils.reinitWebSocket(old);
            runtime.advance(60000);
            assertThat(runtime.attempts).hasSize(1);
            assertThat(old.connectionState()).isEqualTo(AgentConnection.State.STOPPED);
            assertThat(replacement.isCurrent()).isTrue();
        }

        @Test
        void lateTerminalCallbackCannotDeleteNewCredentialOrWarn() throws Exception {
            startAgent();
            AgentConnection.Listener oldListener = listenerOf(CloudSession.current());
            CloudSession replacement = CloudSession.startNew();
            replacement.commitCredential(credential("NEW"));
            oldListener.onCredentialRejected(4401, "old-request");
            oldListener.onUpgradeRequired("6.3.2", "old-request");
            oldListener.onSuperseded();
            oldListener.onPlanLimited(UpgradeRejection.statusOnly(402));
            assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
            assertThat(loggedAt(Level.WARNING)).isEmpty();
            assertThat(replacement.getCredential().getCredentialId()).isEqualTo("cred-NEW");
        }

        @Test
        @DisplayName("a 4401 after a persisted rotation deletes the rotated credential from disk")
        void credentialRejectedAfterRotationDeletesTheRotatedCredential() throws Exception {
            startAgent();
            CloudSession session = CloudSession.current();
            AgentConnection.Listener listener = listenerOf(session);
            assertThat(session.rotateCredential(ROTATED_SECRET, ROTATED_ID)).isTrue();
            assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo(ROTATED_ID);

            listener.onCredentialRejected(4401, null);

            assertThat(ServerCredentialStore.load()).as("the rotated credential the gateway rejected").isNull();
            assertThat(session.getCredential()).isNull();
        }

        @Test
        @DisplayName("a 4401 after a rotation still keeps a credential another writer saved meanwhile")
        void credentialRejectedAfterRotationKeepsACredentialWrittenMeanwhile() throws Exception {
            startAgent();
            CloudSession session = CloudSession.current();
            AgentConnection.Listener listener = listenerOf(session);
            assertThat(session.rotateCredential(ROTATED_SECRET, ROTATED_ID)).isTrue();
            ServerCredentialStore.save(credential("NEW"));

            listener.onCredentialRejected(4401, null);

            assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
            assertThat(loggedAt(Level.WARNING)).noneSatisfy(line ->
                    assertThat(line).contains("The saved credential was deleted"));
        }

        @Test
        void callbackAlsoChecksCurrentIdentityBeforeInvalidationFlagChanges() throws Exception {
            startAgent();
            CloudSession old = CloudSession.current();
            AgentConnection.Listener listener = listenerOf(old);
            Field current = CloudSession.class.getDeclaredField("current");
            current.setAccessible(true);
            CloudSession replacement = new CloudSession();
            current.set(null, replacement);
            try {
                replacement.commitCredential(credential("NEW"));
                assertThat(old.isCurrent()).isTrue();
                listener.onCredentialRejected(4401, null);
                listener.onOpened();
                assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
                assertThat(loggedAt(Level.WARNING)).isEmpty();
                assertThat(loggedAt(Level.INFO)).doesNotContain("UltiCloud: connected.");
            } finally { old.invalidate(); }
        }

        @Test
        void oldConnectionCallbackCannotActAfterSameSessionReinit() throws Exception {
            startAgent();
            AgentConnection.Listener listener = listenerOf(CloudSession.current());
            PluginInitiationUtils.reinitWebSocket();
            runtime.runDue();
            listener.onCredentialRejected(4401, null);
            assertThat(ServerCredentialStore.load()).isNotNull();
            assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.CONNECTING);
            assertThat(loggedAt(Level.WARNING)).isEmpty();
        }

        @Test
        void teardownOfASupersededSessionDoesNotStopGlobalManagersTheCurrentSessionMayAlreadyOwn() {
            CloudSession old = CloudSession.current();
            CloudSession replacement = CloudSession.startNew();
            PluginInitiationUtils.disableCloud(old);
            Mockito.verify(mockMonitor, Mockito.never()).stopMonitoring();
            assertThat(replacement.isCurrent()).isTrue();
        }

        @Test
        void startPollingOnAnAlreadyInvalidatedSessionSchedulesNothing() throws Exception {
            CloudSession session = CloudSession.current();
            session.invalidate();
            session.startDevicePolling(DeviceLoginTestSupport.grant(600, 5),
                    new DeviceLoginTestSupport.RecordingListener());
            Field poll = CloudSession.class.getDeclaredField("pollExecutor");
            poll.setAccessible(true);
            assertThat(poll.get(session)).isNull();
        }

        @Test
        void invalidateWithoutPluginStillStopsOwnedTransport() throws Exception {
            startAgent();
            CloudSession session = CloudSession.current();
            AgentRuntime.Attempt attempt = runtime.last();
            Field instance = UltiTools.class.getDeclaredField("ultiTools");
            instance.setAccessible(true);
            Object plugin = instance.get(null);
            instance.set(null, null);
            try {
                assertThatCode(session::invalidate).doesNotThrowAnyException();
                assertThat(attempt.closedWith).isEqualTo(1000);
                assertThat(runtime.scheduler().isShutdown()).isTrue();
            } finally { instance.set(null, plugin); }
        }

        @Test
        void diskDeleteFailureStillDetachesRejectedCredential() throws Exception {
            startAgent();
            CredentialStore.setSimulateWriteFailureForTesting(true);
            try { runtime.last().refuse(401); }
            finally { CredentialStore.setSimulateWriteFailureForTesting(false); }
            assertThat(CloudSession.current().hasCredential()).isFalse();
            assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.CREDENTIAL_REJECTED);
            assertThat(loggedAt(Level.WARNING)).anyMatch(line -> line.contains("could not be deleted"));
            runtime.advance(60000);
            assertThat(runtime.attempts).hasSize(1);
        }
    }

    /**
     * CR-02 (16-REVIEW-cloud.md): {@code reinitWebSocket}'s second confirmation, through to the
     * connection actually being established, must be covered by {@code session}'s own monitor --
     * the same pattern the 6.3.0 {@code activateCloudIfCurrent} used. Without it, an {@code invalidate()}
     * landing in that window leaves an already-invalidated session holding a live, authenticated
     * WebSocket connection nothing ever closes.
     */
    @Nested
    @DisplayName("重连的「二次确认到建连」现在与会话失效共用同一把锁（CR-02）")
    class ReinitSecondConfirmationIsLockProtected {

        @Test
        @DisplayName("持有会话监视器期间，reinitWebSocket 的第二次确认到建连整段必须进不去")
        void secondConfirmationThroughConnectIsCoveredBySessionLock() throws Exception {
            CloudSession session = CloudSession.current();
            session.commitCredential(credential("OLD"));

            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
            Thread worker = new Thread(() -> {
                started.countDown();
                try {
                    PluginInitiationUtils.reinitWebSocket(session);
                } catch (Exception ignored) {
                    // env.yml/api-url is not configured in this test environment -- the exception
                    // this can throw is irrelevant; what matters is whether entering the region at
                    // all required the lock.
                } finally {
                    finished.countDown();
                }
            }, "cr-02-reinit-lock-probe");
            worker.setDaemon(true);

            synchronized (session) {
                worker.start();
                assertThat(started.await(5, TimeUnit.SECONDS))
                        .as("探针线程本身要真的跑起来，否则下面那条断言是空转")
                        .isTrue();
                assertThat(finished.await(300, TimeUnit.MILLISECONDS))
                        .as("持有会话监视器期间，第二次确认到建连这段必须进不去")
                        .isFalse();
            }

            assertThat(finished.await(5, TimeUnit.SECONDS))
                    .as("释放锁之后应当立刻放行")
                    .isTrue();
            worker.join(1000);
        }

        @Test
        @DisplayName("Round 1 外部评审（第四轮）：持有 CloudSession.class 的监视器期间，disableCloud 的「查有效性再拆全局管理器」整段必须进不去")
        void currencyCheckThroughGlobalTeardownIsCoveredByTheClassLock() throws Exception {
            // 复现的窄缝：一次性、锁外的「session 还是不是 current()」读取，和真正执行
            // stopMonitoring()/shutdown() 之间，另一次登录的 startNew() 加上它自己紧跟着的握手接线
            // 完全可以插进来。CloudSession.class 恰好是 startNew() 自己已经在用的那把锁——
            // 用它把「查」和「拆」纳入同一段临界区，就能让这段代码与任何后续的 startNew() 调用互斥。
            CloudSession session = CloudSession.current();

            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
            Thread worker = new Thread(() -> {
                started.countDown();
                PluginInitiationUtils.disableCloud(session);
                finished.countDown();
            }, "round1-r4-teardown-lock-probe");
            worker.setDaemon(true);

            synchronized (CloudSession.class) {
                worker.start();
                assertThat(started.await(5, TimeUnit.SECONDS))
                        .as("探针线程本身要真的跑起来，否则下面那条断言是空转")
                        .isTrue();
                assertThat(finished.await(300, TimeUnit.MILLISECONDS))
                        .as("持有 CloudSession.class 期间，查有效性到拆全局管理器这段必须进不去")
                        .isFalse();
            }

            assertThat(finished.await(5, TimeUnit.SECONDS))
                    .as("释放锁之后应当立刻放行")
                    .isTrue();
            worker.join(1000);
        }
    }

    @Nested
    @DisplayName("不再打印虚假成功日志（#223）")
    class NoFalseSuccessLog {

        @Test
        @DisplayName("reinitWebSocket 自身不打印 re-initialized successfully")
        void reinitDoesNotClaimSuccess() {
            // initWebsocket() 返回只说明 connect() 被发起了，而 connect() 是异步的：
            // 握手与认证都还没发生。实测这句之后紧跟着的就是一条 401。
            for (int i = 0; i < 3; i++) {
                PluginInitiationUtils.reinitWebSocket();
            }

            assertThat(loggedAt(Level.INFO))
                    .noneSatisfy(line -> assertThat(line).contains("re-initialized successfully"));
            assertThat(loggedAt(Level.WARNING))
                    .noneSatisfy(line -> assertThat(line).contains("re-initialized successfully"));
        }
    }

    @Nested
    @DisplayName("LogStreamManager 幂等（#181）")
    class IdempotentLogStream {

        @Test
        @DisplayName("重复 initialize 之后 root logger 上的框架 handler 恒为 1")
        void repeatedInitializeKeepsExactlyOneHandler() {
            LogStreamManager manager = LogStreamManager.getInstance();

            // 模拟 10 轮重连：每轮 onOpen 都会走一次 onConnectHandler → initialize
            for (int round = 0; round < 10; round++) {
                try {
                    manager.initialize(null);
                } catch (Exception ignored) {
                    // 传 null 客户端时下游可能抛，但 handler 的挂载/摘除必须已经发生
                }
                assertThat(countFrameworkHandlersOnRootLogger())
                        .as("第 %d 轮之后 root logger 上的框架 handler 数量", round + 1)
                        .isLessThanOrEqualTo(1);
            }

            manager.shutdown();
            assertThat(countFrameworkHandlersOnRootLogger())
                    .as("shutdown 之后应当一个不剩")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("日志传输器 flush 有界")
    class BoundedFlush {

        /**
         * {@code sendBatch()} 在 WebSocket 未连接时直接 return 且不消费队列，而
         * {@code flushLogs()} 原先是 {@code while (!logQueue.isEmpty())} —— 死循环。
         *
         * <p>这个缺陷在 {@code disableCloud()} 出现之前就存在（{@code onDisable} 同样走这条
         * 路径），但那时只在关服时触发。logout 走的是命令线程，会直接卡住服务器；
         * 而「面板连不上、队列积压、socket 已断」恰恰是管理员会去 logout 的那个场景。
         *
         * <p><b>超时必须用 SEPARATE_THREAD</b>。JUnit 的 {@code @Timeout} 默认是
         * {@code SAME_THREAD}——它在测试**跑完之后**才判定是否超时，对真正的死循环毫无作用，
         * 回归会让整个 CI 挂住而不是给出一条失败。这一点是实测出来的：把 flushLogs 退回
         * 无界版本做负向对照时，构建直接卡死超过两分钟，而不是在 10 秒时失败。
         */
        @Test
        @org.junit.jupiter.api.Timeout(
                value = 10,
                threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("socket 已断且队列非空时，shutdown 仍会终止而不是死循环")
        void shutdownTerminatesWhenDisconnectedWithQueuedLogs() throws Exception {
            // 必须先「连着」才入得了队：sendLog 在未连接时直接 return，一条都不会进队列。
            // 这也正是真实场景的顺序——连着的时候日志积压，然后连接断掉，然后才 logout/关服。
            java.util.concurrent.atomic.AtomicBoolean connected =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);
            lenient().when(client.isConnected()).thenAnswer(invocation -> connected.get());

            com.ultikits.ultitools.manager.UltiPanelLogTransmitter transmitter =
                    new com.ultikits.ultitools.manager.UltiPanelLogTransmitter(client, "test-server");
            try {
                // batchSize 默认远大于 3，所以这几条会留在队列里而不会被立刻发出去
                for (int i = 0; i < 3; i++) {
                    transmitter.info("queued line " + i, "test");
                }
                assertThat(queueSizeOf(transmitter))
                        .as("前置条件：队列里得真有东西，否则本用例是空的")
                        .isPositive();

                connected.set(false);   // 连接掉了

                assertThatCode(transmitter::shutdown)
                        .as("断连且队列非空时 shutdown 必须终止")
                        .doesNotThrowAnyException();
            } finally {
                transmitter.shutdown();
            }
        }

        /** 反射读队列长度，用于确认前置条件成立——不然这条用例会静默地什么都没测。 */
        private int queueSizeOf(com.ultikits.ultitools.manager.UltiPanelLogTransmitter transmitter)
                throws Exception {
            Field queueField = com.ultikits.ultitools.manager.UltiPanelLogTransmitter.class
                    .getDeclaredField("logQueue");
            queueField.setAccessible(true);
            return ((java.util.Collection<?>) queueField.get(transmitter)).size();
        }
    }

    @Nested
    @DisplayName("迟到的握手不得复活管理器（#264 评审）")
    class LateHandshakeIsIgnored {

        @Test
        @DisplayName("拆线之后到达的握手回调不得踩空会话已清空的客户端引用")
        void lateHandshakeDoesNotDereferenceClearedClient() {
            try {
                startAgent();
                AgentConnection.Listener listener = listenerOf(CloudSession.current());
                PluginInitiationUtils.disableCloud();
                assertThatCode(listener::onOpened).doesNotThrowAnyException();
                assertThat(loggedAt(Level.INFO)).doesNotContain("UltiCloud: connected.");
            } catch (Exception e) { throw new AssertionError(e); }
        }

        @Test
        @DisplayName("Round 1 外部评审：迟到握手的接线必须查这次握手自己那个会话是否仍然有效，不能查事后重新读到的 current()")
        void lateHandshakeInitializesManagersOnItsOwnSessionNotWhateverIsCurrent() {
            try {
                startAgent();
                AgentConnection.Listener listener = listenerOf(CloudSession.current());
                CloudSession replacement = CloudSession.startNew();
                listener.onOpened();
                assertThat(replacement.connectionState()).isEqualTo(AgentConnection.State.STOPPED);
                Mockito.verify(mockMonitor, Mockito.never()).setWebSocketClient(Mockito.any());
                assertThat(loggedAt(Level.INFO)).doesNotContain("UltiCloud: connected.");
            } catch (Exception e) { throw new AssertionError(e); }
        }

        @Test
        @DisplayName("接线与拆线落在同一个会话的监视器上")
        void wiringAndTeardownShareTheSameLock() throws Exception {
            // 光检查 isCloudEnabled() 是不够的：那只是一次锁外的读。读到 true 之后、真正接线
            // 之前，disableCloud() 完全可以插进来把会话置为失效并拆干净，随后接线一侧继续往下
            // 又装回去。状态位表达不了「检查与动作之间不许有人插队」，只有锁能。见 PR #264
            // 第二轮评审。6.3.0 起这把锁不再是一个独立的静态字段，而就是当前会话对象自己的
            // 监视器——reinitWebSocket()（建连一侧）/invalidate() 都在其上做 synchronized，本用例
            // 直接用 CloudSession.current() 本身作为锁对象，不需要反射。22-24 删除了 v1 的管理器
            // 接线 initializeManagers()，建连一侧改由 v2 的 reinitWebSocket() 代表。
            Object lock = CloudSession.current();

            Runnable[] actions = {
                PluginInitiationUtils::reinitWebSocket,
                PluginInitiationUtils::disableCloud
            };

            for (Runnable action : actions) {
                java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

                Thread worker = new Thread(() -> {
                    started.countDown();
                    try {
                        action.run();
                    } catch (Exception ignored) {
                        // 本用例只关心它能不能进得去，不关心它做了什么
                    } finally {
                        finished.countDown();
                    }
                }, "lifecycle-lock-probe");
                worker.setDaemon(true);

                synchronized (lock) {
                    worker.start();
                    assertThat(started.await(5, TimeUnit.SECONDS))
                            .as("探针线程本身要真的跑起来，否则下面那条断言是空转")
                            .isTrue();
                    assertThat(finished.await(300, TimeUnit.MILLISECONDS))
                            .as("持有会话监视器期间，这个动作必须进不去")
                            .isFalse();
                }

                assertThat(finished.await(5, TimeUnit.SECONDS))
                        .as("释放锁之后应当立刻放行")
                        .isTrue();
                worker.join(1000);
            }
        }
    }

    @Nested
    @DisplayName("#465/#466 死锁自由性压测——CloudSession.class / 会话监视器 / 连接监视器这条锁序"
            + "在四类并发路径下不得出现反序")
    class LockOrderDeadlockFreedom {

        @Test
        @DisplayName("startNew / login / reinitWebSocket / disableCloud 四类操作并发混跑 200 轮，"
                + "ThreadMXBean 必须报告零死锁且全部任务在超时内完成")
        // PMD.JUnitTestsShouldIncludeAssert: 真正的断言在 runOneStressRound() 这个共享私有方法里
        // （lock-order-freedom 压测的主体逻辑），与本仓库 CLAUDE.md「Suppressing PMD in tests」
        // 一节记录的既有情形相同——PMD 不认得断言活在共享辅助方法里的形式。
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void concurrentSessionLifecycleOperationsNeverDeadlock() throws Exception {
            // login() 会走到 requestMagicLink() 的网络调用——固定成空字符串，让它在本地失败得
            // 又快又确定，不去碰真实网络，也不会因为网络延迟拖慢本压测。
            HttpRequestUtils.setBaseUrlForTesting("");
            try {
                runOneStressRound();
            } finally {
                HttpRequestUtils.resetBaseUrl();
            }
        }

        /**
         * 四类操作各提交 200 次到一个 4 线程池，混跑到全部完成，然后用
         * {@link ThreadMXBean#findDeadlockedThreads()} 核实没有任何线程互相卡死。之所以是
         * "至少 200 轮或 2 秒"这个量级，是因为 CloudSession.class -> 会话监视器 -> 连接监视器
         * 这条锁序里的任何一次反序，在四线程池、数百次提交的规模下几乎必然在合理时间内触发一次
         * AB-BA 死锁；单次串行调用测不出这类问题，因为死锁本身就是排队顺序的产物。
         */
        private void runOneStressRound() throws Exception {
            int iterationsPerOperation = 200;
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (int i = 0; i < iterationsPerOperation; i++) {
                    // 并发路径一：startNew() 本身——CloudSession.class 外层，被替换会话的
                    // 监视器内层，是本类文档化锁序的"锚"。
                    pool.submit(() -> {
                        try {
                            CloudSession.startNew();
                        } catch (Throwable ignored) {
                            // 本用例只关心死锁自由性，不关心业务结果本身
                        }
                    });
                    // 并发路径二：reinitWebSocket()——会话监视器外层，连接的监视器内层（22-24 删除了
                    // v1 的 initializeManagers() 与 LOG_WIRING_LOCK，建连路径由它代表）。
                    pool.submit(() -> {
                        try {
                            PluginInitiationUtils.reinitWebSocket();
                        } catch (Throwable ignored) {
                            // 同上
                        }
                    });
                    // 并发路径三：disableCloud()——先（不持锁）invalidate 自己的会话监视器，
                    // 再（分开）CloudSession.class，两段不嵌套。
                    pool.submit(() -> {
                        try {
                            PluginInitiationUtils.disableCloud();
                        } catch (Throwable ignored) {
                            // 同上
                        }
                    });
                    // 并发路径四：login()——16-10 补丁新增的那段
                    // synchronized(CloudSession.class){synchronized(session){...}}，是本压测
                    // 真正要验的那条修复过的锁序本身。
                    pool.submit(() -> {
                        try {
                            CloudAuthManager.login(
                                    () -> { },
                                    remaining -> { },
                                    () -> { },
                                    url -> { },
                                    error -> { });
                        } catch (Throwable ignored) {
                            // 同上
                        }
                    });
                }

                pool.shutdown();
                boolean completed = pool.awaitTermination(30, TimeUnit.SECONDS);

                ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
                long[] deadlockedThreadIds = threadBean.findDeadlockedThreads();

                assertThat(deadlockedThreadIds)
                        .as("#465/#466：CloudSession.class -> 会话监视器 -> 连接监视器 这条"
                                + "文档化锁序，必须在 startNew/login/reinitWebSocket/disableCloud"
                                + "四类并发路径下保持一致，不能出现 AB-BA 死锁")
                        .isNull();
                assertThat(completed)
                        .as("全部 " + (iterationsPerOperation * 4) + " 个任务必须在 30 秒超时内跑完，"
                                + "而不是卡在等一把永远等不到的锁")
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** The secret and id of a rotated credential (the shape of the contract's {@code credential.rotate}). */
    private static final String ROTATED_SECRET = "ucs_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String ROTATED_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0CRD";

    private void startAgent() throws Exception {
        CloudSession.current().commitCredential(credential("OLD"));
        PluginInitiationUtils.initWebsocket();
        runtime.runDue();
        assertThat(runtime.attempts).isNotEmpty();
    }

    static ServerCredential credential(String suffix) {
        return new ServerCredential("ucs_" + suffix, "cred-" + suffix,
                HandshakeEndToEndTest.SERVER_ID, 43L, "Survival", 1L);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    static AgentConnection.Listener listenerOf(CloudSession session) throws Exception {
        Field agent = CloudSession.class.getDeclaredField("agentConnection");
        agent.setAccessible(true);
        Field listener = AgentConnection.class.getDeclaredField("listener");
        listener.setAccessible(true);
        return (AgentConnection.Listener) listener.get(agent.get(session));
    }

    static final class AgentRuntime {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final List<AgentConnectionTest.ManualScheduler> schedulers = new ArrayList<>();
        final List<Attempt> attempts = new ArrayList<>();

        void install() {
            CloudSession.setAgentEndpointOverrideForTesting(URI.create("ws://cloud.example.test/v1/agent"));
            CloudSession.setAgentTimingOverrideForTesting(new ReconnectPolicy(() -> 0), AgentConnection.Timing.defaults());
            CloudSession.setAgentRuntimeForTesting(() -> {
                AgentConnectionTest.ManualScheduler scheduler = new AgentConnectionTest.ManualScheduler(clock);
                schedulers.add(scheduler);
                return scheduler;
            }, (uri, headers, events) -> {
                Attempt attempt = new Attempt(headers, events, scheduler());
                attempts.add(attempt);
                return attempt;
            }, clock::get);
        }

        AgentConnectionTest.ManualScheduler scheduler() { return schedulers.get(schedulers.size() - 1); }
        Attempt last() { return attempts.get(attempts.size() - 1); }
        void runDue() { scheduler().runDue(); }
        void advance(long millis) { scheduler().advance(millis); }

        static final class Attempt implements AgentConnection.Handle {
            final Map<String, String> headers;
            final AgentConnection.SocketEvents events;
            final AgentConnectionTest.ManualScheduler scheduler;
            int closedWith;

            Attempt(Map<String, String> headers, AgentConnection.SocketEvents events,
                    AgentConnectionTest.ManualScheduler scheduler) {
                this.headers = headers;
                this.events = events;
                this.scheduler = scheduler;
            }
            /** Every text frame handed to this attempt, in order (plan 22-20: the hello comes first). */
            final List<String> sent = new ArrayList<>();
            @Override public void closeAttempt(int code, String reason) { closedWith = code; }
            @Override public boolean sendText(String text) { sent.add(text); return true; }
            void accept() { events.onOpen(); scheduler.runDue(); }
            /** Answers this attempt's hello with the vendored welcome (plan 22-20: established only then). */
            void welcome() { welcome(HandshakeEndToEndTest.SERVER_ID); }
            void welcome(String serverId) {
                events.onText(HandshakeEndToEndTest.welcomeFor(sent.get(0), serverId).toString());
                scheduler.runDue();
            }
            void close(int code) { events.onClosed(code, "", true); scheduler.runDue(); }
            void refuse(int status) {
                events.onUpgradeRefused(status, UpgradeRejection.statusOnly(status));
                scheduler.runDue();
            }
        }
    }

    /** 数一数 JVM root logger 上挂了几个本框架的 handler。 */
    private static int countFrameworkHandlersOnRootLogger() {
        int count = 0;
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof SystemLogHandler) {
                count++;
            }
        }
        return count;
    }
}
