package com.ultikits.ultitools.manager;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
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

/**
 * Proves the cloud teardown stays unconditional: whatever the capability switches say, logging out
 * stops the server monitor and the player-event listener (the symmetry half of D-11/D-12).
 * <p>
 * Plan 22-24 removed the protocol v1 manager wiring ({@code PluginInitiationUtils.initializeManagers()},
 * reachable only from the v1 handshake callback 22-23 deleted) and with it the nine cases that drove it
 * through a mocked v1 client: monitoring, logs and player events skipped while their switch is off and started
 * once while on, resolved independently of one another, and every manager still handed the v1 client with all
 * switches off. On protocol v2 the same switches decide what the connection collects and declares:
 * {@code OutboundWiringEndToEndTest} (telemetry only with a monitor, the logs switch off sends no
 * {@code log.lines}, the player-event and error switches off send no frame), {@code AgentCapabilitiesTest} and
 * {@code InboundWiringTest#helloDeclaresTheSwitches}; {@code NoV1RuntimePathTest} proves no framework path hands a
 * manager a v1 client any more.
 * <p>
 * Plan 22-23 removed the v1 handshake callback ({@code onWebSocketOpened}) and with it the two
 * server-properties cases (no upload on connect while the switch is off, upload while on). On protocol v2 nothing
 * is uploaded at connect: the hello declares {@code properties:read}/{@code properties:write} only while the switch
 * is on ({@code InboundWiringTest#helloDeclaresTheSwitches}) and a request is refused while it is off
 * ({@code CapabilityGateTracerTest}).
 */
@DisplayName("能力开关与云拆线的对称性")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // tearDown clears the UltiTools singleton
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

        PluginInitiationUtils.enableCloud();
    }

    @AfterEach
    void tearDown() throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static YamlConfiguration emptyConfig() {
        return new YamlConfiguration();
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
