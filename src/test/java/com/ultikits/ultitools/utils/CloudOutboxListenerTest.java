package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;

/**
 * The listener and state rules of plan 22-07 beyond the tracer: capability gating, ordering, the
 * bounded buffer's accounting, the identity lifecycle (logout, a later login) and the payload's
 * exact key set (T-22-29).
 */
@DisplayName("Cloud outbox listener -- gating, ordering, accounting, identity lifecycle")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CloudOutboxListenerTest {

    private static final String SERVER_ID = "01JTESTSERVER00000000000000";
    private static final String OTHER_SERVER_ID = "01JOTHER000000000000000000";
    private static final String SEEN_COMMAND_ID = "01ARYZ6S41TSV4RRFFQ69G5FAV";
    private static final long FLUSH_INTERVAL_MS = 30_000L;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Logger mockLogger;
    private YamlConfiguration config;
    private CloudOutboxListener listener;
    private final CloudReconnectStateMachineTest.AgentRuntime runtime =
            new CloudReconnectStateMachineTest.AgentRuntime();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        mockLogger = mock(Logger.class);
        config = new YamlConfiguration();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(config);
        });
        CloudTestAccess.useTemporaryCredentials(tempDir);
        runtime.install();
        listener = new CloudOutboxListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    @AfterEach
    void tearDown() {
        CloudTestAccess.reset();
        clearUltiToolsInstance();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("quit -> player.leave; a join then a quit of the same player keep enqueue order")
    void quitUsesTheSamePayloadBuilderAndOrderIsKept() throws IOException {
        linkWithConnection();

        PlayerMock player = server.addPlayer("Steve");
        Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(player, "left"));

        List<CloudOutbox.Entry> entries = CloudSession.current().cloudOutbox().unacked();
        assertThat(entries).extracting(CloudOutbox.Entry::type)
                .containsExactly("player.join", "player.leave");
        JsonObject leave = entries.get(1).payload();
        assertThat(leave.keySet()).containsExactlyInAnyOrder("playerUuid", "playerName");
        assertThat(leave.get("playerName").getAsString()).isEqualTo("Steve");
    }

    @Test
    @DisplayName("player-events off -> nothing enqueued; flipping it back on -> enqueued again")
    void capabilityGateIsReadPerEvent() throws IOException {
        linkWithConnection();
        config.set("ultipanel.capabilities.player-events", false);

        server.addPlayer("Steve");
        assertThat(CloudSession.current().cloudOutbox().unacked()).isEmpty();

        config.set("ultipanel.capabilities.player-events", true);
        server.addPlayer("Alex");
        assertThat(CloudSession.current().cloudOutbox().unacked()).extracting(CloudOutbox.Entry::type)
                .containsExactly("player.join");
    }

    @Test
    @DisplayName("1001 joins -> 1000 kept, one dropped, and the dropped count survives flush + reload")
    void capacityAccountingSurvivesAReload() throws IOException {
        linkWithConnection();

        for (int i = 0; i < CloudOutbox.DEFAULT_CAPACITY + 1; i++) {
            listener.onJoin(joinOf("player" + i));
        }

        CloudOutbox outbox = CloudSession.current().cloudOutbox();
        assertThat(outbox.unacked()).hasSize(CloudOutbox.DEFAULT_CAPACITY);
        assertThat(outbox.droppedSinceLastReport()).isEqualTo(1L);
        // The oldest join is the one that was dropped.
        assertThat(outbox.unacked().get(0).payload().get("playerName").getAsString()).isEqualTo("player1");

        runtime.advance(FLUSH_INTERVAL_MS);
        assertThat(stateFile()).exists();

        CloudSession.startNew();
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        CloudOutbox reloaded = CloudSession.current().cloudOutbox();
        assertThat(reloaded.unacked()).hasSize(CloudOutbox.DEFAULT_CAPACITY);
        assertThat(reloaded.droppedSinceLastReport()).isEqualTo(1L);
    }

    @Test
    @DisplayName("logout deletes the file and clears the dedupe set; neither the tick nor the shutdown flush recreates it")
    void logoutDiscardsTheStateForGood() throws Exception {
        linkWithConnection();
        CloudSession session = CloudSession.current();
        session.commandDedupe().firstSeen(SEEN_COMMAND_ID);
        server.addPlayer("Steve");
        runtime.advance(FLUSH_INTERVAL_MS);
        assertThat(stateFile()).exists();
        CloudOutbox capturedOutbox = session.cloudOutbox();
        CommandDedupeSet capturedDedupe = session.commandDedupe();

        CloudAuthManager.logout();

        assertThat(stateFile()).doesNotExist();
        // The credential itself is cleared by the asynchronous revoke 22-06 schedules
        // (CloudLogoutRevokeTest covers it); this plan owns the buffered state, not the credential.
        assertThat(session.cloudOutbox()).isNull();
        assertThat(session.commandDedupe()).isNull();
        assertThat(capturedOutbox.unacked()).isEmpty();
        assertThat(capturedDedupe.snapshot()).isEmpty();

        runtime.advance(FLUSH_INTERVAL_MS * 2);
        server.addPlayer("Alex");
        PluginInitiationUtils.stopWebsocket();
        assertThat(stateFile()).doesNotExist();
    }

    @Test
    @DisplayName("a later login with another serverId starts empty even when an old state file was restored by hand")
    void aDifferentServerIdStartsFromAnEmptyState() throws IOException {
        linkWithConnection();
        server.addPlayer("Steve");
        runtime.advance(FLUSH_INTERVAL_MS);
        byte[] previous = Files.readAllBytes(stateFile());
        CloudAuthManager.logout();
        assertThat(stateFile()).doesNotExist();

        // Restored by hand: the file carries the previous identity's buffered event.
        Files.write(stateFile(), previous);
        assertThat(stateFile()).exists();

        CloudTestAccess.storeLinkedCredential("Elsewhere", OTHER_SERVER_ID);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();

        CloudOutbox outbox = CloudSession.current().cloudOutbox();
        assertThat(outbox).isNotNull();
        assertThat(outbox.unacked()).isEmpty();
    }

    @Test
    @DisplayName("a kick produces exactly one player.leave -- the quit that follows it, not two")
    void aKickIsOneLeave() throws IOException {
        linkWithConnection();
        PlayerMock player = server.addPlayer("Steve");

        // MockBukkit's kickPlayer fires the kick event only; a real server follows it with the quit,
        // which is the event the outbox captures.
        player.kickPlayer("reason");
        Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(player, "kicked"));

        assertThat(CloudSession.current().cloudOutbox().unacked())
                .extracting(CloudOutbox.Entry::type)
                .containsExactly("player.join", "player.leave");
    }

    @Test
    @DisplayName("the 30 s task re-arms itself: a join made after the first tick is saved by the second tick")
    void everyTickReArmsSoLaterChangesAreSaved() throws IOException {
        linkWithConnection();

        server.addPlayer("Steve");
        runtime.advance(FLUSH_INTERVAL_MS);
        assertThat(savedPlayerNames()).containsExactly("Steve");

        // The first tick has finished; this join is saved only if that tick re-armed itself, because
        // nothing else writes the state file while the connection stays up.
        server.addPlayer("Alex");
        assertThat(savedPlayerNames()).containsExactly("Steve");

        runtime.advance(FLUSH_INTERVAL_MS);
        assertThat(savedPlayerNames()).containsExactly("Steve", "Alex");
    }

    @Test
    @DisplayName("the shutdown flush writes before returning even when the 30 s task never ran")
    void shutdownFlushWritesPendingChanges() throws IOException {
        linkWithConnection();
        server.addPlayer("Steve");
        assertThat(stateFile()).doesNotExist();

        PluginInitiationUtils.stopWebsocket();

        assertThat(stateFile()).exists();
    }

    @Test
    @DisplayName("the payload holds exactly playerUuid and playerName -- never an address, display name or location")
    void payloadHasExactlyTheTwoContractKeys() {
        PlayerMock player = server.addPlayer("Steve");

        JsonObject payload = CloudOutboxListener.payloadFor(player);

        assertThat(payload.keySet()).containsExactlyInAnyOrder("playerUuid", "playerName");
        assertThat(payload.get("playerUuid").getAsString()).isEqualTo(player.getUniqueId().toString());
        assertThat(payload.get("playerName").getAsString()).isEqualTo("Steve");
    }

    // ---- fixtures ----

    /** Links the server and starts the session's connection, so the 30 s flush task exists. */
    private void linkWithConnection() throws IOException {
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        runtime.runDue();
    }

    private PlayerJoinEvent joinOf(String name) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        lenient().when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private Path stateFile() {
        return tempDir.resolve("cloud-outbox.json");
    }

    /** The player names of the events the state file holds, in the order it holds them. */
    private List<String> savedPlayerNames() throws IOException {
        JsonObject state = JsonParser.parseString(
                new String(Files.readAllBytes(stateFile()), StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> names = new ArrayList<>();
        for (JsonElement event : state.getAsJsonArray("events")) {
            names.add(event.getAsJsonObject().getAsJsonObject("payload").get("playerName").getAsString());
        }
        return names;
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the repository's test idiom for the plugin singleton
    private static void clearUltiToolsInstance() {
        try {
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not clear the UltiTools singleton", e);
        }
    }
}
