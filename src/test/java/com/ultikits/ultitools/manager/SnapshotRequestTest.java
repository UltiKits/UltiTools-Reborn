package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.AgentMessageRouter;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The panel refresh path: {@code snapshot.request} pushes the requested snapshots and then answers
 * with {@code snapshot.request.result}, through the real router so the capability gate and the
 * invalid-payload mapping are exercised as well.
 */
@DisplayName("UltiCloud v2 snapshot requests")
class SnapshotRequestTest {
    private static final String REQUEST_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";

    private ServerMonitorManager monitor;
    private AgentMessageRouter router;
    private AgentTelemetry telemetry;
    private RecordingChannel channel;
    private final AtomicLong clock = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        monitor = mock(ServerMonitorManager.class);
        when(monitor.getCurrentTPS()).thenReturn(20.0);
        when(monitor.sampledCpuPercent()).thenReturn(5.0);
        publish("world");
        Executor direct = Runnable::run;
        telemetry = new AgentTelemetry(monitor, () -> true, clock::get, direct, targets -> { });
        router = new AgentMessageRouter(capability -> true, capability -> "disabled", () -> 0L);
        router.register("snapshot.request", telemetry);
        channel = new RecordingChannel();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("one requested target is pushed before the result that lists it")
    void singleTarget() {
        router.dispatch(ContractFixtures.text("fixtures/agent/snapshot.request/valid/worlds.json"), channel);

        List<JsonObject> frames = channel.frames();
        assertThat(types(frames)).containsExactly("worlds.snapshot", "snapshot.request.result");
        JsonObject result = frames.get(1);
        assertThat(result.get("replyTo").getAsString()).isEqualTo(REQUEST_ID);
        assertThat(sent(frames)).containsExactly("worlds");
    }

    @Test
    @DisplayName("all three targets are pushed in request order, then the result")
    void allTargets() {
        router.dispatch(ContractFixtures.text("fixtures/agent/snapshot.request/valid/all.json"), channel);

        List<JsonObject> frames = channel.frames();
        assertThat(types(frames)).containsExactly("status", "plugins.snapshot", "worlds.snapshot",
                "snapshot.request.result");
        assertThat(sent(frames)).containsExactly("status", "plugins", "worlds");
    }

    @Test
    @DisplayName("a request is deferred to the main-thread executor, which sends the snapshots and the result")
    void requestWaitsForTheMainThreadExecutor() {
        TestMainThread mainThread = new TestMainThread();
        AgentTelemetry deferred = new AgentTelemetry(monitor, () -> true, clock::get, mainThread, targets -> { });
        AgentMessageRouter deferredRouter = new AgentMessageRouter(capability -> true, capability -> "disabled",
                () -> 0L);
        deferredRouter.register("snapshot.request", deferred);

        deferredRouter.dispatch(ContractFixtures.text("fixtures/agent/snapshot.request/valid/all.json"), channel);

        assertThat(channel.frames()).as("nothing may be sampled or sent on the dispatching thread").isEmpty();
        assertThat(mainThread.pending()).as("the push is waiting for the main thread").isEqualTo(1);

        mainThread.drain();
        assertThat(types(channel.frames())).containsExactly("status", "plugins.snapshot", "worlds.snapshot",
                "snapshot.request.result");
        assertThat(sent(channel.frames())).containsExactly("status", "plugins", "worlds");
    }

    @Test
    @DisplayName("a request takes a fresh sample, so it reports a change made since the last publish")
    void requestTakesAFreshSample() {
        final ServerMonitorManager.ServerStateSnapshot[] published = {snapshot("world")};
        when(monitor.currentStateSnapshot()).thenAnswer(invocation -> published[0]);
        when(monitor.sampleAndPublishState()).thenAnswer(invocation -> {
            published[0] = snapshot("world", "world_nether");
            return true;
        });

        router.dispatch(ContractFixtures.text("fixtures/agent/snapshot.request/valid/worlds.json"), channel);

        List<String> names = new ArrayList<>();
        for (JsonElement element : payload(channel.frames(), "worlds.snapshot").getAsJsonArray("worlds")) {
            names.add(element.getAsJsonObject().get("name").getAsString());
        }
        assertThat(names).as("the refresh must show the world that was added after the last publish")
                .containsExactly("world", "world_nether");
    }

