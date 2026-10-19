package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Entry;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Kind;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Crash consistency of JSON commits across a plugin's tables (20.4-RESEARCH.md sections 12.7 and
 * 12.9; maintainer decision point c and question 5 option A of 20.4-DECISIONS.md).
 * <p>
 * No process is killed. A crash is simulated by a step hook that throws {@link SimulatedCrash} at a
 * named commit step while the commit runs on a dedicated thread, which is then abandoned. The test
 * then drops the {@link JsonStore} and its operators without flushing anything and builds a new
 * store over the same folders, which is what a server restart does.
 */
@Timeout(60)
@DisplayName("JSON commits survive a crash whole, or not at all")
class JsonCrashRecoveryTest {

    private static final String ROWS = "crash_rows";
    private static final String OTHERS = "crash_others";

    /** Bounded wait for a thread or a handshake that must finish. */
    private static final long HANDSHAKE_SECONDS = 10;

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
    }

    // ===== The module case =====

    @Test
    @DisplayName("A transaction over two operators of one plugin commits, the store is dropped without a flush, and a new store shows both writes")
    void twoOperatorCommitSurvivesCrashWhole() throws Exception {
        Site site = new Site("module-case").open();

        site.rows.transaction(() -> {
            site.rows.insert(new CrashRow("r1", "fenced"));
            site.others.insert(new CrashOther("o1", "written"));
        });

        site.open();
        assertBothWritten(site);
    }

    // ===== Interruption at every commit step =====

    @Test
    @DisplayName("A crash before the record's rename (record written, record forced) leaves neither write after a restart")
    void interruptionBeforeTheCommitPointLeavesNeither() throws Exception {
        for (String step : Arrays.asList("commit.recordWritten", "commit.recordForced")) {
            Site site = new Site("before-" + step).open();

            Throwable crash = crashTwoTableCommitAt(site, step);

            assertThat(crash).as("the commit crashed at %s", step).isInstanceOf(SimulatedCrash.class);
            assertThat(crash.getMessage()).as("the commit crashed at %s", step).isEqualTo(step);
            resetStepHooks();
            site.open();
            assertThat(site.rows.getById("r1")).as("rows after a crash at %s", step).isNull();
            assertThat(site.others.getById("o1")).as("others after a crash at %s", step).isNull();
            assertThat(recordNames(site.journal())).as("journal records after recovery (%s)", step).isEmpty();
            assertOnlyEntityFiles(site);
        }
    }

    @Test
    @DisplayName("A crash after the record's rename (renamed, directory forced, between operators, applied) leaves both writes after a restart")
    void interruptionAfterTheCommitPointLeavesBoth() throws Exception {
        for (String step : Arrays.asList("commit.renamed", "commit.directoryForced",
                "commit.applyBetweenOperators", "commit.applied")) {
            Site site = new Site("after-" + step).open();

            Throwable crash = crashTwoTableCommitAt(site, step);

            assertThat(crash).as("the commit crashed at %s", step).isInstanceOf(SimulatedCrash.class);
            assertThat(crash.getMessage()).as("the commit crashed at %s", step).isEqualTo(step);
            resetStepHooks();
            site.open();
            assertBothWritten(site);
            assertThat(recordNames(site.journal())).as("journal records after recovery (%s)", step).isEmpty();
            assertOnlyEntityFiles(site);
        }
    }

    // ===== Failure before the commit point =====

    @Test
    @DisplayName("An I/O failure before the commit point rolls the transaction back with TRANSACTION_FAILED and leaves the operator usable")
    void ioFailureBeforeTheCommitPointRollsBack() throws Exception {
        Site site = new Site("io-failure").open();
        site.store.setLockWaitBoundMillis(1000);
        Files.createDirectories(site.root());
        Files.write(site.journal(), "not a directory".getBytes(StandardCharsets.UTF_8));

        Throwable thrown = catchThrowable(() -> site.rows.transaction(() -> {
            site.rows.insert(new CrashRow("r1", "lost"));
            site.others.insert(new CrashOther("o1", "lost"));
        }));

        assertThat(thrown).as("the commit fails").isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) thrown).getErrorCode()).isEqualTo(ErrorCode.TRANSACTION_FAILED);
        assertThat(site.rows.getById("r1")).as("rolled back: rows").isNull();
        assertThat(site.others.getById("o1")).as("rolled back: others").isNull();
        assertThat(site.rows.transactionManager().hasActiveTransaction()).as("no context left").isFalse();

        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> {
                site.rows.insert(new CrashRow("r2", "other thread"));
                site.others.insert(new CrashOther("o2", "other thread"));
                return null;
            }).get(HANDSHAKE_SECONDS, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
        assertThat(site.rows.getById("r2")).as("no table lock left: another thread's plain write").isNotNull();
        site.rows.insert(new CrashRow("r3", "plain"));
        assertThat(site.rows.getById("r3")).as("plain writes keep working").isNotNull();
        assertThat(site.journal()).as("the occupying file is untouched").isRegularFile();
    }

    // ===== Nothing staged: nothing on disk =====

    @Test
    @DisplayName("A transaction that only reads, and a readOnly @Transactional method, write no record and create no journal directory")
    void emptyTransactionWritesNothing() throws Exception {
        Site site = new Site("empty").open();
        CrashBean bean = site.bean();
        site.rows.insert(new CrashRow("seed", "plain"));
        List<String> steps = recordSteps();

        site.rows.transaction(() -> {
            site.rows.getAll();
            site.others.getAll();
        });
        assertThat(bean.readOnlyCount()).isEqualTo(1);

        assertThat(steps).as("no record was written").doesNotContain("commit.recordWritten");
        assertThat(site.journal()).as("no journal directory").doesNotExist();

        // Positive control: a transaction that writes does write its record.
        site.rows.transaction(() -> site.rows.insert(new CrashRow("r1", "written")));
        assertThat(steps).as("a writing transaction writes its record").contains("commit.recordWritten");
        assertThat(site.journal()).as("the journal directory exists once a record was written").isDirectory();
    }

    // ===== Recovery before the first load =====

    @Test
    @DisplayName("A committed record left on disk is applied before the identity's first operator reads its table, and is then removed")
    void recoveryRunsBeforeFirstLoad() throws Exception {
        Site site = new Site("recover");
        Files.createDirectories(site.root().resolve(ROWS));
        Files.write(site.root().resolve(ROWS).resolve("r1.json"),
                "{\"id\":\"r1\",\"name\":\"stale\"}".getBytes(StandardCharsets.UTF_8));
        JsonJournal journal = new JsonJournal(site.root(), site.journal());
        journal.writeRecord(journal.nextSequence(), Kind.COMMIT, Arrays.asList(
                Entry.put(ROWS, "r1", "{\"id\":\"r1\",\"name\":\"recovered\"}"),
                Entry.put(OTHERS, "o1", "{\"id\":\"o1\",\"label\":\"recovered\"}")));

        site.open();

        assertThat(site.rows.getById("r1")).isNotNull();
        assertThat(site.rows.getById("r1").getName()).as("the record, not the stale file").isEqualTo("recovered");
        assertThat(site.others.getById("o1")).isNotNull();
        assertThat(recordNames(site.journal())).as("the applied record is removed").isEmpty();
    }

    // ===== Directly constructed operator =====

    @Test
    @DisplayName("A directly constructed operator journals its commits in <table dir>.ultitools-journal, beside the table directory")
    void standaloneOperatorJournalsInSiblingDirectory() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("standalone"));
        Path table = parent.resolve("standalone-rows");
        SimpleJsonDataOperator<CrashRow> operator = new SimpleJsonDataOperator<>(table.toString(), CrashRow.class);

        operator.transaction(() -> operator.insert(new CrashRow("s1", "alone")));

        Path sibling = parent.resolve("standalone-rows" + JsonJournal.DIRECTORY_NAME);
        assertThat(recordNames(sibling)).as("one committed record beside the table directory").hasSize(1);
        assertThat(recordNames(sibling).get(0)).endsWith(JsonJournal.COMMITTED_SUFFIX);
        if (Files.isDirectory(table)) {
            assertThat(names(table)).as("no journal file in the table directory")
                    .allMatch(name -> name.endsWith(".json"));
        }

        new JsonJournal(parent, sibling).recover();
        assertThat(table.resolve("s1.json")).as("the record names the table directory").isRegularFile();
        SimpleJsonDataOperator<CrashRow> reopened = new SimpleJsonDataOperator<>(table.toString(), CrashRow.class);
        assertThat(reopened.getById("s1")).isNotNull();
        assertThat(reopened.getById("s1").getName()).isEqualTo("alone");
    }

    // ===== @Transactional =====

    @Test
    @DisplayName("A @Transactional method on a JSON-backed container writes two tables, and a new store after a drop without flush shows both")
    void transactionalPathSurvivesReopen() throws Exception {
        Site site = new Site("transactional").open();
        CrashBean bean = site.bean();

        bean.writeBoth("r1", "o1");

        site.open();
        assertBothWritten(site);
    }

    // ===== Helpers =====

    /** Runs a two-table commit on its own thread with a crash installed at {@code step}. */
    private static Throwable crashTwoTableCommitAt(Site site, String step) throws InterruptedException {
        installCrashAt(step);
        return runOnOwnThread(() -> site.rows.transaction(() -> {
            site.rows.insert(new CrashRow("r1", "fenced"));
            site.others.insert(new CrashOther("o1", "written"));
        }));
    }

    private static void assertBothWritten(Site site) {
        assertThat(site.rows.getById("r1")).as("rows after the restart").isNotNull();
        assertThat(site.rows.getById("r1").getName()).isEqualTo("fenced");
        assertThat(site.others.getById("o1")).as("others after the restart").isNotNull();
        assertThat(site.others.getById("o1").getLabel()).isEqualTo("written");
    }

    /** Every table directory of the site holds only {@code <key>.json} files. */
    private static void assertOnlyEntityFiles(Site site) throws IOException {
        for (String tableName : Arrays.asList(ROWS, OTHERS)) {
            Path table = site.root().resolve(tableName);
            if (Files.isDirectory(table)) {
                assertThat(names(table)).as("files of %s", tableName).allMatch(name -> name.endsWith(".json")
                        && !name.endsWith(".tmp.json") && !name.startsWith("entity-"));
            }
        }
    }

    static void installCrashAt(String crashStep) {
        JsonJournal.installStepHook(step -> {
            if (step.equals(crashStep)) {
                throw new SimulatedCrash(step);
            }
        });
        JsonTransaction.installStepHook(step -> {
            if (step.equals(crashStep)) {
                throw new SimulatedCrash(step);
            }
        });
    }

    /** Installs hooks that only record the steps fired, in order. */
    static List<String> recordSteps() {
        List<String> steps = new CopyOnWriteArrayList<>();
        JsonJournal.installStepHook(steps::add);
        JsonTransaction.installStepHook(steps::add);
        return steps;
    }

    /** Runs {@code body} on a dedicated thread that is abandoned afterwards; returns what it threw. */
    static Throwable runOnOwnThread(ThrowingRunnable body) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "json-crash-recovery");
        thread.setDaemon(true);
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        assertThat(thread.isAlive()).as("the crashing operation finished").isFalse();
        return thrown.get();
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

    /**
     * {@code PluginManager.wireAop} is package-private in another package; reflection reaches the
     * production method itself, as {@code AopActivationTest.invokeWireAop} does.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void invokeWireAop(SimpleContainer context, DataScope scope) throws ReflectiveOperationException {
        Method wireAop = PluginManager.class.getDeclaredMethod("wireAop", SimpleContainer.class, DataScope.class);
        wireAop.setAccessible(true);
        wireAop.invoke(null, context, scope);
    }

    /** {@link DataScope}'s constructor is package-private in another package. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    static DataScope newDataScope(String pluginName, File dataFolder, Class<?>... owned)
            throws ReflectiveOperationException {
        Constructor<DataScope> ctor = DataScope.class.getDeclaredConstructor(String.class, File.class, java.util.Set.class);
        ctor.setAccessible(true);
        return ctor.newInstance(pluginName, dataFolder, new HashSet<>(Arrays.asList(owned)));
    }

    /** A body that may throw anything. */
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** One plugin's JSON data: a store location and an external data folder under the test's folder. */
    final class Site {
        final Path base;
        final File dataFolder;
        final DataScope scope;
        JsonStore store;
        SimpleJsonDataOperator<CrashRow> rows;
        SimpleJsonDataOperator<CrashOther> others;

        Site(String name) throws IOException, ReflectiveOperationException {
            base = Files.createDirectories(tempDir.resolve(name.replace('.', '-')));
            dataFolder = Files.createDirectories(base.resolve("plugin-data")).toFile();
            scope = newDataScope("json-crash-test", dataFolder, CrashRow.class, CrashOther.class);
        }

        /** Builds a new store over the site's folders, as a restart does; the previous one is dropped unflushed. */
        @SuppressWarnings("unchecked")
        Site open() {
            store = new JsonStore(base.resolve("store").toString());
            rows = (SimpleJsonDataOperator<CrashRow>) store.getOperator(scope, CrashRow.class);
            others = (SimpleJsonDataOperator<CrashOther>) store.getOperator(scope, CrashOther.class);
            return this;
        }

        /** The identity root of the external path: {@code <data folder>/data}. */
        Path root() {
            return dataFolder.toPath().resolve("data");
        }

        Path journal() {
            return root().resolve(JsonJournal.DIRECTORY_NAME);
        }

        /** A container bean whose @Transactional advisor is bound to this site's store. */
        CrashBean bean() throws ReflectiveOperationException {
            SimpleContainer context = new SimpleContainer();
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                UltiTools mockUltiTools = mock(UltiTools.class);
                when(mockUltiTools.getDataStore()).thenReturn(store);
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                invokeWireAop(context, scope);
            }
            context.registerBean(CrashBean.class);
            context.refresh();
            CrashBean bean = context.getBean(CrashBean.class);
            bean.rows = rows;
            bean.others = others;
            return bean;
        }
    }

    /** A test-only crash: thrown by a step hook, never caught by the code under test. */
    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String step) {
            super(step);
        }
    }

    // ===== Fixtures =====

    @Table(ROWS)
    public static class CrashRow extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public CrashRow() {
        }

        public CrashRow(String id, String name) {
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
    public static class CrashOther extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        public CrashOther() {
        }

        public CrashOther(String id, String label) {
            setId(id);
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    /** The container bean of the @Transactional cases; its operators come from the site's store. */
    public static class CrashBean {
        volatile DataOperator<CrashRow> rows;
        volatile DataOperator<CrashOther> others;

        @Transactional
        public void writeBoth(String rowId, String otherId) {
            rows.insert(new CrashRow(rowId, "fenced"));
            others.insert(new CrashOther(otherId, "written"));
        }

        @Transactional(readOnly = true)
        public int readOnlyCount() {
            return rows.getAll().size() + others.getAll().size();
        }
    }
}
