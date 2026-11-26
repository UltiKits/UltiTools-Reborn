package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.namesIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * A denied file access -- a {@link SecurityException} from a file-system call -- while staging an
 * update or writing a transaction record takes a checked failure path (Codex, #561): {@code /upm
 * update} returns a failure result, never an exception, and discards only the staging it created
 * itself. A failed uninstall write or revoke delete reports the refusal and keeps the existing
 * module files and journal intact.
 */
@DisplayName("A denied file access in staging or a record write is reported through the checked path (Codex, #561)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateDeniedRecordWriteTest {

    @TempDir
    File serverRoot;

    private File modules;
    private File transactions;
    private File oldJar;
    private UltiToolsPlugin loadedOld;

    @BeforeEach
    void setUp() throws IOException {
        File dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        transactions = ModuleFileTransactions.transactionsFolder(dataFolder);
        oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        loadedOld = loadedModule("Demo", "1.0", "demo");
    }

    /** Transactions whose record write is denied just before the record is moved into place. */
    private ModuleFileTransactions denyingRecordWrites() {
        return new ModuleFileTransactions(modules, transactions, ModuleFileTransactions.FileOps.DEFAULT, reached -> {
            if (ModuleFileTransactions.CrashPoints.BEFORE_RECORD_MOVED.equals(reached)) {
                throw new SecurityException("injected: access denied");
            }
        });
    }

    private ModuleFileTransactions plain() {
        return new ModuleFileTransactions(modules, transactions, ModuleFileTransactions.FileOps.DEFAULT,
                ModuleFileTransactions.CrashPoints.NONE);
    }

    @Test
    @DisplayName("/upm update: a denied record write returns a failure and leaves no record and no staged JAR")
    void stagingWithADeniedRecordWrite_failsAndDiscardsTheStaging() throws IOException {
        ModuleFileTransactions.StageResult[] result = new ModuleFileTransactions.StageResult[1];

        Throwable thrown = catchThrowable(() -> result[0] = denyingRecordWrites().stageUpdate("demo",
                Collections.singletonList(loadedOld), new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar),
                catalogue("demo", "1.1"), downloading("Demo", "1.1", "demo")));

        assertThat(thrown).as("the command gets a result, not an exception").isNull();
        assertThat(result[0].getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result[0].getReasonKey()).isEqualTo(UpmKeys.REASON_RECORD_FAILED);
        assertThat(String.valueOf(PendingTestValues.values(List.of(result[0].getReasonArgs())).getFirst())).contains("injected: access denied");
        assertThat(treeOf(transactions)).as("no record, no staged JAR, no temporary file").isEmpty();
        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
    }

    @Test
    @DisplayName("/upm update: a denied access while checking, before anything is staged, returns a failure and reserves nothing")
    void stagingDeniedWhileChecking_failsAndReservesNothing() throws IOException {
        java.util.function.Function<UltiToolsPlugin, File> denying = module -> {
            throw new SecurityException("injected: access denied");
        };

        ModuleFileTransactions.StageResult denied = plain().stageUpdate("demo", Collections.singletonList(loadedOld),
                denying, catalogue("demo", "1.1"), downloading("Demo", "1.1", "demo"));
        ModuleFileTransactions.StageResult retried = plain().stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                downloading("Demo", "1.1", "demo"));

        assertThat(denied.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(denied.getReasonKey()).isEqualTo(UpmKeys.REASON_ACCESS_DENIED);
        assertThat(String.valueOf(PendingTestValues.values(List.of(denied.getReasonArgs())).getFirst())).contains("injected: access denied");
        assertThat(retried.getOutcome()).as("the denied attempt left no download reserved")
                .isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
    }

    @Test
    @DisplayName("/upm update: a denied access after the checks, before the download, returns a failure and leaves nothing")
    void stagingDeniedBeforeTheDownload_failsAndLeavesNothing() throws IOException {
        List<String> downloads = new java.util.ArrayList<>();

        ModuleFileTransactions.StageResult denied = plain().stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                (link, name, folder) -> downloads.add(name), module -> {
                    throw new SecurityException("injected: access denied");
                });

        assertThat(denied.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(denied.getReasonKey()).isEqualTo(UpmKeys.REASON_ACCESS_DENIED);
        assertThat(downloads).isEmpty();
        assertThat(treeOf(transactions)).isEmpty();
        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
    }

    @Test
    @DisplayName("an uninstall reports a denied journal write and leaves the module JAR in place")
    void queueRemovalWithADeniedWrite_returnsARefusal() throws IOException {
        var result = PendingTestValues.queueRemoval(denyingRecordWrites(), "Demo", "demo", oldJar);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies()).singleElement().satisfies(reply -> {
            assertThat(reply.key()).isEqualTo(UpmKeys.UNINSTALL_NOT_RECORDED);
            assertThat(PendingTestValues.values(reply.args()).getLast()).asString().contains("injected: access denied");
        });
        assertThat(oldJar).exists();
        assertThat(treeOf(transactions)).isEmpty();
    }

    @Test
    @DisplayName("revoking a removal whose record cannot be deleted keeps the full record")
    void revokeRemovalWithADeniedDelete_reportsTheFailure() throws IOException {
        var journal = plain();
        assertThat(PendingTestValues.queueRemoval(journal, "Demo", "demo", oldJar).queued()).isTrue();
        var pending = new PendingChanges(journal);
        var entry = pending.list().getFirst();
        var record = entry.recordFile().toPath();
        byte[] original = Files.readAllBytes(record);

        try (var files = Mockito.mockStatic(Files.class, invocation -> {
            if ("delete".equals(invocation.getMethod().getName()) && record.equals(invocation.getArgument(0))) {
                throw new SecurityException("injected: access denied");
            }
            return invocation.callRealMethod();
        })) {
            var result = pending.revokeByModule("Demo");
            assertThat(result.outcome()).isEqualTo(PendingChanges.RevokeResult.Outcome.FAILED);
            assertThat(result.error()).contains("injected: access denied");
        }
        assertThat(Files.readAllBytes(record)).isEqualTo(original);
        assertThat(pending.list()).containsExactly(entry);
        assertThat(oldJar).exists();
    }
}
