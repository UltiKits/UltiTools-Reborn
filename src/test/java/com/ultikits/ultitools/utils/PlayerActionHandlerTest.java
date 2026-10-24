package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import io.papermc.paper.entity.TeleportFlag;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Game mode and teleport, including every failure the contract maps: an offline subject or target,
 * an unknown world, a cancelled teleport and a throwing call.
 */
@DisplayName("player.action gamemode and teleport")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PlayerActionHandlerTest {

    private final RecordingAudit audit = new RecordingAudit();
    private ServerMock server;

    private final Executor mainThread = task -> {
        assertThat(server.isPrimaryThread()).as("the handler is scheduled on the main thread").isTrue();
        task.run();
    };

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        audit.clear();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void gameModeFixtureChangesThePlayersMode() {
        PlayerMock subject = addPlayer("Alex");
        JsonObject frame = fixture("gamemode");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        assertThat(subject.getGameMode()).isEqualTo(GameMode.CREATIVE);
        assertOk(channel, id(frame));
        assertThat(audit.allowed).containsExactly("gamemode:Alex");
        assertThat(audit.denied).isEmpty();
    }

    @Test
    void teleportToPlayerMovesTheSubjectToTheTargetsLocation() {
        ActionPlayer subject = addPlayer("Steve");
        ActionPlayer target = addPlayer(".Steve");
        WorldMock world = server.addSimpleWorld("target_world");
        target.teleport(new Location(world, 12.5D, 65.0D, -7.5D, 30.0F, 10.0F));
        JsonObject frame = fixture("teleport-to-player");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        Location moved = subject.getLocation();
        assertThat(moved.getWorld()).as("a Floodgate-style name resolves like any other").isEqualTo(world);
        assertThat(moved.getX()).isEqualTo(12.5D);
        assertThat(moved.getY()).isEqualTo(65.0D);
        assertThat(moved.getZ()).isEqualTo(-7.5D);
        assertOk(channel, id(frame));
        assertThat(audit.allowed).containsExactly("teleport:Steve");
    }

    @Test
    void offlineTargetPlayerIsRefused() {
        addPlayer("Steve");
        JsonObject frame = fixture("teleport-to-player");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        assertError(channel, id(frame), "player_not_online");
        assertThat(message(channel)).contains(".Steve");
        assertThat(audit.denied).singleElement().asString().startsWith("teleport:Steve:");
        assertThat(audit.allowed).isEmpty();
    }

    @Test
    void offlineSubjectIsRefused() {
        addPlayer(".Steve");
        JsonObject frame = fixture("teleport-to-player");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        assertError(channel, id(frame), "player_not_online");
        assertThat(message(channel)).contains("Steve");
        assertThat(audit.denied).singleElement().asString().startsWith("teleport:Steve:");
    }

    @Test
    void teleportToCoordinatesKeepsTheSubjectsYawAndPitch() {
        ActionPlayer subject = addPlayer("Steve");
        WorldMock nether = server.addSimpleWorld("world_nether");
        WorldMock start = server.addSimpleWorld("start_world");
        subject.teleport(new Location(start, 0.0D, 64.0D, 0.0D, 90.0F, -20.0F));
        JsonObject frame = fixture("teleport-to-coords");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        Location moved = subject.getLocation();
        assertThat(moved.getWorld()).isEqualTo(nether);
        assertThat(moved.getX()).isEqualTo(10.0D);
        assertThat(moved.getY()).isEqualTo(64.0D);
        assertThat(moved.getZ()).isEqualTo(-5.0D);
        assertThat(moved.getYaw()).isCloseTo(90.0F, within(0.001F));
        assertThat(moved.getPitch()).isCloseTo(-20.0F, within(0.001F));
        assertOk(channel, id(frame));
        assertThat(audit.allowed).containsExactly("teleport:Steve");
    }

    @Test
    void unknownWorldIsRefused() {
        ActionPlayer subject = addPlayer("Steve");
        World start = server.addSimpleWorld("start_world");
        subject.teleport(new Location(start, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
        JsonObject frame = fixture("teleport-to-coords");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        assertError(channel, id(frame), "world_not_found");
        assertThat(message(channel)).contains("world_nether");
        assertThat(subject.getLocation().getWorld()).as("nothing moved").isEqualTo(start);
        assertThat(audit.denied).singleElement().asString().startsWith("teleport:Steve:");
    }

    @Test
    void cancelledTeleportIsReportedAsInternalError() {
        ActionPlayer subject = addPlayer("Steve");
        subject.answerWith(CompletableFuture.completedFuture(Boolean.FALSE));
        JsonObject frame = fixture("teleport-to-player");
        addPlayer(".Steve");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        assertError(channel, id(frame), "internal_error");
        assertThat(message(channel)).isEqualTo("teleport was cancelled");
        assertThat(audit.denied).singleElement().asString().startsWith("teleport:Steve:");
    }

    @Test
    void exceptionallyCompletedTeleportReportsTheExceptionMessageOnly() {
        ActionPlayer subject = addPlayer("Steve");
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        pending.completeExceptionally(new IllegalStateException("stuck in a wall"));
        subject.answerWith(pending);
        JsonObject frame = fixture("teleport-to-player");
        addPlayer(".Steve");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        assertError(channel, id(frame), "internal_error");
        assertThat(message(channel)).isEqualTo("stuck in a wall");
    }

    @Test
    void aThrowingTeleportCallReportsTheExceptionMessageOnly() {
        ActionPlayer subject = addPlayer("Steve");
        subject.failWith(new IllegalStateException("unloaded chunk"));
        JsonObject frame = fixture("teleport-to-player");
        addPlayer(".Steve");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);

        assertError(channel, id(frame), "internal_error");
        assertThat(message(channel)).isEqualTo("unloaded chunk");
        assertThat(subject.getLocation().getWorld()).as("nothing moved").isNotNull();
    }

    @Test
    void theTeleportAnswerMayArriveFromAnotherThread() throws InterruptedException {
        ActionPlayer subject = addPlayer("Steve");
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        subject.answerWith(pending);
        JsonObject frame = fixture("teleport-to-player");
        addPlayer(".Steve");
        RecordingChannel channel = new RecordingChannel();

        handle(frame, channel);
        assertThat(channel.frames()).as("the main thread does not wait for the teleport").isEmpty();

        Thread completer = new Thread(() -> pending.complete(Boolean.TRUE));
        completer.start();
        completer.join(5000L);

        assertOk(channel, id(frame));
        assertThat(audit.allowed).containsExactly("teleport:Steve");
    }

    private void handle(JsonObject frame, RecordingChannel channel) {
        new PlayerActionHandler(mainThread, audit).handle(request(frame), channel);
    }

    private static CloudRequest request(JsonObject frame) {
        return new CloudRequest(id(frame), frame.get("type").getAsString(), frame.get("ts").getAsLong(), null,
                frame.getAsJsonObject("payload"));
    }

    private static String id(JsonObject frame) {
        return frame.get("id").getAsString();
    }

    private static JsonObject fixture(String name) {
        return ContractFixtures.object("fixtures/agent/player.action/valid/" + name + ".json");
    }

    private ActionPlayer addPlayer(String name) {
        ActionPlayer player = new ActionPlayer(server, name);
        server.addPlayer(player);
        return player;
    }

    private static void assertOk(RecordingChannel channel, String id) {
        JsonObject frame = single(channel);
        assertThat(frame.get("type").getAsString()).isEqualTo("player.action.result");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(frame.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(frame.getAsJsonObject("payload").getAsJsonObject("data").size()).isZero();
        assertThat(ContractSchemas.isValid(frame)).as("the result frame matches the vendored schema").isTrue();
    }

    private static void assertError(RecordingChannel channel, String id, String code) {
        JsonObject frame = single(channel);
        assertThat(frame.get("type").getAsString()).isEqualTo("player.action.result");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(frame.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("error");
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo(code);
        assertThat(ContractSchemas.isValid(frame)).as("the result frame matches the vendored schema").isTrue();
    }

    private static String message(RecordingChannel channel) {
        return single(channel).getAsJsonObject("payload").get("message").getAsString();
    }

    private static JsonObject single(RecordingChannel channel) {
        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(1);
        return frames.get(0);
    }

    /**
     * MockBukkit 4.101.0 leaves {@code EntityMock#teleportAsync} unimplemented, so this double owns
     * the future; the succeeding branch still runs MockBukkit's real {@code teleport}.
     */
    public static final class ActionPlayer extends PlayerMock {
        private CompletableFuture<Boolean> answer;
        private RuntimeException failure;

        ActionPlayer(ServerMock server, String name) {
            super(server, name);
        }

        void answerWith(CompletableFuture<Boolean> answer) {
            this.answer = answer;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(Location location, PlayerTeleportEvent.TeleportCause cause,
                                                        TeleportFlag... flags) {
            if (failure != null) {
                throw failure;
            }
            if (answer != null) {
                return answer;
            }
            return CompletableFuture.completedFuture(teleport(location, cause, flags));
        }
    }

    static final class RecordingAudit implements PlayerActionHandler.ActionAudit {
        private final List<String> allowed = new CopyOnWriteArrayList<>();
        private final List<String> denied = new CopyOnWriteArrayList<>();

        @Override
        public void allowed(String action, String player) {
            allowed.add(action + ":" + player);
        }

        @Override
        public void denied(String action, String player, String reason) {
            denied.add(action + ":" + player + ":" + reason);
        }

        void clear() {
            allowed.clear();
            denied.clear();
        }
    }
}
