package com.ultikits.ultitools.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Tracer for the v2 telemetry encoders: a live MockBukkit server, a welcome, and the four
 * contract snapshots the panel expects. {@link RecordingChannel} validates every frame against the
 * vendored JSON Schemas, so "the frame was recorded" already means "the frame is schema-valid".
 */
@DisplayName("UltiCloud v2 telemetry end to end")
class TelemetryEndToEndTest {
    private ServerMock server;
    private ServerMonitorManager monitor;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        world("world");
        world("world_nether");
        server.addPlayer("alice");
        server.addPlayer("bob");
        PluginMock plugin = MockBukkit.createMockPlugin("UltiTools", "6.3.1-SNAPSHOT");
        Logger logger = Logger.getLogger("TelemetryEndToEndTest");
        UltiTools ultiTools = TestHelper.mockUltiToolsInstance(tools -> {
            lenient().when(tools.getLogger()).thenReturn(logger);
            lenient().when(tools.getDescription()).thenReturn(plugin.getDescription());
        });
        assertThat(ultiTools).isNotNull();
        monitor = new ServerMonitorManager();
    }

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.stopMonitoring();
        }
        MockBukkitHelper.safeUnmock();
    }

    private World world(String name) {
        World existing = server.getWorld(name);
        return existing != null ? existing : server.addSimpleWorld(name);
    }

    @Test
    @DisplayName("a welcome sends status, players, plugins and worlds from the live server state")
    void welcomeSendsTheFourContractSnapshots() {
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = new AgentTelemetry(monitor, () -> true, () -> 1000L, Runnable::run);

        telemetry.onWelcome(channel);

        List<JsonObject> frames = channel.frames();
        assertThat(frames).extracting(frame -> frame.get("type").getAsString())
                .containsExactly("status", "players.snapshot", "plugins.snapshot", "worlds.snapshot");

        JsonObject status = payload(frames, "status");
        assertThat(status.keySet()).containsExactlyInAnyOrder("playerCount", "maxPlayers", "tps",
                "cpuPercent", "memUsedMb", "memMaxMb", "software", "ultitoolsVersion",
                "uptimeSeconds", "worldCount");
        assertThat(status.get("playerCount").getAsInt()).isEqualTo(2);
        assertThat(status.get("maxPlayers").getAsInt()).isEqualTo(Math.min(Bukkit.getMaxPlayers(), 100000));
        assertThat(status.get("tps").getAsDouble()).isBetween(0.0, 100.0);
        assertThat(status.get("software").getAsString()).isNotEmpty();
        assertThat(status.get("ultitoolsVersion").getAsString()).isEqualTo("6.3.1-SNAPSHOT");
        assertThat(status.get("worldCount").getAsInt()).isEqualTo(2);

        JsonObject players = payload(frames, "players.snapshot").getAsJsonArray("players").get(0).getAsJsonObject();
        assertThat(players.keySet()).containsExactlyInAnyOrder("uuid", "name", "world", "health",
                "maxHealth", "gameMode", "op");
        List<String> names = new ArrayList<>();
        for (JsonElement element : payload(frames, "players.snapshot").getAsJsonArray("players")) {
            names.add(element.getAsJsonObject().get("name").getAsString());
        }
        assertThat(names).containsExactly("alice", "bob");

        List<String> worldNames = new ArrayList<>();
        for (JsonElement element : payload(frames, "worlds.snapshot").getAsJsonArray("worlds")) {
            worldNames.add(element.getAsJsonObject().get("name").getAsString());
        }
        assertThat(worldNames).containsExactly("world", "world_nether");

        JsonObject self = null;
        for (JsonElement element : payload(frames, "plugins.snapshot").getAsJsonArray("plugins")) {
            if ("UltiTools".equals(element.getAsJsonObject().get("name").getAsString())) {
                self = element.getAsJsonObject();
            }
        }
        assertThat(self).isNotNull();
        assertThat(self.keySet()).containsExactlyInAnyOrder("name", "version", "description", "enabled");
        assertThat(self.get("version").getAsString()).isEqualTo("6.3.1-SNAPSHOT");
        assertThat(self.get("enabled").getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("a welcome arriving on the socket thread samples on the main thread, not with an empty sample")
    void welcomeFromTheSocketThreadSamplesOnTheMainThread() throws Exception {
        TestMainThread mainThread = new TestMainThread();
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = new AgentTelemetry(monitor, () -> true, () -> 1000L, mainThread);

        TestMainThread.runOffTheMainThread(() -> telemetry.onWelcome(channel));

        assertThat(channel.frames()).as("the socket thread may neither sample nor send").isEmpty();
        mainThread.drain();

        List<JsonObject> frames = channel.frames();
        assertThat(payload(frames, "status").get("playerCount").getAsInt()).isEqualTo(2);
        assertThat(payload(frames, "status").get("worldCount").getAsInt()).isEqualTo(2);
        assertThat(payload(frames, "players.snapshot").getAsJsonArray("players")).hasSize(2);
    }

    @Test
    @DisplayName("a disabled telemetry capability records nothing on welcome or tick")
    void disabledTelemetryRecordsNothing() {
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = new AgentTelemetry(monitor, () -> false, () -> 0L, Runnable::run);

        telemetry.onWelcome(channel);
        telemetry.tick(channel);

        assertThat(channel.frames()).isEmpty();
    }

    private static JsonObject payload(List<JsonObject> frames, String type) {
        for (JsonObject frame : frames) {
            if (type.equals(frame.get("type").getAsString())) {
                return frame.getAsJsonObject("payload");
            }
        }
        throw new AssertionError("No " + type + " frame was recorded");
    }
}
