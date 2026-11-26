package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/** A single metadata read failure must not cancel an operator's deferred uninstall (#692). */
class ModuleRemovalMetadataTest {
    @TempDir
    File root;

    @ParameterizedTest
    @ValueSource(strings = {"size", "mtime"})
    void metadataFailureKeepsTheRemovalForALaterStart(String attribute) throws Exception {
        File data = ModuleUpdateFixtures.dataFolderIn(root);
        File modules = ModuleFileTransactions.modulesFolder(data);
        File jar = ModuleUpdateFixtures.moduleJar(new File(modules, "demo.jar"), "Demo", "1.0", "demo");
        ModuleFileTransactions transactions = new ModuleFileTransactions(data);
        assertThat(PendingTestValues.queueRemoval(transactions, "Demo", null, jar).queued()).isTrue();
        File records = ModuleFileTransactions.transactionsFolder(data);
        File record = records.listFiles((dir, name) -> name.endsWith(".json"))[0];
        byte[] original = Files.readAllBytes(record.toPath());
        ModuleFileTransactions start = new ModuleFileTransactions(data);

        try (MockedStatic<Files> files = failingMetadata(jar.toPath(), attribute)) {
            start.applyBeforeLoad();
        }

        assertThat(jar).exists();
        assertThat(Files.readAllBytes(record.toPath())).isEqualTo(original);
        assertThat(start.pendingReports()).hasSize(1);
        assertThat(start.pendingReports().get(0).getKey()).isEqualTo(UpmKeys.START_REMOVAL_UNREADABLE.translationKey());
        assertThat(start.pendingReports().get(0).getLevel()).isEqualTo(Level.SEVERE);
        assertThat(start.pendingReports().get(0).getArgs()[2].toString()).contains(attribute + " failed");

        ModuleFileTransactions retry = new ModuleFileTransactions(data);
        retry.applyBeforeLoad();
        assertThat(jar).doesNotExist();
        assertThat(record).doesNotExist();
        assertThat(retry.pendingReports()).extracting(ModuleFileTransactions.Report::getKey)
                .containsExactly(UpmKeys.START_REMOVAL_REMOVED.translationKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"size", "mtime"})
    void recordingMetadataFailurePreservesAnExistingRecord(String attribute) throws Exception {
        File data = ModuleUpdateFixtures.dataFolderIn(root);
        File modules = ModuleFileTransactions.modulesFolder(data);
        File first = ModuleUpdateFixtures.moduleJar(new File(modules, "first.jar"), "First", "1.0", "first");
        File second = ModuleUpdateFixtures.moduleJar(new File(modules, "second.jar"), "Second", "1.0", "second");
        ModuleFileTransactions transactions = new ModuleFileTransactions(data);
        var loaded = ModuleUpdateFixtures.loadedModule("First", "1.0", "first");
        var staged = transactions.stageUpdate("first", List.of(loaded), ignored -> first,
                ModuleUpdateFixtures.catalogue("first", "1.1"), ModuleUpdateFixtures.downloading("First", "1.1", "first"));
        assertThat(staged.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        File records = ModuleFileTransactions.transactionsFolder(data);
        File record = records.listFiles((dir, name) -> name.endsWith(".json"))[0];
        byte[] original = Files.readAllBytes(record.toPath());
        PluginInstallUtils.PendingResult result;

        try (MockedStatic<Files> files = failingMetadata(second.toPath(), attribute)) {
            result = PendingTestValues.queueRemoval(transactions, "First", "first", first, second);
        }

        assertThat(result.queued()).isFalse();
        assertThat(result.replies()).singleElement().satisfies(reply -> {
            assertThat(reply.key()).isEqualTo(UpmKeys.UNINSTALL_NOT_RECORDED);
            assertThat(PendingTestValues.values(reply.args()).getLast()).asString().contains(attribute + " failed");
        });
        assertThat(Files.readAllBytes(record.toPath())).isEqualTo(original);
        assertThat(first).exists();
        assertThat(second).exists();
    }

    private static MockedStatic<Files> failingMetadata(Path path, String attribute) throws IOException {
        MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS);
        try {
            if ("size".equals(attribute)) {
                files.when(() -> Files.size(path)).thenThrow(new IOException("size failed"));
            } else {
                files.when(() -> Files.getLastModifiedTime(path)).thenThrow(new IOException("mtime failed"));
            }
            return files;
        } catch (RuntimeException | Error failure) {
            files.close();
            throw failure;
        }
    }
}
