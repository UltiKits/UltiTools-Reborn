package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * #486, gate-1 finding B-P2-2: when the panel reconnects on a new client, {@code LogStreamManager}
 * replaces the transmitter. Records the old transmitter could not send -- a held batch and anything
 * still queued -- are carried to the new one and sent first, instead of being dropped with the old
 * object. A socket that closed around a send is exactly what leads to such a reconnect.
 */
@DisplayName("A reconnect on a new client carries the unsent log records over (#486, gate 1)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReconnectCarriesPendingLogsTest {

    private final List<JsonObject> sentByNewClient = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("ReconnectCarriesPendingLogsTest"));
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        });
    }

    @AfterEach
    void tearDown() {
        LogStreamManager.getInstance().shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private static List<String> logMessages(List<JsonObject> frames) {
        List<String> messages = new ArrayList<>();
        for (JsonObject frame : frames) {
            if ("log_batch".equals(frame.get("type").getAsString())) {
                for (JsonElement log : frame.getAsJsonArray("data")) {
                    messages.add(log.getAsJsonObject().get("message").getAsString());
                }
            }
        }
        return messages;
    }

    @Test
    @DisplayName("records queued on the old client's transmitter are sent first through the new one")
    void queuedRecordsSurviveAReconnectOnANewClient() {
        AtomicBoolean oldConnected = new AtomicBoolean(true);
        UltiPanelWebSocketClient oldClient = mock(UltiPanelWebSocketClient.class);
        lenient().when(oldClient.isConnected()).thenAnswer(inv -> oldConnected.get());
        UltiPanelWebSocketClient newClient = mock(UltiPanelWebSocketClient.class);
        when(newClient.isConnected()).thenReturn(true);
        doAnswer(inv -> sentByNewClient.add(inv.getArgument(0))).when(newClient).sendMessage(any(JsonObject.class));

        LogStreamManager manager = LogStreamManager.getInstance();
        manager.initialize(oldClient);
        manager.getLogTransmitter().info("queued before the drop 1", "test");
        manager.getLogTransmitter().info("queued before the drop 2", "test");

        oldConnected.set(false);          // the old connection is gone for good
        manager.initialize(newClient);    // reinitWebSocket: a fresh client
        manager.getLogTransmitter().info("logged after the reconnect", "test");
        manager.getLogTransmitter().flushLogs();

        assertThat(logMessages(sentByNewClient)).containsSubsequence(
                "queued before the drop 1", "queued before the drop 2", "logged after the reconnect");
    }
}
