package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link CredentialStore}'s contract: one owner of the credential document, an atomic
 * temp-plus-rename replace, a parse failure reported as an outcome distinct from absence
 * (plan 08-13, D-12/D-14), and the fail-safe one-time migration from the pre-6.3.0 location to
 * the current one (plan 08-15, D-15).
 */
@DisplayName("CredentialStore -- single-owner, atomically-replaced, migrated-in-place credential file")
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