    @Test
    @DisplayName("a request is answered even when nothing changed, and the forced send restarts the window")
    void forcedSendResetsTheBaseline() {
        telemetry.onWelcome(channel);
        channel.clear();

        clock.set(5000L);
        router.dispatch(ContractFixtures.text("fixtures/agent/snapshot.request/valid/worlds.json"), channel);
        assertThat(types(channel.frames())).containsExactly("worlds.snapshot", "snapshot.request.result");
        channel.clear();

        clock.set(8000L);
        publish("world_nether");
        telemetry.tick(channel);
        assertThat(channel.frames()).as("still inside the window opened by the forced send").isEmpty();

        clock.set(15000L);
        telemetry.tick(channel);
        assertThat(types(channel.frames())).contains("worlds.snapshot");
    }

    @Test
    @DisplayName("an empty target list is refused by the router as an invalid message")
    void emptyTargetsIsInvalid() {
        for (ContractFixtures.Fixture fixture : ContractFixtures.invalidFrames("snapshot.request")) {
            channel.clear();
            router.dispatch(fixture.frame().toString(), channel);
            List<JsonObject> frames = channel.frames();
            assertThat(frames).as(fixture.path()).hasSize(1);
            assertThat(frames.get(0).get("type").getAsString()).as(fixture.path()).isEqualTo("error");
            assertThat(frames.get(0).getAsJsonObject("payload").get("code").getAsString())
                    .as(fixture.path()).isEqualTo(fixture.expect());
        }
    }

    @Test
    @DisplayName("a duplicated target is sent once and reported once")
    void duplicateTargetsSendEachSnapshotOnce() {
        JsonObject request = ContractFixtures.object("fixtures/agent/snapshot.request/valid/worlds.json");
        JsonArray targets = request.getAsJsonObject("payload").getAsJsonArray("targets");
        targets.add("worlds");

        router.dispatch(request.toString(), channel);

        List<JsonObject> frames = channel.frames();
        assertThat(types(frames)).containsExactly("worlds.snapshot", "snapshot.request.result");
        assertThat(sent(frames)).containsExactly("worlds");
    }

    private void publish(String... worldNames) {
        when(monitor.currentStateSnapshot()).thenReturn(snapshot(worldNames));
    }

    private ServerMonitorManager.ServerStateSnapshot snapshot(String... worldNames) {
        JsonArray players = new JsonArray();
        JsonObject player = new JsonObject();
        player.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
        player.addProperty("name", "alice");
        player.addProperty("world", worldNames[0]);
        player.addProperty("health", 20.0);
        player.addProperty("maxHealth", 20.0);
        player.addProperty("gameMode", "SURVIVAL");
        player.addProperty("op", false);
        players.add(player);

        JsonArray plugins = new JsonArray();
        JsonObject plugin = new JsonObject();
        plugin.addProperty("name", "UltiTools");
        plugin.addProperty("version", "6.3.1");
        plugin.add("description", com.google.gson.JsonNull.INSTANCE);
        plugin.addProperty("enabled", true);
        plugins.add(plugin);

        JsonArray worlds = new JsonArray();
        for (String name : worldNames) {
            JsonObject world = new JsonObject();
            world.addProperty("name", name);
            world.addProperty("environment", "NORMAL");
            world.addProperty("difficulty", "NORMAL");
            world.addProperty("playerCount", 1);
            world.addProperty("loadedChunks", 9);
            world.addProperty("pvpEnabled", true);
            JsonObject spawn = new JsonObject();
            spawn.addProperty("x", 0);
            spawn.addProperty("y", 64);
            spawn.addProperty("z", 0);
            world.add("spawnLocation", spawn);
            worlds.add(world);
        }
        return new ServerMonitorManager.ServerStateSnapshot(
                players.size(), 20, true, "1.21.11", worlds.size(), plugins.size(), worlds, players, plugins);
    }

    private static JsonObject payload(List<JsonObject> frames, String type) {
        for (JsonObject frame : frames) {
            if (type.equals(frame.get("type").getAsString())) {
                return frame.getAsJsonObject("payload");
            }
        }
        throw new AssertionError("No " + type + " frame was recorded");
    }

    private static List<String> types(List<JsonObject> frames) {
        List<String> types = new ArrayList<>();
        for (JsonObject frame : frames) {
            types.add(frame.get("type").getAsString());
        }
        return types;
    }

    private static List<String> sent(List<JsonObject> frames) {
        List<String> sent = new ArrayList<>();
        for (JsonObject frame : frames) {
            if (!"snapshot.request.result".equals(frame.get("type").getAsString())) {
                continue;
            }
            for (JsonElement element : frame.getAsJsonObject("payload").getAsJsonObject("data")
                    .getAsJsonArray("sent")) {
                sent.add(element.getAsString());
            }
        }
        return sent;
    }
}
