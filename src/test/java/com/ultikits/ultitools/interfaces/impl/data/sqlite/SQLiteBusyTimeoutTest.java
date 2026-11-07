package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.zaxxer.hikari.HikariDataSource;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/** Real SQLite lock contention; signals interrupt native sleeps, without a Paper server. */
@Timeout(20)
class SQLiteBusyTimeoutTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void waitsForWriterDespiteInterruptedNativeSleeps(int queryTimeout) throws Exception {
        try (Pool pool = pool(); Signals signals = Signals.start()) {
            assertWriteWaits(pool.source, queryTimeout);
        }
    }

    @Test
    void timedStatementDoesNotRemoveHandlerForNextUntimedBorrower() throws Exception {
        try (Pool pool = pool(); Signals signals = Signals.start()) {
            try (Connection connection = pool.source.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                statement.setQueryTimeout(1);
                statement.executeQuery().close();
            }
            assertWriteWaits(pool.source, 0);
        }
    }

    @Test
    void ordinaryContentionWithoutSignalsStillSucceeds() throws Exception {
        try (Pool pool = pool()) {
            assertWriteWaits(pool.source, 0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void persistentLockStillExpiresAtSelectedRealBudget(int queryTimeout) throws Exception {
        try (Pool pool = pool(); Signals signals = Signals.start();
             Connection holder = pool.source.getConnection(); Statement lock = holder.createStatement();
             Connection contender = pool.source.getConnection();
             PreparedStatement update = contender.prepareStatement("UPDATE sample SET value = 9 WHERE id = 1")) {
            holder.setAutoCommit(false);
            lock.executeUpdate("UPDATE sample SET value = 8 WHERE id = 1");
            update.setQueryTimeout(queryTimeout);
            long started = System.nanoTime();
            org.sqlite.SQLiteException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    org.sqlite.SQLiteException.class, update::executeUpdate);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            int expected = queryTimeout == 0 ? 3000 : queryTimeout * 1000;
            assertThat(failure.getErrorCode()).isEqualTo(5);
            assertThat(elapsed).isBetween((long) expected - 150, (long) expected + 3000);
            assertThat(contender.unwrap(org.sqlite.SQLiteConnection.class).getBusyTimeout()).isEqualTo(3000);
            holder.rollback();
        }
    }

    @Test
    void newLockEventAndReplacementConnectionHaveFreshBudgets() throws Exception {
        try (Pool pool = pool(); Signals signals = Signals.start()) {
            assertWriteWaits(pool.source, 0);
            assertWriteWaits(pool.source, 0);
            try (Connection borrowed = pool.source.getConnection()) {
                org.sqlite.SQLiteConnection physical = borrowed.unwrap(org.sqlite.SQLiteConnection.class);
                pool.source.evictConnection(borrowed);
                try (Connection replacement = pool.source.getConnection()) {
                    assertThat(replacement.unwrap(org.sqlite.SQLiteConnection.class)).isNotSameAs(physical);
                }
            }
            assertWriteWaits(pool.source, 0);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private Pool pool() throws Exception {
        SQLiteDataStore store = new SQLiteDataStore();
        Method factory = SQLiteDataStore.class.getDeclaredMethod("poolFor", String.class);
        factory.setAccessible(true);
        HikariDataSource source = (HikariDataSource) factory.invoke(store,
                directory.resolve("busy.db").toFile().getCanonicalPath());
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE sample (id INTEGER PRIMARY KEY, value INTEGER)");
            statement.executeUpdate("INSERT INTO sample VALUES (1, 0)");
        }
        return new Pool(store, source);
    }

    private void assertWriteWaits(HikariDataSource source, int queryTimeout) throws Exception {
        int initial;
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery("SELECT value FROM sample WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            initial = result.getInt(1);
        }
        ScheduledExecutorService threads = Executors.newScheduledThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> writer = threads.submit(() -> {
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.executeUpdate("UPDATE sample SET value = value + 1 WHERE id = 1");
                locked.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("contender never released holder");
                }
                connection.commit();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            threads.schedule(release::countDown, 1200, TimeUnit.MILLISECONDS);
            try (Connection connection = source.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE sample SET value = value + 1 WHERE id = 1")) {
                statement.setQueryTimeout(queryTimeout);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            writer.get(5, TimeUnit.SECONDS);
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
                 java.sql.ResultSet result = statement.executeQuery("SELECT value FROM sample WHERE id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(initial + 2);
            }
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class Pool implements AutoCloseable {
        private final SQLiteDataStore store;
        private final HikariDataSource source;

        private Pool(SQLiteDataStore store, HikariDataSource source) {
            this.store = store;
            this.source = source;
        }

        @Override
        public void close() {
            store.destroyAllOperators();
            assertThat(source.isClosed()).isTrue();
        }
    }

    /** Linux-only fixture, requiring Python's standard library but no extra Maven dependency. */
    private static final class Signals implements AutoCloseable {
        private final Signal signal;
        private final SignalHandler previous;
        private final Process sender;
        private final String threadName;

        private Signals(Signal signal, SignalHandler previous, Process sender, String threadName) {
            this.signal = signal;
            this.previous = previous;
            this.sender = sender;
            this.threadName = threadName;
        }

        private static Signals start() throws Exception {
            assumeTrue(System.getProperty("os.name").equals("Linux"), "native signal fixture requires Linux");
            // WINCH is not a HotSpot-reserved signal. Restore the original handler on every exit.
            Signal signal = new Signal("WINCH");
            SignalHandler previous = Signal.handle(signal, ignored -> { });
            String original = Thread.currentThread().getName();
            Process sender = null;
            try {
                String target = "sqlite645-" + Thread.currentThread().getId();
                Thread.currentThread().setName(target);
                String tid = null;
                try (Stream<Path> tasks = Files.list(Paths.get("/proc/self/task"))) {
                    for (Path task : (Iterable<Path>) tasks::iterator) {
                        String name = new String(Files.readAllBytes(task.resolve("comm")), StandardCharsets.UTF_8).trim();
                        if (name.equals(target)) {
                            tid = task.getFileName().toString();
                            break;
                        }
                    }
                }
                assertThat(tid).as("native tid of sampled contender").isNotNull();
                String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
                String script = "import ctypes,sys,time\n"
                        + "lib=ctypes.CDLL(None,use_errno=True)\n"
                        + "pid,tid,sig=map(int,sys.argv[1:])\n"
                        + "for i in range(1000):\n"
                        + " r=lib.tgkill(pid,tid,sig)\n"
                        + " if r: raise OSError(ctypes.get_errno())\n"
                        + " if i==0: print('READY',flush=True)\n"
                        + " time.sleep(.01)\n";
                sender = new ProcessBuilder("python3", "-I", "-c", script, pid, tid,
                        String.valueOf(signal.getNumber())).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                try (BufferedReader ready = new BufferedReader(
                        new InputStreamReader(sender.getInputStream(), StandardCharsets.UTF_8))) {
                    assertThat(ready.readLine()).isEqualTo("READY");
                }
                return new Signals(signal, previous, sender, original);
            } catch (Exception | AssertionError failure) {
                if (sender != null) {
                    sender.destroyForcibly().waitFor();
                }
                Signal.handle(signal, previous);
                Thread.currentThread().setName(original);
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            sender.destroyForcibly().waitFor();
            Signal.handle(signal, previous);
            Thread.currentThread().setName(threadName);
        }
    }
}
