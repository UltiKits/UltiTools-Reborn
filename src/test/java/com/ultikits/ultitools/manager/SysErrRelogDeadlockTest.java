package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Reproduces #584: a diagnostic the drain thread writes while it holds {@code logDrainLock} is
 * re-logged into the log stream and deadlocks the main thread on {@code batchModeLock}.
 * <p>
 * <b>The false premise.</b> The framework wrote these diagnostics to {@code System.err} on the
 * assumption that standard error bypasses {@code java.util.logging}. On Paper it does not: Paper's
 * {@code io.papermc.paper.logging.SysoutCatcher} wraps {@code System.out}/{@code System.err}, finds
 * the calling plugin with {@code StackWalker.getCallerClass()} and
 * {@code JavaPlugin.getProvidingPlugin(...)}, and logs the line with
 * {@code plugin.getLogger().log(level, ...)} (read with {@code javap -c} on Paper 1.20.6, 1.21.1 and
 * 1.21.4). That is a record on the plugin logger, so it reaches {@link SystemLogHandler}. The
 * {@link SysoutCatcherStandIn} below does exactly that.
 * <p>
 * <b>The deadlock.</b> The main thread logs; {@code sendLog} holds {@code batchModeLock};
 * {@code addToBatch} crosses the size threshold and runs the external size-threshold callback
 * ({@code ServerMonitorManager#drainLogsNow}), which waits for {@code logDrainLock}. The monitor
 * thread holds {@code logDrainLock} and sends a frame on a socket that has just closed
 * ({@code isOpen()} false, {@code isConnected} still true because {@code onClose} has not run); the
 * client's diagnostic is re-logged, reaches {@code sendLog}, and waits for {@code batchModeLock}.
 * <p>
 * The lock objects are the transmitter's real {@code batchModeLock} and a stand-in for
 * {@code ServerMonitorManager#logDrainLock}, wired the way {@code LogStreamManager#initialize}
 * wires the real one. Latches order the two threads deterministically: the monitor thread takes
 * the drain lock first, and sends only once the main thread is blocked on it while holding
 * {@code batchModeLock}.
 */
@DisplayName("#584: a System.err diagnostic re-logged on the drain thread does not deadlock the main thread")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // tearDown resets the UltiTools singleton by reflection (#250)
class SysErrRelogDeadlockTest {

    private static final long DEADLOCK_TIMEOUT_SECONDS = 5;

    private final List<LogRecord> console = new CopyOnWriteArrayList<>();
    private final Logger pluginLogger = Logger.getLogger("UltiTools-SysErrRelogDeadlockTest");
    private final Handler consoleStandIn = new Handler() {
        @Override
        public void publish(LogRecord record) {
            console.add(record);
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    private final Object logDrainLock = new Object();
    private PrintStream originalErr;
    private SysoutCatcherStandIn sysoutCatcher;
    private UltiPanelWebSocketClient client;
    private UltiPanelLogTransmitter transmitter;
    private SystemLogHandler streamHandler;
    private boolean deadlocked;

    /**
     * Paper's {@code SysoutCatcher}, reduced to what matters here: every line printed to the stream
     * is logged on the calling plugin's logger.
     */
    private final class SysoutCatcherStandIn extends PrintStream {

        private final List<String> lines = new CopyOnWriteArrayList<>();

        SysoutCatcherStandIn() {
            super(new ByteArrayOutputStream(), true);
        }

        @Override
        public void println(String line) {
            lines.add(line);
            pluginLogger.log(Level.WARNING, line);
        }

        @Override
        public void println(Object line) {
            println(String.valueOf(line));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        pluginLogger.setUseParentHandlers(false);
        pluginLogger.setLevel(Level.ALL);
        TestHelper.mockUltiToolsInstance(ultiTools ->
                lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger));

        // A client in the window between the socket closing and onClose running: the socket is not
        // open, but the client still reports itself connected.
        client = spy(new UltiPanelWebSocketClient("wss://test.example.com/ws", "server-584", "token"));
        doReturn(true).when(client).isConnected();
        assertThat(client.isOpen()).isFalse();

        transmitter = new UltiPanelLogTransmitter(client, "server-584");
        transmitter.setBatchSize(1);
        transmitter.setExternalDrainMode(true);
        transmitter.setExternalDrainCoordinationLock(logDrainLock);

        streamHandler = new SystemLogHandler(transmitter);
        pluginLogger.addHandler(consoleStandIn);
        pluginLogger.addHandler(streamHandler);

        originalErr = System.err;
        sysoutCatcher = new SysoutCatcherStandIn();
        System.setErr(sysoutCatcher);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setErr(originalErr);
        pluginLogger.removeHandler(consoleStandIn);
        pluginLogger.removeHandler(streamHandler);
        if (!deadlocked) {
            // shutdown() takes batchModeLock; on a deadlocked run that would hang the test JVM.
            transmitter.setExternalDrainCoordinationLock(null);
            transmitter.shutdown();
        }
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /**
     * Runs the two-thread choreography of #584.
     *
     * @param underDrainLock what the monitor thread does while it holds the drain lock, once the
     *                       main thread is blocked on that lock while holding {@code batchModeLock}
     */
    private void runChoreography(Runnable underDrainLock) throws Exception {
        CountDownLatch monitorHoldsDrainLock = new CountDownLatch(1);
        CountDownLatch mainHoldsBatchModeLock = new CountDownLatch(1);
        AtomicReference<Thread> mainThread = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // ServerMonitorManager#drainLogsNow, reduced to its lock: it waits for logDrainLock.
        transmitter.setExternalSizeThresholdCallback(() -> {
            mainHoldsBatchModeLock.countDown();
            synchronized (logDrainLock) {
                // The drain itself is not under test.
            }
        });

        Thread monitor = new Thread(() -> {
            try {
                synchronized (logDrainLock) {
                    monitorHoldsDrainLock.countDown();
                    if (!mainHoldsBatchModeLock.await(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the main thread never reached the drain callback");
                    }
                    awaitBlocked(mainThread.get());
                    underDrainLock.run();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "584-monitor");
        Thread main = new Thread(() -> {
            try {
                // An ordinary line on the main thread: sendLog -> batchModeLock -> addToBatch ->
                // threshold (batch size 1) -> external size-threshold callback -> logDrainLock.
                pluginLogger.info("an ordinary line on the main thread");
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "584-main");
        mainThread.set(main);
        monitor.setDaemon(true);
        main.setDaemon(true);

        monitor.start();
        assertThat(monitorHoldsDrainLock.await(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        main.start();

        monitor.join(TimeUnit.SECONDS.toMillis(DEADLOCK_TIMEOUT_SECONDS));
        main.join(TimeUnit.SECONDS.toMillis(DEADLOCK_TIMEOUT_SECONDS));

        deadlocked = monitor.isAlive() || main.isAlive();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] cycle = threads.findMonitorDeadlockedThreads();
        assertThat(deadlocked)
                .as("monitor thread (holds logDrainLock, wants batchModeLock) and main thread (holds batchModeLock, "
                        + "wants logDrainLock) must both finish; JVM-detected monitor deadlock: %s",
                        cycle == null ? "none" : cycle.length + " thread(s)")
                .isFalse();
        assertThat(failure.get()).isNull();
    }

    /** Waits until {@code thread} is blocked entering a monitor, i.e. on the drain lock. */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLOCK_TIMEOUT_SECONDS);
        while (thread == null || thread.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the main thread never blocked on the drain lock");
            }
            Thread.sleep(5);
        }
    }

    private static JsonObject batchUpdate() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "batch_update");
        return message;
    }

    private boolean consoleContains(String fragment) {
        return console.stream().anyMatch(r -> r.getMessage() != null && r.getMessage().contains(fragment));
    }

    @Test
    @DisplayName("the client's closed-socket diagnostic, sent under logDrainLock, does not deadlock")
    void closedSocketDiagnosticUnderDrainLockDoesNotDeadlock() throws Exception {
        runChoreography(() -> client.sendMessage(batchUpdate()));

        assertThat(consoleContains("WebSocket is not connected"))
                .as("the diagnostic still reaches the console").isTrue();
        assertThat(sysoutCatcher.lines)
                .as("the framework's own diagnostic is not written to System.err").isEmpty();
    }

    @Test
    @DisplayName("any System.err line re-logged on the drain thread under logDrainLock does not deadlock")
    void anyRelogUnderDrainLockDoesNotDeadlock() throws Exception {
        runChoreography(() -> System.err.println("a line printed to System.err under the drain lock"));

        assertThat(consoleContains("a line printed to System.err under the drain lock"))
                .as("the line still reaches the console").isTrue();
    }
}
