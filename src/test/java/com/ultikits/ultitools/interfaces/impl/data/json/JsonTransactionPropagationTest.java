package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Propagation;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.JsonTransactionManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * The {@code @Transactional} propagation matrix on the JSON engine (Phase 20.4, plan 10;
 * maintainer decision point b of 20.4-DECISIONS.md; 20.4-RESEARCH.md sections 12.5 and 12.6).
 * <p>
 * The beans are built in a container wired through the production {@code PluginManager.wireAop},
 * reached by reflection, with {@code UltiTools.getDataStore()} answering a real {@link JsonStore}
 * over a temporary folder. The interceptor decides every propagation from
 * {@code hasActiveTransaction}, {@code suspend} and {@code resume}, and runs a method that joins an
 * active transaction in a {@code begin}/{@code commit}-or-{@code rollback} participation scope,
 * exactly as on SQLite and MySQL; the JSON manager supplies those primitives. Self-invocation is intercepted (the proxy is a
 * subclass of the bean), so each outer method calls its inner method directly.
 * <p>
 * Every case ends with {@link #assertNothingLeftBehind}: no transaction context on the thread that
 * ran it, and no table lock held (another thread writes each table at once). Concurrency is forced
 * with latches and bounded waits; the only cross-thread steps are reads, except the final lock
 * probe, which runs after every transaction has ended.
 */
@Timeout(60)
@DisplayName("@Transactional propagation on the JSON engine behaves as on the JDBC backends (20.4-10)")
class JsonTransactionPropagationTest {

    private static final String ROWS = "propagation_rows";
    private static final String OTHERS = "propagation_others";

    /** Bounded wait for a handshake that must happen. */
    private static final long HANDSHAKE_SECONDS = 10;

    /** The lock-wait bound for these tests: a leaked lock fails the probe quickly. */
    private static final long PROBE_LOCK_WAIT_MILLIS = 2000;

    @TempDir
    Path tempDir;

    private JsonStore store;
    private DataScope scope;
    private SimpleJsonDataOperator<PropRow> rows;
    private SimpleJsonDataOperator<PropOther> others;
    private JsonTransactionManager manager;
    private PropagationBean bean;
    private ClassLevelBean classLevelBean;
    private final List<String> journalSteps = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUpServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        File dataFolder = Files.createDirectories(tempDir.resolve("plugin-data")).toFile();
        scope = newDataScope("json-propagation-test", dataFolder, PropRow.class, PropOther.class);
        store = new JsonStore(tempDir.resolve("store").toString());
        store.setLockWaitBoundMillis(PROBE_LOCK_WAIT_MILLIS);
        rows = (SimpleJsonDataOperator<PropRow>) store.getOperator(scope, PropRow.class);
        others = (SimpleJsonDataOperator<PropOther>) store.getOperator(scope, PropOther.class);
        manager = store.transactionManagerFor(scope);

        SimpleContainer context = new SimpleContainer();
        try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.getDataStore()).thenReturn(store);
            ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            invokeWireAop(context, scope);
        }
        context.registerBean(PropagationBean.class);
        context.registerBean(ClassLevelBean.class);
        context.refresh();
        bean = context.getBean(PropagationBean.class);
        bean.rows = rows;
        bean.others = others;
        bean.manager = manager;
        classLevelBean = context.getBean(ClassLevelBean.class);
        classLevelBean.rows = rows;
        classLevelBean.others = others;

        assertThat(manager.journalIdentity()).as("the bean's operators and the advisor share one identity")
                .isSameAs(store.transactionManagerFor(scope).journalIdentity());
        JsonJournal.installStepHook(journalSteps::add);
    }

    @AfterEach
    void resetStepHooks() {
        JsonJournal.resetStepHook();
        JsonTransaction.resetStepHook();
    }

    // ===== REQUIRED =====

    @Nested
    @DisplayName("REQUIRED")
    class RequiredTests {

        @Test
        @DisplayName("An inner REQUIRED method joins the outer transaction: one commit at the end carries both writes")
        void requiredJoinsTheOuterTransaction() {
            bean.outerRequiredCallsInnerRequired();

            assertThat(bean.activeInInner).as("a transaction is active inside the inner method").isTrue();
            assertThat(bean.innerTransaction).as("the inner method runs in the outer method's transaction")
                    .isNotNull().isSameAs(bean.outerTransaction);
            assertThat(name(rows.getById("req-outer"))).isEqualTo("outer");
            assertThat(label(others.getById("req-inner"))).isEqualTo("inner");
            assertThat(commitRecords()).as("commit records written").isEqualTo(1);
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("An inner joining scope that rolls back marks the outer rollback-only; the outer commit throws UnexpectedRollbackException and applies nothing")
        void innerRollbackMarksTheOuterRollbackOnly() {
            Throwable thrown = catchThrowable(bean::outerSwallowsAFailedJoiningScope);

            assertThat(thrown).as("the outer commit").isInstanceOf(UnexpectedRollbackException.class);
            assertThat(rows.getById("ro-before")).as("the outer write before the inner scope").isNull();
            assertThat(others.getById("ro-inner")).as("the inner scope's write").isNull();
            assertThat(rows.getById("ro-after")).as("the outer write after the inner scope").isNull();
            assertThat(journalSteps).as("no commit record is written for a rolled-back transaction")
                    .doesNotContain("commit.recordWritten");
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("A joined REQUIRED method's failure caught by the outer marks the transaction rollback-only; the outer commit throws UnexpectedRollbackException and applies nothing (20.4-DECISIONS.md Question 9 A)")
        void joinedRequiredMethodFailureCaughtByTheOuterMarksItRollbackOnly() {
            Throwable thrown = catchThrowable(bean::outerCatchesAJoinedRequiredFailure);

            assertThat(thrown).as("the outer commit").isInstanceOf(UnexpectedRollbackException.class);
            assertThat(rows.getById("jf-outer")).as("the outer method's write").isNull();
            assertThat(others.getById("jf-inner")).as("the failed joined method's write").isNull();
            assertThat(commitRecords()).as("commit records written").isZero();
            assertThat(journalSteps).as("no commit record is written for a rolled-back transaction")
                    .doesNotContain("commit.recordWritten");
            assertNothingLeftBehind();
        }
    }

    // ===== REQUIRES_NEW =====

    @Nested
    @DisplayName("REQUIRES_NEW")
    class RequiresNewTests {

        @Test
        @DisplayName("REQUIRES_NEW commits independently, on the outer's table and on another, and survives the outer rollback")
        void requiresNewCommitsIndependentlyOfTheOuterRollback() throws Exception {
            WorkerResult result = runPausedAfterInner(bean::outerCallsRequiresNewThenFails, () -> {
                assertThat(name(rows.getById("rn-same"))).as("the inner write to the outer's table, before the outer ends")
                        .isEqualTo("inner");
                assertThat(label(others.getById("rn-other"))).as("the inner write to another table, before the outer ends")
                        .isEqualTo("inner");
                assertThat(rows.getById("rn-outer")).as("the outer's own write, before the outer ends").isNull();
            });

            assertThat(result.thrown).as("the outer's failure").isInstanceOf(IllegalStateException.class);
            assertThat(bean.activeInInner).isTrue();
            assertThat(bean.innerTransaction).as("the inner transaction is a new one")
                    .isNotNull().isNotSameAs(bean.outerTransaction);
            assertThat(bean.transactionAfterInner).as("the outer transaction is resumed after the inner one")
                    .isSameAs(bean.outerTransaction);
            assertThat(name(rows.getById("rn-same"))).isEqualTo("inner");
            assertThat(label(others.getById("rn-other"))).isEqualTo("inner");
            assertThat(rows.getById("rn-outer")).as("the rolled-back outer write").isNull();
            assertThat(commitRecords()).as("commit records written (the inner one only)").isEqualTo(1);
            result.assertNoContextOnTheWorker();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("A failed REQUIRES_NEW rolls back alone; the outer continues and commits")
        void failedRequiresNewRollsBackAloneAndTheOuterCommits() {
            bean.outerContinuesAfterAFailedRequiresNew();

            assertThat(rows.getById("rnf-inner")).as("the failed inner transaction's write").isNull();
            assertThat(name(rows.getById("rnf-before"))).isEqualTo("outer");
            assertThat(label(others.getById("rnf-after"))).isEqualTo("outer");
            assertThat(commitRecords()).isEqualTo(1);
            assertNothingLeftBehind();
        }
    }

    // ===== NOT_SUPPORTED =====

    @Nested
    @DisplayName("NOT_SUPPORTED")
    class NotSupportedTests {

        @Test
        @DisplayName("NOT_SUPPORTED runs outside the suspended transaction: its writes, one to a table the outer locked, are visible at once and survive the outer rollback")
        void notSupportedWritesAreCommittedImmediatelyAndSurviveTheOuterRollback() throws Exception {
            WorkerResult result = runPausedAfterInner(bean::outerCallsNotSupportedThenFails, () -> {
                assertThat(name(rows.getById("ns-same"))).as("the inner write to the outer's locked table, before the outer ends")
                        .isEqualTo("inner");
                assertThat(label(others.getById("ns-other"))).as("the inner write to another table, before the outer ends")
                        .isEqualTo("inner");
                assertThat(rows.getById("ns-outer")).as("the outer's own write, before the outer ends").isNull();
            });

            assertThat(result.thrown).as("the outer's failure").isInstanceOf(IllegalStateException.class);
            assertThat(bean.activeInInner).as("a transaction is active inside the NOT_SUPPORTED method").isFalse();
            assertThat(bean.transactionAfterInner).as("the outer transaction is resumed after the inner method")
                    .isSameAs(bean.outerTransaction);
            assertThat(name(rows.getById("ns-same"))).isEqualTo("inner");
            assertThat(label(others.getById("ns-other"))).isEqualTo("inner");
            assertThat(rows.getById("ns-outer")).as("the rolled-back outer write").isNull();
            assertThat(commitRecords()).as("plain writes and a rollback write no commit record").isZero();
            result.assertNoContextOnTheWorker();
            assertNothingLeftBehind();
        }
    }

    // ===== SUPPORTS, MANDATORY, NEVER =====

    @Nested
    @DisplayName("SUPPORTS, MANDATORY and NEVER")
    class SupportsMandatoryNeverTests {

        @Test
        @DisplayName("SUPPORTS inside a transaction joins it: its write is discarded with the outer rollback")
        void supportsInsideATransactionJoinsIt() {
            Throwable thrown = catchThrowable(bean::outerCallsSupportsThenFails);

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(bean.activeInInner).isTrue();
            assertThat(bean.innerTransaction).isNotNull().isSameAs(bean.outerTransaction);
            assertThat(rows.getById("sup-outer")).isNull();
            assertThat(others.getById("sup-inner")).as("the joined SUPPORTS write").isNull();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("SUPPORTS without a transaction runs without one: a write made before it throws stays")
        void supportsWithoutATransactionRunsWithoutOne() {
            Throwable thrown = catchThrowable(() -> bean.supportsWriteThenFail("sup-alone"));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(bean.activeInInner).isFalse();
            assertThat(label(others.getById("sup-alone"))).as("the non-transactional write").isEqualTo("inner");
            assertThat(commitRecords()).isZero();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("MANDATORY without a transaction throws IllegalStateException and does not run")
        void mandatoryWithoutATransactionThrows() {
            Throwable thrown = catchThrowable(() -> bean.mandatoryWrite("man-alone"));

            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MANDATORY");
            assertThat(others.getById("man-alone")).as("the MANDATORY body did not run").isNull();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("MANDATORY inside a transaction joins it")
        void mandatoryInsideATransactionJoinsIt() {
            bean.outerCallsMandatory();

            assertThat(bean.innerTransaction).isNotNull().isSameAs(bean.outerTransaction);
            assertThat(label(others.getById("man-inner"))).isEqualTo("inner");
            assertThat(commitRecords()).isEqualTo(1);
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("NEVER inside a transaction throws IllegalStateException; the outer rolls back")
        void neverInsideATransactionThrows() {
            Throwable thrown = catchThrowable(bean::outerCallsNever);

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("NEVER");
            assertThat(others.getById("nev-inner")).as("the NEVER body did not run").isNull();
            assertThat(rows.getById("nev-outer")).as("the rolled-back outer write").isNull();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("NEVER without a transaction runs without one")
        void neverWithoutATransactionRuns() {
            bean.neverWrite("nev-alone");

            assertThat(bean.activeInInner).isFalse();
            assertThat(label(others.getById("nev-alone"))).isEqualTo("inner");
            assertNothingLeftBehind();
        }
    }

    // ===== The participant rule (20.4-13) =====

    /**
     * 20.4-DECISIONS.md Question 9, answer A: every propagation that joins an active transaction
     * (REQUIRED, SUPPORTS, MANDATORY) participates the way Spring's does by default
     * ({@code globalRollbackOnParticipationFailure = true}). The joined method's own rollback rules
     * decide whether its exception marks the transaction rollback-only; the outermost commit then
     * rolls back once and throws {@link UnexpectedRollbackException}.
     */
    @Nested
    @DisplayName("The participant rule: a joined method's failure its rules accept marks the whole transaction (20.4-13)")
    class ParticipationRuleTests {

        @Test
        @DisplayName("A joined SUPPORTS method's failure caught by the outer marks the transaction: UnexpectedRollbackException, nothing applied")
        void joinedSupportsFailureCaughtByTheOuterMarksIt() {
            Throwable thrown = catchThrowable(bean::prOuterCatchesAJoinedSupportsFailure);

            assertThat(thrown).as("the outer commit").isInstanceOf(UnexpectedRollbackException.class);
            assertThat(rows.getById("pr-sup-outer")).as("the outer method's write").isNull();
            assertThat(others.getById("pr-sup-inner")).as("the failed SUPPORTS method's write").isNull();
            assertThat(commitRecords()).isZero();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("A joined MANDATORY method's failure caught by the outer marks the transaction: UnexpectedRollbackException, nothing applied")
        void joinedMandatoryFailureCaughtByTheOuterMarksIt() {
            Throwable thrown = catchThrowable(bean::prOuterCatchesAJoinedMandatoryFailure);

            assertThat(thrown).as("the outer commit").isInstanceOf(UnexpectedRollbackException.class);
            assertThat(rows.getById("pr-man-outer")).as("the outer method's write").isNull();
            assertThat(others.getById("pr-man-inner")).as("the failed MANDATORY method's write").isNull();
            assertThat(commitRecords()).isZero();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("A joined failure matching the inner method's rollbackFor (a checked exception) marks the transaction: UnexpectedRollbackException, nothing applied")
        void joinedFailureMatchingTheInnerRollbackForMarksIt() {
            Throwable thrown = catchThrowable(bean::prOuterCatchesAMatchingRollbackForFailure);

            assertThat(thrown).as("the outer commit").isInstanceOf(UnexpectedRollbackException.class);
            assertThat(rows.getById("pr-rbf-outer")).as("the outer method's write").isNull();
            assertThat(others.getById("pr-rbf-inner")).as("the failed joined method's write").isNull();
            assertThat(commitRecords()).isZero();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("A joined checked failure under the default rules leaves the transaction committable: both writes applied")
        void joinedCheckedFailureUnderDefaultRulesLeavesItCommittable() {
            bean.prOuterCatchesACheckedFailureUnderDefaultRules();

            assertThat(name(rows.getById("pr-chk-outer"))).isEqualTo("outer");
            assertThat(label(others.getById("pr-chk-inner"))).isEqualTo("inner");
            assertThat(commitRecords()).isEqualTo(1);
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("A joined failure listed in the inner method's noRollbackFor leaves the transaction committable: both writes applied")
        void joinedFailureInTheInnerNoRollbackForLeavesItCommittable() {
            bean.prOuterCatchesANoRollbackForFailure();

            assertThat(name(rows.getById("pr-nrf-outer"))).isEqualTo("outer");
            assertThat(label(others.getById("pr-nrf-inner"))).isEqualTo("inner");
            assertThat(commitRecords()).isEqualTo(1);
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("A joined failure that leaves the outer method rolls everything back and reaches the caller as the original exception instance")
        void joinedFailureLeavingTheOuterRollsBackWithTheOriginalException() {
            Throwable thrown = catchThrowable(bean::prOuterLetsAJoinedFailureLeave);

            assertThat(thrown).as("the caller receives the inner method's exception itself")
                    .isNotNull().isSameAs(bean.lastInnerFailure);
            assertThat(rows.getById("pr-leave-outer")).as("the outer method's write").isNull();
            assertThat(others.getById("pr-leave-inner")).as("the failed joined method's write").isNull();
            assertThat(commitRecords()).isZero();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("The outer commit of a marked transaction completes it once: no 'no active' WARNING after a joined failure or a failed nested transaction(...) scope")
        void outerCommitOfAMarkedTransactionCompletesOnce() {
            Throwable[] joined = new Throwable[1];
            List<String> afterJoinedFailure = warningsDuring(
                    () -> joined[0] = catchThrowable(bean::prOuterCatchesAJoinedRequiredFailure));
            assertThat(joined[0]).as("the outer call after a joined failure")
                    .isInstanceOf(UnexpectedRollbackException.class);
            assertThat(afterJoinedFailure).as("JSON manager WARNINGs after a joined failure")
                    .noneMatch(message -> message.contains("no active"));
            assertNothingLeftBehind();

            Throwable[] nested = new Throwable[1];
            List<String> afterNestedScope = warningsDuring(
                    () -> nested[0] = catchThrowable(bean::prOuterSwallowsAFailedNestedScope));
            assertThat(nested[0]).as("the outer call after a failed nested scope")
                    .isInstanceOf(UnexpectedRollbackException.class);
            assertThat(afterNestedScope).as("JSON manager WARNINGs after a failed nested scope")
                    .noneMatch(message -> message.contains("no active"));

            assertThat(rows.getById("pr-once-joined-outer")).isNull();
            assertThat(others.getById("pr-once-joined-inner")).isNull();
            assertThat(rows.getById("pr-once-scope-outer")).isNull();
            assertThat(others.getById("pr-once-scope-inner")).isNull();
            assertThat(commitRecords()).isZero();
            assertNothingLeftBehind();
        }

        @Test
        @DisplayName("An outer method whose own noRollbackFor commits despite its exception after a mark: the caller receives UnexpectedRollbackException with that exception as suppressed")
        void outerThatCommitsDespiteItsOwnExceptionAfterAMarkThrowsUnexpectedRollback() {
            Throwable thrown = catchThrowable(bean::prOuterCommitsDespiteItsOwnExceptionAfterAMark);

            assertThat(thrown).as("the outer call").isInstanceOf(UnexpectedRollbackException.class);
            assertThat(thrown.getSuppressed()).as("the outer method's own exception, kept as suppressed")
                    .containsExactly(bean.lastOuterFailure);
            assertThat(rows.getById("pr-own-outer")).as("the outer method's write").isNull();
            assertThat(others.getById("pr-own-inner")).as("the failed joined method's write").isNull();
            assertThat(commitRecords()).isZero();
            assertNothingLeftBehind();
        }

        /**
         * Runs {@code action} while a handler on {@link JsonTransactionManager}'s logger records
         * the messages of WARNING and more severe records; the handler is removed afterwards.
         */
        private List<String> warningsDuring(Runnable action) {
            Logger logger = Logger.getLogger(JsonTransactionManager.class.getName());
            WarningRecorder recorder = new WarningRecorder();
            logger.addHandler(recorder);
            try {
                action.run();
            } finally {
                logger.removeHandler(recorder);
            }
            return recorder.messages;
        }
    }

    // ===== Class-level @Transactional =====

    @Nested
    @DisplayName("@Transactional on the class")
    class ClassLevelTests {

        @Test
        @DisplayName("A class-level @Transactional applies to its methods: a throw after two writes keeps neither, a normal return commits both")
        void classLevelTransactionalAppliesToMethods() {
            Throwable thrown = catchThrowable(() -> classLevelBean.writeBothThenFail("cl-1"));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(rows.getById("cl-1")).as("the first write of the failed method").isNull();
            assertThat(others.getById("cl-1")).as("the second write of the failed method").isNull();

            classLevelBean.writeBoth("cl-2");

            assertThat(name(rows.getById("cl-2"))).isEqualTo("class-level");
            assertThat(label(others.getById("cl-2"))).isEqualTo("class-level");
            assertThat(commitRecords()).as("one commit record, for the succeeding method").isEqualTo(1);
            assertNothingLeftBehind();
        }
    }

    // ===== The documented difference from JDBC =====

    /**
     * Pins the one documented difference from the JDBC backends (20.4-RESEARCH.md section 12.5).
     * JSON table locks belong to the thread, so a {@code REQUIRES_NEW} or {@code NOT_SUPPORTED}
     * body on the same thread re-enters the locks of its suspended caller instead of waiting for
     * them. When that body writes a key its suspended caller has already staged, the inner write
     * is applied at once, and the caller's later commit replaces it: the caller's value is the one
     * that stays. On SQLite or MySQL the same code would make the inner call wait, on its own other
     * connection, for the row lock its own suspended caller holds, until the lock-wait timeout.
     * <p>
     * The lock-wait bound is raised to 30 seconds here, so an inner call that waited would miss
     * the 10-second handshake rather than fail fast.
     */
    @Nested
    @DisplayName("A same-thread inner call writing a key its suspended caller staged (the documented difference from JDBC)")
    class SameThreadSameKeyTests {

        @Test
        @DisplayName("REQUIRES_NEW: the inner write does not wait and is visible at once; after the outer commit the outer's value stays")
        void sameThreadRequiresNewOnAKeyTheCallerStaged() throws Exception {
            assertSameKeyOutcome(() -> bean.outerStagesKeyThenRequiresNew("x"), 2);
        }

        @Test
        @DisplayName("NOT_SUPPORTED: the inner write does not wait and is visible at once; after the outer commit the outer's value stays")
        void sameThreadNotSupportedOnAKeyTheCallerStaged() throws Exception {
            assertSameKeyOutcome(() -> bean.outerStagesKeyThenNotSupported("x"), 1);
        }

        private void assertSameKeyOutcome(Runnable outer, int expectedCommitRecords) throws Exception {
            rows.insert(new PropRow("x", "initial"));
            store.setLockWaitBoundMillis(TimeUnit.SECONDS.toMillis(30));

            WorkerResult result = runPausedAfterInner(outer, () ->
                    assertThat(name(rows.getById("x")))
                            .as("x read by another thread right after the inner call returned").isEqualTo("inner"));

            assertThat(result.thrown).as("the outer method's failure, if any").isNull();
            assertThat(name(rows.getById("x"))).as("x after the outer commit: the caller's value stays")
                    .isEqualTo("outer");
            assertThat(commitRecords()).isEqualTo(expectedCommitRecords);
            result.assertNoContextOnTheWorker();
            assertNothingLeftBehind();
        }
    }

    // ===== Helpers =====

    /** Records the messages of WARNING and more severe log records. */
    static final class WarningRecorder extends Handler {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                messages.add(String.valueOf(logRecord.getMessage()));
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    /** What a worker thread observed after running an outer method. */
    private final class WorkerResult {
        Throwable thrown;
        boolean activeAfter;
        int depthAfter;
        JsonTransaction transactionAfter;

        void assertNoContextOnTheWorker() {
            assertThat(activeAfter).as("a transaction is still active on the worker").isFalse();
            assertThat(depthAfter).as("the worker's transaction depth").isZero();
            assertThat(transactionAfter).as("the worker's current transaction").isNull();
        }
    }

    /**
     * Runs {@code outer} on a worker thread whose bean pauses right after its inner call returns;
     * while it is paused, runs {@code duringPause} on this thread (reads only), then releases it.
     */
    // PMD.AvoidCatchingThrowable: the worker hands any failure, an assertion error included, back
    // to the test thread, which asserts on it.
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private WorkerResult runPausedAfterInner(Runnable outer, Runnable duringPause) throws InterruptedException {
        CountDownLatch innerReturned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        bean.afterInner = () -> {
            innerReturned.countDown();
            awaitOrFail(release, "the test releases the outer method");
        };
        WorkerResult result = new WorkerResult();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                outer.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
            result.activeAfter = manager.hasActiveTransaction();
            result.depthAfter = manager.getTransactionDepth();
            result.transactionAfter = manager.currentTransaction();
        }, "json-propagation-outer");
        worker.setDaemon(true);
        worker.start();
        try {
            assertThat(innerReturned.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS))
                    .as("the inner call returned without waiting").isTrue();
            duringPause.run();
        } finally {
            release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        }
        assertThat(worker.isAlive()).as("the outer method finished").isFalse();
        result.thrown = thrown.get();
        return result;
    }

    /**
     * No transaction context remains on this thread, and no table lock is held: another thread
     * writes each table within the short lock-wait bound.
     */
    // PMD.AvoidCatchingThrowable: the probe thread hands any failure back to the test thread.
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    private void assertNothingLeftBehind() {
        assertThat(manager.hasActiveTransaction()).as("a transaction is still active on the test thread").isFalse();
        assertThat(manager.getTransactionDepth()).as("the test thread's transaction depth").isZero();
        assertThat(manager.currentTransaction()).as("the test thread's current transaction").isNull();

        store.setLockWaitBoundMillis(PROBE_LOCK_WAIT_MILLIS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread probe = new Thread(() -> {
            try {
                rows.insert(new PropRow("probe", "probe"));
                others.insert(new PropOther("probe", "probe"));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "json-propagation-lock-probe");
        probe.start();
        try {
            probe.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the lock probe", e);
        }
        assertThat(probe.isAlive()).as("the lock probe finished").isFalse();
        assertThat(failure.get()).as("another thread's write to each table (a held lock makes it time out)").isNull();
    }

    private int commitRecords() {
        int count = 0;
        for (String step : journalSteps) {
            if ("commit.renamed".equals(step)) {
                count++;
            }
        }
        return count;
    }

    private static String name(PropRow row) {
        assertThat(row).as("the row read back").isNotNull();
        return row.getName();
    }

    private static String label(PropOther other) {
        assertThat(other).as("the row read back").isNotNull();
        return other.getLabel();
    }

    static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting until " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting until " + what, e);
        }
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
    private static DataScope newDataScope(String pluginName, File dataFolder, Class<?>... owned)
            throws ReflectiveOperationException {
        Constructor<DataScope> ctor = DataScope.class.getDeclaredConstructor(String.class, File.class, java.util.Set.class);
        ctor.setAccessible(true);
        return ctor.newInstance(pluginName, dataFolder, new HashSet<>(Arrays.asList(owned)));
    }

    // ===== Fixtures =====

    @Table(ROWS)
    public static class PropRow extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("name")
        private String name;

        public PropRow() {
        }

        public PropRow(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @Table(OTHERS)
    public static class PropOther extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("label")
        private String label;

        public PropOther() {
        }

        public PropOther(String id, String label) {
            setId(id);
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** A checked exception the participant-rule fixtures throw. */
    public static class CheckedTestException extends Exception {
        private static final long serialVersionUID = 1L;

        public CheckedTestException(String message) {
            super(message);
        }
    }

    /**
     * The propagation fixture. Every outer method calls its inner method on itself; the proxy is
     * the bean's subclass, so the call is intercepted. The inner methods record what they saw.
     */
    public static class PropagationBean {
        volatile SimpleJsonDataOperator<PropRow> rows;
        volatile SimpleJsonDataOperator<PropOther> others;
        volatile JsonTransactionManager manager;
        volatile Runnable afterInner = () -> { };

        volatile JsonTransaction outerTransaction;
        volatile JsonTransaction innerTransaction;
        volatile JsonTransaction transactionAfterInner;
        volatile Boolean activeInInner;
        volatile RuntimeException lastInnerFailure;
        volatile IllegalArgumentException lastOuterFailure;

        private void recordOuter() {
            outerTransaction = manager.currentTransaction();
        }

        private void recordInner() {
            activeInInner = manager.hasActiveTransaction();
            innerTransaction = manager.currentTransaction();
        }

        // --- REQUIRED ---

        @Transactional
        public void outerRequiredCallsInnerRequired() {
            recordOuter();
            rows.insert(new PropRow("req-outer", "outer"));
            innerRequired();
        }

        @Transactional
        public void innerRequired() {
            recordInner();
            others.insert(new PropOther("req-inner", "inner"));
        }

        /**
         * Runs a joining scope ({@code DataOperator.transaction}, which joins the active
         * transaction as REQUIRED does) that fails and rolls back, swallows its failure, and
         * returns normally.
         */
        @Transactional
        public void outerSwallowsAFailedJoiningScope() {
            rows.insert(new PropRow("ro-before", "outer"));
            try {
                rows.transaction((Runnable) () -> {
                    others.insert(new PropOther("ro-inner", "inner"));
                    throw new IllegalStateException("the inner joining scope fails");
                });
            } catch (IllegalStateException expected) {
                // The outer continues; the inner rollback has marked it rollback-only.
            }
            rows.insert(new PropRow("ro-after", "outer"));
        }

        @Transactional
        public void outerCatchesAJoinedRequiredFailure() {
            rows.insert(new PropRow("jf-outer", "outer"));
            try {
                innerRequiredWritesThenFails();
            } catch (IllegalStateException expected) {
                // The joined method's failure has marked the transaction rollback-only.
            }
        }

        @Transactional
        public void innerRequiredWritesThenFails() {
            others.insert(new PropOther("jf-inner", "inner"));
            throw new IllegalStateException("the joined REQUIRED method fails");
        }

        // --- The participant rule (20.4-13); every id starts with "pr-" ---

        @Transactional
        public void prOuterCatchesAJoinedSupportsFailure() {
            rows.insert(new PropRow("pr-sup-outer", "outer"));
            try {
                prInnerSupportsWritesThenFails("pr-sup-inner");
            } catch (IllegalStateException expected) {
                // The joined SUPPORTS method's failure has marked the transaction rollback-only.
            }
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public void prInnerSupportsWritesThenFails(String id) {
            others.insert(new PropOther(id, "inner"));
            throw new IllegalStateException("the joined SUPPORTS method fails");
        }

        @Transactional
        public void prOuterCatchesAJoinedMandatoryFailure() {
            rows.insert(new PropRow("pr-man-outer", "outer"));
            try {
                prInnerMandatoryWritesThenFails("pr-man-inner");
            } catch (IllegalStateException expected) {
                // The joined MANDATORY method's failure has marked the transaction rollback-only.
            }
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public void prInnerMandatoryWritesThenFails(String id) {
            others.insert(new PropOther(id, "inner"));
            throw new IllegalStateException("the joined MANDATORY method fails");
        }

        @Transactional
        public void prOuterCatchesAMatchingRollbackForFailure() {
            rows.insert(new PropRow("pr-rbf-outer", "outer"));
            try {
                prInnerRollbackForWritesThenFails("pr-rbf-inner");
            } catch (CheckedTestException expected) {
                // rollbackFor accepts it: the transaction is marked rollback-only.
            }
        }

        @Transactional(rollbackFor = CheckedTestException.class)
        public void prInnerRollbackForWritesThenFails(String id) throws CheckedTestException {
            others.insert(new PropOther(id, "inner"));
            throw new CheckedTestException("the joined method fails with a rollbackFor type");
        }

        @Transactional
        public void prOuterCatchesACheckedFailureUnderDefaultRules() {
            rows.insert(new PropRow("pr-chk-outer", "outer"));
            try {
                prInnerDefaultRulesWritesThenFailsChecked("pr-chk-inner");
            } catch (CheckedTestException expected) {
                // The default rules do not accept a checked exception: the transaction stays committable.
            }
        }

        @Transactional
        public void prInnerDefaultRulesWritesThenFailsChecked(String id) throws CheckedTestException {
            others.insert(new PropOther(id, "inner"));
            throw new CheckedTestException("the joined method fails with a checked exception");
        }

        @Transactional
        public void prOuterCatchesANoRollbackForFailure() {
            rows.insert(new PropRow("pr-nrf-outer", "outer"));
            try {
                prInnerNoRollbackForWritesThenFails("pr-nrf-inner");
            } catch (IllegalStateException expected) {
                // noRollbackFor lists it: the transaction stays committable.
            }
        }

        @Transactional(noRollbackFor = IllegalStateException.class)
        public void prInnerNoRollbackForWritesThenFails(String id) {
            others.insert(new PropOther(id, "inner"));
            throw new IllegalStateException("the joined method fails with a noRollbackFor type");
        }

        @Transactional
        public void prOuterLetsAJoinedFailureLeave() {
            rows.insert(new PropRow("pr-leave-outer", "outer"));
            prInnerRequiredWritesThenFails("pr-leave-inner");
        }

        @Transactional
        public void prOuterCatchesAJoinedRequiredFailure() {
            rows.insert(new PropRow("pr-once-joined-outer", "outer"));
            try {
                prInnerRequiredWritesThenFails("pr-once-joined-inner");
            } catch (IllegalStateException expected) {
                // The joined method's failure has marked the transaction rollback-only.
            }
        }

        @Transactional
        public void prInnerRequiredWritesThenFails(String id) {
            others.insert(new PropOther(id, "inner"));
            IllegalStateException failure = new IllegalStateException("the joined REQUIRED method fails");
            lastInnerFailure = failure;
            throw failure;
        }

        @Transactional
        public void prOuterSwallowsAFailedNestedScope() {
            rows.insert(new PropRow("pr-once-scope-outer", "outer"));
            try {
                rows.transaction((Runnable) () -> {
                    others.insert(new PropOther("pr-once-scope-inner", "inner"));
                    throw new IllegalStateException("the nested transaction(...) scope fails");
                });
            } catch (IllegalStateException expected) {
                // The nested scope's rollback has marked the transaction rollback-only.
            }
        }

        @Transactional(noRollbackFor = IllegalArgumentException.class)
        public void prOuterCommitsDespiteItsOwnExceptionAfterAMark() {
            rows.insert(new PropRow("pr-own-outer", "outer"));
            try {
                prInnerRequiredWritesThenFails("pr-own-inner");
            } catch (IllegalStateException expected) {
                // The joined method's failure has marked the transaction rollback-only.
            }
            IllegalArgumentException failure = new IllegalArgumentException("the outer method fails after the mark");
            lastOuterFailure = failure;
            throw failure;
        }

        // --- REQUIRES_NEW ---

        @Transactional
        public void outerCallsRequiresNewThenFails() {
            recordOuter();
            rows.insert(new PropRow("rn-outer", "outer"));
            innerRequiresNew();
            transactionAfterInner = manager.currentTransaction();
            afterInner.run();
            throw new IllegalStateException("the outer rolls back after its REQUIRES_NEW call");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void innerRequiresNew() {
            recordInner();
            rows.insert(new PropRow("rn-same", "inner"));
            others.insert(new PropOther("rn-other", "inner"));
        }

        @Transactional
        public void outerContinuesAfterAFailedRequiresNew() {
            rows.insert(new PropRow("rnf-before", "outer"));
            try {
                innerRequiresNewWritesThenFails();
            } catch (IllegalStateException expected) {
                // The inner transaction rolled back alone; the outer continues.
            }
            others.insert(new PropOther("rnf-after", "outer"));
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void innerRequiresNewWritesThenFails() {
            rows.insert(new PropRow("rnf-inner", "inner"));
            throw new IllegalStateException("the REQUIRES_NEW method fails");
        }

        // --- NOT_SUPPORTED ---

        @Transactional
        public void outerCallsNotSupportedThenFails() {
            recordOuter();
            rows.insert(new PropRow("ns-outer", "outer"));
            innerNotSupported();
            transactionAfterInner = manager.currentTransaction();
            afterInner.run();
            throw new IllegalStateException("the outer rolls back after its NOT_SUPPORTED call");
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void innerNotSupported() {
            recordInner();
            rows.insert(new PropRow("ns-same", "inner"));
            others.insert(new PropOther("ns-other", "inner"));
        }

        // --- SUPPORTS ---

        @Transactional
        public void outerCallsSupportsThenFails() {
            recordOuter();
            rows.insert(new PropRow("sup-outer", "outer"));
            supportsWrite("sup-inner");
            throw new IllegalStateException("the outer rolls back after its SUPPORTS call");
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public void supportsWrite(String id) {
            recordInner();
            others.insert(new PropOther(id, "inner"));
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public void supportsWriteThenFail(String id) {
            recordInner();
            others.insert(new PropOther(id, "inner"));
            throw new IllegalStateException("the SUPPORTS method fails");
        }

        // --- MANDATORY ---

        @Transactional
        public void outerCallsMandatory() {
            recordOuter();
            mandatoryWrite("man-inner");
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public void mandatoryWrite(String id) {
            recordInner();
            others.insert(new PropOther(id, "inner"));
        }

        // --- NEVER ---

        @Transactional
        public void outerCallsNever() {
            rows.insert(new PropRow("nev-outer", "outer"));
            neverWrite("nev-inner");
        }

        @Transactional(propagation = Propagation.NEVER)
        public void neverWrite(String id) {
            recordInner();
            others.insert(new PropOther(id, "inner"));
        }

        // --- The same key, written by the caller and by a same-thread inner call ---

        @Transactional
        public void outerStagesKeyThenRequiresNew(String key) {
            rows.update(new PropRow(key, "outer"));
            requiresNewWritesKey(key);
            afterInner.run();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNewWritesKey(String key) {
            rows.update(new PropRow(key, "inner"));
        }

        @Transactional
        public void outerStagesKeyThenNotSupported(String key) {
            rows.update(new PropRow(key, "outer"));
            notSupportedWritesKey(key);
            afterInner.run();
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void notSupportedWritesKey(String key) {
            rows.update(new PropRow(key, "inner"));
        }
    }

    /** {@code @Transactional} on the class only; no method carries it. */
    @Transactional
    public static class ClassLevelBean {
        volatile SimpleJsonDataOperator<PropRow> rows;
        volatile SimpleJsonDataOperator<PropOther> others;

        public void writeBoth(String id) {
            rows.insert(new PropRow(id, "class-level"));
            others.insert(new PropOther(id, "class-level"));
        }

        public void writeBothThenFail(String id) {
            writeBoth(id);
            throw new IllegalStateException("the class-level transactional method fails");
        }
    }
}
