package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * #486, gate-1 finding B-P2-1: between the socket leaving the open state and {@code onClose} running
 * (a closing handshake with a dead peer can last a long time), the real client's {@code sendMessage}
 * refuses to send and returns quietly, while its connected flag still reads {@code true}. A log batch
 * sent in that window must not count as delivered.
 * <p>
 * This uses the real {@link UltiPanelWebSocketClient}; only the socket's own state and the wire are
 * replaced, so the client's own {@code sendMessage} logic decides what goes out.
 */
@DisplayName("A batch sent into a closing socket is kept (#486, gate 1)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ClosingSocketLogHoldTest {

    private ClosingClient client;
    private UltiPanelLogTransmitter transmitter;

    /** The real client with a controllable socket state and a recording wire. */
    static final class ClosingClient extends UltiPanelWebSocketClient {
        volatile boolean socketOpen = true;
        final List<String> wire = new ArrayList<>();

        ClosingClient() throws URISyntaxException {
            super("ws://127.0.0.1:1", "test-server", "token");
        }

        @Override
        public boolean isOpen() {
            return socketOpen;
        }

        @Override
        public void send(String text) {
            wire.add(text);
        }
    }

    @BeforeEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // onOpen's flag, without opening a socket
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();
        client = new ClosingClient();
        Field connected = UltiPanelWebSocketClient.class.getDeclaredField("isConnected");
        connected.setAccessible(true);
        connected.setBoolean(client, true);
        transmitter = new UltiPanelLogTransmitter(client, "test-server");
    }

    @AfterEach
    void tearDown() {
        transmitter.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private List<String> sentLogMessages() {
        List<String> messages = new ArrayList<>();
        for (String text : client.wire) {
            JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
            if ("log_batch".equals(frame.get("type").getAsString())) {
                for (JsonElement log : frame.getAsJsonArray("data")) {
                    messages.add(log.getAsJsonObject().get("message").getAsString());
                }
            }
        }
        return messages;
    }

    @Test
    @DisplayName("while the socket is closing nothing is lost, and the batch goes out once it is open again")
    void batchSentWhileClosingIsKept() {
        transmitter.info("A", "test");
        transmitter.info("B", "test");

        client.socketOpen = false;          // closing: onClose has not run, the flag still reads true
        transmitter.flushLogs();
        assertThat(sentLogMessages()).isEmpty();

        client.socketOpen = true;           // reconnected on the same client
        transmitter.flushLogs();

        assertThat(sentLogMessages()).containsExactly("A", "B");
    }

    @Test
    @DisplayName("the client does not report itself connected while its socket is not open")
    void clientIsNotConnectedWhileItsSocketIsNotOpen() {
        client.socketOpen = false;

        assertThat(client.isConnected()).isFalse();
        client.socketOpen = true;
        assertThat(client.isConnected()).isTrue();
    }
}
