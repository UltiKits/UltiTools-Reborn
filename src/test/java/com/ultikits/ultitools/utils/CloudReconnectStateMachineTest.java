package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.manager.LogStreamManager;

/**
 * 云连接重连状态机的行为：全局预算、logout 的终止语义、以及成功日志的位置。
 *
 * <p>在 issue #181 / #223 之前，有四个地方各自独立决定「要不要继续重连」而谁都不是所有者：
 * 客户端 {@code onClose} 按每实例 5 次算、{@code reinitWebSocket} 造新实例把计数清零、
 * {@code ulticloud logout} 只清凭证根本不碰状态机、只有 {@code onDisable} 拆得干净。
 * 于是 logout 之后插件仍在拿已作废的凭证持续敲面板。
 */
@DisplayName("云连接重连状态机")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // 测试需要反射读写内部状态，与仓库其它测试类一致
class CloudReconnectStateMachineTest {

    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        mockLogger = mock(Logger.class);
        // Consumer 重载：先打桩、后发布。见 TestHelper javadoc 与 issue #250。
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            // 必须是惰性 answer，不能 thenReturn(LogStreamManager.getInstance())：
            // 这个回调跑在 mock 被发布**之前**（见 TestHelper javadoc），而
            // LogStreamManager 的构造函数会调 UltiTools.getInstance().getLogger()，
            // 此时静态字段还是 null。改成 answer 之后求值推迟到真正被调用时，那时已经发布完毕。
            lenient().when(ultiTools.getLogStreamManager())
                    .thenAnswer(invocation -> LogStreamManager.getInstance());
        });
        PluginInitiationUtils.enableCloud();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 把状态机复位，避免污染同 JVM 里的其它测试类（surefire 未配 forkCount，全部跑在一个
        // JVM 里，见 issue #250）。
        PluginInitiationUtils.enableCloud();
        removeLeakedSystemLogHandlers();

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
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mockLogger, Mockito.atLeast(0)).log(Mockito.eq(level), captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("全局重连预算（#181）")
    class ReconnectBudget {

        @Test
        @DisplayName("外层 reinit 有上限，耗尽后进入 disabled 终态而不是无限循环")
        void reinitLoopIsBounded() {
            // 客户端自身那 5 次是每实例的上限，而 reinitWebSocket 每次都造新实例，
            // 所以在加预算之前，这个循环没有任何东西能让它停下来。
            for (int i = 0; i < 40; i++) {
                assertThatCode(PluginInitiationUtils::reinitWebSocket).doesNotThrowAnyException();
            }

            assertThat(PluginInitiationUtils.isCloudEnabled())
                    .as("预算耗尽之后状态机必须停下来，而不是继续造新客户端")
                    .isFalse();

            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("gave up after"));
        }

        @Test
        @DisplayName("预算耗尽进入终态时，拆线动作必须与 logout 一致")
        void exhaustedBudgetTearsDownCloudResources() {
            // 先让云侧真正接上线，否则下面断言的是一个空集合，测试等于什么都没测。
            LogStreamManager manager = LogStreamManager.getInstance();
            try {
                manager.initialize(null);
            } catch (Exception ignored) {
                // 传 null 客户端时下游可能抛，但 handler 的挂载必须已经发生
            }
            assertThat(countFrameworkHandlersOnRootLogger())
                    .as("前置条件：日志 handler 应当已挂在 root logger 上")
                    .isEqualTo(1);

            for (int i = 0; i < 15; i++) {
                PluginInitiationUtils.reinitWebSocket();
            }

            assertThat(PluginInitiationUtils.isCloudEnabled())
                    .as("预算耗尽之后必须进入 disabled 终态")
                    .isFalse();

            // 终态的日志原文是 "Cloud features are now idle"。只翻 cloudEnabled 标志的话，
            // 日志传输器与 root logger handler、玩家事件监听器、token 刷新调度以及静态
            // panelWS/token 引用会全部留着继续跑——那句话就成了谎。
            assertThat(countFrameworkHandlersOnRootLogger())
                    .as("既然已宣告 cloud features are now idle，root logger 就不该还挂着框架 handler")
                    .isZero();
        }

        @Test
        @DisplayName("握手成功会把预算清零，使长期运行的服务器不会耗尽额度")
        void successfulHandshakeResetsBudget() {
            for (int i = 0; i < 5; i++) {
                PluginInitiationUtils.reinitWebSocket();
            }
            assertThat(PluginInitiationUtils.isCloudEnabled()).isTrue();

            // 一次真正的 onOpen
            PluginInitiationUtils.onWebSocketConnected();

            // 预算被清零，因此还能再撑满一整轮
            for (int i = 0; i < 9; i++) {
                PluginInitiationUtils.reinitWebSocket();
            }
            assertThat(PluginInitiationUtils.isCloudEnabled())
                    .as("清零之后应当还有额度，不该因为之前用掉的 5 次就提前进入终态")
                    .isTrue();
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
        @DisplayName("initWebsocket 自身不得把状态机置回启用态")
        void initWebsocketDoesNotResurrectDisabledState() throws Exception {
            // 回归测试，对应 PR 评审里的 P1：initWebsocket 曾经在开头 set(true)。
            // 由于 reinitWebSocket 也复用它，一个正在途中的重连（与 logout 跑在不同线程，
            // 中间还隔着一次 token 刷新的网络调用）能把刚被关掉的状态机重新拉起来。
            PluginInitiationUtils.disableCloud();
            assertThat(PluginInitiationUtils.isCloudEnabled()).isFalse();

            // 直接调 initWebsocket：它会因为没有 token 而抛 IOException，
            // 但关键是——无论成败，它都不该动 cloudEnabled。
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
            com.ultikits.ultitools.websocket.UltiPanelWebSocketClient client =
                    mock(com.ultikits.ultitools.websocket.UltiPanelWebSocketClient.class);
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
        @DisplayName("云已关闭时 initializeManagers 直接返回")
        void managersAreNotWiredWhenCloudDisabled() {
            // logout 之后仍可能有一次在途的握手落地。没有这道闸的话，disableCloud() 刚摘掉的
            // 监听器会被这次迟到的 onOpen 原样装回去——「谁都不是所有者」的毛病换个地方重现。
            PluginInitiationUtils.disableCloud();

            PluginInitiationUtils.initializeManagers();

            Mockito.verify(UltiTools.getInstance(), Mockito.never()).getServerMonitorManager();
            assertThat(loggedAt(Level.FINE))
                    .anySatisfy(line -> assertThat(line).contains("跳过管理器初始化"));
        }

        @Test
        @DisplayName("云开启时照常接线")
        void managersAreWiredWhenCloudEnabled() {
            // 负向对照：闸门只该拦住关闭态，正常路径必须原样通过。
            PluginInitiationUtils.enableCloud();

            PluginInitiationUtils.initializeManagers();

            Mockito.verify(UltiTools.getInstance(), Mockito.atLeastOnce()).getServerMonitorManager();
        }

        @Test
        @DisplayName("接线与拆线落在同一把生命周期锁上")
        void wiringAndTeardownShareTheSameLock() throws Exception {
            // 光检查 cloudEnabled 是不够的：那只是一次锁外的读。读到 true 之后、真正接线之前，
            // disableCloud() 完全可以插进来把开关置否并拆干净，随后接线一侧继续往下又装回去。
            // 状态位表达不了「检查与动作之间不许有人插队」，只有锁能。见 PR #264 第二轮评审。
            Field lockField = PluginInitiationUtils.class.getDeclaredField("cloudLifecycleLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(null);

            Runnable[] actions = {
                PluginInitiationUtils::initializeManagers,
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
                    assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            .as("探针线程本身要真的跑起来，否则下面那条断言是空转")
                            .isTrue();
                    assertThat(finished.await(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .as("持有生命周期锁期间，这个动作必须进不去")
                            .isFalse();
                }

                assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .as("释放锁之后应当立刻放行")
                        .isTrue();
                worker.join(1000);
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
