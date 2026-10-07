package com.ultikits.ultitools.events;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.HandlerEntry;
import com.ultikits.ultitools.entities.Subscription;

/**
 * Central event bus for inter-module communication.
 * Supports sync and async dispatch, annotation and programmatic subscriptions.
 *
 * @since 6.2.2
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Invokes @ModuleEventHandler methods -- see 08-GATE05-TRIAGE.md
public class EventBus {
    private static final Logger LOGGER = Logger.getLogger(EventBus.class.getName());

    private final Map<Class<? extends ModuleEvent>, CopyOnWriteArrayList<HandlerEntry>> handlers =
            new ConcurrentHashMap<>();

    private final ExecutorService asyncPool;

    /**
     * The module instance the framework is loading on this thread, if any (#506): set by
     * {@link #beginRegistrationScope(UltiToolsPlugin)} while a module's container refreshes and its
     * {@code registerSelf()} runs, and recorded on every handler registered meanwhile without an
     * explicit owner instance. A {@code ThreadLocal}, like {@code TabCompletionManager}'s scope, so a
     * registration another thread makes during that window is not attributed to the module.
     */
    private final ThreadLocal<UltiToolsPlugin> registrationScopeOwner = new ThreadLocal<>();

    public EventBus() {
        this.asyncPool = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "UltiTools-EventBus-Async");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // --- Registration ---

    /**
     * Register an annotation-based handler.
     */
    public void register(Class<? extends ModuleEvent> eventType, EventPriority priority,
                         boolean ignoreCancelled, String ownerModule,
                         Method method, Object instance) {
        register(eventType, priority, ignoreCancelled, ownerModule, null, method, instance);
    }

    /**
     * Register an annotation-based handler and record the module instance that owns it (#506).
     * <p>
     * {@link #unregisterByOwnerInstance(UltiToolsPlugin)} then releases exactly this instance's
     * handlers, whatever name they were filed under. The framework registers every module's
     * {@code @ModuleEventHandler} methods this way, so unloading a superseded copy of a module
     * cannot release its replacement's handlers, which share its name.
     *
     * @param eventType       the event type handled
     * @param priority        the dispatch priority
     * @param ignoreCancelled whether a cancelled event skips this handler
     * @param ownerModule     the owning module's name, used by {@link #unregisterAll(String)}
     * @param ownerInstance   the owning module instance; {@code null} records none
     * @param method          the handler method
     * @param instance        the bean the method is invoked on
     * @since 6.3.0
     */
    public void register(Class<? extends ModuleEvent> eventType, EventPriority priority,
                         boolean ignoreCancelled, String ownerModule, UltiToolsPlugin ownerInstance,
                         Method method, Object instance) {
        method.setAccessible(true); // NOPMD - required for handler invocation
        HandlerEntry entry = new HandlerEntry(eventType, priority, ignoreCancelled, ownerModule,
                ownerInstanceOrScope(ownerInstance), method, instance);
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(entry);
    }

    /**
     * Register a programmatic handler. Returns a Subscription for manual unsubscribe.
     */
    public <T extends ModuleEvent> Subscription subscribe(Class<T> eventType, Consumer<T> consumer) {
        return subscribe(eventType, EventPriority.NORMAL, false, null, consumer);
    }

    /**
     * Register a programmatic handler with full options.
     */
    public <T extends ModuleEvent> Subscription subscribe(Class<T> eventType, EventPriority priority,
                                                           boolean ignoreCancelled, String ownerModule,
                                                           Consumer<T> consumer) {
        return subscribe(eventType, priority, ignoreCancelled, ownerModule, null, consumer);
    }

    /**
     * Register a programmatic handler and record the module instance that owns it (#506), so
     * {@link #unregisterByOwnerInstance(UltiToolsPlugin)} releases it whatever name it was filed
     * under. Without an instance, a handler subscribed while the framework loads a module (see
     * {@link #beginRegistrationScope(UltiToolsPlugin)}) is recorded against that module; one
     * subscribed later is filed under {@code ownerModule} only.
     *
     * @param eventType       the event type handled
     * @param priority        the dispatch priority
     * @param ignoreCancelled whether a cancelled event skips this handler
     * @param ownerModule     the owning module's name, used by {@link #unregisterAll(String)}
     * @param ownerInstance   the owning module instance; {@code null} records the loading module, if any
     * @param consumer        the handler
     * @param <T>             the event type
     * @return a subscription for manual unsubscribe
     * @since 6.3.0
     */
    @SuppressWarnings("unchecked")
    public <T extends ModuleEvent> Subscription subscribe(Class<T> eventType, EventPriority priority,
                                                           boolean ignoreCancelled, String ownerModule,
                                                           UltiToolsPlugin ownerInstance, Consumer<T> consumer) {
        HandlerEntry entry = new HandlerEntry(eventType, priority, ignoreCancelled, ownerModule,
                ownerInstanceOrScope(ownerInstance), (Consumer<? extends ModuleEvent>) consumer);
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(entry);

        AtomicBoolean active = new AtomicBoolean(true);
        return new Subscription() {
            @Override
            public void unsubscribe() {
                if (active.compareAndSet(true, false)) {
                    CopyOnWriteArrayList<HandlerEntry> list = handlers.get(eventType);
                    if (list != null) {
                        list.remove(entry);
                    }
                }
            }

            @Override
            public boolean isActive() {
                return active.get();
            }
        };
    }

    /**
     * Unregister all handlers owned by a module.
     */
    public void unregisterAll(String moduleName) {
        for (CopyOnWriteArrayList<HandlerEntry> list : handlers.values()) {
            Iterator<HandlerEntry> it = list.iterator();
            while (it.hasNext()) {
                HandlerEntry entry = it.next();
                if (moduleName.equals(entry.getOwnerModule())) {
                    list.remove(entry);
                }
            }
        }
    }

    /**
     * Unregister every handler registered with {@code ownerInstance} as its owner (#506), whatever
     * module name it was filed under. Handlers registered with a name only are not matched here;
     * {@link #unregisterAll(String)} releases those.
     *
     * @param ownerInstance the module instance being unloaded; {@code null} matches nothing
     * @since 6.3.0
     */
    public void unregisterByOwnerInstance(UltiToolsPlugin ownerInstance) {
        if (ownerInstance == null) {
            return;
        }
        for (CopyOnWriteArrayList<HandlerEntry> list : handlers.values()) {
            list.removeIf(entry -> entry.getOwnerInstance() == ownerInstance);
        }
    }

    /**
     * Releases every handler recorded against {@code ownerInstance} and returns the action that
     * gives them back (#562). {@code PluginManager} calls this for a loaded copy of a module just
     * before a newer copy of the same module builds its container and runs {@code registerSelf()}:
     * from then on the older copy receives no event, so nothing the newer copy publishes while it
     * loads reaches code of the copy it replaces. When the newer copy fails to load, the framework
     * first releases what the failed copy registered and then runs the returned action, which puts
     * each released handler back at its place in its event type's list, so it is dispatched as
     * before and a {@link Subscription} the older copy holds still removes it; when the newer copy
     * loads, the action is dropped and the older copy is unloaded. Running the action more than
     * once restores nothing more. A handler is never released from, or restored into, another
     * copy's registrations: only entries recorded against {@code ownerInstance} are matched.
     * Intended for {@code PluginManager}, not for module authors.
     *
     * @param ownerInstance the loaded copy being superseded; {@code null} releases nothing
     * @return the action that restores what this call released
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Runnable releaseForSupersede(UltiToolsPlugin ownerInstance) {
        List<ReleasedHandler> released = new ArrayList<>();
        if (ownerInstance != null) {
            for (Map.Entry<Class<? extends ModuleEvent>, CopyOnWriteArrayList<HandlerEntry>> byType : handlers.entrySet()) {
                CopyOnWriteArrayList<HandlerEntry> list = byType.getValue();
                int position = 0;
                for (HandlerEntry entry : list) {
                    // The iterator is a snapshot; positions are those of the list as it was.
                    if (entry.getOwnerInstance() == ownerInstance && list.remove(entry)) {
                        released.add(new ReleasedHandler(byType.getKey(), entry, position));
                    }
                    position++;
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

    private void restore(List<ReleasedHandler> released) {
        for (ReleasedHandler handler : released) {
            CopyOnWriteArrayList<HandlerEntry> list = handlers.computeIfAbsent(handler.eventType,
                    k -> new CopyOnWriteArrayList<>());
            if (list.contains(handler.entry)) {
                continue;
            }
            try {
                list.add(Math.min(handler.position, list.size()), handler.entry);
            } catch (IndexOutOfBoundsException shrunkMeanwhile) {
                // Another thread removed a handler between size() and add(): keep the handler, at the end.
                list.add(handler.entry);
            }
        }
    }

    /** One handler {@link #releaseForSupersede(UltiToolsPlugin)} removed, and where it stood. */
    private static final class ReleasedHandler {
        private final Class<? extends ModuleEvent> eventType;
        private final HandlerEntry entry;
        private final int position;

        private ReleasedHandler(Class<? extends ModuleEvent> eventType, HandlerEntry entry, int position) {
            this.eventType = eventType;
            this.entry = entry;
            this.position = position;
        }
    }

    /**
     * Attributes every handler registered on this thread, until {@link #endRegistrationScope()},
     * to {@code owner} when the registration names no owner instance itself (#506). The framework
     * opens this scope while it loads a module -- around the module's container refresh, where
     * {@code @PostConstruct} runs, and around its {@code registerSelf()} -- so that a module's
     * programmatic subscriptions made while it loads are released with that module instance.
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

    private UltiToolsPlugin ownerInstanceOrScope(UltiToolsPlugin ownerInstance) {
        return ownerInstance != null ? ownerInstance : registrationScopeOwner.get();
    }

    // --- Dispatch ---

    /**
     * Publish an event synchronously. Handlers run on the calling thread in priority order.
     */
    public void publish(ModuleEvent event) {
        List<HandlerEntry> sorted = collectHandlers(event.getClass());
        for (HandlerEntry entry : sorted) {
            if (entry.isIgnoreCancelled() && event instanceof Cancellable && ((Cancellable) event).isCancelled()) {
                continue;
            }
            invokeHandler(entry, event);
        }
    }

    /**
     * Publish an event asynchronously. Handlers run on a worker thread.
     * Cancellable events are rejected — cancellation is only meaningful for sync dispatch.
     */
    public void publishAsync(ModuleEvent event) {
        if (event instanceof Cancellable) {
            throw new IllegalArgumentException(
                    "Cannot publish Cancellable event asynchronously: " + event.getClass().getName());
        }
        List<HandlerEntry> sorted = collectHandlers(event.getClass());
        asyncPool.submit(() -> {
            for (HandlerEntry entry : sorted) {
                invokeHandler(entry, event);
            }
        });
    }

    /**
     * Shutdown the async thread pool. Called on plugin disable.
     */
    public void shutdown() {
        asyncPool.shutdown();
        try {
            if (!asyncPool.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // --- Internal ---

    /**
     * Collect all handlers matching the event type (including superclass handlers), sorted by priority.
     */
    private List<HandlerEntry> collectHandlers(Class<? extends ModuleEvent> eventType) {
        List<HandlerEntry> result = new ArrayList<>();
        for (Map.Entry<Class<? extends ModuleEvent>, CopyOnWriteArrayList<HandlerEntry>> entry : handlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventType)) {
                result.addAll(entry.getValue());
            }
        }
        Collections.sort(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void invokeHandler(HandlerEntry entry, ModuleEvent event) {
        try {
            if (entry.isProgrammatic()) {
                ((Consumer<ModuleEvent>) entry.getConsumer()).accept(event);
            } else {
                entry.getMethod().invoke(entry.getInstance(), event);
            }
        } catch (Exception e) {
            String handlerDesc = entry.isProgrammatic()
                    ? "programmatic handler"
                    : entry.getInstance().getClass().getName() + "#" + entry.getMethod().getName();
            LOGGER.log(Level.WARNING,
                    String.format("[EventBus] Handler %s (module: %s) threw exception", handlerDesc, entry.getOwnerModule()),
                    e);
        }
    }
}
