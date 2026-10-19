package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.manager.DataScope;

/**
 * The flush as a checkpoint (20.4-RESEARCH.md section 12.7): one at a time per identity, never
 * around a commit that is between its record's rename and its apply, changed entries only, crash
 * atomic, and never bringing an older value back.
 * <p>
 * Crashes are simulated with step hooks that throw {@link SimulatedCrash} on a dedicated thread,
 * which is then abandoned; the store is dropped without flushing and a new one is built over the
 * same folders. Pauses use latches and bounded waits only; "has not happened yet" is a 300 ms
 * bounded wait that must return {@code false}, followed by the release and the positive check.
 */
@Timeout(60)
@DisplayName("JSON flush is a checkpoint: serialized, safe around commits, dirty entries only, crash atomic")
class JsonCheckpointTest {

    private static final String ROWS = "cp_rows";
    private static final String OTHERS = "cp_others";

    private static final long HANDSHAKE_SECONDS = 10;
    private static final long NOT_YET_MILLIS = 300;

    /** The checkpoint steps of 20.4-RESEARCH.md section 12.9, in the order a checkpoint fires them. */
    private static final List<String> CHECKPOINT_STEPS = Arrays.asList(
            "checkpoint.captured", "checkpoint.recordWritten", "checkpoint.recordForced", "checkpoint.renamed",
            "checkpoint.directoryForced", "checkpoint.entityTempWritten", "checkpoint.betweenEntityFiles",
            "checkpoint.tableDirsForced", "checkpoint.betweenTruncations", "checkpoint.truncated");

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUpServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
    }

    @AfterEach
    void resetStepHooks() {
        JsonJournal.resetStepHook();
        JsonTransaction.resetStepHook();
        JsonIdentity.resetStepHook();
    }

    // ===== No older value comes back =====

    @Test
    @DisplayName("A commit sets X = 1, a plain write sets X = 2, a flush is interrupted after its entity files: a restart shows X = 2")
    void noRegressionOfPlainWrite() throws Exception {
        Site site = new Site("regression").open();
        site.rows.transaction(() -> site.rows.insert(new Row("x", "1")));
        site.rows.update(new Row("x", "2"));

        installHook(crashAt("checkpoint.tableDirsForced"));
        Throwable crash = runOnOwnThread(site.rows::flush);
        resetStepHooks();
        site.open();

        assertThat(site.rows.getById("x")).isNotNull();
        assertThat(site.rows.getById("x").getName())
                .as("the plain write after the commit, captured by the interrupted flush, is what a restart shows")
                .isEqualTo("2");
        assertThat(crash).as("the flush crashed at checkpoint.tableDirsForced").isInstanceOf(SimulatedCrash.class);
        assertThat(crash.getMessage()).isEqualTo("checkpoint.tableDirsForced");
    }

    // ===== Interruption at every checkpoint step =====

    @Test
    @DisplayName("A flush interrupted at any checkpoint step: committed transactions whole, captured plain writes present once the record is renamed, only <key>.json files")
    void interruptionAtEveryCheckpointStep() throws Exception {
        for (String step : CHECKPOINT_STEPS) {
            Site site = new Site("step-" + step).open();
            site.rows.insert(new Row("d1", "old"));
            site.rows.flush();
            site.rows.transaction(() -> {
                site.rows.insert(new Row("r1", "committed"));
                site.others.insert(new Other("o1", "committed"));
            });
            site.rows.insert(new Row("p1", "plain"));
            site.rows.delById("d1");

            installHook(crashAt(step));
            Throwable crash = runOnOwnThread(site.rows::flush);
            assertThat(crash).as("the flush crashed at %s", step).isInstanceOf(SimulatedCrash.class);
            assertThat(crash.getMessage()).as("the flush crashed at %s", step).isEqualTo(step);
            resetStepHooks();
            site.open();

            assertThat(site.rows.getById("r1")).as("committed row after a crash at %s", step).isNotNull();
            assertThat(site.others.getById("o1")).as("committed other after a crash at %s", step).isNotNull();
            boolean recordRenamed = CHECKPOINT_STEPS.indexOf(step) >= CHECKPOINT_STEPS.indexOf("checkpoint.renamed");
            if (recordRenamed) {
                assertThat(site.rows.getById("p1")).as("captured plain write after a crash at %s", step).isNotNull();
                assertThat(site.rows.getById("d1")).as("captured plain delete after a crash at %s", step).isNull();
            } else {
                assertThat(site.rows.getById("p1")).as("uncaptured plain write after a crash at %s", step).isNull();
                assertThat(site.rows.getById("d1")).as("uncaptured plain delete after a crash at %s", step).isNotNull();
            }
            assertOnlyEntityFiles(site, step);
            assertThat(recordNames(site.journal())).as("journal records after recovery (%s)", step).isEmpty();
        }
    }

    // ===== The gate span: no checkpoint around an unapplied commit =====

    @Test
    @DisplayName("A checkpoint waits for a commit parked between its record's rename and its apply, then captures it with a higher number")
    void checkpointWaitsForACommitBetweenRenameAndApply() throws Exception {
        for (GateVariant variant : GateVariant.values()) {
            Site site = new Site("gate-" + variant).open();
            site.rows.insert(new Row("p0", "plain"));
            CountDownLatch commitParked = new CountDownLatch(1);
            CountDownLatch commitRelease = new CountDownLatch(1);
            CountDownLatch checkpointCaptured = new CountDownLatch(1);
            AtomicReference<List<String>> recordsAtCommit = new AtomicReference<>();
            AtomicReference<List<String>> recordsAtCheckpoint = new AtomicReference<>();
            installHook(step -> {
                if ("commit.renamed".equals(step) && "commit-J".equals(Thread.currentThread().getName())) {
                    recordsAtCommit.set(recordNamesQuietly(site.journal()));
                    commitParked.countDown();
                    await(commitRelease);
                } else if ("checkpoint.captured".equals(step)) {
                    checkpointCaptured.countDown();
                } else if ("checkpoint.renamed".equals(step)) {
                    recordsAtCheckpoint.set(recordNamesQuietly(site.journal()));
                    if (variant == GateVariant.CRASH_AT_RENAMED) {
                        throw new SimulatedCrash(step);
                    }
                } else if ("checkpoint.tableDirsForced".equals(step) && variant == GateVariant.CRASH_AT_TABLE_DIRS_FORCED) {
                    throw new SimulatedCrash(step);
                }
            });

            Worker commit = Worker.start("commit-J", () -> site.rows.transaction(() -> {
                site.rows.insert(new Row("r1", "J"));
                site.others.insert(new Other("o1", "J"));
            }));
            assertThat(commitParked.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("J parked at commit.renamed").isTrue();
            Worker checkpoint = Worker.start("checkpoint-K", site.rows::flush);

            assertThat(checkpointCaptured.await(NOT_YET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("[%s] the checkpoint does not capture while J is between its rename and its apply", variant)
                    .isFalse();
            commitRelease.countDown();
            assertThat(checkpointCaptured.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS))
                    .as("[%s] the checkpoint captures once J has applied", variant).isTrue();
            commit.join();
            checkpoint.join();
            resetStepHooks();

            assertThat(commit.failure()).as("[%s] J committed", variant).isNull();
            long j = highestNumber(recordsAtCommit.get());
            long k = highestNumber(recordsAtCheckpoint.get());
            assertThat(k).as("[%s] the checkpoint's number K is above J's", variant).isGreaterThan(j);
            if (variant == GateVariant.FINISH) {
                assertThat(checkpoint.failure()).as("the checkpoint finished").isNull();
                assertThat(readName(site.root().resolve(ROWS).resolve("r1.json"))).as("K wrote J's row").isEqualTo("J");
                assertThat(readName(site.root().resolve(OTHERS).resolve("o1.json"))).as("K wrote J's other").isEqualTo("J");
            } else {
                assertThat(checkpoint.failure()).as("[%s] the checkpoint crashed", variant).isInstanceOf(SimulatedCrash.class);
            }

            site.open();
            assertThat(site.rows.getById("r1")).as("[%s] J's row after a restart", variant).isNotNull();
            assertThat(site.rows.getById("r1").getName()).isEqualTo("J");
            assertThat(site.others.getById("o1")).as("[%s] J's other after a restart", variant).isNotNull();
            assertThat(site.others.getById("o1").getName()).isEqualTo("J");
        }
    }

    // ===== The checkpoint mutex: the newest value ends on disk =====

    @Test
    @DisplayName("Two checkpoints of one plugin run one at a time: the newer value of X ends on disk and in memory, and X is no longer dirty")
    void concurrentCheckpointsEndOnTheNewestValue() throws Exception {
        Site site = new Site("mutex").open();
        site.rows.transaction(() -> site.rows.insert(new Row("x", "1")));
        AtomicInteger captures = new AtomicInteger();
        CountDownLatch firstParked = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondCaptured = new CountDownLatch(1);
        installHook(step -> {
            if ("checkpoint.captured".equals(step)) {
                if (captures.incrementAndGet() == 1) {
                    firstParked.countDown();
                    await(firstRelease);
                } else {
                    secondCaptured.countDown();
                }
            }
        });

        Worker first = Worker.start("checkpoint-C1", site.rows::flush);
        assertThat(firstParked.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("C1 captured X = 1 and parked").isTrue();
        site.rows.update(new Row("x", "2"));
        Worker second = Worker.start("checkpoint-C2", site.rows::flush);

        assertThat(secondCaptured.await(NOT_YET_MILLIS, TimeUnit.MILLISECONDS))
                .as("C2 does not capture while C1 is running").isFalse();
        firstRelease.countDown();
        assertThat(secondCaptured.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("C2 captures after C1").isTrue();
        first.join();
        second.join();
        resetStepHooks();

        assertThat(first.failure()).isNull();
        assertThat(second.failure()).isNull();
        assertThat(readName(site.root().resolve(ROWS).resolve("x.json"))).as("X's file").isEqualTo("2");
        assertThat(site.rows.getById("x").getName()).as("X in memory").isEqualTo("2");
        List<String> steps = recordSteps();
        site.rows.flush();
        assertThat(steps).as("X is no longer dirty: a further flush captures nothing").doesNotContain("checkpoint.captured");
        resetStepHooks();
        site.open();
        assertThat(site.rows.getById("x").getName()).as("X after a restart").isEqualTo("2");
    }

    @Test
    @DisplayName("A key written again after a checkpoint's capture stays dirty and is written by the next checkpoint")
    void reWrittenKeyStaysDirty() throws Exception {
        Site site = new Site("rewritten").open();
        site.rows.insert(new Row("x", "1"));
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger captures = new AtomicInteger();
        installHook(step -> {
            if ("checkpoint.captured".equals(step) && captures.incrementAndGet() == 1) {
                parked.countDown();
                await(release);
            }
        });

        Worker first = Worker.start("checkpoint-C1", site.rows::flush);
        assertThat(parked.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("C1 captured X = 1").isTrue();
        site.rows.update(new Row("x", "2"));
        release.countDown();
        first.join();
        assertThat(first.failure()).isNull();
        assertThat(readName(site.root().resolve(ROWS).resolve("x.json"))).as("C1 wrote what it captured").isEqualTo("1");

        site.rows.flush();
        assertThat(captures.get()).as("the next flush captured X again").isEqualTo(2);
        assertThat(readName(site.root().resolve(ROWS).resolve("x.json"))).as("the next checkpoint wrote X = 2").isEqualTo("2");
    }

    // ===== Changed entries only; tracked deletions through a checkpoint =====

    @Test
    @DisplayName("A flush writes only entries changed since the last checkpoint and deletes the files of deleted entries")
    void flushWritesOnlyChangedEntries() throws Exception {
        Site site = new Site("dirty-only").open();
        site.rows.insert(new Row("a", "a"));
        site.rows.insert(new Row("b", "b"));
        site.rows.flush();
        Path a = site.root().resolve(ROWS).resolve("a.json");
        Path b = site.root().resolve(ROWS).resolve("b.json");
        FileTime old = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(a, old);
        byte[] aBytes = Files.readAllBytes(a);

        site.rows.update(new Row("b", "b2"));
        site.rows.flush();

        assertThat(Files.getLastModifiedTime(a)).as("an untouched entry's file is not rewritten").isEqualTo(old);
        assertThat(Files.readAllBytes(a)).isEqualTo(aBytes);
        assertThat(readName(b)).isEqualTo("b2");

        site.rows.delById("b");
        site.rows.flush();
        assertThat(b).as("a deleted entry's file is deleted by the flush").doesNotExist();
        assertThat(a).exists();
    }

    @Test
    @DisplayName("gc() leaves the file of a key with a pending dirty mark; the next flush deletes it; orphans are still collected")
    void gcKeepsTheFileOfADirtyKey() throws Exception {
        Site site = new Site("gc").open();
        site.rows.insert(new Row("a", "a"));
        site.rows.flush();
        Path a = site.root().resolve(ROWS).resolve("a.json");
        Path orphan = site.root().resolve(ROWS).resolve("orphan.json");
        Files.write(orphan, "{\"id\":\"orphan\",\"name\":\"o\"}".getBytes(StandardCharsets.UTF_8));

        site.rows.delById("a");
        site.rows.gc();

        assertThat(a).as("a tracked deletion reaches disk only through a checkpoint").exists();
        assertThat(orphan).as("a file with no entry and no mark is collected").doesNotExist();
        site.rows.flush();
        assertThat(a).as("the checkpoint deleted it").doesNotExist();
    }

    // ===== Store-wide flush and shutdown =====

    @Test
    @DisplayName("flushAllCaches and destroyAllOperators run one checkpoint per identity and write every dirty entry")
    void storeRunsOneCheckpointPerIdentity() throws Exception {
        Site site = new Site("store-wide").open();
        File secondFolder = Files.createDirectories(site.base.resolve("second-plugin")).toFile();
        DataScope secondScope = newDataScope("json-checkpoint-second", secondFolder, Row.class);
        @SuppressWarnings("unchecked")
        SimpleJsonDataOperator<Row> secondRows =
                (SimpleJsonDataOperator<Row>) site.store.getOperator(secondScope, Row.class);
        site.rows.insert(new Row("r1", "first"));
        site.others.insert(new Other("o1", "first"));
        secondRows.insert(new Row("s1", "second"));
        AtomicInteger captures = new AtomicInteger();
        installHook(step -> {
            if ("checkpoint.captured".equals(step)) {
                captures.incrementAndGet();
            }
        });

        site.store.flushAllCaches();

        assertThat(captures.get()).as("flushAllCaches: one checkpoint per identity").isEqualTo(2);
        assertThat(site.root().resolve(ROWS).resolve("r1.json")).exists();
        assertThat(site.root().resolve(OTHERS).resolve("o1.json")).exists();
        assertThat(secondFolder.toPath().resolve("data").resolve(ROWS).resolve("s1.json")).exists();

        site.rows.update(new Row("r1", "again"));
        site.others.update(new Other("o1", "again"));
        secondRows.update(new Row("s1", "again"));
        captures.set(0);
        site.store.destroyAllOperators();
        assertThat(captures.get()).as("destroyAllOperators: one final checkpoint per identity").isEqualTo(2);
        assertThat(readName(site.root().resolve(OTHERS).resolve("o1.json"))).isEqualTo("again");
        assertThat(readName(secondFolder.toPath().resolve("data").resolve(ROWS).resolve("s1.json"))).isEqualTo("again");
    }

    // ===== An id that cannot name a file =====

    @Test
    @DisplayName("An entity id that is not one file-name segment is refused at the write, inside and outside a transaction, and never blocks a flush")
    void entityIdThatCannotNameAFileIsRefusedAtTheWrite() throws Exception {
        Site site = new Site("invalid-id").open();
        for (String id : Arrays.asList("a/b", "a\\b", "", "a\u0000b")) {
            assertThatThrownBy(() -> site.rows.insert(new Row(id, "x")))
                    .as("plain insert of id '%s'", id)
                    .isInstanceOf(DataAccessException.class)
                    .extracting(t -> ((DataAccessException) t).getErrorCode())
                    .isEqualTo(ErrorCode.DATA_ENTITY_INVALID);
            assertThatThrownBy(() -> site.rows.transaction(() -> site.rows.insert(new Row(id, "x"))))
                    .as("insert of id '%s' inside a transaction", id)
                    .isInstanceOf(DataAccessException.class)
                    .extracting(t -> ((DataAccessException) t).getErrorCode())
                    .isEqualTo(ErrorCode.DATA_ENTITY_INVALID);
        }
        assertThat(site.rows.getAll()).as("nothing was stored").isEmpty();

        site.rows.insert(new Row("fine", "x"));
        site.rows.flush();
        assertThat(site.root().resolve(ROWS).resolve("fine.json")).exists();
    }

    // ===== Helpers =====

    private static void assertOnlyEntityFiles(Site site, String step) throws IOException {
        for (String tableName : Arrays.asList(ROWS, OTHERS)) {
            Path table = site.root().resolve(tableName);
            if (Files.isDirectory(table)) {
                assertThat(names(table)).as("files of %s after a crash at %s", tableName, step)
                        .allMatch(name -> name.endsWith(".json") && !name.startsWith("entity-"));
            }
        }
    }

    /** The {@code name} member of an entity file. */
    static String readName(Path file) throws IOException {
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject().get("name").getAsString();
    }

    static long highestNumber(List<String> recordNames) {
        assertThat(recordNames).as("records seen at the step").isNotNull().isNotEmpty();
        long highest = 0L;
        for (String name : recordNames) {
            highest = Math.max(highest, Long.parseLong(name.substring(0, name.indexOf('.'))));
        }
        return highest;
    }

    static Consumer<String> crashAt(String crashStep) {
        return step -> {
            if (step.equals(crashStep)) {
                throw new SimulatedCrash(step);
            }
        };
    }

    /** Installs one callback in the step hooks of the journal, the transaction and the identity. */
    static void installHook(Consumer<String> hook) {
        JsonJournal.installStepHook(hook::accept);
        JsonTransaction.installStepHook(hook::accept);
        JsonIdentity.installStepHook(hook::accept);
    }

    static List<String> recordSteps() {
        List<String> steps = new CopyOnWriteArrayList<>();
        installHook(steps::add);
        return steps;
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("a pause was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while paused", e);
        }
    }

    static Throwable runOnOwnThread(ThrowingRunnable body) throws InterruptedException {
        Worker worker = Worker.start("json-checkpoint-crash", body);
        worker.join();
        return worker.failure();
    }

    static List<String> recordNamesQuietly(Path journal) {
        try {
            return recordNames(journal);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> recordNames(Path journal) throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(journal)) {
            return names;
        }
        for (String name : names(journal)) {
            if (name.endsWith(JsonJournal.COMMITTED_SUFFIX) || name.endsWith(JsonJournal.TEMPORARY_SUFFIX)) {
                names.add(name);
            }
        }
        return names;
    }

    static List<String> names(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                names.add(path.getFileName().toString());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** {@link DataScope}'s constructor is package-private in another package. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    static DataScope newDataScope(String pluginName, File dataFolder, Class<?>... owned)
            throws ReflectiveOperationException {
        Constructor<DataScope> ctor = DataScope.class.getDeclaredConstructor(String.class, File.class, java.util.Set.class);
        ctor.setAccessible(true);
        return ctor.newInstance(pluginName, dataFolder, new HashSet<>(Arrays.asList(owned)));
    }

    enum GateVariant {
        FINISH,
        CRASH_AT_RENAMED,
        CRASH_AT_TABLE_DIRS_FORCED
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** A dedicated thread that records what its body threw; joined with a bound. */
    static final class Worker {
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Worker(String name, ThrowingRunnable body) {
            thread = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, name);
            thread.setDaemon(true);
        }

        static Worker start(String name, ThrowingRunnable body) {
            Worker worker = new Worker(name, body);
            worker.thread.start();
            return worker;
        }

        void join() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
            assertThat(thread.isAlive()).as("%s finished", thread.getName()).isFalse();
        }

        Throwable failure() {
            return failure.get();
        }
    }

    /** One plugin's JSON data under the test folder; {@link #open()} builds a new store, as a restart does. */
    final class Site {
        final Path base;
        final File dataFolder;
        final DataScope scope;
        JsonStore store;
        SimpleJsonDataOperator<Row> rows;
        SimpleJsonDataOperator<Other> others;

        Site(String name) throws IOException, ReflectiveOperationException {
            base = Files.createDirectories(tempDir.resolve(name.replace('.', '-')));
            dataFolder = Files.createDirectories(base.resolve("plugin-data")).toFile();
            scope = newDataScope("json-checkpoint-test", dataFolder, Row.class, Other.class);
        }

        @SuppressWarnings("unchecked")
        Site open() {
            store = new JsonStore(base.resolve("store").toString());
            rows = (SimpleJsonDataOperator<Row>) store.getOperator(scope, Row.class);
            others = (SimpleJsonDataOperator<Other>) store.getOperator(scope, Other.class);
            return this;
        }

        Path root() {
            return dataFolder.toPath().resolve("data");
        }

        Path journal() {
            return root().resolve(JsonJournal.DIRECTORY_NAME);
        }
    }

    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String step) {
            super(step);
        }
    }

    // ===== Fixtures =====

    @Table(ROWS)
    public static class Row extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public Row() {
        }

        public Row(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Table(OTHERS)
    public static class Other extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public Other() {
        }

        public Other(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
