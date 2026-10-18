package com.ultikits.ultitools.interfaces.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Thread contract of {@link SimpleTempListener}'s shared registration set (issue #676, case 39).
 *
 * <p>The set is static and shared by every temporary listener. {@code register()} runs on the
 * caller's thread and {@code unregister()} runs on the event's thread when a handler returns
 * {@code true}, which is an asynchronous thread for an asynchronous event. Every access must
 * therefore synchronize on the set's own monitor. The lock tests hold that monitor from the test
 * thread and observe, through the JVM's thread management interface, that a helper thread is
 * blocked on exactly that monitor.
 */
@DisplayName("SimpleTempListener registration set thread contract")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SimpleTempListenerRegistrationLockTest {

    /** Upper bound for observing a helper thread's state. */
    private static final long OBSERVE_MILLIS = 5_000L;

    /** Upper bound for every join of a thread this class starts. */
    private static final long JOIN_MILLIS = 10_000L;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();
    }

    @AfterEach
    void tearDown() {
        clearHandlers(SyncProbeEvent.getHandlerList());
        clearHandlers(AsyncProbeEvent.getHandlerList());
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("register() waits for the shared registration set's monitor before registering with Bukkit")
    void registerTakesTheSharedRegistrationLock() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        SimpleTempListener<SyncProbeEvent> listener = new SimpleTempListener<>(
                SyncProbeEvent.class, EventPriority.NORMAL, event -> {
                    delivered.incrementAndGet();
                    return false;
                }, event -> true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread helper = newThread("register-helper", listener::register, failure);

        Set<?> registered = registrationSet();
        boolean blockedOnSet;
        boolean registeredWithBukkitWhileHeld;
        synchronized (registered) {
            helper.start();
            blockedOnSet = awaitBlockedOn(helper, registered) != null;
            registeredWithBukkitWhileHeld = isRegisteredWithBukkit(listener, SyncProbeEvent.getHandlerList());
        }
        joinBounded(helper);

        assertThat(blockedOnSet)
                .as("register() on another thread must block on the shared registration set's monitor")
                .isTrue();
        assertThat(registeredWithBukkitWhileHeld)
                .as("register() must not reach Bukkit's registerEvent while the set's monitor is held")
                .isFalse();
        assertThat(failure.get()).isNull();

        server.getPluginManager().callEvent(new SyncProbeEvent(0));
        assertThat(delivered.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Auto-unregistration on an asynchronous event thread waits for the shared registration set's monitor")
    void asyncAutoUnregisterTakesTheSharedRegistrationLock() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        SimpleTempListener<AsyncProbeEvent> listener = new SimpleTempListener<>(
                AsyncProbeEvent.class, EventPriority.NORMAL, event -> {
                    delivered.incrementAndGet();
                    return true;
                }, event -> true);
        listener.register();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread helper = newThread("async-fire-helper",
                () -> server.getPluginManager().callEvent(new AsyncProbeEvent(0)), failure);

        Set<?> registered = registrationSet();
        ThreadInfo blockedInfo;
        synchronized (registered) {
            helper.start();
            blockedInfo = awaitBlockedOn(helper, registered);
        }
        joinBounded(helper);

        assertThat(blockedInfo)
                .as("an asynchronous event thread auto-unregistering must block on the shared registration set's monitor")
                .isNotNull();
        assertThat(isInsideUnregister(blockedInfo))
                .as("the asynchronous event thread must be blocked inside SimpleTempListener.unregister()")
                .isTrue();
        assertThat(failure.get()).isNull();
        assertThat(delivered.get()).isEqualTo(1);

        fireOffMainThread(new AsyncProbeEvent(1));
        assertThat(delivered.get())
                .as("an auto-unregistered listener receives no further events")
                .isEqualTo(1);

        listener.register();
        fireOffMainThread(new AsyncProbeEvent(2));
        assertThat(delivered.get())
                .as("register() on the auto-unregistered instance registers it again")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Concurrent registration and asynchronous auto-unregistration of distinct listeners keep every marker")
    void concurrentRegistrationAndAutoUnregistrationKeepEveryMarker() throws Exception {
        int threadsPerSide = 4;
        int listenersPerThread = 50;
        int listenerCount = threadsPerSide * listenersPerThread;

        AtomicInteger[] syncDelivered = counters(listenerCount);
        List<SimpleTempListener<SyncProbeEvent>> registeredSide = new ArrayList<>();
        for (int i = 0; i < listenerCount; i++) {
            AtomicInteger counter = syncDelivered[i];
            registeredSide.add(new SimpleTempListener<>(SyncProbeEvent.class, EventPriority.NORMAL, event -> {
                counter.incrementAndGet();
                return false;
            }, event -> true));
        }

        AtomicInteger[] asyncDelivered = counters(listenerCount);
        List<SimpleTempListener<AsyncProbeEvent>> unregisteredSide = new ArrayList<>();
        for (int i = 0; i < listenerCount; i++) {
            int id = i;
            AtomicInteger counter = asyncDelivered[i];
            SimpleTempListener<AsyncProbeEvent> listener = new SimpleTempListener<>(
                    AsyncProbeEvent.class, EventPriority.NORMAL, event -> {
                        counter.incrementAndGet();
                        return true;
                    }, event -> event.getId() == id);
            listener.register();
            unregisteredSide.add(listener);
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < threadsPerSide; t++) {
            int first = t * listenersPerThread;
            threads.add(newThread("registrar-" + t, () -> {
                awaitStart(start);
                for (int i = first; i < first + listenersPerThread; i++) {
                    registeredSide.get(i).register();
                }
            }, failure));
            threads.add(newThread("async-firer-" + t, () -> {
                awaitStart(start);
                for (int i = first; i < first + listenersPerThread; i++) {
                    server.getPluginManager().callEvent(new AsyncProbeEvent(i));
                }
            }, failure));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            joinBounded(thread);
        }
        assertThat(failure.get()).isNull();

        Set<?> registered = registrationSet();
        for (int i = 0; i < listenerCount; i++) {
            SimpleTempListener<AsyncProbeEvent> listener = unregisteredSide.get(i);
            assertThat(asyncDelivered[i].get()).as("async listener %d delivered once", i).isEqualTo(1);
            assertThat(registered.contains(listener)).as("async listener %d marker cleared", i).isFalse();
            assertThat(isRegisteredWithBukkit(listener, AsyncProbeEvent.getHandlerList()))
                    .as("async listener %d unregistered from Bukkit", i).isFalse();
        }
        for (int i = 0; i < listenerCount; i++) {
            assertThat(registered.contains(registeredSide.get(i))).as("listener %d marker kept", i).isTrue();
        }

        for (SimpleTempListener<SyncProbeEvent> listener : registeredSide) {
            listener.register();
        }
        server.getPluginManager().callEvent(new SyncProbeEvent(0));
        for (int i = 0; i < listenerCount; i++) {
            assertThat(syncDelivered[i].get())
                    .as("a repeated register() on listener %d must not add a second delivery", i)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("A listener whose Bukkit registration was removed is not retained by the registration set")
    @SuppressWarnings("PMD.DoNotCallGarbageCollectionExplicitly") // the only way to observe a weak key
    void droppedListenerIsNotRetainedByTheRegistrationSet() throws Exception {
        WeakReference<SimpleTempListener<SyncProbeEvent>> ref = registerThenRemoveFromBukkitOnly();

        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }

        assertThat(ref.get())
                .as("the registration set must hold listeners weakly")
                .isNull();
    }

    @Test
    @DisplayName("A handler unregistering during synchronous dispatch while another thread registers does not deadlock")
    void handlerUnregisteringDuringConcurrentRegistrationDoesNotDeadlock() throws Exception {
        int rounds = 200;
        AtomicInteger selfUnregistered = new AtomicInteger();
        List<SimpleTempListener<SyncProbeEvent>> selfUnregistering = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread dispatcher = newThread("sync-dispatcher", () -> {
            awaitStart(start);
            for (int i = 0; i < rounds; i++) {
                int id = i;
                SimpleTempListener<SyncProbeEvent> listener = new SimpleTempListener<>(
                        SyncProbeEvent.class, EventPriority.NORMAL, event -> {
                            selfUnregistered.incrementAndGet();
                            return true;
                        }, event -> event.getId() == id);
                selfUnregistering.add(listener);
                listener.register();
                server.getPluginManager().callEvent(new SyncProbeEvent(id));
            }
        }, failure);
        Thread registrar = newThread("registrar", () -> {
            awaitStart(start);
            for (int i = 0; i < rounds; i++) {
                new SimpleTempListener<>(SyncProbeEvent.class, EventPriority.NORMAL,
                        event -> false, event -> true).register();
            }
        }, failure);

        dispatcher.start();
        registrar.start();
        start.countDown();
        joinBounded(dispatcher);
        joinBounded(registrar);

        assertThat(failure.get()).isNull();
        assertThat(selfUnregistered.get()).isEqualTo(rounds);
        Set<?> registered = registrationSet();
        for (SimpleTempListener<SyncProbeEvent> listener : selfUnregistering) {
            assertThat(registered.contains(listener)).isFalse();
            assertThat(isRegisteredWithBukkit(listener, SyncProbeEvent.getHandlerList())).isFalse();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Registers a listener, then removes only its Bukkit registration (not through
     * {@code unregister()}), so its marker stays in the set. Kept in its own method so no local
     * variable of the test method keeps the listener reachable.
     */
    private static WeakReference<SimpleTempListener<SyncProbeEvent>> registerThenRemoveFromBukkitOnly()
            throws ReflectiveOperationException {
        SimpleTempListener<SyncProbeEvent> listener = new SimpleTempListener<>(
                SyncProbeEvent.class, EventPriority.NORMAL, event -> false, event -> true);
        listener.register();
        assertThat(registrationSet().contains(listener)).as("marker present after register()").isTrue();
        HandlerList.unregisterAll(listener);
        assertThat(isRegisteredWithBukkit(listener, SyncProbeEvent.getHandlerList())).isFalse();
        return new WeakReference<>(listener);
    }

    private static AtomicInteger[] counters(int size) {
        AtomicInteger[] counters = new AtomicInteger[size];
        for (int i = 0; i < size; i++) {
            counters[i] = new AtomicInteger();
        }
        return counters;
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("start gate was not opened in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads the private static registration set. Production visibility is deliberately not
     * widened for this test.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Set<?> registrationSet() throws ReflectiveOperationException {
        Field field = SimpleTempListener.class.getDeclaredField("REGISTERED");
        field.setAccessible(true);
        return (Set<?>) field.get(null);
    }

    /**
     * Polls until {@code thread} is blocked entering the monitor of {@code monitor}, or until it
     * terminates or the bound elapses.
     *
     * @return the thread's info while blocked on that monitor, or {@code null} if it never was
     */
    private static ThreadInfo awaitBlockedOn(Thread thread, Object monitor) throws InterruptedException {
        int monitorIdentity = System.identityHashCode(monitor);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OBSERVE_MILLIS);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean()
                    .getThreadInfo(new long[] {thread.getId()}, true, false)[0];
            if (info == null || info.getThreadState() == Thread.State.TERMINATED) {
                return null;
            }
            LockInfo lock = info.getLockInfo();
            if (info.getThreadState() == Thread.State.BLOCKED && lock != null
                    && lock.getIdentityHashCode() == monitorIdentity
                    && lock.getClassName().equals(monitor.getClass().getName())) {
                return info;
            }
            Thread.sleep(1L);
        }
        return null;
    }

    private static boolean isInsideUnregister(ThreadInfo info) {
        for (StackTraceElement frame : info.getStackTrace()) {
            if (SimpleTempListener.class.getName().equals(frame.getClassName())
                    && "unregister".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRegisteredWithBukkit(Object listener, HandlerList handlers) {
        for (RegisteredListener registeredListener : handlers.getRegisteredListeners()) {
            if (registeredListener.getListener() == listener) {
                return true;
            }
        }
        return false;
    }

    private static void clearHandlers(HandlerList handlers) {
        for (RegisteredListener registeredListener : handlers.getRegisteredListeners()) {
            handlers.unregister(registeredListener);
        }
    }

    private static Thread newThread(String name, Runnable body, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) { // NOPMD - surfaced to the test thread through the reference
                failure.compareAndSet(null, t);
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void joinBounded(Thread thread) throws InterruptedException {
        thread.join(JOIN_MILLIS);
        assertThat(thread.isAlive())
                .as("thread %s must finish within %d ms", thread.getName(), JOIN_MILLIS)
                .isFalse();
    }

    /** Asynchronous events may not be called on the server's main thread. */
    private void fireOffMainThread(Event event) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = newThread("async-fire", () -> server.getPluginManager().callEvent(event), failure);
        thread.start();
        joinBounded(thread);
        assertThat(failure.get()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // Probe events
    // ---------------------------------------------------------------------------------------

    /** Synchronous test-local event. */
    static final class SyncProbeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final int id;

        SyncProbeEvent(int id) {
            super(false);
            this.id = id;
        }

        int getId() {
            return id;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** Asynchronous test-local event, like {@code AsyncPlayerChatEvent}. */
    static final class AsyncProbeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final int id;

        AsyncProbeEvent(int id) {
            super(true);
            this.id = id;
        }

        int getId() {
            return id;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
