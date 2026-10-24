package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the ack-first {@code server.power} request: a structured stop or restart that never
 * passes through the command blocklist and is allowed only while the operator's
 * {@code ultipanel.capabilities.power-actions} switch is on.
 * <p>
 * The id is recorded and persisted before {@code command.ack} is sent, so a redelivered id is
 * acknowledged again but never stops or restarts the server a second time. The result frame is
 * handed to the channel before the pre-shutdown hook runs and before the power action is
 * scheduled, so the panel always learns what happened before the server begins to go down.
 * <p>
 * Restart is pre-checked against the server's {@code restart-script}: Spigot's own restart stops
 * the server when that script is missing, which would turn a requested restart into a stop that
 * nobody asked for. The check therefore answers {@code power_unsupported} instead of degrading.
 * <p>
 * Package-private framework plumbing; not for module use.
 *
 * @since 6.3.1
 */
final class PowerActionHandler implements CloudRequestHandler {

    /** The frozen contract request type this handler consumes. */
    static final String TYPE = "server.power";
    /** The frozen contract result type every delivery is answered with. */
    static final String RESULT_TYPE = "server.power.result";
    /** The contract's first action value. */
    static final String ACTION_STOP = "stop";
    /** The contract's second action value. */
    static final String ACTION_RESTART = "restart";
    /** {@code server.power} carries no executor field, so the action log names the panel itself. */
    static final String ACTOR = "panel";

    private static final Logger LOGGER = Logger.getLogger(PowerActionHandler.class.getName());

    private final DedupeLedger ledger;
    private final BooleanSupplier powerEnabled;
    private final PowerExecutor executor;
    private final Runnable preShutdownHook;
    private final Consumer<RemoteActionLog.Entry> actionLog;

    /**
     * Creates the handler.
     *
     * @param ledger the session ledger the request id is recorded in before the acknowledgement
     * @param powerEnabled the operator's {@code power-actions} switch, read on every delivery
     * @param executor schedules the stop or restart and reports whether a restart is possible
     * @param preShutdownHook runs after the result frame and before the power action is scheduled
     * @param actionLog receives one allowed or denied entry per delivery, or null to record nothing
     */
    PowerActionHandler(DedupeLedger ledger, BooleanSupplier powerEnabled, PowerExecutor executor,
                       Runnable preShutdownHook, Consumer<RemoteActionLog.Entry> actionLog) {
        this.ledger = ledger;
        this.powerEnabled = powerEnabled;
        this.executor = executor;
        this.preShutdownHook = preShutdownHook;
        this.actionLog = actionLog;
    }

    /**
     * Validates the action, then runs persist-acknowledge-execute. A payload that does not match
     * the contract propagates as {@code invalid_message} before anything is recorded.
     *
     * @param request the parsed request
     * @param channel the reply channel
     */
    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        reader.keys(new String[] {"action"});
        String action = reader.enumValue("action", ACTION_STOP, ACTION_RESTART);
        AckFirstGate.handle(request, channel, ledger, () -> execute(request, channel, action));
    }

    /**
     * Runs once per newly recorded id: the operator switch, then (for a restart) the restart
     * pre-check, then the result, the pre-shutdown hook and the scheduled power action in that
     * order.
     */
    private void execute(CloudRequest request, CloudChannel channel, String action) {
        if (!powerEnabled.getAsBoolean()) {
            String refusal = Capability.POWER_ACTIONS.refusalMessage();
            record(RemoteActionLog.Entry.denied(Capability.POWER_ACTIONS, actionType(action), action, ACTOR, refusal));
            channel.replyError(request.id(), RESULT_TYPE, "power_disabled", refusal, null);
            return;
        }
        if (ACTION_RESTART.equals(action) && !executor.restartSupported()) {
            String message = "This server cannot restart: the 'restart-script' under 'settings' in spigot.yml "
                    + "does not name an existing file.";
            record(RemoteActionLog.Entry.denied(Capability.POWER_ACTIONS, actionType(action), action, ACTOR, message));
            channel.replyError(request.id(), RESULT_TYPE, "power_unsupported", message, null);
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("action", action);
        channel.replyOk(request.id(), RESULT_TYPE, data);
        record(RemoteActionLog.Entry.allowed(Capability.POWER_ACTIONS, actionType(action), action, ACTOR));
        LOGGER.info("UltiCloud: the panel requested a server " + action + "; it begins in 1 s.");
        runPreShutdownHook(action);
        schedule(action);
    }

    /**
     * A failing hook must not cancel an action the operator asked for: it is logged once and the
     * power action proceeds.
     */
    private void runPreShutdownHook(String action) {
        try {
            preShutdownHook.run();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "UltiCloud: the pre-shutdown hook failed before the server " + action
                    + "; the action continues.", ex);
        }
    }

    /**
     * The result frame was already handed to the channel, so a scheduler refusal is reported
     * locally and nothing tries to answer the panel a second time.
     */
    private void schedule(String action) {
        try {
            if (ACTION_STOP.equals(action)) {
                executor.scheduleStop();
            } else {
                executor.scheduleRestart();
            }
        } catch (RuntimeException ex) {
            LOGGER.log(Level.SEVERE, "UltiCloud: could not schedule the server " + action
                    + "; the result was already sent.", ex);
        }
    }

    private void record(RemoteActionLog.Entry entry) {
        if (actionLog != null) {
            actionLog.accept(entry);
        }
    }

    private static String actionType(String action) {
        return TYPE + "." + action;
    }

    /**
     * The two calls {@link BukkitPowerExecutor} turns into a main-thread stop or restart, kept
     * behind an interface so the handler stays unit-testable without a live server.
     */
    interface PowerExecutor {

        /**
         * Whether this server can restart at all — the {@code settings.restart-script} file the
         * server would run exists.
         *
         * @return true when a restart would actually restart rather than stop
         */
        boolean restartSupported();

        /** Schedules the stop on the main thread, after the result frame was sent. */
        void scheduleStop();

        /** Schedules the restart on the main thread, after the result frame was sent. */
        void scheduleRestart();
    }
}
