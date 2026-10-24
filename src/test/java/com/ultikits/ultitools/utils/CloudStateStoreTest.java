package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.exceptions.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Robustness and serialisation of {@code cloud-outbox.json} (S-15, S-32): every failure mode degrades to "start
 * empty, keep the evidence, retry", every write and delete is serialised by one JVM-wide lock, and a detached store
 * never writes again.
 */
@DisplayName("CloudStateStore -- unreadable, foreign, oversized, failed and concurrent writes, detach")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CloudStateStoreTest {

    private static final String S1 = "S1";
    private static final String S2 = "S2";
    /** A player name that must never appear in a log line. */
    private static final String SECRET_NAME = "SecretSteve";
    private static final String VALID_ID = "01ARYZ6S41TSV4RRFFQ69G5FAV";
    private static final long HANG_BOUND_NANOS = TimeUnit.SECONDS.toNanos(20);

    @TempDir
    Path tempDir;

    private final AtomicLong clock = new AtomicLong(1_760_000_000_000L);
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private Logger logger;
    private Path file;

    @BeforeEach
    void setUp() {
        file = tempDir.resolve(CloudStateStore.FILE_NAME);
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        });
    }

    private CloudStateStore newStore(int capacity) {
        CloudStateStore store = new CloudStateStore(file, new CloudOutbox(capacity, clock::get),
                new CommandDedupeSet(CommandDedupeSet.DEFAULT_DEDUPE_MAX, CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS,
                        clock::get));
        store.setLoggerForTesting(logger);
        return store;
    }

    private CloudStateStore newStore() {
        return newStore(CloudOutbox.DEFAULT_CAPACITY);
    }

    private static JsonObject payload(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("playerUuid", "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        o.addProperty("playerName", name);
        return o;
    }

    private static String document(int version, String serverId, String events, String commandIds) {
        return "{\"version\":" + version + ",\"serverId\":\"" + serverId + "\",\"events\":" + events
                + ",\"dropped\":0,\"commandIds\":" + commandIds + "}";
    }

    private static String event(String id, String type, String payload) {
        return "{\"id\":\"" + id + "\",\"type\":" + type + ",\"ts\":1760000000000,\"payload\":" + payload + "}";
    }

    private static String validPayload() {
        return "{\"playerName\":\"" + SECRET_NAME + "\"}";
    }

    private void writeFile(String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private Path unreadablePath() {
        return tempDir.resolve(CloudStateStore.FILE_NAME + CloudStateStore.UNREADABLE_SUFFIX);
    }

    private List<LogRecord> at(Level level) {
        synchronized (records) {
            return records.stream().filter(r -> r.getLevel().equals(level)).collect(Collectors.toList());
        }
    }

    private void assertNoLogCarriesContent() {
        synchronized (records) {
            assertThat(records).noneSatisfy(r -> assertThat(r.getMessage()).contains(SECRET_NAME));
        }
    }

    private JsonObject readFile() throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private List<String> tempFiles() throws IOException {
        try (Stream<Path> listing = Files.list(tempDir)) {
            return listing.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(CloudStateStore.FILE_NAME + ".") && n.endsWith(".tmp"))
                    .collect(Collectors.toList());
        }
    }

    private static boolean posixAndNotRoot() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                && !"root".equals(System.getProperty("user.name"));
    }

    // ---- unreadable files ----

    static Stream<Arguments> unreadableDocuments() {
        return Stream.of(
                Arguments.of("a zero-byte file", ""),
                Arguments.of("a truncated document", "{\"version\":"),
                Arguments.of("the JSON literal null", "null"),
                Arguments.of("a JSON array", "[]"),
                Arguments.of("another version", document(2, S1, "[]", "[]")),
                Arguments.of("a version written as 1.0", document(1, S1, "[]", "[]").replace(":1,", ":1.0,")),
                Arguments.of("events that are not an array", document(1, S1, "{}", "[]")),
                Arguments.of("an event with an invalid ULID",
                        document(1, S1, "[" + event("not-a-ulid", "\"player.join\"", validPayload()) + "]", "[]")),
                Arguments.of("an event with no type",
                        document(1, S1, "[" + event(VALID_ID, "null", validPayload()) + "]", "[]")),
                Arguments.of("an event whose payload is not an object",
                        document(1, S1, "[" + event(VALID_ID, "\"player.join\"", "[\"" + SECRET_NAME + "\"]") + "]",
                                "[]")),
                Arguments.of("two events with one id",
                        document(1, S1, "[" + event(VALID_ID, "\"player.join\"", validPayload()) + ","
                                + event(VALID_ID, "\"player.leave\"", validPayload()) + "]", "[]")),
                Arguments.of("a command id whose seenAt is text",
                        document(1, S1, "[]", "[{\"id\":\"" + VALID_ID + "\",\"seenAt\":\"" + SECRET_NAME + "\"}]")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableDocuments")
    @DisplayName("an unreadable file is moved aside with one WARNING, the store starts empty, the next flush rewrites")
    void unreadableFileIsMovedAside(String description, String content) throws IOException {
        Files.write(unreadablePath(), "an older unreadable copy".getBytes(StandardCharsets.UTF_8));
        writeFile(content);
        CloudStateStore store = newStore();
        store.outbox().enqueue("player.join", payload("memory"));
        store.dedupe().firstSeen(VALID_ID);

        store.load(S1);

        assertThat(Files.exists(file)).as("%s is moved away", description).isFalse();
        assertThat(new String(Files.readAllBytes(unreadablePath()), StandardCharsets.UTF_8))
                .as("the new copy replaces the older one").isEqualTo(content);
        assertThat(store.outbox().unacked()).isEmpty();
        assertThat(store.dedupe().firstSeen(VALID_ID)).isTrue();
        assertThat(at(Level.WARNING)).hasSize(1).allSatisfy(r -> assertThat(r.getMessage())
                .contains(file.toString(), unreadablePath().toString()));
        assertNoLogCarriesContent();

        assertThat(store.flushIfDirty(S1)).as("the next flush writes a fresh file").isTrue();
        assertThat(readFile().get("serverId").getAsString()).isEqualTo(S1);
    }

    @Test
    @DisplayName("a file larger than 8 MiB is unreadable even when its JSON is valid")
    void oversizedFileIsUnreadable() throws IOException {
        StringBuilder padded = new StringBuilder(document(1, S1, "[]", "[]"));
        while (padded.length() <= CloudStateStore.MAX_FILE_BYTES) {
            padded.append("          ");
        }
        writeFile(padded.toString());
        CloudStateStore store = newStore();

        store.load(S1);

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.size(unreadablePath())).isGreaterThan(CloudStateStore.MAX_FILE_BYTES);
        assertThat(at(Level.WARNING)).hasSize(1);
    }

    @Test
    @DisplayName("when even the move aside fails, the file stays in place, a WARNING is logged and the store starts empty")
    void failedMoveAsideKeepsTheFile() throws IOException {
        assumeTrue(posixAndNotRoot(), "needs POSIX permissions enforced for this user");
        writeFile("{\"version\":");
        CloudStateStore store = newStore();
        store.outbox().enqueue("player.join", payload("memory"));
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-x------"));
        try {
            store.load(S1);
        } finally {
            Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwx------"));
        }

        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo("{\"version\":");
        assertThat(Files.exists(unreadablePath())).isFalse();
        assertThat(store.outbox().unacked()).isEmpty();
        assertThat(at(Level.WARNING)).hasSize(1).allSatisfy(r -> assertThat(r.getMessage()).contains(file.toString()));

        assertThat(store.flushIfDirty(S1)).as("the next successful flush replaces it").isTrue();
        assertThat(readFile().get("version").getAsInt()).isEqualTo(1);
    }

    // ---- foreign server, capacity on load ----

    @Test
    @DisplayName("a file written for another server is discarded with one INFO line and rewritten on the next flush")
    void foreignServerFileIsDiscarded() throws IOException {
        CloudStateStore writer = newStore();
        writer.outbox().enqueue("player.join", payload(SECRET_NAME));
        writer.flush(S1);

        CloudStateStore store = newStore();
        store.load(S2);

        assertThat(store.outbox().unacked()).isEmpty();
        assertThat(Files.exists(unreadablePath())).as("a foreign file is not evidence of corruption").isFalse();
        assertThat(at(Level.INFO)).hasSize(1)
                .allSatisfy(r -> assertThat(r.getMessage()).contains(file.toString(), "different server"));
        assertThat(at(Level.WARNING)).isEmpty();
        assertNoLogCarriesContent();

        assertThat(store.flushIfDirty(S2)).isTrue();
        JsonObject rewritten = readFile();
        assertThat(rewritten.get("serverId").getAsString()).isEqualTo(S2);
        assertThat(rewritten.getAsJsonArray("events")).isEmpty();
    }

    @Test
    @DisplayName("more valid entries than the capacity keep the newest and add the rest to the dropped count")
    void loadAppliesTheCapacity() throws IOException {
        CloudStateStore writer = newStore(10);
        for (String name : Arrays.asList("a", "b", "c", "d", "e")) {
            writer.outbox().enqueue("player.join", payload(name));
        }
        writer.flush(S1);

        CloudStateStore store = newStore(2);
        store.load(S1);

        List<String> names = store.outbox().unacked().stream()
                .map(e -> e.payload().get("playerName").getAsString()).collect(Collectors.toList());
        assertThat(names).containsExactly("d", "e");
        assertThat(store.outbox().droppedSinceLastReport()).isEqualTo(3);
        assertThat(store.flushIfDirty(S1)).as("the trimmed state is written back").isTrue();
        assertThat(readFile().get("dropped").getAsLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("load deletes leftover cloud-outbox.json.*.tmp files and nothing else")
    void loadDeletesOnlyItsOwnTempLeftovers() throws IOException {
        Path leftover = tempDir.resolve(CloudStateStore.FILE_NAME + "." + VALID_ID + ".tmp");
        Path credentials = tempDir.resolve("credentials.json");
        Path credentialsTemp = tempDir.resolve("credentials.json.tmp");
        Path unreadable = unreadablePath();
        for (Path p : Arrays.asList(leftover, credentials, credentialsTemp, unreadable)) {
            Files.write(p, "x".getBytes(StandardCharsets.UTF_8));
        }

        newStore().load(S1);

        assertThat(Files.exists(leftover)).isFalse();
        assertThat(credentials).exists();
        assertThat(credentialsTemp).exists();
        assertThat(unreadable).exists();
    }

    // ---- failed write ----

    @Test
    @DisplayName("a failed write throws, keeps the previous file, keeps the dirty flags and leaves no temp file")
    void failedWriteKeepsThePreviousFileAndRetries() throws IOException {
        assumeTrue(posixAndNotRoot(), "needs POSIX permissions enforced for this user");
        CloudStateStore store = newStore();
        store.outbox().enqueue("player.join", payload("a"));
        store.flush(S1);
        byte[] previous = Files.readAllBytes(file);
        store.outbox().enqueue("player.join", payload("b"));
        store.dedupe().firstSeen(VALID_ID);

        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatThrownBy(() -> store.flush(S1)).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining(file.toString());
            assertThatThrownBy(() -> store.flushIfDirty(S1)).isInstanceOf(DataAccessException.class);
        } finally {
            Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwx------"));
        }

        assertThat(Files.readAllBytes(file)).isEqualTo(previous);
        assertThat(store.outbox().isDirty()).isTrue();
        assertThat(store.dedupe().isDirty()).isTrue();
        assertThat(tempFiles()).isEmpty();

        assertThat(store.flushIfDirty(S1)).as("the next flush retries").isTrue();
        assertThat(readFile().getAsJsonArray("events")).hasSize(2);
    }

    // ---- serialisation ----

    /** Holds the first flush that reaches the before-move hook until {@link #release} is counted down. */
    private static final class HoldFirst implements Runnable {
        final CountDownLatch atHook = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean used = new AtomicBoolean();

        @Override
        public void run() {
            if (used.compareAndSet(false, true)) {
                atHook.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static Thread start(String name, Runnable body, AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }, name);
        t.start();
        return t;
    }

    /** Waits, without sleeping, until {@code t} is blocked on a monitor; fails after a generous hang bound. */
    private static void awaitBlocked(Thread t) {
        long deadline = System.nanoTime() + HANG_BOUND_NANOS;
        while (t.getState() != Thread.State.BLOCKED) {
            assertThat(t.isAlive()).as("%s is still running", t.getName()).isTrue();
            assertThat(System.nanoTime() - deadline).as("%s blocks on the write lock", t.getName()).isNegative();
            Thread.yield();
        }
    }

    private static List<String> eventNames(JsonObject document) {
        List<String> names = new ArrayList<>();
        JsonArray events = document.getAsJsonArray("events");
        events.forEach(e -> names.add(e.getAsJsonObject().getAsJsonObject("payload").get("playerName").getAsString()));
        return names;
    }

    @Test
    @DisplayName("a second flush on the same store waits for a held first flush, then writes its newer snapshot")
    void concurrentFlushesOnOneStoreAreSerialised() throws Exception {
        CloudStateStore store = newStore();
        HoldFirst hold = new HoldFirst();
        store.setBeforeMoveHookForTesting(hold);
        store.outbox().enqueue("player.join", payload("a"));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = start("flush-A", () -> store.flush(S1), failure);
        assertThat(hold.atHook.await(20, TimeUnit.SECONDS)).isTrue();
        store.outbox().enqueue("player.join", payload("b"));
        Thread second = start("flush-B", () -> store.flush(S1), failure);
        awaitBlocked(second);
        assertThat(Files.exists(file)).as("nothing has been moved into place while A is held").isFalse();

        hold.release.countDown();
        first.join();
        second.join();

        assertThat(failure.get()).isNull();
        assertThat(eventNames(readFile())).containsExactly("a", "b");
        assertThat(tempFiles()).isEmpty();
        assertThat(store.flushIfDirty(S1)).as("B's write covered every change").isFalse();
    }

    @Test
    @DisplayName("two store instances on the same path are serialised by the same JVM-wide lock")
    void concurrentFlushesOnTwoStoresAreSerialised() throws Exception {
        CloudStateStore older = newStore();
        CloudStateStore newer = newStore();
        HoldFirst hold = new HoldFirst();
        older.setBeforeMoveHookForTesting(hold);
        older.outbox().enqueue("player.join", payload("old"));
        newer.outbox().enqueue("player.join", payload("new"));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = start("flush-older", () -> older.flush(S1), failure);
        assertThat(hold.atHook.await(20, TimeUnit.SECONDS)).isTrue();
        Thread second = start("flush-newer", () -> newer.flush(S1), failure);
        awaitBlocked(second);

        hold.release.countDown();
        first.join();
        second.join();

        assertThat(failure.get()).isNull();
        assertThat(eventNames(readFile())).containsExactly("new");
        assertThat(tempFiles()).isEmpty();
    }

    // ---- detach ----

    @Test
    @DisplayName("detachAndDelete waits for a held flush, deletes the file, and the store never writes again")
    void detachWaitsForAFlushAndStopsEveryLaterWrite() throws Exception {
        CloudStateStore store = newStore();
        HoldFirst hold = new HoldFirst();
        store.setBeforeMoveHookForTesting(hold);
        store.outbox().enqueue("player.join", payload("a"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean existed = new AtomicBoolean();

        Thread flusher = start("flush", () -> store.flush(S1), failure);
        assertThat(hold.atHook.await(20, TimeUnit.SECONDS)).isTrue();
        Thread detacher = start("detach", () -> existed.set(store.detachAndDelete()), failure);
        awaitBlocked(detacher);

        hold.release.countDown();
        flusher.join();
        detacher.join();

        assertThat(failure.get()).isNull();
        assertThat(existed.get()).as("the held flush wrote the file before the delete").isTrue();
        assertThat(store.isDetached()).isTrue();
        assertThat(Files.exists(file)).isFalse();

        store.outbox().enqueue("player.join", payload("b"));
        store.flush(S1);
        assertThat(store.flushIfDirty(S1)).isFalse();
        assertThat(Files.exists(file)).as("a detached store never writes").isFalse();
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    @DisplayName("detachAndDelete reports whether the file existed")
    void detachReportsWhetherTheFileExisted() {
        CloudStateStore never = newStore();
        assertThat(never.isDetached()).isFalse();
        assertThat(never.detachAndDelete()).isFalse();

        CloudStateStore written = newStore();
        written.outbox().enqueue("player.join", payload("a"));
        written.flush(S1);
        assertThat(written.detachAndDelete()).isTrue();
        assertThat(Files.exists(file)).isFalse();
    }
}
