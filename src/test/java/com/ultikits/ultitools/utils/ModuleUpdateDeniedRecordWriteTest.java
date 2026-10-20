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
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * A denied file access -- a {@link SecurityException} from a file-system call -- while staging an
 * update or writing a transaction record takes a checked failure path (Codex, #561): {@code /upm
 * update} returns a failure result, never an exception, and discards only the staging it created
 * itself; the two public record operations throw {@code IOException}, which their callers already
 * handle.
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
        assertThat(result[0].getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_RECORD_FAILED);
        assertThat(String.valueOf(result[0].getReasonArgs()[0])).contains("injected: access denied");
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
        assertThat(denied.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_ACCESS_DENIED);
        assertThat(String.valueOf(denied.getReasonArgs()[0])).contains("injected: access denied");
        assertThat(retried.getOutcome()).as("the denied attempt left no download reserved")
                .isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
    }

    @Test
    @DisplayName("/upm update: a denied access after the checks, before the download, returns a failure and leaves nothing")
    void stagingDeniedBeforeTheDownload_failsAndLeavesNothing() throws IOException {
        java.util.List<String> downloads = new java.util.ArrayList<>();

        ModuleFileTransactions.StageResult denied = plain().stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                (link, name, folder) -> downloads.add(name), module -> {
                    throw new SecurityException("injected: access denied");
                });

        assertThat(denied.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(denied.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_ACCESS_DENIED);
        assertThat(downloads).isEmpty();
        assertThat(treeOf(transactions)).isEmpty();
        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
    }

    @Test
    @DisplayName("recording a deferred deletion: a denied record write throws IOException")
    void recordDeferredRemovalWithADeniedWrite_throwsIOException() {
        Throwable thrown = catchThrowable(() -> denyingRecordWrites().recordDeferredRemoval("Demo",
                Collections.singletonList(oldJar)));

        assertThat(thrown).isInstanceOf(IOException.class).hasCauseInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("forgetting a deferred deletion on install: a denied record write throws IOException")
    void forgetDeferredRemovalWithADeniedWrite_throwsIOException() throws IOException {
        File companion = moduleJar(new File(modules, "demo-extra.jar"), "Demo", "1.0", "demo");
        plain().recordDeferredRemoval("Demo", Arrays.asList(oldJar, companion));

        Throwable thrown = catchThrowable(() -> denyingRecordWrites().forgetDeferredRemoval("demo-1.0.jar"));

        assertThat(thrown).isInstanceOf(IOException.class).hasCauseInstanceOf(SecurityException.class);
    }
}
