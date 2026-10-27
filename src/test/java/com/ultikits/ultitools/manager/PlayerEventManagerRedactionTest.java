package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A {@code player_command} event carries only the normalised command name, registered or not;
 * chat events and the chat debug log line are redacted.
 */
@DisplayName("PlayerEventManager redaction of cloud-bound events")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "deprecation"})
class PlayerEventManagerRedactionTest {

    private final SensitiveCommandRegistry registry = new SensitiveCommandRegistry();
    private ServerMock server;
    private PlayerEventManager manager;
    private UltiPanelWebSocketClient client;
    private LogStreamManager logStreamManager;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        Logger mockLogger = mock(Logger.class);
        client = mock(UltiPanelWebSocketClient.class);
        when(client.isConnected()).thenReturn(true);
        when(client.getServerId()).thenReturn("redaction-server");
        logStreamManager = mock(LogStreamManager.class);

        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getLogStreamManager()).thenReturn(logStreamManager);
            lenient().when(ultiTools.getSensitiveCommandRegistry()).thenReturn(registry);
        });

        manager = new PlayerEventManager();
        Field wsField = PlayerEventManager.class.getDeclaredField("webSocketClient");
        wsField.setAccessible(true);
        wsField.set(manager, client);
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private JsonObject sentMessage() {
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(client).sendMessage(captor.capture());
        return captor.getValue();
    }

    private JsonObject sendCommand(String commandLine) {
        PlayerMock player = server.addPlayer();
        manager.onPlayerCommandPreprocess(new PlayerCommandPreprocessEvent(player, commandLine));
        return sentMessage();
    }

    @Test
    @DisplayName("an unregistered command's event carries the name only, no arguments")
    void unregisteredCommandIsNameOnly() {
        JsonObject message = sendCommand("/msg Alex hi");

        JsonObject data = message.getAsJsonObject("data");
        assertThat(data.get("command").getAsString()).isEqualTo("msg");
        assertThat(data.has("cancelled")).isTrue();
        assertThat(message.toString()).doesNotContain("Alex hi");
    }

    @Test
    @DisplayName("the name is normalised: slash and namespace stripped, lower-cased")
    void nameIsNormalised() {
        assertThat(sendCommand("/Essentials:SPAWN").getAsJsonObject("data").get("command").getAsString())
                .isEqualTo("spawn");
    }

    @Test
    @DisplayName("a registered command's event carries the name only")
    void registeredCommandIsNameOnly() {
        registry.register("login");

        JsonObject message = sendCommand("/login pw");

        assertThat(message.getAsJsonObject("data").get("command").getAsString()).isEqualTo("login");
        assertThat(message.toString()).doesNotContain("login pw");
    }

    @Test
    @DisplayName("a chat message and its debug log line are redacted")
    void chatIsRedacted() {
        registry.register("login");
        PlayerMock player = server.addPlayer();

        manager.onPlayerChat(new PlayerChatEvent(player, "look: /login pw"));

        JsonObject data = sentMessage().getAsJsonObject("data");
        assertThat(data.get("message").getAsString()).isEqualTo("look: /login ***");

        ArgumentCaptor<String> logLine = ArgumentCaptor.forClass(String.class);
        verify(logStreamManager).sendCustomLog(eq("debug"), logLine.capture(), eq("plugin:UltiTools"));
        assertThat(logLine.getValue()).contains("look: /login ***").doesNotContain("/login pw");
    }

    @Test
    @DisplayName("an unregistered chat message is sent verbatim")
    void unregisteredChatVerbatim() {
        registry.register("login");
        PlayerMock player = server.addPlayer();

        manager.onPlayerChat(new PlayerChatEvent(player, "see /home set base"));

        assertThat(sentMessage().getAsJsonObject("data").get("message").getAsString())
                .isEqualTo("see /home set base");
    }
}
