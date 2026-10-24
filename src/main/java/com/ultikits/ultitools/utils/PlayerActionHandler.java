package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Handles the structured {@code player.action} request — kick, game mode and teleport — as Bukkit
 * API calls on the main thread. No console command is ever assembled, so a player name, a world
 * name and a kick reason are always data and never command text or formatting (user decision 13).
 *
 * <p>Every delivery is answered with {@code player.action.result}: {@code ok} with an empty data
 * object, or the contract's {@code player_not_online}, {@code world_not_found} or
 * {@code internal_error}. A payload that does not match the frozen schema is rejected before
 * anything is scheduled, so an invalid frame never reaches a player.</p>
 *
 * <p>None of the three Bukkit calls reports whether it took effect: a game mode change and a kick
 * are both cancellable by other plugins, and the platform discards that answer. Each action is
 * therefore answered {@code ok} only after its post-condition was observed — the game mode now
 * equals the requested one, or the player is no longer online — and as a cancelled action
 * otherwise. A throwing call is answered {@code internal_error} with one fixed message; the
 * exception class and stack stay in the local log, never on the wire.</p>
 *
 * <p>Teleports use {@link Player#teleportAsync(Location)} and are answered when the returned future
 * completes; that answer may therefore come from a thread other than the main thread, which
 * {@link CloudChannel} permits. Every executed or refused action is reported once through the
 * audit port, which integration binds to the remote action log under the
 * {@code players:actions} capability. The capability switch itself is enforced by the router.</p>
 *
 * <p>Package-private framework plumbing; not for module use.</p>
 *
 * @since 6.3.1
 */
final class PlayerActionHandler implements CloudRequestHandler {

    /** The frozen contract request type this handler consumes. */
    static final String TYPE = "player.action";
    /** The frozen contract result type every delivery is answered with. */
    static final String RESULT_TYPE = "player.action.result";
    /** The contract's first action value. */
    static final String ACTION_KICK = "kick";
    /** The contract's second action value. */
    static final String ACTION_GAMEMODE = "gamemode";
    /** The contract's third action value. */
    static final String ACTION_TELEPORT = "teleport";
    /** The teleport target kind that names another online player. */
    static final String TARGET_PLAYER = "player";
    /** The teleport target kind that names a world and a position. */
    static final String TARGET_COORDS = "coords";
    /** The contract result code for a subject or target that is not online. */
    static final String CODE_PLAYER_NOT_ONLINE = "player_not_online";
    /** The contract result code for coordinates whose world does not exist. */
    static final String CODE_WORLD_NOT_FOUND = "world_not_found";
    /** The contract result code for a cancelled action or a throwing call. */
    static final String CODE_INTERNAL_ERROR = "internal_error";
    /** The result message used when another plugin cancels the teleport. */
    static final String TELEPORT_CANCELLED = "teleport was cancelled";
    /** The result message used when another plugin cancels the game mode change. */
    static final String GAME_MODE_CANCELLED = "game mode change was cancelled";
    /** The result message used when another plugin cancels the kick. */
    static final String KICK_CANCELLED = "kick was cancelled";
    /**
     * The one message a throwing call is answered with; the same fixed text the agent router uses
     * for {@code internal_error}, so no exception text ever reaches the wire.
     */
    static final String INTERNAL_ERROR_MESSAGE = "The request could not be processed.";

    private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};
    private static final int NAME_MIN = 1;
    private static final int NAME_MAX = 32;
    private static final int REASON_MAX = 256;
    private static final int WORLD_MIN = 1;
    private static final int WORLD_MAX = 128;
    private static final double HORIZONTAL_LIMIT = 30000000.0D;
    private static final double LOWEST_Y = -2048.0D;
    private static final double HIGHEST_Y = 4096.0D;
    private static final String OFFLINE_SUFFIX = " is not online";
    private static final String MISSING_WORLD_SUFFIX = " does not exist";

    private static final Logger LOGGER = Logger.getLogger(PlayerActionHandler.class.getName());

    private final Executor mainThread;
    private final ActionAudit audit;

    /**
     * Creates the handler.
     *
     * @param mainThread runs every Bukkit call this handler makes; in production this is the
     *                   server's main thread, never the caller's thread
     * @param audit receives one allowed or denied entry per executed or refused action, or null
     *              to record nothing
     */
    PlayerActionHandler(Executor mainThread, ActionAudit audit) {
        this.mainThread = mainThread;
        this.audit = audit;
    }

    /**
     * Validates the payload on the dispatching thread, then performs the action on the main
     * thread. A payload that does not match the contract propagates as {@code invalid_message}
     * before anything is scheduled.
     *
     * @param request the parsed request
     * @param channel the reply channel
     */
    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        final Action action = parse(request);
        mainThread.execute(() -> perform(request, channel, action));
    }

    /**
     * Reads one branch of the discriminated union, validating exactly the keys that branch allows.
     */
    private static Action parse(CloudRequest request) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        String name = reader.enumValue("action", ACTION_KICK, ACTION_GAMEMODE, ACTION_TELEPORT);
        if (ACTION_KICK.equals(name)) {
            reader.keys(new String[] {"action", "player"}, "reason");
            return Action.kick(reader.string("player", NAME_MIN, NAME_MAX, ContractPrimitives.MC_NAME),
                    optionalReason(reader));
        }
        if (ACTION_GAMEMODE.equals(name)) {
            reader.keys(new String[] {"action", "player", "mode"});
            return Action.gameMode(reader.string("player", NAME_MIN, NAME_MAX, ContractPrimitives.MC_NAME),
                    reader.enumValue("mode", GAME_MODES));
        }
        reader.keys(new String[] {"action", "player", "target"});
        return Action.teleport(reader.string("player", NAME_MIN, NAME_MAX, ContractPrimitives.MC_NAME),
                parseTarget(request.id(), reader.object("target")));
    }

    /**
     * A kick reason is optional; when present it must be a string of at most 256 code points.
     */
    private static String optionalReason(PayloadReader reader) {
        return reader.optional("reason") == null ? null : reader.string("reason", 0, REASON_MAX, null);
    }

    /**
     * Reads the teleport target, validating exactly the keys its kind allows.
     */
    private static Target parseTarget(String id, JsonObject object) {
        PayloadReader reader = new PayloadReader(id, object);
        String kind = reader.enumValue("kind", TARGET_PLAYER, TARGET_COORDS);
        if (TARGET_PLAYER.equals(kind)) {
            reader.keys(new String[] {"kind", "player"});
            return Target.toPlayer(reader.string("player", NAME_MIN, NAME_MAX, ContractPrimitives.MC_NAME));
        }
        reader.keys(new String[] {"kind", "world", "x", "y", "z"});
        return Target.toCoords(reader.string("world", WORLD_MIN, WORLD_MAX, null),
                reader.number("x", -HORIZONTAL_LIMIT, HORIZONTAL_LIMIT),
                reader.number("y", LOWEST_Y, HIGHEST_Y),
                reader.number("z", -HORIZONTAL_LIMIT, HORIZONTAL_LIMIT));
    }

    /**
     * Runs once per accepted request, on the main thread: resolve the subject, then act.
     */
    private void perform(CloudRequest request, CloudChannel channel, Action action) {
        Player subject = onlinePlayer(action.player);
        if (subject == null) {
            deny(channel, request, action, CODE_PLAYER_NOT_ONLINE, action.player + OFFLINE_SUFFIX);
            return;
        }
        if (ACTION_KICK.equals(action.action)) {
            runKick(channel, request, action, subject);
        } else if (ACTION_GAMEMODE.equals(action.action)) {
            runGameMode(channel, request, action, subject);
        } else {
            runTeleport(channel, request, action, subject);
        }
    }

    /**
     * Kicks with the reason as one plain-text component, or with the server's own default message
     * when the panel sent no reason. The kick is reported as done only when the player is no longer
     * online: another plugin may cancel the {@code PlayerKickEvent}, and the platform returns no
     * answer either way.
     */
    private void runKick(CloudChannel channel, CloudRequest request, Action action, Player subject) {
        boolean performed;
        try {
            if (action.reason == null) {
                subject.kick();
            } else {
                subject.kick(Component.text(action.reason));
            }
            performed = !subject.isOnline();
        } catch (RuntimeException ex) {
            fail(channel, request, action, ex);
            return;
        }
        if (!performed) {
            deny(channel, request, action, CODE_INTERNAL_ERROR, KICK_CANCELLED);
            return;
        }
        succeed(channel, request, action);
    }

    /**
     * Applies the requested game mode and reports it as done only when the player's mode now equals
     * the requested one: another plugin may cancel the {@code PlayerGameModeChangeEvent}, and the
     * platform discards that answer.
     */
    private void runGameMode(CloudChannel channel, CloudRequest request, Action action, Player subject) {
        boolean performed;
        try {
            GameMode requested = GameMode.valueOf(action.mode.toUpperCase(Locale.ROOT));
            subject.setGameMode(requested);
            performed = subject.getGameMode() == requested;
        } catch (RuntimeException ex) {
            fail(channel, request, action, ex);
            return;
        }
        if (!performed) {
            deny(channel, request, action, CODE_INTERNAL_ERROR, GAME_MODE_CANCELLED);
            return;
        }
        succeed(channel, request, action);
    }

    /**
     * Starts the asynchronous teleport and answers when its future completes. The answer may come
     * from a thread other than the main thread; every Bukkit call was made before it.
     */
    private void runTeleport(CloudChannel channel, CloudRequest request, Action action, Player subject) {
        Location destination = destination(channel, request, action, subject);
        if (destination == null) {
            return;
        }
        CompletableFuture<Boolean> pending;
        try {
            pending = subject.teleportAsync(destination);
        } catch (RuntimeException ex) {
            fail(channel, request, action, ex);
            return;
        }
        pending.whenComplete((completed, error) -> completeTeleport(channel, request, action, completed, error));
    }

    /**
     * Resolves the destination, answering {@code player_not_online} or {@code world_not_found} and
     * returning null when the target cannot be resolved. Coordinates keep the subject's own yaw and
     * pitch.
     */
    private Location destination(CloudChannel channel, CloudRequest request, Action action, Player subject) {
        if (TARGET_PLAYER.equals(action.target.kind)) {
            Player target = onlinePlayer(action.target.player);
            if (target == null) {
                deny(channel, request, action, CODE_PLAYER_NOT_ONLINE, action.target.player + OFFLINE_SUFFIX);
                return null;
            }
            return target.getLocation();
        }
        World world = Bukkit.getWorld(action.target.world);
        if (world == null) {
            deny(channel, request, action, CODE_WORLD_NOT_FOUND, action.target.world + MISSING_WORLD_SUFFIX);
            return null;
        }
        Location here = subject.getLocation();
        return new Location(world, action.target.x, action.target.y, action.target.z, here.getYaw(), here.getPitch());
    }

    /**
     * Answers the teleport once its future completes. A refusing channel is logged rather than
     * answered a second time, so one request never produces two results.
     */
    private void completeTeleport(CloudChannel channel, CloudRequest request, Action action,
                                  Boolean completed, Throwable error) {
        try {
            if (error != null) {
                fail(channel, request, action, error);
            } else if (Boolean.TRUE.equals(completed)) {
                succeed(channel, request, action);
            } else {
                deny(channel, request, action, CODE_INTERNAL_ERROR, TELEPORT_CANCELLED);
            }
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "UltiCloud: could not answer the teleport action.", ex);
        }
    }

    private void succeed(CloudChannel channel, CloudRequest request, Action action) {
        record(action, null);
        channel.replyOk(request.id(), RESULT_TYPE, new JsonObject());
    }

    private void deny(CloudChannel channel, CloudRequest request, Action action, String code, String message) {
        record(action, message);
        channel.replyError(request.id(), RESULT_TYPE, code, message, null);
    }

    /**
     * Maps a throwing call to the contract's {@code internal_error} with one fixed message: the
     * exception class and stack stay in the local log and never reach the wire, as in the agent
     * router (22-08).
     */
    private void fail(CloudChannel channel, CloudRequest request, Action action, Throwable error) {
        LOGGER.log(Level.WARNING, "UltiCloud: the player action " + action.action
                + " failed (exception=" + error.getClass().getName() + ").", error);
        deny(channel, request, action, CODE_INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    private void record(Action action, String refusal) {
        if (audit == null) {
            return;
        }
        if (refusal == null) {
            audit.allowed(action.action, action.player);
        } else {
            audit.denied(action.action, action.player, refusal);
        }
    }

    private static Player onlinePlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player != null && player.isOnline() ? player : null;
    }

    /**
     * The audit port integration binds to the remote action log; one entry per executed or refused
     * action, none for a payload that never ran.
     */
    interface ActionAudit {

        /**
         * Records an action that ran.
         *
         * @param action the contract action name
         * @param player the requested player name, exactly as the panel sent it
         */
        void allowed(String action, String player);

        /**
         * Records an action that was refused before it changed anything.
         *
         * @param action the contract action name
         * @param player the requested player name, exactly as the panel sent it
         * @param reason the same safe text the panel was given
         */
        void denied(String action, String player, String reason);
    }

    /**
     * One validated request, independent of the payload object it was read from.
     */
    private static final class Action {
        private final String action;
        private final String player;
        private final String reason;
        private final String mode;
        private final Target target;

        private Action(String action, String player, String reason, String mode, Target target) {
            this.action = action;
            this.player = player;
            this.reason = reason;
            this.mode = mode;
            this.target = target;
        }

        static Action kick(String player, String reason) {
            return new Action(ACTION_KICK, player, reason, null, null);
        }

        static Action gameMode(String player, String mode) {
            return new Action(ACTION_GAMEMODE, player, null, mode, null);
        }

        static Action teleport(String player, Target target) {
            return new Action(ACTION_TELEPORT, player, null, null, target);
        }
    }

    /**
     * One validated teleport target: another online player, or a world and a position.
     */
    private static final class Target {
        private final String kind;
        private final String player;
        private final String world;
        private final double x;
        private final double y;
        private final double z;

        private Target(String kind, String player, String world, double x, double y, double z) {
            this.kind = kind;
            this.player = player;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static Target toPlayer(String player) {
            return new Target(TARGET_PLAYER, player, null, 0, 0, 0);
        }

        static Target toCoords(String world, double x, double y, double z) {
            return new Target(TARGET_COORDS, null, world, x, y, z);
        }
    }
}
