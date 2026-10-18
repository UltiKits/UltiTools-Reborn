package com.ultikits.ultitools.websocket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.manager.PluginManager;
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

    /** How many responder calls run at once, each on its own registry-owned worker thread. */
    static final int RESPONDER_WORKER_THREADS = 4;

    /** How many admitted requests may wait for a free worker before a request is refused. */
    static final int RESPONDER_QUEUE_CAPACITY = 64;

    /** How long an idle worker thread is kept before it ends, so an idle registry holds no worker. */
    private static final long WORKER_KEEP_ALIVE_SECONDS = 30L;

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
     * The deadline, in milliseconds, every admitted request gets; {@link #RESPONDER_TIMEOUT_MILLIS}
     * outside tests.
     */
    private final long deadlineMillis;

    /**
     * Runs responder calls, separate from {@link #timeoutScheduler} so a blocked call can never
     * delay a deadline. Bounded threads, a bounded queue and an abort policy; invocations are handed
     * over with {@code execute}, never {@code submit}, so a deadline can take a not-yet-started
     * invocation back out of the queue with {@link ThreadPoolExecutor#remove(Runnable)}.
     */
    private final ThreadPoolExecutor workers;

    /** How many admitted requests may wait for a free worker; named in a refusal. */
    private final int queueCapacity;

    /**
     * Every admitted request whose outcome has not settled yet; {@link #shutdown()} settles them.
     * An invocation leaves this set through the completion hook registered at admission.
     */
    private final Set<Invocation> admitted = ConcurrentHashMap.newKeySet();

    /** Set first thing in {@link #shutdown()}; a request admitted afterwards fails at once. */
    private volatile boolean shutDown;

    /**
     * Creates a registry with the shipped deadline ({@link #RESPONDER_TIMEOUT_MILLIS}) and worker
     * bounds ({@link #RESPONDER_WORKER_THREADS}, {@link #RESPONDER_QUEUE_CAPACITY}).
     */
    public PanelResponderRegistry() {
        this(RESPONDER_TIMEOUT_MILLIS, RESPONDER_WORKER_THREADS, RESPONDER_QUEUE_CAPACITY);
    }

    /**
     * Test seam: a registry with its own deadline and worker bounds.
     *
     * @param deadlineMillis the deadline every admitted request gets, in milliseconds
     * @param workerThreads  how many responder calls run at once
     * @param queueCapacity  how many admitted requests may wait for a free worker
     */
    PanelResponderRegistry(long deadlineMillis, int workerThreads, int queueCapacity) {
        this.deadlineMillis = deadlineMillis;
        this.queueCapacity = queueCapacity;
        this.workers = createWorkers(workerThreads, queueCapacity);
    }

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

    private static ThreadPoolExecutor createWorkers(int workerThreads, int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        // The framework's own loader, not the dispatching thread's: an idle worker must never pin a
        // module's class loader across a reload.
        ClassLoader frameworkLoader = PanelResponderRegistry.class.getClassLoader();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(workerThreads, workerThreads,
                WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity), r -> {
                    Thread thread = new Thread(r, "UltiTools-PanelResponderRegistry-Worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setContextClassLoader(frameworkLoader);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
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
     * @param ownerModule the name of the module registering this responder. The responder is
     *                    recorded against a module instance (#506, #562 item 1): the module the
     *                    framework is loading on this thread, if any; otherwise the copy listed as
     *                    loaded under {@code ownerModule} (the first listed when more than one is);
     *                    otherwise none, and it is filed under the name only. Prefer {@link
     *                    #registerResponder(String, Function, String, UltiToolsPlugin)} when the
     *                    module instance is at hand
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
     *                      #beginRegistrationScope(UltiToolsPlugin)}), otherwise the copy listed as
     *                      loaded under {@code ownerModule} ({@link
     *                      PluginManager#findRegistrationOwner(String)}), which is exactly {@link
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
        if (recordedOwner == null) {
            recordedOwner = listedCopyNamed(ownerModule);
        }
        ResponderEntry entry = new ResponderEntry(responder, ownerModule, recordedOwner);
        ResponderEntry existing = responders.putIfAbsent(messageType, entry);
        if (existing != null) {
            throw PluginModuleException.responderTypeAlreadyOwned(messageType, existing.ownerModule);
        }
    }

    /**
     * The copy listed as loaded under {@code moduleName}, which a registration made by name outside
     * every load scope belongs to (#562 item 1); {@code null} before the framework is running.
     */
    private static UltiToolsPlugin listedCopyNamed(String moduleName) {
        UltiTools framework = UltiTools.getInstance();
        PluginManager modules = framework == null ? null : framework.getPluginManager();
        return modules == null ? null : modules.findRegistrationOwner(moduleName);
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

        Invocation invocation = new Invocation(messageType, entry, data, outcome);
        admitted.add(invocation);
        // Any settlement, including a caller's cancel, frees the queue slot and the deadline.
        outcome.whenComplete((value, failure) -> invocation.release());
        if (shutDown) {
            invocation.settle(null, invocation.shutDownFailure());
            return outcome;
        }
        try {
            invocation.deadline = timeoutScheduler.schedule(invocation::expire, deadlineMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException timerStopped) {
            invocation.settle(null, invocation.shutDownFailure());
            return outcome;
        }
        try {
            workers.execute(invocation);
        } catch (RejectedExecutionException refused) {
            // Not chained: the executor's message names internal objects and would reach the panel.
            invocation.settle(null, workers.isShutdown() ? invocation.shutDownFailure() : invocation.refusal());
        }
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
     * Test-only accessor for whether {@link #workers} has been shut down.
     *
     * @return {@code true} if the worker executor has been shut down
     */
    boolean isWorkerPoolShutdownForTesting() {
        return workers.isShutdown();
    }

    /**
     * Test-only accessor for how many admitted invocations are waiting for a free worker.
     *
     * @return the number of queued, not-yet-started invocations
     */
    int queuedInvocationCountForTesting() {
        return workers.getQueue().size();
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
        shutDown = true;
        timeoutScheduler.shutdownNow();
        workers.getQueue().clear();
        // shutdown(), never shutdownNow(): a running module call is not interrupted.
        workers.shutdown();
        for (Invocation invocation : admitted) {
            invocation.settle(null, invocation.shutDownFailure());
        }
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

    /**
     * One admitted request: the responder entry captured at admission, its deadline and its
     * outcome. Runs on a worker; every settlement goes through {@link #settle}.
     */
    private final class Invocation implements Runnable {
        private final String messageType;
        private final ResponderEntry entry;
        private final JsonObject data;
        private final CompletableFuture<JsonObject> outcome;
        private volatile ScheduledFuture<?> deadline;

        private Invocation(String messageType, ResponderEntry entry, JsonObject data,
                CompletableFuture<JsonObject> outcome) {
            this.messageType = messageType;
            this.entry = entry;
            this.data = data;
            this.outcome = outcome;
        }

        @Override
        public void run() {
            if (outcome.isDone()) {
                return;
            }
            CompletableFuture<JsonObject> responderFuture = callResponder();
            if (responderFuture == null) {
                return;
            }
            responderFuture.whenComplete((value, throwable) -> {
                if (throwable != null) {
                    settle(null, throwable);
                } else if (value == null) {
                    settle(null, new IllegalStateException("Responder for '" + messageType
                            + "' (owned by module '" + entry.ownerModule + "') completed with null"));
                } else {
                    settle(value, null);
                }
            });
        }

        /**
         * Calls the responder at the framework's boundary to module code: whatever the call throws
         * becomes this request's outcome instead of being lost in the worker. Returns {@code null}
         * when the outcome is already settled here.
         */
        // PMD.AvoidCatchingGenericException: this is the boundary to module code; any exception,
        // including a checked one thrown sneakily, must become the request's outcome.
        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        private CompletableFuture<JsonObject> callResponder() {
            CompletableFuture<JsonObject> responderFuture;
            try {
                responderFuture = entry.responder.apply(data);
            } catch (Exception | LinkageError e) {
                settle(null, e);
                return null;
            } catch (Error e) {
                settle(null, e);
                throw e;
            }
            if (responderFuture == null) {
                settle(null, new IllegalStateException("Responder for '" + messageType
                        + "' (owned by module '" + entry.ownerModule + "') returned null"));
            }
            return responderFuture;
        }

        /** The completion hook: frees the queue slot and the deadline, and stops tracking. */
        private void release() {
            workers.remove(this);
            ScheduledFuture<?> task = deadline;
            if (task != null) {
                task.cancel(false);
            }
            admitted.remove(this);
        }

        private RejectedExecutionException refusal() {
            return new RejectedExecutionException("Panel responder for '" + messageType + "' (owned by module '"
                    + entry.ownerModule + "') was refused: " + workers.getMaximumPoolSize()
                    + " responder calls are running and " + queueCapacity + " requests are waiting.");
        }

        private RejectedExecutionException shutDownFailure() {
            return new RejectedExecutionException("The panel responder registry shut down before the responder for '"
                    + messageType + "' (owned by module '" + entry.ownerModule + "') completed.");
        }

        /** The deadline action: take a not-yet-started invocation back, then fail the outcome. */
        private void expire() {
            workers.remove(this);
            settle(null, PluginModuleException.responderTimedOut(messageType, entry.ownerModule, deadlineMillis));
        }

        /**
         * The one settle path. Cancels the deadline before completing the outcome: a
         * {@link CompletableFuture} runs later-registered dependents first, so a caller waiting on
         * the outcome must find the deadline already gone. A second settlement is a no-op.
         */
        private void settle(JsonObject value, Throwable failure) {
            ScheduledFuture<?> task = deadline;
            if (task != null) {
                task.cancel(false);
            }
            if (failure != null) {
                outcome.completeExceptionally(failure);
            } else {
                outcome.complete(value);
            }
        }
    }
}
