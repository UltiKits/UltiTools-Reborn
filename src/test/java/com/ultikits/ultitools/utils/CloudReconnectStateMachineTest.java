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
