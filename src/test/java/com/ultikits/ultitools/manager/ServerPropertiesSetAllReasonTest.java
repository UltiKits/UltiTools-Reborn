package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * A {@code set_all} reply names why each refused key was not written, and the server log names each refusal once
 * (17-66 review round 1, R66-I1). Before this, the single-key {@code set} reply carried the reason but the
 * {@code set_all} reply carried only the {@code failed} key array, and a refused key in a batch was logged twice:
 * once by the manager's own refusal warning and again, with the same reason, by the batch summary line.
 * Reasons never carry a value.
 */
class ServerPropertiesSetAllReasonTest {

    @TempDir
    Path directory;

    private Path file;
    private ServerPropertiesManager manager;
    private UltiPanelWebSocketClient socket;
    private final List<String> logLines = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                logLines.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
            // Records are kept in memory; there is nothing to flush.
        }

        @Override
        public void close() {
            // No resource is held; there is nothing to close.
        }
    };

    @BeforeEach
    void setUp() {
        file = directory.resolve("server.properties");
        manager = new ServerPropertiesManager(directory.toFile());
        socket = mock(UltiPanelWebSocketClient.class);
        lenient().when(socket.getServerId()).thenReturn("srv-1");
        manager.setWebSocketClient(socket);
        Logger.getLogger(ServerPropertiesManager.class.getName()).addHandler(capture);
        Logger pluginLogger = mock(Logger.class);
        doAnswer(invocation -> {
            if (((Level) invocation.getArgument(0)).intValue() >= Level.WARNING.intValue()) {
                logLines.add(invocation.getArgument(1));
            }
            return null;
        }).when(pluginLogger).log(any(Level.class), anyString());
        TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger));
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws ReflectiveOperationException {
        Logger.getLogger(ServerPropertiesManager.class.getName()).removeHandler(capture);
        // UltiTools.ultiTools is a global static field; clear it so it does not leak into later test classes.
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Test
    void theSetAllReplyNamesTheRefusalReasonOfEachFailedKeyWithoutAValue() throws IOException {
        byte[] original = ("# keep me\nmax-players=20\nview-distance=10\nview-distance=11\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        JsonObject reply = setAll("max-players", "30", "view-distance", "6");

        assertThat(reply.get("success").getAsBoolean()).isFalse();
        assertThat(reply.getAsJsonArray("failed").toString()).isEqualTo("[\"view-distance\"]");
        assertThat(reply.has("failureReasons")).as("set_all reply carries the reasons").isTrue();
        JsonObject reasons = reply.getAsJsonObject("failureReasons");
        assertThat(reasons.keySet()).containsExactly("view-distance");
        assertThat(reasons.get("view-distance").getAsString()).contains("more than one line").contains("3")
                .contains("4").doesNotContain("=6").doesNotContain("=10").doesNotContain("=11");
    }

    @Test
    void aRefusedKeyInABatchIsLoggedOnce() throws IOException {
        Files.write(file, ("max-players=20\nview-distance=10\nview-distance=11\n").getBytes(StandardCharsets.UTF_8));

        setAll("max-players", "30", "view-distance", "6");

        long naming = logLines.stream().filter(line -> line.contains("view-distance")).count();
        assertThat(naming).as("log lines naming the refused key: %s", logLines).isEqualTo(1);
        assertThat(logLines.get(0)).contains("more than one line");
    }

    @Test
    void aNonWhitelistedKeyIsStillNamedInTheBatchSummaryLine() throws IOException {
        // Control: keys that were not refused by the one-line write keep the batch summary line.
        Files.write(file, ("max-players=20\nrcon.password=secret\n").getBytes(StandardCharsets.UTF_8));

        setAll("max-players", "30", "rcon.password", "hacked");

        assertThat(logLines).hasSize(1);
        assertThat(logLines.get(0)).contains("rcon.password").doesNotContain("hacked");
    }

    private JsonObject setAll(String... keysAndValues) {
        JsonObject values = new JsonObject();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.addProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        JsonObject request = new JsonObject();
        request.addProperty("action", "set_all");
        request.add("values", values);
        manager.handleServerProperties(request);
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(socket).sendMessage(captor.capture());
        return captor.getValue();
    }
}
