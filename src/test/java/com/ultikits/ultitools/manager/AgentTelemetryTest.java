package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cadence and change detection of the v2 telemetry encoders, driven only by the injected clock:
 * no wall-clock sleeps and no Bukkit scheduler.
 */
@DisplayName("UltiCloud v2 telemetry cadence")
class AgentTelemetryTest {
    /** One stable uuid per player name, so re-sampling the same player maps to the same payload. */
    private static final Map<String, Integer> UUID_BY_NAME = new LinkedHashMap<>();

    private ServerMonitorManager monitor;
    private final AtomicLong clock = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        monitor = mock(ServerMonitorManager.class);
        when(monitor.getCurrentTPS()).thenReturn(20.0);
        when(monitor.sampledCpuPercent()).thenReturn(12.5);
        publish(players(player("alice", "SURVIVAL")), plugins(plugin("UltiTools", "6.3.1", null, true)),
                worlds(world("world", "NORMAL", "NORMAL")));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("status every ten seconds, no snapshot churn without a change")
    void statusCadence() {
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = telemetry();
        telemetry.onWelcome(channel);
        channel.clear();

        for (int second = 1; second <= 60; second++) {
            clock.set(second * 1000L);
            telemetry.tick(channel);
        }

        assertThat(types(channel)).containsOnly("status").hasSize(6);
    }

    @Test
    @DisplayName("two player changes inside one window coalesce into one frame with the latest state")
    void playerChangesCoalesce() {
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = telemetry();
        telemetry.onWelcome(channel);
        channel.clear();

        clock.set(3000L);
        publish(players(player("alice", "SURVIVAL"), player("bob", "CREATIVE")),
                plugins(plugin("UltiTools", "6.3.1", null, true)), worlds(world("world", "NORMAL", "NORMAL")));
        telemetry.tick(channel);

        clock.set(5000L);
        publish(players(player("alice", "SURVIVAL"), player("bob", "CREATIVE"), player("carol", "SURVIVAL")),
                plugins(plugin("UltiTools", "6.3.1", null, true)), worlds(world("world", "NORMAL", "NORMAL")));
        telemetry.tick(channel);
        assertThat(channel.frames()).as("inside the ten second window nothing may be sent").isEmpty();

        clock.set(10000L);
        telemetry.tick(channel);

        assertThat(types(channel)).containsExactly("status", "players.snapshot");
        List<String> names = new ArrayList<>();
        for (JsonElement element : payload(channel, "players.snapshot").getAsJsonArray("players")) {
            names.add(element.getAsJsonObject().get("name").getAsString());
        }
        assertThat(names).containsExactly("alice", "bob", "carol");
        assertThat(payload(channel, "players.snapshot").getAsJsonArray("players").get(1)
                .getAsJsonObject().get("gameMode").getAsString()).isEqualTo("creative");
    }

    @Test
    @DisplayName("a disabled plugin and a loaded world each produce one snapshot")
    void pluginAndWorldChanges() {
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = telemetry();
        telemetry.onWelcome(channel);
        channel.clear();

        clock.set(3000L);
        publish(players(player("alice", "SURVIVAL")), plugins(plugin("UltiTools", "6.3.1", null, false)),
                worlds(world("world", "NORMAL", "NORMAL")));
        telemetry.tick(channel);

        clock.set(6000L);
        publish(players(player("alice", "SURVIVAL")), plugins(plugin("UltiTools", "6.3.1", null, false)),
                worlds(world("world", "NORMAL", "NORMAL"), world("world_nether", "NETHER", "HARD")));
        telemetry.tick(channel);

        clock.set(10000L);
        telemetry.tick(channel);

        assertThat(types(channel)).containsExactly("status", "plugins.snapshot", "worlds.snapshot");
        assertThat(payload(channel, "plugins.snapshot").getAsJsonArray("plugins").get(0)
                .getAsJsonObject().get("enabled").getAsBoolean()).isFalse();
        JsonArray reportedWorlds = payload(channel, "worlds.snapshot").getAsJsonArray("worlds");
        assertThat(reportedWorlds).hasSize(2);
        assertThat(reportedWorlds.get(1).getAsJsonObject().get("environment").getAsString()).isEqualTo("nether");
    }

    @Test
    @DisplayName("a field the contract does not carry never triggers a frame")
    void unmappedFieldChangesAreInvisible() {
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = telemetry();
        telemetry.onWelcome(channel);
        channel.clear();

        JsonObject moved = player("alice", "SURVIVAL");
        moved.addProperty("x", 1234.5);
        moved.addProperty("foodLevel", 3);
        clock.set(12000L);
        publish(players(moved), plugins(plugin("UltiTools", "6.3.1", null, true)),
                worlds(world("world", "NORMAL", "NORMAL")));
        telemetry.tick(channel);

        assertThat(types(channel)).containsOnly("status");
    }

    @Test
    @DisplayName("after a close the next welcome sends every snapshot again")
    void closeThenWelcomeResendsEverything() {
        RecordingChannel channel = new RecordingChannel();
        AgentTelemetry telemetry = telemetry();
        telemetry.onWelcome(channel);
        channel.clear();

        telemetry.onClose();
        clock.set(20000L);
        telemetry.onWelcome(channel);

        assertThat(types(channel)).containsExactly("status", "players.snapshot", "plugins.snapshot",
                "worlds.snapshot");
    }

