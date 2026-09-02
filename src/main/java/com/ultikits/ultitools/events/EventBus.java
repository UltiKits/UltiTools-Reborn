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
        method.setAccessible(true); // NOPMD - required for handler invocation
        HandlerEntry entry = new HandlerEntry(eventType, priority, ignoreCancelled, ownerModule, method, instance);
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
    @SuppressWarnings("unchecked")
    public <T extends ModuleEvent> Subscription subscribe(Class<T> eventType, EventPriority priority,
                                                           boolean ignoreCancelled, String ownerModule,
                                                           Consumer<T> consumer) {
        HandlerEntry entry = new HandlerEntry(eventType, priority, ignoreCancelled, ownerModule,
                (Consumer<? extends ModuleEvent>) consumer);
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
