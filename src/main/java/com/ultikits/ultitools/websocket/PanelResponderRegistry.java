package com.ultikits.ultitools.websocket;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

/**
 * The single-owner registry a module uses to claim a request/response responder for a panel
 * message type the framework itself does not own (WIRE-16, D-26/D-27).
 * <p>
 * A module owns at most one responder per message type. Ownership is decided against exactly one
 * source of truth for the framework's own types — {@link PluginInitiationUtils#isFrameworkOwnedType}
 * — so a module can never silently take over {@code execute_command} or any of the framework's other
 * 23 inbound message types; that check runs before the module-owner check, and both refusals throw a
 * typed {@link PluginModuleException} naming the offender rather than a raw {@code RuntimeException}.
 * <p>
 * A module-owned type is served from the exact same lookup {@code PluginInitiationUtils
 * #handleInboundMessage} already uses for the framework's own 24 types — its unknown-type branch
 * consults this registry, not a second dispatch mechanism (01-CONTEXT D-10/D-11).
 * <p>
 * Deliberately does not require a {@code <module>:<type>} namespace prefix — D-26 rejected that as a
 * cross-repository protocol convention the panel would also have to honour, rather than an in-repo
 * check the framework can enforce alone.
 *
 * @since 6.3.0
 */
public class PanelResponderRegistry {

    /**
     * The bounded wall-clock time {@link #dispatch} gives a registered responder's future to
     * complete before completing exceptionally on the caller's behalf (D-27). One timeout, applied
     * in exactly one place — inside {@code dispatch} — so no responder and no caller implements its
     * own. 3 seconds: long enough for a responder doing real database or disk work, short enough
     * that a human operator watching the panel does not start to think it froze. Package-private
     * (not private) so {@code PanelResponderRegistryTest} can bound its own wait without
     * duplicating this value.
     */
    static final long RESPONDER_TIMEOUT_MILLIS = 3000L;

    /**
     * Pairs a responder function with the module name that registered it. Never exposed outside
     * this class — {@link #hasResponder(String)}/{@link #unregisterAll(String)} are the only
     * externally visible views of what this map holds.
     */
    private final Map<String, ResponderEntry> responders = new ConcurrentHashMap<>();

    /**
     * Schedules {@link #RESPONDER_TIMEOUT_MILLIS}'s one-timeout-in-one-place enforcement. A
     * dedicated single-thread pool, not a shared framework scheduler, so one slow responder cannot
     * starve unrelated timeout tasks. {@code setRemoveOnCancelPolicy(true)} makes a cancelled task
     * — the fast path, when the responder completes before the timeout fires — removed from the
     * queue synchronously inside {@code cancel()}, rather than lingering in the queue until its
     * scheduled time; without it a fast responder would still "leak" a queued task for
     * {@link #RESPONDER_TIMEOUT_MILLIS}. There is no {@code CompletableFuture.orTimeout} on the
     * Java 8 bytecode target this framework compiles to (that method is Java 9+), which is why the
     * timeout is hand-scheduled here rather than chained.
     */
    private final ScheduledThreadPoolExecutor timeoutScheduler = createTimeoutScheduler();