    @Test
    @DisplayName("out-of-schema values are clamped or cut and the frame stays valid")
    void outOfBoundsValuesAreClamped() {
        when(monitor.getCurrentTPS()).thenReturn(150.0);
        when(monitor.sampledCpuPercent()).thenReturn(20000.0);
        StringBuilder longName = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            longName.append('a');
        }
        StringBuilder longDescription = new StringBuilder();
        for (int index = 0; index < 900; index++) {
            longDescription.append('d');
        }
        publish(players(player(longName.toString(), "SURVIVAL")),
                plugins(plugin("UltiTools", "6.3.1", longDescription.toString(), true)),
                worlds(world("world", "NORMAL", "NORMAL")));

        RecordingChannel channel = new RecordingChannel();
        telemetry().onWelcome(channel);

        assertThat(payload(channel, "status").get("tps").getAsDouble()).isEqualTo(100.0);
        assertThat(payload(channel, "status").get("cpuPercent").getAsDouble()).isEqualTo(10000.0);
        assertThat(payload(channel, "players.snapshot").getAsJsonArray("players").get(0)
                .getAsJsonObject().get("name").getAsString()).hasSize(32);
        assertThat(payload(channel, "plugins.snapshot").getAsJsonArray("plugins").get(0)
                .getAsJsonObject().get("description").getAsString()).hasSize(500);
    }

    @Test
    @DisplayName("a player whose name cannot be made valid is omitted and logged at FINE without the name")
    void unrepresentablePlayerIsOmitted() {
        publish(players(player("", "SURVIVAL"), player("alice", "SURVIVAL"), player("bad name", "SURVIVAL")),
                plugins(plugin("UltiTools", "6.3.1", null, true)), worlds(world("world", "NORMAL", "NORMAL")));

        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(AgentTelemetry.class.getName());
        Level previousLevel = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // In-memory handler; nothing to flush.
            }

            @Override
            public void close() {
                // In-memory handler; nothing to close.
            }
        };
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            RecordingChannel channel = new RecordingChannel();
            telemetry().onWelcome(channel);

            JsonArray reported = payload(channel, "players.snapshot").getAsJsonArray("players");
            assertThat(reported).hasSize(1);
            assertThat(reported.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("alice");
            assertThat(records).anyMatch(record -> record.getLevel() == Level.FINE
                    && String.valueOf(record.getMessage()).contains("omitted"));
            for (LogRecord record : records) {
                assertThat(String.valueOf(record.getMessage())).doesNotContain("bad name");
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    private AgentTelemetry telemetry() {
        return new AgentTelemetry(monitor, () -> true, clock::get);
    }

    private void publish(JsonArray players, JsonArray plugins, JsonArray worlds) {
        when(monitor.currentStateSnapshot()).thenReturn(new ServerMonitorManager.ServerStateSnapshot(
                players.size(), 20, true, "1.21.11", worlds.size(), plugins.size(), worlds, players, plugins));
    }

    private static List<String> types(RecordingChannel channel) {
        List<String> types = new ArrayList<>();
        for (JsonObject frame : channel.frames()) {
            types.add(frame.get("type").getAsString());
        }
        return types;
    }

    private static JsonObject payload(RecordingChannel channel, String type) {
        for (JsonObject frame : channel.frames()) {
            if (type.equals(frame.get("type").getAsString())) {
                return frame.getAsJsonObject("payload");
            }
        }
        throw new AssertionError("No " + type + " frame was recorded");
    }

    private static JsonArray players(JsonObject... entries) {
        JsonArray players = new JsonArray();
        for (JsonObject entry : entries) {
            players.add(entry);
        }
        return players;
    }

    private static JsonArray plugins(JsonObject... entries) {
        JsonArray plugins = new JsonArray();
        for (JsonObject entry : entries) {
            plugins.add(entry);
        }
        return plugins;
    }

    private static JsonArray worlds(JsonObject... entries) {
        JsonArray worlds = new JsonArray();
        for (JsonObject entry : entries) {
            worlds.add(entry);
        }
        return worlds;
    }

    private static JsonObject player(String name, String gameMode) {
        JsonObject player = new JsonObject();
        Integer index = UUID_BY_NAME.get(name);
        if (index == null) {
            index = UUID_BY_NAME.size() + 1;
            UUID_BY_NAME.put(name, index);
        }
        player.addProperty("uuid", String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", index));
        player.addProperty("name", name);
        player.addProperty("world", "world");
        player.addProperty("health", 20.0);
        player.addProperty("maxHealth", 20.0);
        player.addProperty("gameMode", gameMode);
        player.addProperty("op", false);
        return player;
    }

    private static JsonObject plugin(String name, String version, String description, boolean enabled) {
        JsonObject plugin = new JsonObject();
        plugin.addProperty("name", name);
        plugin.addProperty("version", version);
        plugin.addProperty("description", description);
        plugin.addProperty("enabled", enabled);
        plugin.addProperty("author", "nobody");
        return plugin;
    }

    private static JsonObject world(String name, String environment, String difficulty) {
        JsonObject world = new JsonObject();
        world.addProperty("name", name);
        world.addProperty("environment", environment);
        world.addProperty("difficulty", difficulty);
        world.addProperty("playerCount", 1);
        world.addProperty("loadedChunks", 25);
        world.addProperty("pvpEnabled", true);
        JsonObject spawn = new JsonObject();
        spawn.addProperty("x", 0);
        spawn.addProperty("y", 64);
        spawn.addProperty("z", 0);
        world.add("spawnLocation", spawn);
        return world;
    }
}
