package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link CredentialStore}'s contract: one owner of {@code data.json}, an atomic
 * temp-plus-rename replace, and a parse failure reported as an outcome distinct from absence
 * (plan 08-13, D-12/D-14).
 */
@DisplayName("CredentialStore -- single-owner, atomically-replaced data.json")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CredentialStoreTest {

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

    // ---- Deterministic interleaving: no torn file, no lost update (D-14). Each case releases all
    // threads together via a CyclicBarrier so contention is real, joins every thread, and asserts
    // on file content AFTER the join -- the assertion after the join is the point; its absence is
    // exactly what makes DataStoreManagerTest#concurrentReadWriteShouldBeSafe worthless (D-14). ----

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

    @Test
    @DisplayName("a reader racing concurrent writers never observes a parse failure")
    void concurrentReadNeverObservesPartialWrite() throws Exception {
        int writerCount = 8;
        CredentialStore.write(new LinkedHashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + 1);
        CyclicBarrier barrier = new CyclicBarrier(writerCount + 1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger parseFailures = new AtomicInteger(0);
        AtomicInteger readsPerformed = new AtomicInteger(0);
        try {
            Future<?> readerFuture = pool.submit(() -> {
                awaitBarrier(barrier);
                while (!stop.get()) {
                    CredentialStore.ReadResult result = CredentialStore.read();
                    readsPerformed.incrementAndGet();
                    if (result.isParseFailure()) {
                        parseFailures.incrementAndGet();
                    }
                }
            });

            List<Future<?>> writers = new ArrayList<>();
            for (int i = 0; i < writerCount; i++) {
                int writerIndex = i;
                writers.add(pool.submit(() -> {
                    awaitBarrier(barrier);
                    for (int iteration = 0; iteration < 50; iteration++) {
                        Map<String, Object> doc = new LinkedHashMap<>();
                        doc.put("writer", writerIndex);
                        doc.put("iteration", iteration);
                        CredentialStore.write(doc);
                    }
                }));
            }
            for (Future<?> writer : writers) {
                writer.get(20, TimeUnit.SECONDS);
            }
            stop.set(true);
            readerFuture.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        // Assertion AFTER the join, with a control assertion so this cannot vacuously pass.
        assertThat(readsPerformed.get()).as("the reader must actually have raced the writers").isPositive();
        assertThat(parseFailures.get())
                .as("every read must either parse or report absence -- never a parse failure from a torn write")
                .isZero();
    }
}
