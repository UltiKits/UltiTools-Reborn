package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Proves the four outbound capabilities (D-12) close by never starting their collection — never by
 * dropping data at a send exit — and that every {@code UltiTools} WebSocket manager getter stays
 * non-null regardless of capability configuration, because construction stays capability-free
 * (D-11).
 * <p>
 * Reaches {@code PluginInitiationUtils.initializeManagers()} / {@code onWebSocketOpened} via
 * reflection: this class lives in {@code manager}, a different package from {@code utils}, the
 * same approach {@code CapabilityGateTracerTest} in {@code entities} uses to reach
 * {@code handleInboundMessage}.
 */
@DisplayName("能力开关的出站独立性 —— wireManagers / onWebSocketOpened")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reach package-private test seams
class CapabilityGateIndependenceTest {

    private Logger mockLogger;
    private ServerMonitorManager mockServerMonitorManager;
    private CommandExecutionManager mockCommandExecutionManager;
    private FileOperationManager mockFileOperationManager;
    private ServerPropertiesManager mockServerPropertiesManager;
    private LogStreamManager mockLogStreamManager;
    private PlayerEventManager mockPlayerEventManager;
    private ErrorReportCollector mockErrorReportCollector;
    private RemoteActionLog mockRemoteActionLog;
    private ConfigManager mockConfigManager;
    private UltiPanelWebSocketClient mockPanelWs;
    private Object previousPanelWs;

    @BeforeEach
    void setUp() throws Exception {
        mockLogger = mock(Logger.class);
        mockServerMonitorManager = mock(ServerMonitorManager.class);
        mockCommandExecutionManager = mock(CommandExecutionManager.class);
        mockFileOperationManager = mock(FileOperationManager.class);
        mockServerPropertiesManager = mock(ServerPropertiesManager.class);
        mockLogStreamManager = mock(LogStreamManager.class);
        mockPlayerEventManager = mock(PlayerEventManager.class);
        mockErrorReportCollector = mock(ErrorReportCollector.class);
        mockRemoteActionLog = mock(RemoteActionLog.class);
        mockConfigManager = mock(ConfigManager.class);
        lenient().when(mockConfigManager.toJson()).thenReturn("{}");

        // 必须用 Consumer 重载：先打桩、后发布。见 TestHelper 的 javadoc 与 issue #250。
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(emptyConfig());
            lenient().when(ultiTools.getServerMonitorManager()).thenReturn(mockServerMonitorManager);
            lenient().when(ultiTools.getCommandExecutionManager()).thenReturn(mockCommandExecutionManager);
            lenient().when(ultiTools.getFileOperationManager()).thenReturn(mockFileOperationManager);
            lenient().when(ultiTools.getServerPropertiesManager()).thenReturn(mockServerPropertiesManager);
            lenient().when(ultiTools.getLogStreamManager()).thenReturn(mockLogStreamManager);
            lenient().when(ultiTools.getPlayerEventManager()).thenReturn(mockPlayerEventManager);
            lenient().when(ultiTools.getErrorReportCollector()).thenReturn(mockErrorReportCollector);
            lenient().when(ultiTools.getRemoteActionLog()).thenReturn(mockRemoteActionLog);
            lenient().when(ultiTools.getConfigManager()).thenReturn(mockConfigManager);
            lenient().when(ultiTools.i18n(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        });

        mockPanelWs = mock(UltiPanelWebSocketClient.class);
        lenient().when(mockPanelWs.getServerId()).thenReturn("test-server-uuid");
        lenient().when(mockPanelWs.isConnected()).thenReturn(true);
        previousPanelWs = setPanelWs(mockPanelWs);

        PluginInitiationUtils.enableCloud();
    }

    @AfterEach
    void tearDown() throws Exception {
        // panelWS 是静态字段，不还原会漏给同一个 JVM 里后面的测试类。
        setPanelWs(previousPanelWs);
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static YamlConfiguration emptyConfig() {
        return new YamlConfiguration();
    }

    private static YamlConfiguration configWith(String path, boolean value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, value);
        return config;
    }

