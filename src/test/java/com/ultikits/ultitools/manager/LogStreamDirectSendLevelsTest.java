package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Case B (29b95cd4): the panel's {@code log_stream} {@code levels} must govern every log entry
 * the framework sends directly, not only the records {@link SystemLogHandler} publishes.
 * <p>
 * Every levels value is applied through the real panel path,
 * {@link LogStreamManager#handleLogStreamMessage(JsonObject)} with action {@code config}, and
 * the {@code config_updated} answer is asserted before any entry point runs. The handler is a
 * real {@link SystemLogHandler} over the same mocked transmitter the manager sends through, so
 * a record published to the handler is a positive control that the transmitter is wired.
 */
@DisplayName("LogStreamManager direct entries obey the panel's log levels")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection wires the manager's private collaborators
class LogStreamDirectSendLevelsTest {

    private static final String SOURCE = "plugin:UltiTools";
    private static final String CHAT_LINE = "[chat] <MatrixChatter> hello";

    private LogStreamManager manager;
    private UltiPanelWebSocketClient webSocketClient;
    private UltiPanelLogTransmitter transmitter;
    private SystemLogHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        Logger mockLogger = mock(Logger.class);
        FileConfiguration mockConfig = mock(FileConfiguration.class);
        // Stubs are finished before the mock is published through UltiTools.getInstance() (#250).
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(mockConfig);
        });

        webSocketClient = mock(UltiPanelWebSocketClient.class);
        when(webSocketClient.isConnected()).thenReturn(true);
        lenient().when(webSocketClient.getServerId()).thenReturn("test-server-uuid");

        resetSingleton();
        manager = LogStreamManager.getInstance();

        transmitter = mock(UltiPanelLogTransmitter.class);
        handler = new SystemLogHandler(transmitter);
        setField("systemLogHandler", handler);
        setField("logTransmitter", transmitter);
        setField("webSocketClient", webSocketClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            try {
                manager.shutdown();
            } catch (Exception ignored) {
                // shutdown logs through the mocked plugin; the handler sweep below is what matters
            }
        }
        Logger rootLogger = Logger.getLogger("");
        for (java.util.logging.Handler leaked : rootLogger.getHandlers()) {
            if (leaked instanceof SystemLogHandler) {
                rootLogger.removeHandler(leaked);
            }
        }
        resetSingleton();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static void resetSingleton() throws Exception {
        Field instanceField = LogStreamManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = LogStreamManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    /**
     * Sends a {@code log_stream} {@code config} request with the given levels through the real
     * panel path and returns the {@code status} the manager answered with.
     */
    private String sendLevelsRequest(String... levels) {
        JsonObject data = new JsonObject();
        data.addProperty("action", "config");
        data.addProperty("clientId", "client-levels");
        JsonArray levelsArray = new JsonArray();
        for (String level : levels) {
            levelsArray.add(level);
        }
        data.add("levels", levelsArray);

        clearInvocations(webSocketClient);
        manager.handleLogStreamMessage(data);

        ArgumentCaptor<JsonObject> response = ArgumentCaptor.forClass(JsonObject.class);
        verify(webSocketClient, times(1)).sendMessage(response.capture());
        List<JsonObject> sent = response.getAllValues();
        JsonObject answer = sent.get(sent.size() - 1);
        assertThat(answer.get("type").getAsString()).isEqualTo("log_stream_response");
        return answer.getAsJsonObject("data").get("status").getAsString();
    }

    /** Applies the given levels through the real panel path and asserts they were accepted. */
    private void applyLevelsThroughPanel(String... levels) {
        assertThat(sendLevelsRequest(levels))
                .as("the levels request is accepted")
                .isEqualTo("config_updated");
    }

    private static PlayerJoinEvent joinEvent(String playerName) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(playerName);
        return new PlayerJoinEvent(player, playerName + " joined");
    }

    private static PlayerQuitEvent quitEvent(String playerName) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(playerName);
        return new PlayerQuitEvent(player, playerName + " left");
    }

    /**
     * Drives every direct log entry point once: the five that send at {@code info} and the chat
     * line that sends at {@code debug}.
     * <p>
     * The chat entry is {@code sendCustomLog("debug", ..., "plugin:UltiTools")}, exactly the call
     * {@code PlayerEventManager#onPlayerChat} makes; {@code PlayerEventManager}'s join and quit
     * handlers call {@link LogStreamManager#sendPlayerEventLog}, driven here directly.
     */
    private void runEveryDirectEntry() {
        manager.onPlayerJoin(joinEvent("MatrixJoiner"));
        manager.onPlayerQuit(quitEvent("MatrixQuitter"));
        manager.sendPlayerEventLog("KICK", "MatrixKicked", "kicked");
        manager.sendPluginActionLog("MATRIX_ACTION", "matrix details");
        manager.onServerLoad(new ServerLoadEvent(ServerLoadEvent.LoadType.STARTUP));
        manager.sendCustomLog("debug", CHAT_LINE, SOURCE);
    }

    /** Verifies each of the five info entries driven by {@link #runEveryDirectEntry()} was sent once. */
    private void verifyEveryInfoEntrySent() {
        verify(transmitter, times(1)).sendLog(eq("info"), contains("JOIN: MatrixJoiner"), eq(SOURCE), any());
        verify(transmitter, times(1)).sendLog(eq("info"), contains("QUIT: MatrixQuitter"), eq(SOURCE), any());
        verify(transmitter, times(1)).sendLog(eq("info"), contains("KICK: MatrixKicked"), eq(SOURCE), any());
        verify(transmitter, times(1)).sendLog(eq("info"), contains("MATRIX_ACTION: matrix details"), eq(SOURCE), any());
        verify(transmitter, times(1)).sendLog(eq("info"), any(), eq("server"), any());
        verify(transmitter, times(5)).sendLog(eq("info"), any(), any(), any());
    }

    private void invokeSendInitializationLogs() throws Exception {
        Method method = LogStreamManager.class.getDeclaredMethod("sendInitializationLogs");
        method.setAccessible(true);
        method.invoke(manager);
    }

    @Test
    @DisplayName("case 29b95cd4: under levels [error] a player join line is not streamed")
    void playerJoinLineObeysPanelLevels() {
        applyLevelsThroughPanel("error");

        // Positive control: the level state is applied and the transmitter is wired -- an
        // error record published to the handler reaches sendLog at error.
        handler.publish(new LogRecord(Level.SEVERE, "direct-levels-severe-control"));
        verify(transmitter, times(1))
                .sendLog(eq("error"), contains("direct-levels-severe-control"), any(), any());
        clearInvocations(transmitter);

        manager.onPlayerJoin(joinEvent("LevelsPlayer"));

        verify(transmitter, never()).sendLog(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the two stream-start lines obey the panel's levels")
    void streamStartLinesObeyLevels() throws Exception {
        // Positive control: under [info] both start lines reach the transmitter.
        applyLevelsThroughPanel("info");
        invokeSendInitializationLogs();
        verify(transmitter, times(2)).info(any(), any());
        clearInvocations(transmitter);

        applyLevelsThroughPanel("error");
        invokeSendInitializationLogs();

        verify(transmitter, never()).info(any(), any());
        verify(transmitter, never()).sendLog(any(), any(), any(), any());
    }

    // Characterization control (passes after Task 1): every direct entry is reachable under all
    // four levels, then levels [] stops every one of them.
    @Test
    @DisplayName("levels [] stops every direct entry")
    void emptyLevelsStopEveryDirectEntry() {
        applyLevelsThroughPanel("info", "warning", "error", "debug");
        runEveryDirectEntry();
        verifyEveryInfoEntrySent();
        verify(transmitter, times(1)).sendLog(eq("debug"), eq(CHAT_LINE), eq(SOURCE), any());
        clearInvocations(transmitter);

        applyLevelsThroughPanel();
        runEveryDirectEntry();

        verify(transmitter, never()).sendLog(any(), any(), any(), any());
    }

    // Characterization control (passes after Task 1).
    @Test
    @DisplayName("levels [info] keeps the five info entries and drops the debug chat line")
    void infoLevelsKeepInfoEntriesAndDropDebugChat() {
        applyLevelsThroughPanel("info");
        runEveryDirectEntry();

        verifyEveryInfoEntrySent();
        verify(transmitter, never()).sendLog(eq("debug"), any(), any(), any());
    }

    // Characterization control (passes after Task 1).
    @Test
    @DisplayName("levels [debug] keeps the chat line and drops the info entries")
    void debugLevelsKeepChatAndDropInfo() {
        applyLevelsThroughPanel("debug");
        runEveryDirectEntry();

        verify(transmitter, times(1)).sendLog(eq("debug"), eq(CHAT_LINE), eq(SOURCE), any());
        verify(transmitter, never()).sendLog(eq("info"), any(), any(), any());
    }

    // Characterization control (passes after Task 1).
    @Test
    @DisplayName("level match is case-insensitive, a null or blank level counts as info, and the sent level text is unchanged")
    void levelMatchIsCaseInsensitiveAndBlankMeansInfo() {
        applyLevelsThroughPanel("info");
        manager.sendCustomLog("INFO", "upper-case-entry", "src");
        manager.sendCustomLog("  ", "blank-entry", "src");
        manager.sendCustomLog(null, "null-entry", "src");

        verify(transmitter, times(1)).sendLog("INFO", "upper-case-entry", "src", null);
        verify(transmitter, times(1)).sendLog("  ", "blank-entry", "src", null);
        verify(transmitter, times(1)).sendLog(isNull(), eq("null-entry"), eq("src"), isNull());
        clearInvocations(transmitter);

        applyLevelsThroughPanel("error");
        // Positive control: a mixed-case enabled level is still sent, with its text unchanged.
        manager.sendCustomLog("ERROR", "upper-case-error", "src");
        verify(transmitter, times(1)).sendLog("ERROR", "upper-case-error", "src", null);

        manager.sendCustomLog("INFO", "upper-case-entry", "src");
        manager.sendCustomLog("  ", "blank-entry", "src");
        manager.sendCustomLog(null, "null-entry", "src");

        verify(transmitter, never()).sendLog(any(), eq("upper-case-entry"), any(), any());
        verify(transmitter, never()).sendLog(any(), eq("blank-entry"), any(), any());
        verify(transmitter, never()).sendLog(any(), eq("null-entry"), any(), any());
    }

    // Characterization control (passes after Task 1): with no handler wired there is no level
    // state, a levels request changes nothing, and every direct entry is still sent.
    @Test
    @DisplayName("with no log handler wired, direct sends stay unfiltered")
    void noHandlerKeepsDirectSendsUnfiltered() throws Exception {
        setField("systemLogHandler", null);

        assertThat(sendLevelsRequest("error"))
                .as("no level state exists to apply the request to")
                .isEqualTo("config_unchanged");
        runEveryDirectEntry();
        invokeSendInitializationLogs();

        verifyEveryInfoEntrySent();
        verify(transmitter, times(1)).sendLog(eq("debug"), eq(CHAT_LINE), eq(SOURCE), any());
        verify(transmitter, times(2)).info(any(), any());
    }
}
