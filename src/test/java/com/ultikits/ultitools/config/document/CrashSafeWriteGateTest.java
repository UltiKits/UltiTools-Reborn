package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.ultikits.ultitools.config.CrashSafeYamlFiles;
import com.ultikits.ultitools.config.OperatorFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The strict publication strategy must not bypass or change the legacy YAML ownership gate. */
@Timeout(30)
class CrashSafeWriteGateTest {
    @TempDir
    Path directory;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        file = directory.resolve("kit.yml");
        Files.write(file, "items: old\n".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void resetSeams() {
        StrictYamlPublication.FILES.set(null);
        OperatorFileWriter.FAULT.set(null);
    }

    @Test
    void strictReadsNeverRunLegacyTempOrRecordedBackupMaintenance() throws Exception {
        AtomicConfigWriter.write(file, "items: old\n", new AtomicConfigWriter.FileOperations() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test refusal");
            }
        });
        Path oldTemp = directory.resolve("kit.yml.tmp-0123456789abcdef");
        Path leftover = directory.resolve("kit.yml.ultitools-stage-0123456789abcdef0123456789abcdef.tmp");
        Path foreign = directory.resolve("kit.yml.tmp-not-owned");
        Files.write(oldTemp, new byte[]{1});
        Files.write(leftover, new byte[]{2});
        Files.write(foreign, new byte[]{3});
        Path backup;
        try (Stream<Path> entries = Files.list(directory)) {
            backup = entries.filter(path -> path.getFileName().toString().contains(".ultitools-backup-")).findFirst().get();
        }
        byte[] backupBytes = Files.readAllBytes(backup);
        assertThat(edit("new")).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(edit("new")).isEqualTo(OperatorFiles.WriteResult.UNCHANGED);
        assertThat(Files.readAllBytes(oldTemp)).containsExactly(1);
        assertThat(Files.readAllBytes(leftover)).containsExactly(2);
        assertThat(Files.readAllBytes(foreign)).containsExactly(3);
        assertThat(Files.readAllBytes(backup)).isEqualTo(backupBytes);
        assertThat(ConfigDocument.load(file).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(oldTemp).doesNotExist();
        assertThat(backup).doesNotExist();
        assertThat(leftover).exists();
        assertThat(foreign).exists();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes", "mode", "owner", "group", "symlink", "directory", "deleted"})
    void finalRecheckAbandonsChangedContentKindOrPromisedMetadata(String mutation) throws Exception {
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public void force(FileChannel channel) throws IOException {
                StrictYamlPublication.Operations.super.force(channel);
                if ("bytes".equals(mutation)) {
                    Files.write(file, "items: operator\n".getBytes(StandardCharsets.UTF_8));
                } else if ("mode".equals(mutation)) {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
                } else if ("deleted".equals(mutation)) {
                    Files.delete(file);
                } else if ("symlink".equals(mutation) || "directory".equals(mutation)) {
                    Files.delete(file);
                    if ("symlink".equals(mutation)) {
                        Files.createSymbolicLink(file, directory.resolve("missing.yml"));
                    } else {
                        Files.createDirectory(file);
                    }
                }
            }

            @Override
            public java.nio.file.attribute.PosixFileAttributes posix(Path path) throws IOException {
                java.nio.file.attribute.PosixFileAttributes attributes = StrictYamlPublication.Operations.super.posix(path);
                // Deterministically vary identity metadata without requiring chown privileges on the test host.
                if (path.equals(file) && ("owner".equals(mutation) || "group".equals(mutation)) && checks++ > 0) {
                    return StrictYamlPublicationTest.changedIdentity(attributes, "owner".equals(mutation));
                }
                return attributes;
            }
            private int checks;
        });
        assertThat(CrashSafeYamlFiles.write(snapshot, Collections.singletonMap(Collections.singletonList("items"), "new")))
                .isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        if ("bytes".equals(mutation)) {
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo("items: operator\n");
        } else if ("mode".equals(mutation) || "owner".equals(mutation) || "group".equals(mutation)) {
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo("items: old\n");
        }
        try (Stream<Path> entries = Files.list(directory)) {
            assertThat(entries.filter(path -> path.getFileName().toString().contains(".ultitools-stage-")).count()).isZero();
        }
    }

    @Test
    void bothArgumentMapsAndNestedValuesAreDetachedBeforeGateIo() throws Exception {
        List<String> path = new ArrayList<>(Collections.singletonList("items"));
        List<Object> value = new ArrayList<>(Collections.singletonList("intended"));
        Map<List<String>, Object> changes = new LinkedHashMap<>();
        changes.put(path, value);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        OperatorFileWriter.FAULT.set(step -> {
            if (step == OperatorFileWriter.Step.EDIT) {
                path.set(0, "unowned");
                value.set(0, "mutated");
                changes.clear();
            }
        });
        assertThat(CrashSafeYamlFiles.write(snapshot, changes)).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(ConfigDocument.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .get(Collections.singletonList("items"))).isEqualTo(Collections.singletonList("intended"));
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("o.O", value);
        OperatorFileWriter.FAULT.set(step -> {
            if (step == OperatorFileWriter.Step.EDIT) {
                create.clear();
                value.clear();
            }
        });
        Path created = directory.resolve("new.yml");
        assertThat(CrashSafeYamlFiles.create(created.toFile(), create)).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(ConfigDocument.parse(new String(Files.readAllBytes(created), StandardCharsets.UTF_8))
                .get(Collections.singletonList("o.O"))).isEqualTo(Collections.singletonList("mutated"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingSnapshotsSerializeWithStrictAndLegacyGates(boolean legacySecond) throws Exception {
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public void force(FileChannel channel) throws IOException {
                StrictYamlPublication.Operations.super.force(channel);
                staged.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("test did not release staged write");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<OperatorFiles.WriteResult> first = workers.submit(() -> CrashSafeYamlFiles.write(snapshot,
                    Collections.singletonMap(Collections.singletonList("items"), "first")));
            assertThat(staged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<OperatorFiles.WriteResult> second = workers.submit(() -> {
                secondStarted.countDown();
                Map<List<String>, Object> values = Collections.singletonMap(Collections.singletonList("items"), "second");
                return legacySecond ? OperatorFiles.write(snapshot, values) : CrashSafeYamlFiles.write(snapshot, values);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo("items: first\n");
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private OperatorFiles.WriteResult edit(String value) throws Exception {
        return CrashSafeYamlFiles.write(OperatorFiles.read(file.toFile()),
                Collections.singletonMap(Arrays.asList("items"), value));
    }
}