    private static YamlConfiguration configWithTwo(String pathA, boolean valueA, String pathB, boolean valueB) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(pathA, valueA);
        config.set(pathB, valueB);
        return config;
    }

    private static YamlConfiguration configWithAllEightDisabled() {
        YamlConfiguration config = new YamlConfiguration();
        for (Capability capability : Capability.values()) {
            if (capability.getConfigKey() != null) {
                config.set(capability.getConfigPath(), false);
            }
        }
        return config;
    }

    private Object setPanelWs(Object value) throws Exception {
        Field field = PluginInitiationUtils.class.getDeclaredField("panelWS");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    private static void invokeInitializeManagers() throws Exception {
        Method method = PluginInitiationUtils.class.getDeclaredMethod("initializeManagers");
        method.setAccessible(true);
        method.invoke(null);
    }

    private static void invokeOnWebSocketOpened(UltiPanelWebSocketClient client) throws Exception {
        Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "onWebSocketOpened", UltiPanelWebSocketClient.class);
        method.setAccessible(true);
        method.invoke(null, client);
    }

    @Nested
    @DisplayName("monitoring 能力")
    class MonitoringCapability {

        @Test
        @DisplayName("禁用时：仍设置 client 引用，但从不调用 startMonitoring，并记一条 INFO")
        void disabledSkipsStartMonitoringButStillSetsClient() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith(Capability.MONITORING.getConfigPath(), false));

            invokeInitializeManagers();

            verify(mockServerMonitorManager).setWebSocketClient(mockPanelWs);
            verify(mockServerMonitorManager, never()).startMonitoring();
            verify(mockLogger).log(eq(Level.INFO), contains(Capability.MONITORING.getConfigPath()));
        }

        @Test
        @DisplayName("启用时：startMonitoring 恰好被调用一次")
        void enabledCallsStartMonitoringExactlyOnce() throws Exception {
            invokeInitializeManagers();

            verify(mockServerMonitorManager, times(1)).startMonitoring();
        }
    }

    @Nested
    @DisplayName("logs 能力")
    class LogsCapability {

        @Test
        @DisplayName("禁用时：LogStreamManager.initialize 从不被调用，并记一条 INFO")
        void disabledSkipsInitialize() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith(Capability.LOGS.getConfigPath(), false));

            invokeInitializeManagers();

            verify(mockLogStreamManager, never()).initialize(any());
            verify(mockLogger).log(eq(Level.INFO), contains(Capability.LOGS.getConfigPath()));
        }

        @Test
        @DisplayName("启用时：initialize 恰好被调用一次")
        void enabledCallsInitializeExactlyOnce() throws Exception {
            invokeInitializeManagers();

            verify(mockLogStreamManager, times(1)).initialize(mockPanelWs);
        }
    }

    @Nested
    @DisplayName("player-events 能力")
    class PlayerEventsCapability {

        @Test
        @DisplayName("禁用时：PlayerEventManager.initialize 从不被调用，并记一条 INFO")
        void disabledSkipsInitialize() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith(Capability.PLAYER_EVENTS.getConfigPath(), false));

            invokeInitializeManagers();

            verify(mockPlayerEventManager, never()).initialize(any());
            verify(mockLogger).log(eq(Level.INFO), contains(Capability.PLAYER_EVENTS.getConfigPath()));
        }

        @Test
        @DisplayName("启用时：initialize 恰好被调用一次")
        void enabledCallsInitializeExactlyOnce() throws Exception {
            invokeInitializeManagers();

            verify(mockPlayerEventManager, times(1)).initialize(mockPanelWs);
        }
    }

    @Nested
    @DisplayName("server-properties 能力")
    class ServerPropertiesCapability {

        @Test
        @DisplayName("默认关闭（D-08）：连接建立时不上传服务器属性，并记一条 INFO")
        void disabledByDefaultSkipsUpload() throws Exception {
            invokeOnWebSocketOpened(mockPanelWs);

            verify(mockServerPropertiesManager, never()).getSafeProperties();
            verify(mockLogger).log(eq(Level.INFO), contains(Capability.SERVER_PROPERTIES.getConfigPath()));
        }

        @Test
        @DisplayName("启用时：连接建立时上传服务器属性")
        void enabledUploadsOnConnect() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith(Capability.SERVER_PROPERTIES.getConfigPath(), true));
            lenient().when(mockServerPropertiesManager.getSafeProperties())
                    .thenReturn(java.util.Collections.singletonMap("difficulty", "normal"));

            invokeOnWebSocketOpened(mockPanelWs);

            verify(mockServerPropertiesManager, times(1)).getSafeProperties();
        }
    }

    @Nested
    @DisplayName("D-11：全部八个能力关闭时，构造与 getter 均不受影响")
    class NullSafetyAcrossAllCapabilitiesDisabled {

        @Test
        @DisplayName("每一个 UltiTools WebSocket 管理器 getter 仍然非空，且接线不抛异常")
        void everyManagerGetterStaysNonNull() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig()).thenReturn(configWithAllEightDisabled());

            invokeInitializeManagers();

            assertThat(UltiTools.getInstance().getServerMonitorManager()).isNotNull();
            assertThat(UltiTools.getInstance().getCommandExecutionManager()).isNotNull();
            assertThat(UltiTools.getInstance().getFileOperationManager()).isNotNull();
            assertThat(UltiTools.getInstance().getServerPropertiesManager()).isNotNull();
            assertThat(UltiTools.getInstance().getLogStreamManager()).isNotNull();
            assertThat(UltiTools.getInstance().getPlayerEventManager()).isNotNull();
            assertThat(UltiTools.getInstance().getErrorReportCollector()).isNotNull();

            // 每个管理器仍然拿到了 client 引用——只有「启动采集」被关掉，管理器本身从未被
            // 摘掉或替换成 null（D-11）。
            verify(mockServerMonitorManager).setWebSocketClient(mockPanelWs);
            verify(mockCommandExecutionManager).setWebSocketClient(mockPanelWs);
            verify(mockFileOperationManager).setWebSocketClient(mockPanelWs);
            verify(mockServerPropertiesManager).setWebSocketClient(mockPanelWs);

            verify(mockServerMonitorManager, never()).startMonitoring();
            verify(mockLogStreamManager, never()).initialize(any());
            verify(mockPlayerEventManager, never()).initialize(any());
        }
    }

    @Nested
    @DisplayName("能力之间彼此独立解析（D-11, REMOTE-01 adjacency edge）")
    class IndependentResolution {

        @Test
        @DisplayName("monitoring 关闭、logs 开启：logs 仍按自己的键决定，不受 monitoring 影响")
        void monitoringOffLogsOnAreResolvedIndependently() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig()).thenReturn(configWithTwo(
                    Capability.MONITORING.getConfigPath(), false,
                    Capability.LOGS.getConfigPath(), true));

            invokeInitializeManagers();

            verify(mockServerMonitorManager, never()).startMonitoring();
            verify(mockLogStreamManager, times(1)).initialize(mockPanelWs);
        }

        @Test
        @DisplayName("logs 关闭、monitoring 开启：monitoring 仍按自己的键决定，不受 logs 影响")
        void logsOffMonitoringOnAreResolvedIndependently() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig()).thenReturn(configWithTwo(
                    Capability.LOGS.getConfigPath(), false,
                    Capability.MONITORING.getConfigPath(), true));

            invokeInitializeManagers();

            verify(mockServerMonitorManager, times(1)).startMonitoring();
            verify(mockLogStreamManager, never()).initialize(any());
        }
    }

    @Nested
    @DisplayName("拆线路径与能力开关无关，保持无条件（对称性）")
    class TeardownStaysUnconditional {

        @Test
        @DisplayName("全部能力关闭时，disableCloud 仍无条件调用 stopMonitoring 与 shutdown")
        void teardownRunsUnconditionallyEvenWithAllCapabilitiesDisabled() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig()).thenReturn(configWithAllEightDisabled());

            PluginInitiationUtils.disableCloud();

            verify(mockServerMonitorManager).stopMonitoring();
            verify(mockPlayerEventManager).shutdown();
        }
    }
}
