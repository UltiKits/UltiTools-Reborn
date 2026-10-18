package com.ultikits.ultitools.interfaces.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.Set;
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

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

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
