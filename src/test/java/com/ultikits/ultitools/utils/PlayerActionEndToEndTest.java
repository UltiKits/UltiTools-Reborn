package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.contract.RecordingChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracer for user decision 13: a panel kick travels from the frozen fixture through the router and
 * the handler to a live MockBukkit player, and every refusal answers the contract's result code.
 * <p>
 * The reason is observed on the {@code Player#kick} call itself rather than through
 * {@code PlayerKickEvent}: MockBukkit 4.101.0 passes the literal {@code Plugin} as the event's
 * reason and the component it was given as the leave message, so the event cannot show what the
 * handler sent.
 */
@DisplayName("player.action end to end: fixture to router to handler to MockBukkit player")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PlayerActionEndToEndTest {

    private static final String REQUEST_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";

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
    }

    @AfterEach
    void tearDown() {
        audit.clear();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void kickWithReasonDisconnectsThePlayerAndAnswersOk() {
        KickablePlayer subject = addPlayer("Notch_fan");
        JsonObject frame = ContractFixtures.object("fixtures/agent/player.action/valid/kick-with-reason.json");
        String id = frame.get("id").getAsString();
        String reason = frame.getAsJsonObject("payload").get("reason").getAsString();
        frame.getAsJsonObject("payload").addProperty("player", subject.getName());
        RecordingChannel channel = new RecordingChannel();

        router().dispatch(frame.toString(), channel);

        assertThat(subject.isOnline()).as("the player is disconnected").isFalse();
        assertThat(subject.kickReasons).as("the reason reaches Bukkit as plain text")
                .containsExactly(Component.text(reason));
        JsonObject result = single(channel);
        assertThat(result.get("type").getAsString()).isEqualTo("player.action.result");
        assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(result.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(result.getAsJsonObject("payload").getAsJsonObject("data").size()).isZero();
        assertThat(ContractSchemas.isValid(result)).as("the result frame matches the vendored schema").isTrue();
        assertThat(audit.allowed).containsExactly("kick:Notch_fan");
        assertThat(audit.denied).isEmpty();
    }

    @Test
    void formattingCodesInAReasonStayLiteralText() {
        KickablePlayer subject = addPlayer("Steve");
        String reason = "&cStop " + (char) 0x00A7 + "c now";
        RecordingChannel channel = new RecordingChannel();

        router().dispatch(action("kick", "Steve", reason).toString(), channel);

        assertThat(subject.isOnline()).isFalse();
        assertThat(subject.kickReasons).containsExactly(Component.text(reason));
        assertThat(single(channel).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(audit.allowed).containsExactly("kick:Steve");
    }

    @Test
    void kickWithoutReasonUsesTheServersDefaultMessage() {
        KickablePlayer subject = addPlayer("Steve");
        RecordingChannel channel = new RecordingChannel();

        router().dispatch(action("kick", "Steve").toString(), channel);

        assertThat(subject.isOnline()).isFalse();
        assertThat(subject.kicksWithoutReason).as("Player#kick() lets the server supply its own message")
                .hasValue(1);
        assertThat(subject.kickReasons).isEmpty();
        assertThat(audit.allowed).containsExactly("kick:Steve");
    }

    @Test
    void unknownPlayerIsRefusedWithPlayerNotOnline() {
        RecordingChannel channel = new RecordingChannel();

        router().dispatch(action("kick", "Ghost").toString(), channel);

        JsonObject error = single(channel);
        assertThat(error.get("type").getAsString()).isEqualTo("player.action.result");
        assertThat(error.get("replyTo").getAsString()).isEqualTo(REQUEST_ID);
        assertThat(error.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("error");
        assertThat(error.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("player_not_online");
        assertThat(error.getAsJsonObject("payload").get("message").getAsString()).contains("Ghost");
        assertThat(audit.allowed).isEmpty();
        assertThat(audit.denied).singleElement().asString().startsWith("kick:Ghost:");
    }

    @Test
    void everyInvalidFixtureIsRejectedAsInvalidMessageWithoutTouchingAPlayer() {
        KickablePlayer bystander = addPlayer("Steve");
        List<ContractFixtures.Fixture> invalid = ContractFixtures.invalidFrames("player.action");
        assertThat(invalid).isNotEmpty();

        for (ContractFixtures.Fixture fixture : invalid) {
            RecordingChannel channel = new RecordingChannel();
            router().dispatch(fixture.frame().toString(), channel);
            JsonObject error = single(channel);
            assertThat(error.get("type").getAsString()).as(fixture.path()).isEqualTo("error");
            assertThat(error.getAsJsonObject("payload").get("code").getAsString())
                    .as(fixture.path() + ": " + fixture.why()).isEqualTo(fixture.expect());
            assertThat(error.get("replyTo").getAsString())
                    .as(fixture.path()).isEqualTo(fixture.frame().get("id").getAsString());
        }

        assertThat(bystander.isOnline()).as("no Bukkit call was made").isTrue();
        assertThat(bystander.kickReasons).isEmpty();
        assertThat(bystander.kicksWithoutReason).hasValue(0);
        assertThat(audit.allowed).isEmpty();
        assertThat(audit.denied).as("a rejected payload is not an executed action").isEmpty();
    }

    private AgentMessageRouter router() {
        AgentMessageRouter router = new AgentMessageRouter("players:actions"::equals,
                capability -> "The operator disabled the " + capability + " capability.");
        router.register("player.action", new PlayerActionHandler(mainThread, audit));
        return router;
    }

    private KickablePlayer addPlayer(String name) {
        KickablePlayer player = new KickablePlayer(server, name);
        server.addPlayer(player);
        return player;
    }

    private static JsonObject action(String action, String player) {
        return action(action, player, null);
    }

    private static JsonObject action(String action, String player, String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", action);
        payload.addProperty("player", player);
        if (reason != null) {
            payload.addProperty("reason", reason);
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("v", 2);
        frame.addProperty("id", REQUEST_ID);
        frame.addProperty("type", "player.action");
        frame.addProperty("ts", 1761274800000L);
        frame.add("payload", payload);
        return frame;
    }

    private static JsonObject single(RecordingChannel channel) {
        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(1);
        return frames.get(0);
    }

    /**
     * Records exactly what the handler hands to Bukkit, then lets MockBukkit perform the real
     * disconnect. MockBukkit's no-argument {@code kick()} delegates to {@code kick(Component)} with
     * its own default message, so that delegation is not recorded as a handler call.
     */
    public static final class KickablePlayer extends PlayerMock {
        private final List<Component> kickReasons = new CopyOnWriteArrayList<>();
        private final AtomicInteger kicksWithoutReason = new AtomicInteger();
        private boolean delegating;

        KickablePlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void kick(Component reason) {
            if (!delegating) {
                kickReasons.add(reason);
            }
            super.kick(reason);
        }

        @Override
        public void kick() {
            kicksWithoutReason.incrementAndGet();
            delegating = true;
            try {
                super.kick();
            } finally {
                delegating = false;
            }
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
