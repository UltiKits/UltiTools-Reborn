package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * 6.3.1 behaviour of the kept v1 entry points of the managers (plan 22-25, S-04 option b, S-67):
 * each still takes the client a caller hands in -- the v1 senders behind it stay until 6.4.0 -- and
 * writes exactly one INFO line per run that names the protocol v1 removal and the 6.4.0 removal.
 * The framework itself never calls any of them ({@code NoV1RuntimePathTest}).
 */
@DisplayName("6.3.1: the managers' v1 entry points keep the client and say once per run that v1 is gone")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings({"removal", "PMD.AvoidAccessibilityAlteration"}) // drives the deprecated v1 members; reads their client field
class V1CloudEntryPointTest {

    private final List<Logger> watched = new ArrayList<>();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };
    private UltiPanelWebSocketClient client;
    private LogStreamManager logStreamManager;

    @TempDir
    File serverRoot;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Logger pluginLogger = Logger.getLogger("UltiTools-V1CloudEntryPointTest");
        FileConfiguration config = mock(FileConfiguration.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(config);
        });
        V1CloudNotice.resetForTesting();
        resetLogStreamSingleton();
        client = mock(UltiPanelWebSocketClient.class);
        when(client.getServerId()).thenReturn("server-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Logger logger : watched) {
            logger.removeHandler(capture);
        }
        if (logStreamManager != null) {
            try {
                logStreamManager.shutdown();
            } catch (Exception ignored) {
                // A failed shutdown under mocks does not matter; the handler sweep below does.
            }
        }
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof SystemLogHandler) {
                root.removeHandler(handler);
            }
        }
        resetLogStreamSingleton();
        V1CloudNotice.resetForTesting();
        MockBukkitHelper.safeUnmock();
    }

    private static void resetLogStreamSingleton() throws Exception {
        Field instance = LogStreamManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private void watch(Class<?> owner) {
        Logger logger = Logger.getLogger(owner.getName());
        logger.addHandler(capture);
        watched.add(logger);
    }

    private List<LogRecord> noticesNaming(String member) {
        List<LogRecord> matching = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getMessage() != null && record.getMessage().contains(member)) {
                matching.add(record);
            }
        }
        return matching;
    }

    private void assertOneNotice(String member) {
        List<LogRecord> notices = noticesNaming(member);
        assertThat(notices).as("INFO lines naming %s", member).hasSize(1);
        assertThat(notices.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(notices.get(0).getMessage()).contains("protocol v1").contains("6.4.0");
    }

    private static Object clientField(Object manager) throws Exception {
        Field field = manager.getClass().getDeclaredField("webSocketClient");
        field.setAccessible(true);
        return field.get(manager);
    }

    @Test
    @DisplayName("the four setWebSocketClient setters store the client and log one INFO line each per run")
    void settersStoreTheClientAndLogOncePerRun() throws Exception {
        watch(ServerMonitorManager.class);
        watch(CommandExecutionManager.class);
        watch(FileOperationManager.class);
        watch(ServerPropertiesManager.class);
        ServerMonitorManager monitor = new ServerMonitorManager();
        CommandExecutionManager commands = new CommandExecutionManager();
        FileOperationManager files = new FileOperationManager();
        ServerPropertiesManager properties = new ServerPropertiesManager(serverRoot);

        for (int round = 0; round < 2; round++) {
            monitor.setWebSocketClient(client);
            commands.setWebSocketClient(client);
            files.setWebSocketClient(client);
            properties.setWebSocketClient(client);
        }

        assertThat(clientField(monitor)).isSameAs(client);
        assertThat(clientField(commands)).isSameAs(client);
        assertThat(clientField(files)).isSameAs(client);
        assertThat(clientField(properties)).isSameAs(client);
        assertOneNotice("ServerMonitorManager#setWebSocketClient");
        assertOneNotice("CommandExecutionManager#setWebSocketClient");
        assertOneNotice("FileOperationManager#setWebSocketClient");
        assertOneNotice("ServerPropertiesManager#setWebSocketClient");
    }

    @Test
    @DisplayName("PlayerEventManager#initialize keeps the client and logs one INFO line per run")
    void playerEventInitializeLogsOncePerRun() throws Exception {
        watch(PlayerEventManager.class);
        PlayerEventManager players = new PlayerEventManager();
        try {
            players.initialize(client);
            players.initialize(client);

            assertThat(clientField(players)).isSameAs(client);
            assertOneNotice("PlayerEventManager#initialize");
        } finally {
            players.shutdown();
        }
    }

    @Test
    @DisplayName("LogStreamManager#initialize and the transmitter's v1 constructor log one INFO line each per run")
    void logStreamInitializeAndTransmitterLogOncePerRun() throws Exception {
        watch(LogStreamManager.class);
        watch(UltiPanelLogTransmitter.class);
        logStreamManager = LogStreamManager.getInstance();

        logStreamManager.initialize(client);
        logStreamManager.initialize(client);
        UltiPanelLogTransmitter direct = new UltiPanelLogTransmitter(client, "server-1");
        direct.shutdown();

        assertThat(clientField(logStreamManager)).isSameAs(client);
        assertThat(logStreamManager.getLogTransmitter()).as("the v1 transmitter is still built").isNotNull();
        assertOneNotice("LogStreamManager#initialize");
        assertOneNotice("UltiPanelLogTransmitter(UltiPanelWebSocketClient, String)");
    }
}
