package com.ultikits.ultitools.websocket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
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

    private static final Logger LOGGER = Logger.getLogger(PanelResponderRegistry.class.getName());

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
     * Pairs a responder function with the module name (and, when recorded, the module instance)
     * that registered it. Never exposed outside this class — {@link #hasResponder(String)},
     * {@link #unregisterAll(String)}, {@link #unregisterByOwnerInstance(UltiToolsPlugin)} and
     * {@link #releaseForSupersede(UltiToolsPlugin)} are the only externally visible views of what this
     * map holds.
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

    /**
     * The module instance the framework is loading on this thread, if any (#506): set by
     * {@link #beginRegistrationScope(UltiToolsPlugin)} while a module's container refreshes and its
     * {@code registerSelf()} runs, and recorded on every responder registered meanwhile without an
     * explicit owner instance.
     */
    private final ThreadLocal<UltiToolsPlugin> registrationScopeOwner = new ThreadLocal<>();

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
        registerResponder(messageType, responder, ownerModule, null);
    }

    /**
     * Same as {@link #registerResponder(String, Function, String)}, and additionally records the
     * module instance that owns the responder (#506), so {@link
     * #unregisterByOwnerInstance(UltiToolsPlugin)} releases exactly that instance's responders
     * whatever name they were filed under. Two copies of one module share a name; only the instance
     * tells a superseded copy's responders from its replacement's.
     *
     * @param messageType   the exact message type string this responder will serve
     * @param responder     the responder function
     * @param ownerModule   the name of the module registering this responder
     * @param ownerInstance the module instance registering it; {@code null} records the module the
     *                      framework is loading on this thread, if any (see {@link
     *                      #beginRegistrationScope(UltiToolsPlugin)}), which is exactly {@link
     *                      #registerResponder(String, Function, String)}
     * @throws IllegalArgumentException if {@code messageType} or {@code ownerModule} is
     *                                   {@code null}/empty, or {@code responder} is {@code null}
     * @throws PluginModuleException    if the framework already owns {@code messageType}, or
     *                                   another registration already claims it
     * @since 6.3.0
     */
    public void registerResponder(String messageType, Function<JsonObject, CompletableFuture<JsonObject>> responder,
            String ownerModule, UltiToolsPlugin ownerInstance) {
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
        UltiToolsPlugin recordedOwner = ownerInstance != null ? ownerInstance : registrationScopeOwner.get();
        ResponderEntry entry = new ResponderEntry(responder, ownerModule, recordedOwner);
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
     * Removes every responder registered with {@code ownerInstance} as its owner (#506), whatever
     * module name it was filed under. A responder registered with a name only is not matched here;
     * {@link #unregisterAll(String)} releases those.
     *
     * @param ownerInstance the module instance being unloaded; {@code null} is a no-op
     * @since 6.3.0
     */
    public void unregisterByOwnerInstance(UltiToolsPlugin ownerInstance) {
        if (ownerInstance == null) {
            return;
        }
        responders.values().removeIf(entry -> entry.ownerInstance == ownerInstance);
    }

    /**
     * Releases every responder recorded against {@code ownerInstance} and returns the action that
     * gives them back (#562). {@code PluginManager} calls this for a loaded copy of a module just
     * before a newer copy of the same module builds its container and runs {@code registerSelf()},
     * so the newer copy can claim the message types the older copy held: while both copies exist,
     * a type belongs to exactly one of them, and the older copy holds none of its types while the
     * newer copy registers. When the newer copy fails to load, the framework first releases what
     * the failed copy registered and then runs the returned action, which puts each released
     * responder back under its type, unchanged; when the newer copy loads, the action is dropped
     * and the older copy is unloaded. Running the action more than once restores nothing more.
     * <p>
     * Each responder is removed only while it is still the exact registration this call matched,
     * and put back only while its type is free: a type another registration claimed in between
     * keeps that registration, and one WARNING names the type and the module that lost it -- a
     * copy never takes over, or keeps, a type another copy holds. Intended for {@code
     * PluginManager}, not for module authors.
     *
     * @param ownerInstance the loaded copy being superseded; {@code null} releases nothing
     * @return the action that restores what this call released
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Runnable releaseForSupersede(UltiToolsPlugin ownerInstance) {
        Map<String, ResponderEntry> released = new LinkedHashMap<>();
        if (ownerInstance != null) {
            for (Map.Entry<String, ResponderEntry> registered : responders.entrySet()) {
                ResponderEntry entry = registered.getValue();
                // remove(key, value) is atomic and compares by identity (ResponderEntry keeps
                // Object#equals): a type registered again in the meantime is left alone.
                if (entry.ownerInstance == ownerInstance && responders.remove(registered.getKey(), entry)) {
                    released.put(registered.getKey(), entry);
                }
            }
        }
        AtomicBoolean pending = new AtomicBoolean(true);
        return () -> {
            if (pending.compareAndSet(true, false)) {
                restore(released);
            }
        };
    }

    private void restore(Map<String, ResponderEntry> released) {
        for (Map.Entry<String, ResponderEntry> entry : released.entrySet()) {
            ResponderEntry existing = responders.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && existing != entry.getValue()) {
                LOGGER.warning(String.format("Panel responder type '%s' was claimed by module '%s' while module '%s'"
                        + " was being replaced; it stays with '%s', and '%s' no longer answers it.",
                        entry.getKey(), existing.ownerModule, entry.getValue().ownerModule, existing.ownerModule,
                        entry.getValue().ownerModule));
            }
        }
    }

    /**
     * Attributes every responder registered on this thread, until {@link #endRegistrationScope()},
     * to {@code owner} when the registration names no owner instance itself (#506). The framework
     * opens this scope while it loads a module -- around its container refresh and its
     * {@code registerSelf()} -- so a responder a module registers while it loads, through {@link
     * #registerResponder(String, Function, String)}, is released with that module instance.
     * Intended for {@code PluginManager}, not for module authors. Scopes do not nest.
     *
     * @param owner the module instance being loaded
     * @since 6.3.0
     */
    public void beginRegistrationScope(UltiToolsPlugin owner) {
        if (owner != null) {
            registrationScopeOwner.set(owner);
        } else {
            registrationScopeOwner.remove();
        }
    }

    /**
     * Ends the scope started by {@link #beginRegistrationScope(UltiToolsPlugin)} on this thread.
     *
     * @since 6.3.0
     */
    public void endRegistrationScope() {
        registrationScopeOwner.remove();
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
     * Shuts down {@link #timeoutScheduler} (WR-01, 06-REVIEW.md).
     * <p>
     * {@link #timeoutScheduler} is a dedicated single-thread pool per instance — nothing else
     * shares it and nothing else stops it, so without this call a plugin {@code /reload} (which
     * constructs a fresh {@link PanelResponderRegistry} on every {@code onEnable} without ever
     * disposing of the previous one) leaks one more daemon
     * {@code UltiTools-PanelResponderRegistry-Timeout} thread per cycle for the life of the
     * process. {@link ScheduledThreadPoolExecutor#shutdownNow()}, not the graceful
     * {@code shutdown()}, because any timeout task still queued at this point belongs to a
     * responder dispatch this instance is being torn down anyway — there is no in-flight work here
     * worth draining.
     */
    public void shutdown() {
        timeoutScheduler.shutdownNow();
    }

    /**
     * An immutable pairing of a responder function with the module name that registered it and,
     * when the registration named one, the module instance (#506).
     */
    private static final class ResponderEntry {
        private final Function<JsonObject, CompletableFuture<JsonObject>> responder;
        private final String ownerModule;
        private final UltiToolsPlugin ownerInstance;

        private ResponderEntry(Function<JsonObject, CompletableFuture<JsonObject>> responder, String ownerModule,
                UltiToolsPlugin ownerInstance) {
            this.responder = responder;
            this.ownerModule = ownerModule;
            this.ownerInstance = ownerInstance;
        }
    }
}
