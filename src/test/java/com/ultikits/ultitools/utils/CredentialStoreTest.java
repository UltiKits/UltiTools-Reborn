package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.exceptions.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

/**
 * Pins {@link CredentialStore}'s contract: one owner of the credential document, an atomic
 * temp-plus-rename replace, a parse failure reported as an outcome distinct from absence
 * (plan 08-13, D-12/D-14), and the fail-safe one-time migration from the pre-6.3.0 location to
 * the current one (plan 08-15, D-15).
 */
@DisplayName("CredentialStore -- single-owner, atomically-replaced, migrated-in-place credential file")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CredentialStoreTest {

    /**
     * #569: size of the first value of the paused document -- large enough that serialization
     * must flush partial bytes to disk before the pause point.
     */
    private static final int BULK_CHARS = 64 * 1024;

    /**
     * #569: bounds one thread-state transition, never a throughput -- a hang detector only. The
     * class-level {@code @Timeout} stays the overall budget; this bound only gives a single hung
     * transition a readable message.
     */
    private static final long HANG_BOUND_MILLIS = 20_000L;

    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    private Path dataFile;

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("data.json");
        CredentialStore.setTargetPathForTesting(dataFile);
    }

    @AfterEach
    void tearDown() {
        CredentialStore.clearTargetPathForTesting();
        // Defence in depth: a MigrationTests failure that skips its own @AfterEach must not leak
        // these two test-only overrides into every other test class sharing this JVM.
        CredentialStore.clearOldLocationForTesting();
        CredentialStore.setSimulateWriteFailureForTesting(false);
    }

    // ---- migrate(): the fail-safe one-time move from the pre-6.3.0 location to the current one
    // (plan 08-15). Old and new locations are overridden independently so a case can put either
    // file, both, or neither in place before calling migrate() directly. ----

    @Nested
    @DisplayName("migrate() -- old location -> new location, fail-safe on every path (D-15/T-08-55)")
    class MigrationTests {

        private Path oldFile;
        private Path newFile;

        @BeforeEach
        void setUp() {
            oldFile = tempDir.resolve("old-location").resolve("data.json");
            newFile = tempDir.resolve("new-location").resolve("credentials.json");
            CredentialStore.setOldLocationForTesting(oldFile);
            CredentialStore.setTargetPathForTesting(newFile);
        }

        @AfterEach
        void tearDown() {
            CredentialStore.clearOldLocationForTesting();
            CredentialStore.setSimulateWriteFailureForTesting(false);
        }

        private void writeOldFile(String json) throws IOException {
            Files.createDirectories(oldFile.getParent());
            Files.write(oldFile, json.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("old file present, new file absent: content moves across and the old file is gone")
        void migratesWhenOldPresentAndNewAbsent() throws IOException {
            writeOldFile("{\"uuid\":\"abc123\",\"access_token\":\"tok\"}");

            CredentialStore.migrate();

            assertThat(Files.exists(newFile)).as("the new file must exist after migration").isTrue();
            assertThat(Files.exists(oldFile)).as("the old file must be gone after a successful migration").isFalse();

            CredentialStore.ReadResult result = CredentialStore.read();
            assertThat(result.isParsed()).isTrue();
            assertThat(result.data())
                    .containsEntry("uuid", "abc123")
                    .containsEntry("access_token", "tok");
        }

        @Test
        @DisplayName("old file present, new file already present: the new file is left untouched, the old file is deleted")
        void leavesNewFileUntouchedWhenBothExist() throws IOException {
            writeOldFile("{\"uuid\":\"old-value\"}");
            Files.createDirectories(newFile.getParent());
            Files.write(newFile, "{\"uuid\":\"new-value\"}".getBytes(StandardCharsets.UTF_8));

            CredentialStore.migrate();

            assertThat(Files.exists(oldFile))
                    .as("the old file must be removed once the new one is confirmed present")
                    .isFalse();
            String newContent = new String(Files.readAllBytes(newFile), StandardCharsets.UTF_8);
            assertThat(newContent)
                    .as("the new file's own content must survive untouched, not be overwritten by the old file's")
                    .contains("new-value")
                    .doesNotContain("old-value");
        }

        @Test
        @DisplayName("old file absent: migrate() is a no-op -- no file created, no log noise")
        void noOpWhenOldFileAbsent() {
            CredentialStore.migrate();

            assertThat(Files.exists(newFile)).as("migrate() must not create a file out of nothing").isFalse();
            assertThat(Files.exists(oldFile)).isFalse();
        }

        @Test
        @DisplayName("running migrate() twice is a no-op the second time; the new file is unchanged")
        void secondRunIsNoOp() throws IOException {
            writeOldFile("{\"uuid\":\"abc123\"}");

            CredentialStore.migrate();
            String contentAfterFirstRun = new String(Files.readAllBytes(newFile), StandardCharsets.UTF_8);

            CredentialStore.migrate();

            assertThat(Files.exists(oldFile)).as("the old file must already be gone before the second run").isFalse();
            String contentAfterSecondRun = new String(Files.readAllBytes(newFile), StandardCharsets.UTF_8);
            assertThat(contentAfterSecondRun)
                    .as("a second migrate() run must not touch the already-migrated file")
                    .isEqualTo(contentAfterFirstRun);
        }

        @Test
        @DisplayName("old file exists but does not parse: it is NOT deleted, and nothing is written to the new location")
        void doesNotDeleteAnUnparseableOldFile() throws IOException {
            writeOldFile("{\"access_token\":\"partial-tok");

            CredentialStore.migrate();

            assertThat(Files.exists(oldFile)).as("an unparseable old file must never be discarded").isTrue();
            assertThat(Files.exists(newFile))
                    .as("nothing may be written to the new location from an unparseable old file")
                    .isFalse();
        }

        @Test
        @DisplayName("the new file write fails: the old file survives and no partial new file appears")
        void oldFileSurvivesAWriteFailure() throws IOException {
            writeOldFile("{\"uuid\":\"abc123\"}");
            CredentialStore.setSimulateWriteFailureForTesting(true);

            CredentialStore.migrate();

            assertThat(Files.exists(oldFile)).as("the old file must survive a failed migration write").isTrue();
            assertThat(Files.exists(newFile))
                    .as("no partial file may appear at the new location after a failed write")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("read() on an absent file reports absence and returns an empty map")
    void readOnAbsentFileReportsAbsentAndEmptyMap() {
        CredentialStore.ReadResult result = CredentialStore.read();

        assertThat(result.isAbsent()).isTrue();
        assertThat(result.isParsed()).isFalse();
        assertThat(result.isParseFailure()).isFalse();
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("read() on a valid file returns its parsed contents")
    void readOnValidFileReturnsParsedContents() throws IOException {
        Files.write(dataFile, "{\"uuid\":\"abc123\",\"access_token\":\"tok\"}"
                .getBytes(StandardCharsets.UTF_8));

        CredentialStore.ReadResult result = CredentialStore.read();

        assertThat(result.isParsed()).isTrue();
        assertThat(result.data())
                .containsEntry("uuid", "abc123")
                .containsEntry("access_token", "tok");
    }

    @Test
    @DisplayName("read() on a torn file reports a parse failure distinguishable from absence")
    void readOnTornFileReportsParseFailureDistinctFromAbsence() throws IOException {
        // A deliberately truncated fragment -- what a crash mid truncate-then-write leaves behind
        // under the two-writer design this class replaces.
        Files.write(dataFile, "{\"access_token\":\"partial-tok".getBytes(StandardCharsets.UTF_8));

        CredentialStore.ReadResult result = CredentialStore.read();

        assertThat(result.isParseFailure())
                .as("a torn file must not be reported as absent")
                .isTrue();
        assertThat(result.isAbsent()).isFalse();
        assertThat(result.isParsed()).isFalse();
        assertThatThrownBy(result::data)
                .as("a parse failure must not silently hand back an empty map as if the file were fresh")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("write() on an absent parent directory creates the directory and then the file")
    void writeOnAbsentParentDirectoryCreatesDirectoryThenFile() throws IOException {
        Path nestedFile = tempDir.resolve("nested").resolve("deeper").resolve("data.json");
        CredentialStore.setTargetPathForTesting(nestedFile);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("uuid", "created");
        CredentialStore.write(doc);

        assertThat(Files.exists(nestedFile)).isTrue();
        assertThat(new String(Files.readAllBytes(nestedFile), StandardCharsets.UTF_8)).contains("created");
    }

    @Test
    @DisplayName("write() replaces an existing file's contents completely, no residue of a longer document")
    void writeReplacesExistingContentCompletely() throws IOException {
        Map<String, Object> longDoc = new LinkedHashMap<>();
        longDoc.put("uuid", "a-long-uuid-value-that-will-not-survive-the-next-write");
        longDoc.put("access_token", "a-long-access-token-value-padding-padding-padding-padding");
        longDoc.put("refresh_token", "a-long-refresh-token-value-padding-padding-padding-padding");
        CredentialStore.write(longDoc);

        Map<String, Object> shortDoc = new LinkedHashMap<>();
        shortDoc.put("uuid", "short");
        CredentialStore.write(shortDoc);

        String content = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
        assertThat(content)
                .as("no residue of the longer previous document may survive a complete replace")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token")
                .doesNotContain("padding");

        CredentialStore.ReadResult reread = CredentialStore.read();
        assertThat(reread.isParsed()).isTrue();
        assertThat(reread.data()).hasSize(1).containsEntry("uuid", "short");
    }

    @Test
    @DisplayName("after write(), no temporary file remains in the parent directory")
    void writeLeavesNoTemporaryFileBehind() throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("uuid", "clean");
        CredentialStore.write(doc);

        try (Stream<Path> siblings = Files.list(tempDir)) {
            List<String> names = siblings.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            assertThat(names)
                    .as("the parent directory must contain exactly the target file, no leftover temp file")
                    .containsExactly("data.json");
        }
    }

    @Test
    @DisplayName("update() throws NullPointerException when the mutator returns null, instead of "
            + "silently writing an empty document (WR-03)")
    void updateThrowsWhenMutatorReturnsNull() throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("access_token", "must-not-be-lost");
        CredentialStore.write(doc);

        assertThatThrownBy(() -> CredentialStore.update(existing -> null))
                .as("a null mutator return is a caller bug and must fail loudly, not truncate the file")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("after update() throws on a null mutator return, the credential file is left "
            + "untouched -- no partial or empty write occurred (WR-03)")
    void updateLeavesFileUntouchedWhenMutatorReturnsNull() throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("access_token", "must-not-be-lost");
        CredentialStore.write(doc);
        byte[] contentBefore = Files.readAllBytes(dataFile);

        assertThatThrownBy(() -> CredentialStore.update(existing -> null))
                .isInstanceOf(NullPointerException.class);

        assertThat(Files.readAllBytes(dataFile))
                .as("a rejected null-mutator update must not truncate or otherwise modify the file")
                .isEqualTo(contentBefore);
        CredentialStore.ReadResult reread = CredentialStore.read();
        assertThat(reread.isParsed()).isTrue();
        assertThat(reread.data()).containsEntry("access_token", "must-not-be-lost");
    }

    // ---- Deterministic interleaving: no torn file, no lost update (D-14). Each case releases all
    // threads together via a CyclicBarrier so contention is real, joins every thread, and asserts
    // on file content AFTER the join -- the assertion after the join is the point; its absence is
    // exactly what makes DataStoreManagerTest#concurrentReadWriteShouldBeSafe worthless (D-14). The
    // reader case further down is paced by latches instead of a barrier; see its own comment (#569). ----

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Barrier wait failed", e);
        }
    }

    @Test
    @DisplayName("N concurrent write() calls leave a file that parses and equals exactly one payload, not a splice")
    void concurrentWritesLeaveExactlyOnePayloadNoSplice() throws Exception {
        int threadCount = 8;
        List<String> payloads = IntStream.range(0, threadCount)
                .mapToObj(i -> "payload-" + i)
                .collect(Collectors.toList());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String payload : payloads) {
                futures.add(pool.submit(() -> {
                    awaitBarrier(barrier);
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("marker", payload);
                    CredentialStore.write(doc);
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        // Assertion AFTER the join.
        CredentialStore.ReadResult result = CredentialStore.read();
        assertThat(result.isParsed())
                .as("a spliced write would fail to parse -- Gson requires the entire document consumed")
                .isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(payloads).contains((String) result.data().get("marker"));
    }

    @Test
    @DisplayName("N concurrent update() calls each adding a distinct key leave a file containing all N keys (lost-update case)")
    void concurrentUpdatesLoseNoKeys() throws Exception {
        int threadCount = 8;
        CredentialStore.write(new LinkedHashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                String key = "key-" + i;
                futures.add(pool.submit(() -> {
                    awaitBarrier(barrier);
                    CredentialStore.update(existing -> {
                        existing.put(key, "value-for-" + key);
                        return existing;
                    });
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        // Assertion AFTER the join -- the two-monitor design loses keys here.
        CredentialStore.ReadResult result = CredentialStore.read();
        assertThat(result.isParsed()).isTrue();
        assertThat(result.data()).hasSize(threadCount);
        for (int i = 0; i < threadCount; i++) {
            assertThat(result.data()).containsEntry("key-" + i, "value-for-key-" + i);
        }
    }

    // ---- Issue #569: a reader overlapping an in-flight write, made deterministic. The previous
    // version of this test raced one spinning reader against 8 writers x 50 writes and bounded the
    // writers' join at 20 s. That bounded the wall-clock throughput of 400 lock-serialized
    // filesystem writes, not correctness: measured at about 65 ms on an idle machine, the same
    // unmodified test timed out (java.util.concurrent.TimeoutException from Future.get) once the
    // JVM shared one CPU with 120 busy loops, and no parse failure was observed in any run. It also
    // could not detect an in-place write under the lock at all, because read() and write() share
    // that lock -- yet an in-place write is exactly what a crash mid-write would expose on disk.
    // This version pauses ONE real write at the instant the risk exists (partial bytes already on
    // disk) and checks every observable view at that instant, so its running time no longer scales
    // with 400 x scheduler delay. Every thread is released, interrupted and awaited in the finally,
    // and the pool's termination is asserted. The pause sits in the serialization step, so this
    // proves that a write never goes to the credential path in place; the final replace step (the
    // atomic move itself) is outside what a caller-side pause can reach. ----

    @Test
    @DisplayName("a read() overlapping an in-flight write never observes a partial document, and the "
            + "credential path keeps the previous complete document while the new one is being written (#569)")
    void concurrentReadNeverObservesPartialWrite() throws Exception {
        // Pin migrate()'s old-location lookup to a path that does not exist, so migrate() is a no-op
        // here whatever UltiTools instance an earlier test class in the same fork left behind.
        CredentialStore.setOldLocationForTesting(
                tempDir.resolve("no-pre-6.3.0-location").resolve("data.json"));

        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("marker", "previous-complete-document");
        CredentialStore.write(previous);

        char[] bulk = new char[BULK_CHARS];
        Arrays.fill(bulk, 'x');
        CountDownLatch writerPaused = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        PausingDocument next = new PausingDocument(writerPaused, releaseWriter);
        next.put("bulk", new String(bulk));
        next.put("marker", "next-complete-document");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> CredentialStore.write(next));
            awaitPause(writerPaused, writer);
            // The write is now in flight: CredentialStore.write(next) has not returned, and is
            // part-way through serializing `next`, with most of its first entry already flushed to disk.
            // Every violated view is collected, so one run reports all of them.
            List<String> violations = new ArrayList<>();

            // (1) Non-vacuity control: the risk this test guards against really exists right now --
            // an unparseable prefix of `next` is on disk in the credential file's directory.
            if (filesHoldingAPartialDocument(dataFile.getParent()).isEmpty()) {
                violations.add("control: while the write is paused, no file in the credential directory "
                        + "holds an unparseable prefix of the in-flight document, so this test no longer "
                        + "exercises a write in progress and proves nothing (if the store now serializes "
                        + "into memory before writing, the pause point has to move, the store is not "
                        + "necessarily broken)");
            }

            // (2) On-disk view -- what a crash at this instant, or any other process reading the
            // file, would find: the credential path still names the previous complete document.
            if (!Files.exists(dataFile)) {
                violations.add("on disk: the credential path vanished while a replacement was in flight");
            } else {
                String onDisk = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
                if (!parses(onDisk)) {
                    violations.add("on disk: while a write was in flight the credential path held a "
                            + "partial document -- the write went to the credential path in place instead "
                            + "of to a temporary file");
                }
                if (!onDisk.contains("previous-complete-document")) {
                    violations.add("on disk: while a write was in flight the credential path no longer "
                            + "held the previous document");
                }
            }

            // (3) In-process view: a read() issued now, while the write is in flight. It either
            // returns straight away or blocks on the store lock; the write is released only after
            // one of the two has happened, so the read genuinely overlaps the write. This check can
            // only tell apart a read path that takes no store lock at all (migrate() included): any
            // lock on the way in holds the reader until the write is done. An in-place write is
            // caught by (2) whatever the read path does.
            AtomicReference<Thread> readerThread = new AtomicReference<>();
            Future<CredentialStore.ReadResult> reader = pool.submit(() -> {
                readerThread.set(Thread.currentThread());
                return CredentialStore.read();
            });
            awaitFinishedOrBlocked(reader, readerThread);
            releaseWriter.countDown();
            writer.get(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS);
            CredentialStore.ReadResult overlapping = reader.get(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS);

            if (overlapping.isParseFailure()) {
                violations.add("read(): a read overlapping an in-flight write observed a partial document");
            } else if (!overlapping.isParsed()) {
                violations.add("read(): the credential file exists throughout, yet the overlapping read "
                        + "reported it absent");
            } else {
                Object marker = overlapping.data().get("marker");
                if (!"previous-complete-document".equals(marker) && !"next-complete-document".equals(marker)) {
                    violations.add("read(): the overlapping read returned neither complete document, "
                            + "marker=" + marker);
                }
            }

            assertThat(violations)
                    .as("every view of the credential file while a write was in flight")
                    .isEmpty();
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
            pool.awaitTermination(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS);
        }
        assertThat(pool.isTerminated())
                .as("no worker thread may outlive this test in the shared surefire fork")
                .isTrue();

        // After the write lands, the paused document is there in full, not truncated by the pause.
        CredentialStore.ReadResult after = CredentialStore.read();
        assertThat(after.isParsed()).isTrue();
        assertThat(after.data()).containsEntry("marker", "next-complete-document");
        assertThat((String) after.data().get("bulk")).hasSize(BULK_CHARS);
    }

    private static boolean parses(String json) {
        try {
            GSON.fromJson(json, Map.class);
            return true;
        } catch (JsonParseException e) {
            return false;
        }
    }

    private static List<Path> filesHoldingAPartialDocument(Path directory) throws IOException {
        List<Path> partials = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (content.contains("xxxxxxxxxxxxxxxx") && !parses(content)) {
                    partials.add(file);
                }
            }
        }
        return partials;
    }

    /**
     * Waits until the writer is paused mid-serialization. Fails at once, with the writer's own
     * exception if it threw, when the write finished without ever pausing.
     */
    private static void awaitPause(CountDownLatch paused, Future<?> writer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANG_BOUND_MILLIS);
        while (!paused.await(10, TimeUnit.MILLISECONDS)) {
            if (writer.isDone()) {
                writer.get();
                throw new AssertionError("write() completed without pausing mid-serialization: the "
                        + "store no longer serializes the caller's document inside write(), so this "
                        + "test's pause point needs revisiting");
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("the writer did not reach the pause point within "
                        + HANG_BOUND_MILLIS + " ms");
            }
        }
    }

    /** Waits until the reader has either returned or is blocked entering a monitor. */
    private static void awaitFinishedOrBlocked(Future<?> reader, AtomicReference<Thread> readerThread)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANG_BOUND_MILLIS);
        while (!reader.isDone()) {
            Thread thread = readerThread.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("the overlapping read() neither returned nor blocked on the "
                        + "store lock within " + HANG_BOUND_MILLIS + " ms");
            }
            Thread.sleep(1);
        }
    }

    // ---- #573 part 1: an existing credential file that is empty, whitespace-only or unparseable is
    // preserved under the maintainer's #470 rule (an unreadable file is treated like an unparseable
    // one and never overwritten): no new server UUID, no token write, one SEVERE line naming the
    // file. Only a file that does not exist at all starts a fresh identity. ----

    static Stream<Arguments> unreadableContents() {
        return Stream.of(
                Arguments.of("empty", ""),
                Arguments.of("whitespace-only", "  \n\t \r\n  "),
                Arguments.of("JSON null", "null"),
                Arguments.of("truncated JSON",
                        "{\"uuid\":\"kept-server-identity\",\"cloud_token\":{\"access_token\":\"synthetic-tok"));
    }

    /** Pins migrate()'s old-location lookup to a path that does not exist. */
    private void pinNoPre630Location() {
        CredentialStore.setOldLocationForTesting(tempDir.resolve("no-pre-6.3.0-location").resolve("data.json"));
    }

    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static TokenEntity syntheticToken() {
        TokenEntity token = new TokenEntity();
        token.setAccess_token("synthetic-access-token");
        token.setRefresh_token("synthetic-refresh-token");
        return token;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableContents")
    @DisplayName("read() reports an existing empty, whitespace-only or unparseable file as a parse failure, never as a document (#573)")
    void readReportsAnUnreadableFileAsAParseFailure(String kind, String content) throws IOException {
        pinNoPre630Location();
        Files.write(dataFile, content.getBytes(StandardCharsets.UTF_8));

        CredentialStore.ReadResult result = CredentialStore.read();

        assertThat(result.isParseFailure()).as("a %s credential file is a torn file", kind).isTrue();
        assertThat(result.isParsed()).isFalse();
        assertThat(result.isAbsent()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableContents")
    @DisplayName("getUltiToolsUUID() fails with an IOException naming the file and writes no new identity over an unreadable file (#573)")
    void getUltiToolsUUIDFailsWithoutWritingANewIdentity(String kind, String content) throws IOException {
        pinNoPre630Location();
        byte[] before = content.getBytes(StandardCharsets.UTF_8);
        Files.write(dataFile, before);

        assertThatThrownBy(CommonUtils::getUltiToolsUUID)
                .as("a %s credential file must be a clear, declared failure, not a fresh identity", kind)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(dataFile.toAbsolutePath().toString());

        assertThat(Files.readAllBytes(dataFile)).as("the %s file must be left byte-for-byte untouched", kind)
                .isEqualTo(before);
        assertThat(namesIn(tempDir)).as("nothing else may be written beside it").containsExactly("data.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableContents")
    @DisplayName("saving or clearing the cloud token refuses to overwrite an unreadable file (#573)")
    void tokenSaveAndClearRefuseToOverwriteAnUnreadableFile(String kind, String content) throws IOException {
        pinNoPre630Location();
        byte[] before = content.getBytes(StandardCharsets.UTF_8);
        Files.write(dataFile, before);
        TokenStore tokenStore = new TokenStore();

        assertThatThrownBy(() -> tokenStore.save(syntheticToken()))
                .as("a login or refresh must not replace a %s credential file", kind)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(dataFile.toAbsolutePath().toString());
        assertThatThrownBy(() -> tokenStore.clearIfMatches(syntheticToken()))
                .as("a logout must not rewrite a %s credential file", kind)
                .isInstanceOf(DataAccessException.class);

        assertThat(Files.readAllBytes(dataFile)).isEqualTo(before);
        assertThat(namesIn(tempDir)).containsExactly("data.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableContents")
    @DisplayName("an unreadable pre-6.3.0 data.json with no current file blocks identity generation and is kept byte-for-byte (#573)")
    void anUnreadablePre630FileWithNoCurrentFileBlocksIdentityGeneration(String kind, String content)
            throws IOException {
        Path oldFile = tempDir.resolve("old-location").resolve("data.json");
        Path newFile = tempDir.resolve("new-location").resolve("credentials.json");
        Files.createDirectories(oldFile.getParent());
        byte[] before = content.getBytes(StandardCharsets.UTF_8);
        Files.write(oldFile, before);
        CredentialStore.setOldLocationForTesting(oldFile);
        CredentialStore.setTargetPathForTesting(newFile);

        assertThat(CredentialStore.read().isParseFailure())
                .as("with only a %s pre-6.3.0 file present the store is unreadable, not empty", kind)
                .isTrue();
        assertThatThrownBy(CommonUtils::getUltiToolsUUID)
                .as("a %s pre-6.3.0 file must not be replaced by a fresh identity at the new location", kind)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(oldFile.toAbsolutePath().toString());

        assertThat(Files.readAllBytes(oldFile)).isEqualTo(before);
        assertThat(Files.exists(newFile)).as("no current file may be created").isFalse();
    }

    @Test
    @DisplayName("an unreadable pre-6.3.0 data.json beside a valid current file is left alone and the current identity is used (#573)")
    void anUnreadablePre630FileBesideAValidCurrentFileIsLeftAlone() throws Exception {
        Path oldFile = tempDir.resolve("old-location").resolve("data.json");
        Path newFile = tempDir.resolve("new-location").resolve("credentials.json");
        Files.createDirectories(oldFile.getParent());
        Files.createDirectories(newFile.getParent());
        byte[] oldBefore = new byte[0];
        Files.write(oldFile, oldBefore);
        Files.write(newFile, "{\"uuid\":\"current-server-identity\"}".getBytes(StandardCharsets.UTF_8));
        CredentialStore.setOldLocationForTesting(oldFile);
        CredentialStore.setTargetPathForTesting(newFile);

        assertThat(CommonUtils.getUltiToolsUUID()).isEqualTo("current-server-identity");
        assertThat(Files.readAllBytes(oldFile)).isEqualTo(oldBefore);
    }

    @Test
    @DisplayName("a credential file that does not exist at all still starts a fresh identity (#573 must not change a fresh install)")
    void aMissingFileStillStartsAFreshIdentity() throws Exception {
        pinNoPre630Location();
        assertThat(Files.exists(dataFile)).isFalse();

        String uuid = CommonUtils.getUltiToolsUUID();

        assertThat(uuid).matches("[0-9a-f]{32}");
        CredentialStore.ReadResult reread = CredentialStore.read();
        assertThat(reread.isParsed()).isTrue();
        assertThat(reread.data()).containsEntry("uuid", uuid);
    }

    @Test
    @DisplayName("an unreadable credential file is reported as one SEVERE line naming it, once per episode (#573)")
    void anUnreadableFileIsReportedOnceAsSevere() throws IOException {
        pinNoPre630Location();
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        });
        UltiTools plugin = mock(UltiTools.class);
        when(plugin.getLogger()).thenReturn(logger);
        try (MockedStatic<UltiTools> ultiTools = mockStatic(UltiTools.class)) {
            ultiTools.when(UltiTools::getInstance).thenReturn(plugin);
            Files.write(dataFile, new byte[0]);

            CredentialStore.read();
            CredentialStore.read();
            assertThatThrownBy(CommonUtils::getUltiToolsUUID).isInstanceOf(IOException.class);
            assertThat(records.stream().filter(r -> Level.SEVERE.equals(r.getLevel())).collect(Collectors.toList()))
                    .as("repeated reads of the same unreadable file log it once")
                    .hasSize(1)
                    .allSatisfy(r -> assertThat(r.getMessage()).contains(dataFile.toAbsolutePath().toString()));

            Files.write(dataFile, "{\"uuid\":\"restored-identity\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(CredentialStore.read().isParsed()).isTrue();
            Files.write(dataFile, "   ".getBytes(StandardCharsets.UTF_8));
            CredentialStore.read();
        }

        assertThat(records.stream().filter(r -> Level.SEVERE.equals(r.getLevel())).count())
                .as("the file breaking again after a good read is a new episode and is reported again")
                .isEqualTo(2);
    }

    // ---- #573 part 3: the final replace step itself. The #569 test pauses in serialization, before
    // the replace; these two reach the replace from outside the store too: the temporary file is
    // observed (and, in the second, removed) while the writer is paused, with no hook in the store. ----

    private static PausingDocument pausedNextDocument(CountDownLatch paused, CountDownLatch release) {
        char[] bulk = new char[BULK_CHARS];
        Arrays.fill(bulk, 'x');
        PausingDocument next = new PausingDocument(paused, release);
        next.put("bulk", new String(bulk));
        next.put("marker", "next-complete-document");
        return next;
    }

    @Test
    @DisplayName("the replace step renames the written temporary file onto the credential path, not a copy (#573)")
    void replaceStepRenamesTheTemporaryFileOntoTheCredentialPath() throws Exception {
        pinNoPre630Location();
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("marker", "previous-complete-document");
        CredentialStore.write(previous);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PausingDocument next = pausedNextDocument(paused, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = pool.submit(() -> CredentialStore.write(next));
            awaitPause(paused, writer);
            List<Path> partials = filesHoldingAPartialDocument(dataFile.getParent());
            assertThat(partials).as("the in-flight document is written to one temporary file").hasSize(1);
            Path temporary = partials.get(0);
            assertThat(temporary).as("never to the credential path itself").isNotEqualTo(dataFile);
            Object temporaryKey = Files.readAttributes(temporary, BasicFileAttributes.class).fileKey();
            assumeTrue(temporaryKey != null, "this platform exposes no file keys");

            releaseWriter(release, writer);

            assertThat(Files.readAttributes(dataFile, BasicFileAttributes.class).fileKey())
                    .as("the credential path must now name the very file that was written -- a rename, not a copy")
                    .isEqualTo(temporaryKey);
            assertThat(Files.exists(temporary)).isFalse();
            assertThat(CredentialStore.read().data()).containsEntry("marker", "next-complete-document");
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS);
        }
        assertThat(pool.isTerminated()).isTrue();
    }

    @Test
    @DisplayName("a failure at the replace step leaves the previous credential file byte-for-byte intact (#573)")
    void aFailureAtTheReplaceStepLeavesThePreviousFileIntact() throws Exception {
        pinNoPre630Location();
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("marker", "previous-complete-document");
        CredentialStore.write(previous);
        byte[] before = Files.readAllBytes(dataFile);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PausingDocument next = pausedNextDocument(paused, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = pool.submit(() -> CredentialStore.write(next));
            awaitPause(paused, writer);
            List<Path> partials = filesHoldingAPartialDocument(dataFile.getParent());
            assertThat(partials).hasSize(1);
            Path temporary = partials.get(0);
            assertThat(temporary).isNotEqualTo(dataFile);
            // The writer keeps its open handle and finishes writing; the rename that follows then
            // has no source. That is a failure at exactly the replace step, reached from outside.
            Files.delete(temporary);
            release.countDown();

            assertThatThrownBy(() -> writer.get(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DataAccessException.class)
                    .hasRootCauseInstanceOf(NoSuchFileException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS);
        }
        assertThat(pool.isTerminated()).isTrue();

        assertThat(Files.readAllBytes(dataFile))
                .as("a failed replace must leave the previous file exactly as it was")
                .isEqualTo(before);
        assertThat(namesIn(tempDir)).as("and no temporary file behind").containsExactly("data.json");
        assertThat(CredentialStore.read().data()).containsEntry("marker", "previous-complete-document");
    }

    private static void releaseWriter(CountDownLatch release, Future<?> writer) throws Exception {
        release.countDown();
        writer.get(HANG_BOUND_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * A credential document whose serialization pauses before its second entry: it holds the
     * caller inside {@link CredentialStore#write(Map)} -- after the first (bulk) entry has been
     * handed to the writer and mostly flushed to disk -- until the test releases it. Gson's map
     * adapter serializes a map by iterating {@link #entrySet()} once, so this needs no hook in the
     * store; a store that serialized some other way, or copied the map first, would pause elsewhere
     * or not at all, and the test then fails with a message saying so. It must not be an anonymous
     * or local class: Gson excludes those and would serialize them as {@code null}.
     */
    private static final class PausingDocument extends LinkedHashMap<String, Object> {

        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch paused;
        private final transient CountDownLatch release;

        PausingDocument(CountDownLatch paused, CountDownLatch release) {
            this.paused = paused;
            this.release = release;
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            Set<Map.Entry<String, Object>> entries = super.entrySet();
            return new AbstractSet<Map.Entry<String, Object>>() {
                @Override
                public int size() {
                    return entries.size();
                }

                @Override
                public Iterator<Map.Entry<String, Object>> iterator() {
                    Iterator<Map.Entry<String, Object>> delegate = entries.iterator();
                    return new Iterator<Map.Entry<String, Object>>() {
                        private int yielded;

                        @Override
                        public boolean hasNext() {
                            if (yielded == 1) {
                                pauseUntilReleased();
                            }
                            return delegate.hasNext();
                        }

                        @Override
                        public Map.Entry<String, Object> next() {
                            yielded++;
                            return delegate.next();
                        }
                    };
                }
            };
        }

        void pauseUntilReleased() {
            paused.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while paused mid-serialization", e);
            }
        }
    }

    // ---- The single-owner invariant (promote decision, assumption_delta_decision). Structural,
    // not runtime: a source scan, because "no other class touches the credential file" cannot be
    // observed by calling an API -- it is a property of the whole src/main tree.
    //
    // Checks BOTH quoted literals, deliberately: "data.json" (the pre-6.3.0 name -- still the
    // right thing for CredentialStore.migrate() to read, but wrong for any other class to open
    // directly) and "credentials.json" (the current, >= 6.3.0 name, plan 08-15). Renaming the
    // live file must not silently narrow this scan back down to the name nothing writes to
    // anymore -- see 08-14-SUMMARY.md's note that this expectation has to move with the rename. ----

    @Test
    @DisplayName("no class other than CredentialStore opens a reader or writer on the credential file (old or new name)")
    void onlyCredentialStoreTouchesTheCredentialFileDirectly() throws IOException {
        Path srcRoot = Paths.get("src/main/java");
        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")
                        && !file.getFileName().toString().equals("CredentialStore.java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            // Quoted literal only -- a bare substring match also fires on this file's own package
            // name (com.ultikits.ultitools.interfaces.impl.data.json), a false positive that has
            // nothing to do with the credential file (measured during plan 08-13's own execution).
            boolean mentionsCredentialFile = content.contains("\"data.json\"")
                    || content.contains("\"credentials.json\"");
            boolean opensReaderOrWriter = content.contains("Files.newBufferedReader(")
                    || content.contains("Files.newBufferedWriter(")
                    || content.contains("Files.newInputStream(")
                    || content.contains("Files.newOutputStream(");
            if (mentionsCredentialFile && opensReaderOrWriter) {
                offenders.add(file.toString() + " -- route through CredentialStore instead");
            }
        }

        assertThat(offenders)
                .as("every reader or writer on the credential file must go through CredentialStore")
                .isEmpty();
    }
}
