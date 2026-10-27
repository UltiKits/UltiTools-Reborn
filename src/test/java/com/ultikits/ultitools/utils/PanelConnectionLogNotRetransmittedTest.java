package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * A line the framework logs about the panel connection itself -- above all the panel's own
 * {@code error} replies -- is written to the local console but never handed to the log stream.
 * <p>
 * Measured on a real server (phase 17, row {@code ultitools.config.config-yml} part (c)): with
 * {@code ultipanel.logging.batch.enabled: false} the panel answered "Rate limit exceeded" 42,066
 * times. Each {@code error} reply was logged as SEVERE through the shared plugin logger, the log
 * stream sent that line to the panel, the panel rejected it as over quota and replied with another
 * {@code error}. The SystemLogHandler's same-thread guard cannot stop this, because every reply is
 * a separate inbound message. Since 6.3.1 (plan 22-23) the protocol v1 inbound path is gone; the v2 connection logs
 * through the same marked sink.
 * <p>
 * The plugin logger here is a real {@link Logger} carrying the same two handlers a server gives it:
 * a console stand-in and the stream's {@link SystemLogHandler}. Each case is paired with the
 * positive control {@link #ordinaryLineIsStillStreamed()}, which proves the harness does hand an
 * ordinary line to the transmitter, so a "never sent" verdict is not an artefact of the set-up.
 */
@DisplayName("Panel-connection log lines are not sent back to the panel")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // tearDown resets the UltiTools singleton by reflection (#250)
class PanelConnectionLogNotRetransmittedTest {

    private final List<String> console = new CopyOnWriteArrayList<>();
    private final Logger pluginLogger = Logger.getLogger("UltiTools-PanelConnectionLogTest");
    private final Handler consoleStandIn = new Handler() {
        @Override
        public void publish(LogRecord record) {
            console.add(record.getMessage());
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

    private UltiPanelLogTransmitter transmitter;
    private SystemLogHandler streamHandler;

    @BeforeEach
    void setUp() {
        transmitter = mock(UltiPanelLogTransmitter.class);
        streamHandler = new SystemLogHandler(transmitter);
        pluginLogger.setUseParentHandlers(false);
        pluginLogger.setLevel(Level.ALL);
        pluginLogger.addHandler(consoleStandIn);
        pluginLogger.addHandler(streamHandler);
        TestHelper.mockUltiToolsInstance(ultiTools ->
                lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger));
    }

    @AfterEach
    void tearDown() throws Exception {
        pluginLogger.removeHandler(consoleStandIn);
        pluginLogger.removeHandler(streamHandler);
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private void assertLoggedLocallyButNotStreamed(String fragment) {
        assertThat(console).as("the line is still written to the console")
                .anySatisfy(line -> assertThat(line).contains(fragment));
        verify(transmitter, never()).sendLog(anyString(), contains(fragment), any(), any());
    }

    @Test
    @DisplayName("control: an ordinary framework line is handed to the stream")
    void ordinaryLineIsStillStreamed() {
        pluginLogger.info("an ordinary framework line");

        verify(transmitter).sendLog(anyString(), argThat(m -> m != null && m.contains("an ordinary framework line")),
                any(), any());
    }

    /**
     * Plan 22-23 removed the protocol v1 inbound handlers whose log lines the five former cases drove (the panel's
     * {@code error}, {@code notification} and refused {@code subscribe} replies, an unknown and a type-less message).
     * On protocol v2 the connection, the protocol and its router log through one sink,
     * {@code PanelConnectionLog::log} (the sink {@code CloudSession#startConnection} and {@code AgentProtocol} pass),
     * so this case drives that sink with the line a gateway {@code error} frame produces.
     */
    @Test
    @DisplayName("a line of the v2 connection's log sink is logged locally and never streamed")
    void v2ConnectionLogLineIsNotStreamed() {
        com.ultikits.ultitools.websocket.PanelConnectionLog.log(Level.SEVERE,
                "UltiCloud: the gateway answered error rate_limited: Rate limit exceeded. Please slow down.");

        assertLoggedLocallyButNotStreamed("Rate limit exceeded");
    }

    @Test
    @DisplayName("the client's connection-error and disconnect lines are logged locally and never streamed")
    void clientLifecycleLinesAreNotStreamed() throws Exception {
        UltiPanelWebSocketClient client = new UltiPanelWebSocketClient("ws://localhost:1", "test-server", "token");
        try {
            client.disconnect();
            client.onError(new IllegalStateException("connection-error-from-socket"));
            client.onClose(1006, "closed-by-peer", true);
        } finally {
            client.disconnect();
        }

        assertLoggedLocallyButNotStreamed("connection-error-from-socket");
        assertLoggedLocallyButNotStreamed("closed-by-peer");
    }
}
