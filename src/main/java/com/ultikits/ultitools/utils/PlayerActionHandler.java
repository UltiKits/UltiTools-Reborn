package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Handles the structured {@code player.action} request as Bukkit API calls on the main thread. No
 * console command is ever assembled, so a player name and a kick reason are always data and never
 * command text or formatting (user decision 13).
 *
 * <p>Every delivery is answered with {@code player.action.result}: {@code ok} with an empty data
 * object, or the contract's {@code player_not_online} or {@code internal_error}. A payload that
 * does not match the frozen schema is rejected before anything is scheduled, so an invalid frame
 * never reaches a player.</p>
 *
 * <p>Every executed or refused action is reported once through the audit port, which integration
 * binds to the remote action log under the {@code players:actions} capability. The capability
 * switch itself is enforced by the router.</p>
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
    /** The contract result code for a subject that is not online. */
    static final String CODE_PLAYER_NOT_ONLINE = "player_not_online";
    /** The contract result code for a call that threw. */
    private static final String CODE_INTERNAL_ERROR = "internal_error";

    private static final int NAME_MIN = 1;
    private static final int NAME_MAX = 32;
    private static final int REASON_MAX = 256;
    private static final int MESSAGE_MAX = 500;
    private static final String OFFLINE_SUFFIX = " is not online";
    private static final String FALLBACK_MESSAGE = "the action failed";

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
     * Reads the kick branch of the discriminated union, validating exactly the keys it allows.
     */
    private static Action parse(CloudRequest request) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        reader.keys(new String[] {"action", "player"}, "reason");
        reader.enumValue("action", ACTION_KICK);
        return Action.kick(reader.string("player", NAME_MIN, NAME_MAX, ContractPrimitives.MC_NAME),
                optionalReason(reader));
    }

    /**
     * A kick reason is optional; when present it must be a string of at most 256 code points.
     */
    private static String optionalReason(PayloadReader reader) {
        return reader.optional("reason") == null ? null : reader.string("reason", 0, REASON_MAX, null);
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
        runKick(channel, request, action, subject);
    }

    /**
     * Kicks with the reason as one plain-text component, or with the server's own default message
     * when the panel sent no reason.
     */
    private void runKick(CloudChannel channel, CloudRequest request, Action action, Player subject) {
        try {
            if (action.reason == null) {
                subject.kick();
            } else {
                subject.kick(Component.text(action.reason));
            }
        } catch (RuntimeException ex) {
            fail(channel, request, action, ex);
            return;
        }
        succeed(channel, request, action);
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
     * Maps a throwing call to the contract's {@code internal_error}, carrying only the exception
     * message (never a class name prefix, payload or stack trace) on the wire.
     */
    private void fail(CloudChannel channel, CloudRequest request, Action action, Throwable error) {
        LOGGER.log(Level.WARNING, "UltiCloud: the player action " + action.action + " failed.", error);
        deny(channel, request, action, CODE_INTERNAL_ERROR, safeMessage(error));
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
     * Returns the exception's own message, bounded to the result schema's 500 code points and
     * replaced by the exception type only when the message is absent or empty.
     */
    private static String safeMessage(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isEmpty()) {
            text = error.getClass().getSimpleName();
        }
        if (text == null || text.isEmpty()) {
            return FALLBACK_MESSAGE;
        }
        int count = text.codePointCount(0, text.length());
        return count <= MESSAGE_MAX ? text : text.substring(0, text.offsetByCodePoints(0, MESSAGE_MAX));
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

        private Action(String action, String player, String reason) {
            this.action = action;
            this.player = player;
            this.reason = reason;
        }

        static Action kick(String player, String reason) {
            return new Action(ACTION_KICK, player, reason);
        }
    }
}