    private static ScheduledThreadPoolExecutor createTimeoutScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "UltiTools-PanelResponderRegistry-Timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * Registers {@code responder} as the single owner of {@code messageType}.
     * <p>
     * Validates all three arguments first. Then checks
     * {@link PluginInitiationUtils#isFrameworkOwnedType(String)} before checking module ownership —
     * deliberately in that order, so a module attempting {@code execute_command} is told the
     * framework owns it, not that some other module does. Finally uses an atomic
     * {@code putIfAbsent}-shaped check so a collision — with the framework, with another module, or
     * with the same module registering twice — always throws rather than silently displacing an
     * existing registration.
     *
     * @param messageType the exact message type string this responder will serve, matched by
     *                    {@code String.equals} — no case folding, no Unicode normalization
     * @param responder   the responder function, invoked with the inbound message's {@code data}
     *                    and returning a {@link CompletableFuture} that resolves the reply
     * @param ownerModule the name of the module registering this responder
     * @throws IllegalArgumentException if {@code messageType} or {@code ownerModule} is
     *                                   {@code null}/empty, or {@code responder} is {@code null}
     * @throws PluginModuleException    if the framework already owns {@code messageType}, or
     *                                   another registration (including the same module's own
     *                                   prior registration) already claims it
     */
    public void registerResponder(String messageType, Function<JsonObject, CompletableFuture<JsonObject>> responder,
            String ownerModule) {
        if (messageType == null || messageType.isEmpty()) {
            throw new IllegalArgumentException("messageType must not be null or empty");
        }
        if (responder == null) {
            throw new IllegalArgumentException("responder must not be null");
        }
        if (ownerModule == null || ownerModule.isEmpty()) {
            throw new IllegalArgumentException("ownerModule must not be null or empty");
        }
        if (PluginInitiationUtils.isFrameworkOwnedType(messageType)) {
            throw PluginModuleException.responderTypeOwnedByFramework(messageType);
        }
        ResponderEntry entry = new ResponderEntry(responder, ownerModule);
        ResponderEntry existing = responders.putIfAbsent(messageType, entry);
        if (existing != null) {
            throw PluginModuleException.responderTypeAlreadyOwned(messageType, existing.ownerModule);
        }
    }

    /**
     * Removes every responder {@code moduleName} owns — the mirror of
     * {@code EventBus.unregisterAll(String)}'s one-{@code String}-parameter void signature and
     * iterate-and-remove-by-owner shape, placed adjacent to the two existing
     * {@code eventBus.unregisterAll(...)} call sites in {@code PluginManager} rather than a new
     * lifecycle mechanism.
     * <p>
     * A module that registered nothing is a no-op: nothing is removed and nothing is thrown.
     * {@code moduleName == null} is also a no-op.
     *
     * @param moduleName the module whose responders should be removed, possibly {@code null}
     */
    public void unregisterAll(String moduleName) {
        if (moduleName == null) {
            return;
        }
        responders.values().removeIf(entry -> moduleName.equals(entry.ownerModule));
    }

    /**
     * Whether a responder is currently registered for the exact string {@code messageType}.
     *
     * @param messageType the message type to check, possibly {@code null}
     * @return {@code true} if a responder owns this exact type
     */
    public boolean hasResponder(String messageType) {
        return messageType != null && responders.containsKey(messageType);
    }

    /**
     * Invokes {@code messageType}'s registered responder and returns a future that is already
     * bounded by {@link #RESPONDER_TIMEOUT_MILLIS} — the single timeout-and-reply-shape point every
     * failure mode routes through (D-27):
     * <ul>
     *   <li>the responder throws synchronously, before returning a future — caught and becomes a
     *       failed future rather than escaping;</li>
     *   <li>the responder returns {@code null} — treated as an explicit failure, not a
     *       {@code NullPointerException};</li>
     *   <li>the responder's future never completes — the scheduled timeout task completes this
     *       method's returned future exceptionally with
     *       {@link PluginModuleException#responderTimedOut(String, String, long)};</li>
     *   <li>the responder's future completes exceptionally — that exception is relayed directly.</li>
     * </ul>
     * On the fast path (the responder's future is already complete, or completes before the
     * timeout fires), the scheduled timeout task is cancelled inside the same
     * {@code whenComplete} callback that resolves the returned future, so nothing is left pending.
     *
     * @param messageType the message type to dispatch, expected to already have a registered
     *                     responder ({@link #hasResponder(String)})
     * @param data         the inbound message's {@code data} object, passed straight to the
     *                     responder
     * @param requestId    the request's correlation id — not used by {@code dispatch} itself, but
     *                     accepted so the call site does not have to separately track it; reserved
     *                     for the caller assembling the outbound reply
     * @return a future that always completes — successfully with the responder's result, or
     *         exceptionally with a failure describing what went wrong
     */
    public CompletableFuture<JsonObject> dispatch(String messageType, JsonObject data, String requestId) {
        CompletableFuture<JsonObject> outcome = new CompletableFuture<>();
        ResponderEntry entry = responders.get(messageType);
        if (entry == null) {
            outcome.completeExceptionally(
                    new IllegalStateException("No responder registered for '" + messageType + "'"));
            return outcome;
        }

        CompletableFuture<JsonObject> responderFuture;
        try {
            responderFuture = entry.responder.apply(data);
        } catch (RuntimeException e) {
            outcome.completeExceptionally(e);
            return outcome;
        }
        if (responderFuture == null) {
            outcome.completeExceptionally(new IllegalStateException("Responder for '" + messageType
                    + "' (owned by module '" + entry.ownerModule + "') returned null"));
            return outcome;
        }

        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(
                () -> outcome.completeExceptionally(
                        PluginModuleException.responderTimedOut(messageType, entry.ownerModule,
                                RESPONDER_TIMEOUT_MILLIS)),
                RESPONDER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        responderFuture.whenComplete((value, throwable) -> {
            // Cancelling here — win or lose the race against the scheduled task above — is what
            // guarantees the fast path leaves nothing pending: if the timeout already fired,
            // outcome is already complete and these calls are harmless no-ops (CompletableFuture's
            // second completion attempt is silently ignored).
            timeoutTask.cancel(false);
            if (throwable != null) {
                outcome.completeExceptionally(throwable);
            } else if (value == null) {
                outcome.completeExceptionally(new IllegalStateException("Responder for '" + messageType
                        + "' (owned by module '" + entry.ownerModule + "') completed with null"));
            } else {
                outcome.complete(value);
            }
        });
        return outcome;
    }

    /**
     * Test-only accessor for how many timeout tasks are still queued. Proves the fast path in
     * {@link #dispatch} leaves nothing pending, rather than merely proving a reply arrived.
     *
     * @return the number of scheduled-but-not-yet-run-or-cancelled timeout tasks
     */
    int pendingTimeoutTaskCountForTesting() {
        return timeoutScheduler.getQueue().size();
    }

    /**
     * Test-only accessor for whether {@link #timeoutScheduler} has been shut down — proves
     * {@link #shutdown()} actually stops the dedicated timeout thread (WR-01), rather than merely
     * proving the method exists and does not throw.
     *
     * @return {@code true} if {@link #timeoutScheduler} has been shut down
     */
    boolean isTimeoutSchedulerShutdownForTesting() {
        return timeoutScheduler.isShutdown();
    }

    /**
     * Releases {@link #timeoutScheduler} (WR-01, 06-REVIEW.md).
     * <p>
     * <b>RED placeholder</b> — intentionally a no-op so the paired failing test proves the leak via
     * an assertion rather than a compile error. The GREEN commit replaces this body with the real
     * fix: {@link ScheduledThreadPoolExecutor#shutdownNow()} on {@link #timeoutScheduler}, so a
     * plugin {@code /reload} does not leak one more {@code UltiTools-PanelResponderRegistry-Timeout}
     * thread per cycle.
     */
    public void shutdown() {
        // Filled in by the paired GREEN commit.
    }

    /** An immutable pairing of a responder function and the module name that registered it. */
    private static final class ResponderEntry {
        private final Function<JsonObject, CompletableFuture<JsonObject>> responder;
        private final String ownerModule;

        private ResponderEntry(Function<JsonObject, CompletableFuture<JsonObject>> responder, String ownerModule) {
            this.responder = responder;
            this.ownerModule = ownerModule;
        }
    }
}
