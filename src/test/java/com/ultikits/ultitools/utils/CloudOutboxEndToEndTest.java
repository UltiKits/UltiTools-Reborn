package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;

/**
 * The tracer of plan 22-07 (D-14, D-15, D-17, S-12, S-15, S-16): a linked server captures a join
 * while the connection is down, persists it on the 30 s cadence, reloads the same entry with the
 * same envelope id after a restart, and loses the file for good when the identity goes --
 * no tick, no invalidation and no shutdown flush ever recreates it afterwards.
 */
@DisplayName("Cloud outbox end to end -- capture, persist, restart, identity cleared")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CloudOutboxEndToEndTest {

    private static final String SERVER_ID = "01JTESTSERVER00000000000000";
    private static final long FLUSH_INTERVAL_MS = 30_000L;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private Logger mockLogger;
    /** The same agent seams {@code CloudReconnectStateMachineTest} uses: no real socket, no sleep. */
    private final CloudReconnectStateMachineTest.AgentRuntime runtime =
            new CloudReconnectStateMachineTest.AgentRuntime();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        mockLogger = mock(Logger.class);
        // Consumer overload: stub first, publish afterwards (TestHelper javadoc, issue #250).
        TestHelper.mockUltiToolsInstance(ultiTools ->
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger));
        // Fresh session, credential file and state file under the temporary directory.
        CloudTestAccess.useTemporaryCredentials(tempDir);
        runtime.install();
        Bukkit.getPluginManager().registerEvents(new CloudOutboxListener(), plugin);
    }

    @AfterEach
    void tearDown() {
        CloudTestAccess.reset();
        clearUltiToolsInstance();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("join while disconnected -> outbox -> 30 s flush -> restart reloads the same id -> reject deletes it")
    void joinSurvivesARestartAndDiesWithTheIdentity() throws IOException {
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        runtime.runDue();

        CloudSession session = CloudSession.current();
        assertThat(session.cloudOutbox()).isNotNull();
        assertThat(stateFile()).doesNotExist();

        PlayerMock player = server.addPlayer("Steve");
        List<CloudOutbox.Entry> entries = session.cloudOutbox().unacked();
        assertThat(entries).hasSize(1);
        CloudOutbox.Entry entry = entries.get(0);
        assertThat(entry.type()).isEqualTo("player.join");
        assertThat(Ulid.isValid(entry.id())).isTrue();
        JsonObject payload = entry.payload();
        assertThat(payload.keySet()).containsExactlyInAnyOrder("playerUuid", "playerName");
        assertThat(payload.get("playerUuid").getAsString()).isEqualTo(player.getUniqueId().toString());
        assertThat(payload.get("playerName").getAsString()).isEqualTo("Steve");

        // The 30 s tick on the session's agent scheduler is what persists (no sleeping).
        runtime.advance(FLUSH_INTERVAL_MS);
        assertThat(stateFile()).exists();
        if (posix()) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(stateFile())))
                    .isEqualTo("rw-------");
        }
        assertThat(persisted().get("serverId").getAsString()).isEqualTo(SERVER_ID);

        // Restart: invalidate (flushes), fresh session, adopt the saved credential again.
        CloudSession.startNew();
        assertThat(CloudSession.current().cloudOutbox()).isNull();
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        List<CloudOutbox.Entry> reloaded = CloudSession.current().cloudOutbox().unacked();
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).id()).isEqualTo(entry.id());
        assertThat(reloaded.get(0).type()).isEqualTo("player.join");
        assertThat(reloaded.get(0).payload().get("playerName").getAsString()).isEqualTo("Steve");

        // The identity goes away: the credential is rejected and the state goes with it.
        PluginInitiationUtils.initWebsocket();
        runtime.runDue();
        CloudSession linked = CloudSession.current();
        runtime.last().refuse(401);
        assertThat(linked.hasCredential()).isFalse();
        assertThat(linked.cloudOutbox()).isNull();
        assertThat(stateFile()).doesNotExist();

        // Nothing recreates it: not the 30 s tick, not a later join, not the shutdown flush.
        runtime.advance(FLUSH_INTERVAL_MS * 2);
        server.addPlayer("Alex");
        PluginInitiationUtils.stopWebsocket();
        assertThat(stateFile()).doesNotExist();
    }

    @Test
    @DisplayName("positive control: the shutdown flush persists a pending join when the identity survives")
    void shutdownFlushPersistsForALinkedServer() throws IOException {
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        runtime.runDue();

        server.addPlayer("Steve");
        assertThat(stateFile()).doesNotExist();

        // No 30 s tick has run: only the shutdown flush can write this file.
        PluginInitiationUtils.stopWebsocket();
        assertThat(stateFile()).exists();
        assertThat(persisted().get("serverId").getAsString()).isEqualTo(SERVER_ID);
        assertThat(persisted().getAsJsonArray("events")).hasSize(1);
    }

    @Test
    @DisplayName("an unlinked server never creates cloud-outbox.json")
    void unlinkedServerWritesNothing() {
        server.addPlayer("Steve");
        assertThat(CloudSession.current().cloudOutbox()).isNull();

        CloudSession.current().flushCloudState();
        PluginInitiationUtils.stopWebsocket();
        assertThat(stateFile()).doesNotExist();
    }

    private Path stateFile() {
        return tempDir.resolve("cloud-outbox.json");
    }

    private JsonObject persisted() {
        try {
            return JsonParser.parseString(new String(Files.readAllBytes(stateFile()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("could not read the state file", e);
        }
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

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
