package com.ultikits.ultitools.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * The shutdown window of the inbound UltiCloud requests that change server state (22-23 shutdown-window round,
 * maintainer decision of 2026-10-26); internal, not for module use.
 * <p>
 * <b>One signal.</b> {@link Signal#enter()} is the only question every state-changing path asks: in production it is
 * {@link PluginInitiationUtils#enterPanelChange()}, which is {@code true} only while
 * {@link PluginInitiationUtils#isAcceptingPanelMessages()} holds (the accepting flag {@code UltiTools#onDisable} clears
 * as its first step, and Bukkit's {@code isEnabled()}, which turns {@code false} before {@code onDisable} runs). A path
 * that entered holds a shared permit until {@link Signal#exit()}; {@code stopAcceptingPanelMessages} waits for every
 * permit to be returned before {@code onDisable} goes on, so a change that started before the disable finishes, and
 * writes its action-log entry, before the action log closes later in {@code onDisable}.
 * <p>
 * <b>Consumers.</b> {@link AgentMessageRouter#dispatch(String, CloudChannel)} (every covered type, before the switch
 * gate and the handler, for the whole handler call) and {@link SerialRequestWorker} (the covered file and property
 * types again when the worker runs them, since a request queued before the disable may run after it). A request that
 * hands its change to another thread takes a permit of its own for that work and returns it when the work ends, from
 * whichever thread ends it (22-23 second verification): {@link ModuleRequestHandler} for the module responder's
 * worker, {@link PlayerLinkChannel} for the link handler thread. The command handler reads the same flag through
 * {@code Bindings#disabling} on the server thread; work handed to the server thread is not waited for, since it can
 * only run in a later tick and Bukkit cancels it when UltiTools disables.
 * <p>
 * <b>Covered types and their refusal</b> (every other request type keeps working until the connection closes):
 * <ul>
 *   <li>{@code command.execute}, {@code server.power}: acknowledged first through the shared ledger (so a redelivery
 *       after a restart is acknowledged and never run), then {@code command.result} / {@code server.power.result}
 *       {@code internal_error};</li>
 *   <li>{@code files.write}, {@code files.delete}, {@code properties.update}, {@code config.update},
 *       {@code player.action}, {@code module.request}: their result type with {@code internal_error};</li>
 *   <li>{@code credential.rotate}: its acknowledgement has no failure form, so the generic {@code error}
 *       {@code internal_error} with {@code replyTo}; the gateway sends the rotation again after the next welcome;</li>
 *   <li>{@code player.link.completed}: an at-least-once event, not a request. It is left unanswered, so the gateway
 *       delivers it again after the next welcome (contract SEMANTICS 19) and the account is provisioned then; a
 *       failure receipt would end the delivery with the account not created.</li>
 * </ul>
 * The message of every refusal says the server is shutting down. No refusal changes server state or writes an
 * operator file; the ledger records an acknowledged id as it does for every ack-first request.
 *
 * @since 6.3.1
 */
final class ShutdownGate {

    /** The message of every refusal. */
    static final String MESSAGE = "The server is shutting down; this request was not carried out.";

    private static final String GENERIC_ERROR = "error";
    private static final String INTERNAL_ERROR = "internal_error";

    /** How a refused type is answered. */
    enum Refusal {
        /** {@code command.ack} through the ledger, then the result type with {@code internal_error}. */
        ACK_THEN_RESULT,
        /** The result type with {@code internal_error}. */
        RESULT,
        /** The generic {@code error} with {@code replyTo}. */
        GENERIC,
        /** Nothing: the gateway delivers it again after the next welcome. */
        NONE
    }

    private static final Map<String, Refusal> REFUSALS;
    private static final Map<String, String> RESULT_TYPES;

    static {
        Map<String, Refusal> refusals = new LinkedHashMap<>();
        Map<String, String> results = new LinkedHashMap<>();
        cover(refusals, results, "command.execute", Refusal.ACK_THEN_RESULT, "command.result");
        cover(refusals, results, "server.power", Refusal.ACK_THEN_RESULT, "server.power.result");
        cover(refusals, results, "files.write", Refusal.RESULT, "files.write.result");
        cover(refusals, results, "files.delete", Refusal.RESULT, "files.delete.result");
        cover(refusals, results, "properties.update", Refusal.RESULT, "properties.update.result");
        cover(refusals, results, "config.update", Refusal.RESULT, "config.update.result");
        cover(refusals, results, "player.action", Refusal.RESULT, "player.action.result");
        cover(refusals, results, "module.request", Refusal.RESULT, "module.response");
        cover(refusals, results, "credential.rotate", Refusal.GENERIC, GENERIC_ERROR);
        cover(refusals, results, "player.link.completed", Refusal.NONE, null);
        REFUSALS = Collections.unmodifiableMap(refusals);
        RESULT_TYPES = Collections.unmodifiableMap(results);
    }

    /** The one shutdown signal; see the class comment. */
    interface Signal {
        /** A signal that never refuses: for bindings with no plugin behind them. */
        Signal OPEN = new Signal() {
            @Override
            public boolean enter() {
                return true;
            }

            @Override
            public void exit() {
                // nothing was taken
            }
        };

        /**
         * @return {@code true} when a state change may start now; the caller then calls {@link #exit()} exactly
         *         once when it ends. {@code false} when the server is shutting down; nothing was taken.
         */
        boolean enter();

        /** Returns the permit {@link #enter()} gave; may run on a thread other than the one that entered. */
        void exit();
    }

    private final Signal signal;
    private final DedupeLedger ledger;

    /**
     * @param signal the shutdown signal
     * @param ledger the ledger every ack-first request is recorded in, or {@code null} when none is bound (an ack-first
     *               refusal then sends its acknowledgement without recording the id)
     */
    ShutdownGate(Signal signal, DedupeLedger ledger) {
        this.signal = Objects.requireNonNull(signal, "signal");
        this.ledger = ledger;
    }

    /** @return the state-changing request types this gate covers, in contract order */
    static Set<String> coveredTypes() {
        return REFUSALS.keySet();
    }

    /**
     * @param type a registered message type
     * @return whether the type changes server state and is refused while the server is shutting down
     */
    static boolean covers(String type) {
        return REFUSALS.containsKey(type);
    }

    /** @return how {@code type} is answered when refused, or {@code null} when it is not covered */
    static Refusal refusalOf(String type) {
        return REFUSALS.get(type);
    }

    /** @return the type a refusal of {@code type} is sent as, or {@code null} when none is sent */
    static String resultTypeOf(String type) {
        return RESULT_TYPES.get(type);
    }

    /** @return see {@link Signal#enter()} */
    boolean enter() {
        return signal.enter();
    }

    /** @see Signal#exit() */
    void exit() {
        signal.exit();
    }

    /**
     * Answers a covered request that was not let in. Never runs the request.
     *
     * @param request the refused request
     * @param channel the connection it arrived on
     */
    void refuse(final CloudRequest request, final CloudChannel channel) {
        final Refusal refusal = REFUSALS.get(request.type());
        if (refusal == null || refusal == Refusal.NONE) {
            PanelConnectionLog.log(Level.FINE, "UltiCloud: " + request.type() + " left for redelivery: the server is "
                    + "shutting down.");
            return;
        }
        final String resultType = RESULT_TYPES.get(request.type());
        PanelConnectionLog.log(Level.FINE, "UltiCloud: " + request.type() + " refused: the server is shutting down.");
        if (refusal == Refusal.ACK_THEN_RESULT && ledger != null) {
            AckFirstGate.handle(request, channel, ledger, new Runnable() {
                @Override
                public void run() {
                    channel.replyError(request.id(), resultType, INTERNAL_ERROR, MESSAGE, null);
                }
            });
            return;
        }
        if (refusal == Refusal.ACK_THEN_RESULT) {
            channel.reply(request.id(), "command.ack", new JsonObject());
        }
        channel.replyError(request.id(), resultType, INTERNAL_ERROR, MESSAGE, null);
    }

    private static void cover(Map<String, Refusal> refusals, Map<String, String> results, String type,
                              Refusal refusal, String resultType) {
        refusals.put(type, refusal);
        results.put(type, resultType);
    }
}
