package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plan 17-56 Task 3: a config file is replaced only by a complete new file (#574). A failure at any step -
 * creating the temporary file, writing it, forcing it to disk, moving it - leaves the target byte-identical and
 * no temporary file behind; eligible atomic-replace failures use a forced backup before an in-place write.
 */
@DisplayName("AtomicConfigWriter - replace a file completely or not at all")
class AtomicConfigWriterTest {

    private static final String OLD = "old: content\n";
    private static final String NEW = "new: content\nsecond: line\n";

    @TempDir
    Path tempDir;

    private Path target;
    private final List<LogRecord> records = new ArrayList<>();
    private final Logger logger = Logger.getLogger(AtomicConfigWriter.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Level previousLevel;

    @BeforeEach
    void setUp() throws IOException {
        target = tempDir.resolve("config.yml");
        Files.write(target, OLD.getBytes(StandardCharsets.UTF_8));
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest(name = "temporary identity: {0}")
    @ValueSource(strings = {"normal", "parent-link", "target-link", "missing-parent"})
    void stagedIdentitySurvivesEveryPathSpelling(String shape) throws Exception {
        Path spelled = target;
        if ("parent-link".equals(shape)) {
            Path alias = tempDir.resolve("alias");
            Files.createSymbolicLink(alias, tempDir);
            spelled = alias.resolve(target.getFileName());
        } else if ("target-link".equals(shape)) {
            spelled = tempDir.resolve("linked.yml");
            Files.createSymbolicLink(spelled, target);
        } else if ("missing-parent".equals(shape)) {
            Path absent = tempDir.resolve("missing/deeper/config.yml");
            assertThatThrownBy(() -> AtomicConfigWriter.stage(absent, NEW)).isInstanceOf(IOException.class);
            assertThat(ConfigDocument.load(absent).state()).isEqualTo(ConfigLoadResult.State.ABSENT);
            assertThat(Files.exists(absent.getParent())).isFalse();
            return;
        }
        AtomicConfigWriter.StagedWrite staged = AtomicConfigWriter.stage(spelled, NEW);
        try {
            assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);
            assertThat(ConfigDocument.load(spelled).state()).isEqualTo(ConfigLoadResult.State.LOADED);
            assertThat(Files.exists(staged.temporary())).isTrue();
            staged.commit();
            assertThat(content(target)).isEqualTo(NEW);
            assertThat(Files.exists(staged.temporary())).isFalse();
            AtomicConfigWriter.StagedWrite discarded = AtomicConfigWriter.stage(spelled, OLD);
            assertThat(discarded.discard()).isTrue();
            assertThat(content(target)).isEqualTo(NEW);
        } finally {
            staged.discard();
        }
    }

    @Test
    @DisplayName("a write replaces the file and leaves nothing beside it; a missing target is created")
    void writeReplacesTheFile() throws IOException {
        Path created = tempDir.resolve("created.yml");

        AtomicConfigWriter.write(target, NEW);
        AtomicConfigWriter.write(created, "a: 1\n");

        assertThat(content(target)).isEqualTo(NEW);
        assertThat(content(created)).isEqualTo("a: 1\n");
        assertThat(siblings()).containsExactlyInAnyOrder("config.yml", "created.yml");
    }

    @ParameterizedTest(name = "failure at {0}")
    @ValueSource(strings = {"create", "write", "force", "attributes", "move"})
    @DisplayName("a failure at any step leaves the target byte-identical and no temporary file")
    void failureLeavesTargetIntact(String step) {
        IOException failure = new IOException("injected failure at " + step);

        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, failingAt(step, failure))).isSameAs(failure);

        assertThat(content(target)).isEqualTo(OLD);
        assertThat(siblings()).containsExactly("config.yml");
    }

    @ParameterizedTest(name = "fallback for {0}")
    @ValueSource(strings = {"atomic", "EBUSY", "EXDEV", "busy-reason", "cross-device-reason", "temp-denied", "temp-read-only"})
    void eligibleFallbackForcesBackupBeforeTargetAndLogsEachSave(String trigger) throws IOException {
        ObservedFallback files = new ObservedFallback(trigger);
        AtomicConfigWriter.write(target, NEW, files);
        assertThat(content(target)).isEqualTo(NEW);
        assertThat(content(backup())).isEqualTo(OLD);
        assertThat(files.events).containsSubsequence("backup-write", "backup-force", "target-open", "target-write", "target-force");
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getMessage()).contains(target.toString(), "in-place", ".bak", files.failure.getMessage());
        assertThat(siblings()).containsExactlyInAnyOrder("config.yml", "config.yml.bak");
        assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(Files.exists(backup())).isFalse();
        AtomicConfigWriter.write(target, NEW + "third: line\n", new ObservedFallback(trigger));
        assertThat(content(backup())).isEqualTo(NEW);
        assertThat(warnings()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "temp-denied"})
    void backupHasTargetPermissionsBeforeFirstWrite(String trigger) throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        ObservedFallback files = new ObservedFallback(trigger) {
            @Override public void write(FileChannel channel, ByteBuffer data) throws IOException {
                if (Files.exists(backup())) {
                    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(backup())))
                            .isEqualTo("rw-------");
                }
                super.write(channel, data);
            }
        };
        AtomicConfigWriter.write(target, NEW, files);
        assertThat(content(backup())).isEqualTo(OLD);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
    }

    @Test
    void repeatedFallbackRefreshesBackupBeforeOverwritingTarget() throws IOException {
        AtomicConfigWriter.write(target, NEW, new RotatingFallback(null));
        assertThat(content(backup())).isEqualTo(OLD);
        RotatingFallback second = new RotatingFallback(null);
        AtomicConfigWriter.write(target, NEW + "third: line\n", second);
        assertThat(content(target)).isEqualTo(NEW + "third: line\n");
        assertThat(content(backup())).isEqualTo(NEW);
        assertThat(second.events).containsSubsequence("copy-write", "copy-force", "backup-move", "target-open");
        assertThat(siblings()).containsExactlyInAnyOrder("config.yml", "config.yml.bak");
        assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(Files.exists(backup())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "write", "force", "move"})
    void backupRefreshRefusalKeepsExistingBackupAndTarget(String fault) throws IOException {
        Files.write(backup(), "earlier backup\n".getBytes(StandardCharsets.UTF_8));
        RotatingFallback files = new RotatingFallback(fault);
        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, files)).isInstanceOf(IOException.class);
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(content(backup())).isEqualTo("earlier backup\n");
        assertThat(files.events).doesNotContain("target-open");
        assertThat(siblings()).containsExactlyInAnyOrder("config.yml", "config.yml.bak");
    }

    @Test
    void refreshedBackupSurvivesTargetFailureWithTargetPermissions() throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        Files.write(backup(), "earlier backup\n".getBytes(StandardCharsets.UTF_8));
        RotatingFallback files = new RotatingFallback("target");
        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, files)).isInstanceOf(IOException.class);
        assertThat(content(backup())).isEqualTo(OLD);
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(backup()))).isEqualTo("rw-------");
        assertThat(files.events).containsSubsequence("copy-force", "backup-move", "target-open");
        assertThat(siblings()).containsExactlyInAnyOrder("config.yml", "config.yml.bak");
    }

    private class RotatingFallback implements AtomicConfigWriter.FileOperations {
        private final String fault;
        private final List<String> events = new ArrayList<>();
        private FileChannel copy;
        private String priorBackup;
        RotatingFallback(String fault) { this.fault = fault; }
        @Override public FileChannel open(Path temporary, java.nio.file.attribute.FileAttribute<?>... attributes) throws IOException {
            if (temporary.getFileName().toString().startsWith("config.yml.bak.tmp-")) {
                if ("create".equals(fault)) { throw new IOException("injected copy create"); }
                priorBackup = content(backup());
                copy = AtomicConfigWriter.FileOperations.super.open(temporary, attributes);
                return copy;
            }
            return AtomicConfigWriter.FileOperations.super.open(temporary, attributes);
        }
        @Override public void write(FileChannel channel, ByteBuffer data) throws IOException {
            if (channel == copy) {
                events.add("copy-write");
                if ("write".equals(fault)) { throw new IOException("injected copy write"); }
            }
            AtomicConfigWriter.FileOperations.super.write(channel, data);
        }
        @Override public void force(FileChannel channel) throws IOException {
            if (channel == copy) {
                events.add("copy-force");
                if ("force".equals(fault)) { throw new IOException("injected copy force"); }
            }
            AtomicConfigWriter.FileOperations.super.force(channel);
        }
        @Override public void move(Path source, Path destination, CopyOption... options) throws IOException {
            if (!destination.equals(backup())) {
                throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected fallback");
            }
            events.add("backup-move");
            assertThat(content(backup())).isEqualTo(priorBackup);
            assertThat(events).contains("copy-force");
            if ("move".equals(fault)) { throw new IOException("injected copy move"); }
            assertThat(java.util.Arrays.asList(options)).contains(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            AtomicConfigWriter.FileOperations.super.move(source, destination, options);
        }
        @Override public FileChannel openTarget(Path file) throws IOException {
            events.add("target-open");
            if (copy != null) { assertThat(events).contains("backup-move"); }
            if ("target".equals(fault)) { throw new IOException("injected target open"); }
            return AtomicConfigWriter.FileOperations.super.openTarget(file);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "write", "force"})
    void backupFailureRefusesBeforeTouchingTarget(String step) throws IOException {
        if ("create".equals(step)) {
            Files.createDirectory(backup());
        }
        ObservedFallback files = new ObservedFallback("atomic");
        files.failBackup = step;
        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, files)).isInstanceOf(IOException.class);
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(files.events).doesNotContain("target-write", "target-force");
        assertThat(Files.exists(backup())).isEqualTo("create".equals(step));
        assertThat(warnings()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "force"})
    void inPlaceFailureKeepsCompleteForcedBackup(String step) {
        ObservedFallback files = new ObservedFallback("atomic");
        files.failTarget = step;
        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, files)).isInstanceOf(IOException.class);
        assertThat(content(backup())).isEqualTo(OLD);
        assertThat(files.events).containsSubsequence("backup-write", "backup-force", "target-write");
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getMessage()).contains("in-place", ".bak", "failed");
    }

    @Test
    void eligibleFallbackWithAbsentTargetRefusesWithoutCreatingTargetOrBackup() throws IOException {
        Files.delete(target);
        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, new ObservedFallback("atomic")))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(target)).isFalse();
        assertThat(Files.exists(backup())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Permission denied", "EBUSY-ish", "cross-device", "No space left on device"})
    void arbitraryMoveReasonDoesNotEnterFallback(String reason) {
        AtomicConfigWriter.FileOperations files = new AtomicConfigWriter.FileOperations() {
            @Override public void move(Path source, Path destination, CopyOption... options) throws IOException {
                throw new java.nio.file.FileSystemException(source.toString(), destination.toString(), reason);
            }
        };
        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, files)).isInstanceOf(IOException.class);
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(Files.exists(backup())).isFalse();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void deferredStageDoesNotTouchTargetOrBackupUntilCommitAndDiscardDoesNothing() throws IOException {
        AtomicConfigWriter.StagedWrite discarded = AtomicConfigWriter.stage(target, NEW, new ObservedFallback("temp-denied"));
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(Files.exists(discarded.temporary())).isFalse();
        assertThat(Files.exists(backup())).isFalse();
        assertThat(discarded.discard()).isTrue();
        assertThat(warnings()).isEmpty();
        AtomicConfigWriter.StagedWrite staged = AtomicConfigWriter.stage(target, NEW, new ObservedFallback("temp-denied"));
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(Files.exists(backup())).isFalse();
        staged.commit();
        assertThat(content(target)).isEqualTo(NEW);
        assertThat(content(backup())).isEqualTo(OLD);
    }

    @Test
    void attributeReadAccessDeniedIsNotAEligibleTemporaryCreateFailure() throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        java.nio.file.attribute.PosixFileAttributeView denied = org.mockito.Mockito.mock(
                java.nio.file.attribute.PosixFileAttributeView.class);
        org.mockito.Mockito.when(denied.readAttributes()).thenThrow(new java.nio.file.AccessDeniedException(target.toString()));
        try (org.mockito.MockedStatic<Files> mocked = org.mockito.Mockito.mockStatic(Files.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.getFileAttributeView(target.toAbsolutePath(), java.nio.file.attribute.PosixFileAttributeView.class))
                    .thenReturn(denied);
            assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW)).isInstanceOf(java.nio.file.AccessDeniedException.class);
        }
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(Files.exists(backup())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "force", "attributes"})
    void accessDeniedAfterTemporaryOpenNeverEntersFallback(String step) {
        assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW,
                failingAt(step, new java.nio.file.AccessDeniedException("injected " + step))))
                .isInstanceOf(java.nio.file.AccessDeniedException.class);
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(Files.exists(backup())).isFalse();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void successfulLoadDeletesBackupOnlyAfterParsingAndCleanupFailureKeepsLoaded() throws IOException {
        Files.write(backup(), OLD.getBytes(StandardCharsets.UTF_8));
        List<Path> deletions = new ArrayList<>();
        AtomicConfigWriter.FileOperations denied = new AtomicConfigWriter.FileOperations() {
            @Override public void delete(Path file) throws IOException {
                deletions.add(file);
                throw new IOException("cleanup denied");
            }
        };
        assertThat(ConfigDocument.load(target, denied).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(deletions).containsExactly(backup());
        assertThat(Files.exists(backup())).isTrue();
        assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(Files.exists(backup())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "unreadable", "syntax", "utf8"})
    void unsuccessfulLoadNeverDeletesBackup(String kind) throws IOException {
        Files.write(backup(), OLD.getBytes(StandardCharsets.UTF_8));
        AtomicConfigWriter.FileOperations files = new AtomicConfigWriter.FileOperations() { };
        if ("absent".equals(kind)) {
            Files.delete(target);
        } else if ("unreadable".equals(kind)) {
            files = new AtomicConfigWriter.FileOperations() {
                @Override public byte[] read(Path file) throws IOException { throw new IOException("read denied"); }
            };
        } else {
            Files.write(target, "syntax".equals(kind) ? "bad: [\n".getBytes(StandardCharsets.UTF_8)
                    : new byte[]{'k', ':', ' ', (byte) 0xFF});
        }
        assertThat(ConfigDocument.load(target, files).state()).isNotEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(content(backup())).isEqualTo(OLD);
    }

    @Test
    void fallbackResolvesSymlinkForBackupAndCleanup() throws IOException {
        Path real = Files.createDirectory(tempDir.resolve("shared")).resolve("real.yml");
        Files.write(real, OLD.getBytes(StandardCharsets.UTF_8));
        Path link = tempDir.resolve("linked.yml");
        Files.createSymbolicLink(link, real);
        AtomicConfigWriter.write(link, NEW, new ObservedFallback("atomic"));
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(content(real.resolveSibling("real.yml.bak"))).isEqualTo(OLD);
        assertThat(Files.exists(link.resolveSibling("linked.yml.bak"))).isFalse();
        assertThat(ConfigDocument.load(link).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(Files.exists(real.resolveSibling("real.yml.bak"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "runtime-cleanup"})
    void completeBackupSurvivesTargetOpenFailureAndCleanupRuntimeFailure(String step) throws IOException {
        if ("runtime-cleanup".equals(step)) {
            Files.write(backup(), OLD.getBytes(StandardCharsets.UTF_8));
            AtomicConfigWriter.FileOperations files = new AtomicConfigWriter.FileOperations() {
                @Override public void delete(Path file) { throw new SecurityException("cleanup denied"); }
            };
            assertThat(ConfigDocument.load(target, files).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        } else {
            ObservedFallback files = new ObservedFallback("atomic") {
                @Override public FileChannel openTarget(Path file) throws IOException {
                    throw new java.nio.file.AccessDeniedException(file.toString());
                }
            };
            assertThatThrownBy(() -> AtomicConfigWriter.write(target, NEW, files)).isInstanceOf(IOException.class);
            assertThat(content(target)).isEqualTo(OLD);
            assertThat(warnings()).hasSize(1);
        }
        assertThat(content(backup())).isEqualTo(OLD);
    }

    @Test
    void temporaryCreateRefusalWithUnremovablePartialFileRefusesRatherThanStaging() throws IOException {
        AtomicConfigWriter.FileOperations files = new AtomicConfigWriter.FileOperations() {
            @Override public FileChannel open(Path file, java.nio.file.attribute.FileAttribute<?>... attributes) throws IOException {
                Files.write(file, "partial".getBytes(StandardCharsets.UTF_8));
                throw new java.nio.file.AccessDeniedException(file.toString());
            }
            @Override public void delete(Path file) throws IOException { throw new IOException("cannot clean partial temp"); }
        };
        assertThatThrownBy(() -> AtomicConfigWriter.stage(target, NEW, files)).isInstanceOf(IOException.class)
                .hasMessageContaining("cannot clean partial temp");
        assertThat(content(target)).isEqualTo(OLD);
        assertThat(Files.exists(backup())).isFalse();
    }

    @Test
    void loadCannotRemoveBackupWhileInPlaceCommitHasNotFinished() throws Exception {
        java.util.concurrent.CountDownLatch targetOpening = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch loading = new java.util.concurrent.CountDownLatch(1);
        ObservedFallback files = new ObservedFallback("atomic") {
            @Override public FileChannel openTarget(Path file) throws IOException {
                assertThat(content(backup())).isEqualTo(OLD);
                targetOpening.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IOException("test release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
                return super.openTarget(file);
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> writing = pool.submit(() -> {
                try { AtomicConfigWriter.write(target, NEW, files); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            assertThat(targetOpening.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<ConfigLoadResult> load = pool.submit(() -> {
                loading.countDown();
                return ConfigDocument.load(target);
            });
            assertThat(loading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> load.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(content(backup())).isEqualTo(OLD);
            release.countDown();
            writing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(load.get(5, java.util.concurrent.TimeUnit.SECONDS).document().get(java.util.Arrays.asList("new")))
                    .isEqualTo("content");
            assertThat(Files.exists(backup())).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Path backup() {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    private List<LogRecord> warnings() {
        return records.stream().filter(r -> r.getLevel() == Level.WARNING).collect(Collectors.toList());
    }

    /** Counts the writer's existing write/force operations across temp, backup and target channels. */
    private class ObservedFallback implements AtomicConfigWriter.FileOperations {
        private final String trigger;
        private final IOException failure;
        private final List<String> events = new ArrayList<>();
        private int writeCalls;
        private int forceCalls;
        private String failBackup;
        private String failTarget;

        ObservedFallback(String trigger) {
            this.trigger = trigger;
            String reason = "busy-reason".equals(trigger) ? "Device or resource busy"
                    : "cross-device-reason".equals(trigger) ? "Invalid cross-device link" : trigger;
            failure = "atomic".equals(trigger) ? new AtomicMoveNotSupportedException("source", "target", "injected")
                    : "temp-denied".equals(trigger) ? new java.nio.file.AccessDeniedException("temporary")
                    : new java.nio.file.FileSystemException("source", "target",
                            "temp-read-only".equals(trigger) ? "Read-only file system" : reason);
        }

        @Override public FileChannel open(Path temporary, java.nio.file.attribute.FileAttribute<?>... attributes) throws IOException {
            if (trigger.startsWith("temp-")) { throw failure; }
            return AtomicConfigWriter.FileOperations.super.open(temporary, attributes);
        }

        @Override public void move(Path source, Path destination, CopyOption... options) throws IOException {
            throw failure;
        }

        @Override public FileChannel openTarget(Path file) throws IOException {
            assertThat(events).contains("backup-force");
            events.add("target-open");
            return AtomicConfigWriter.FileOperations.super.openTarget(file);
        }

        @Override public void write(FileChannel channel, ByteBuffer data) throws IOException {
            writeCalls++;
            int step = writeCalls + (trigger.startsWith("temp-") ? 1 : 0);
            String name = step == 1 ? "temp" : step == 2 ? "backup" : "target";
            events.add(name + "-write");
            if ("backup".equals(name) && "write".equals(failBackup)
                    || "target".equals(name) && "write".equals(failTarget)) {
                ByteBuffer partial = data.duplicate(); partial.limit(partial.position() + partial.remaining() / 2);
                channel.write(partial);
                throw new IOException("injected " + name + " write");
            }
            AtomicConfigWriter.FileOperations.super.write(channel, data);
        }

        @Override public void force(FileChannel channel) throws IOException {
            forceCalls++;
            int step = forceCalls + (trigger.startsWith("temp-") ? 1 : 0);
            String name = step == 1 ? "temp" : step == 2 ? "backup" : "target";
            events.add(name + "-force");
            if ("backup".equals(name) && "force".equals(failBackup)
                    || "target".equals(name) && "force".equals(failTarget)) {
                throw new IOException("injected " + name + " force");
            }
            AtomicConfigWriter.FileOperations.super.force(channel);
        }
    }

    @Test
    @DisplayName("a staged write leaves the target unchanged until it is committed; a discarded one leaves nothing")
    void stagedWrite() throws IOException {
        AtomicConfigWriter.StagedWrite staged = AtomicConfigWriter.stage(target, NEW);
        AtomicConfigWriter.StagedWrite discarded = AtomicConfigWriter.stage(target, "discarded: true\n");

        assertThat(content(target)).isEqualTo(OLD);
        assertThat(Files.exists(staged.temporary())).isTrue();
        assertThat(discarded.discard()).isTrue();
        staged.commit();

        assertThat(content(target)).isEqualTo(NEW);
        assertThat(siblings()).containsExactly("config.yml");
    }

    @Test
    @DisplayName("a staged write whose move fails leaves the target unchanged and removes its temporary file")
    void stagedCommitFailure() throws IOException {
        IOException failure = new IOException("injected move failure");
        AtomicConfigWriter.StagedWrite staged = AtomicConfigWriter.stage(target, NEW, failingAt("move", failure));

        assertThatThrownBy(staged::commit).isSameAs(failure);

        assertThat(content(target)).isEqualTo(OLD);
        assertThat(siblings()).containsExactly("config.yml");
    }

    @Test
    @DisplayName("the target's POSIX permissions are kept (a 600 file stays 600)")
    void permissionsAreKept() throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"), "POSIX file system");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));

        AtomicConfigWriter.write(target, NEW);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("a symbolic link to the config file stays a link; the file it points to is replaced")
    void symbolicLinkIsKept() throws IOException {
        Path real = Files.createDirectory(tempDir.resolve("shared")).resolve("real.yml");
        Files.write(real, OLD.getBytes(StandardCharsets.UTF_8));
        Path link = tempDir.resolve("linked.yml");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }

        AtomicConfigWriter.write(link, NEW);

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(content(real)).isEqualTo(NEW);
        try (Stream<Path> files = Files.list(real.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("real.yml");
        }
    }

    @Test
    @DisplayName("a stale temporary file of the writer's exact pattern is deleted at the next load, with one FINE line")
    void staleTemporaryIsDeletedAtLoad() throws IOException {
        Path stale = tempDir.resolve("config.yml.tmp-0123456789abcdef");
        Path upperCase = tempDir.resolve("config.yml.tmp-0123456789ABCDEF");
        Path shortSuffix = tempDir.resolve("config.yml.tmp-0123");
        Path otherFile = tempDir.resolve("other.yml.tmp-0123456789abcdef");
        Path operatorBackup = tempDir.resolve("config.yml.tmp-backup");
        for (Path file : new Path[]{stale, upperCase, shortSuffix, otherFile, operatorBackup}) {
            Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        }

        ConfigLoadResult result = ConfigDocument.load(target);

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(upperCase)).isTrue();
        assertThat(Files.exists(shortSuffix)).isTrue();
        assertThat(Files.exists(otherFile)).isTrue();
        assertThat(Files.exists(operatorBackup)).isTrue();
        assertThat(records.stream().filter(r -> r.getLevel() == Level.FINE).collect(Collectors.toList())).hasSize(1);
    }

    @Test
    @DisplayName("the writer's own temporary names match the pattern the cleanup deletes")
    void temporaryNamesMatchTheCleanupPattern() {
        for (int i = 0; i < 50; i++) {
            assertThat(AtomicConfigWriter.isTemporaryOf("config.yml", AtomicConfigWriter.temporaryName("config.yml"))).isTrue();
        }
        assertThat(AtomicConfigWriter.isTemporaryOf("config.yml", "config.yml.tmp-0123456789abcde")).isFalse();
        assertThat(AtomicConfigWriter.isTemporaryOf("config.yml", "xconfig.yml.tmp-0123456789abcdef")).isFalse();
    }

    @Test
    void loadDoesNotDeleteLiveStagedWrite() throws IOException {
        AtomicConfigWriter.StagedWrite staged = AtomicConfigWriter.stage(target, NEW);
        assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(Files.exists(staged.temporary())).isTrue();
        staged.commit();
        assertThat(content(target)).isEqualTo(NEW);
        assertThat(siblings()).containsExactly("config.yml");
    }

    @Test
    void temporaryHasTargetPermissionsBeforeFirstWrite() throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        AtomicConfigWriter.FileOperations inspect = new AtomicConfigWriter.FileOperations() {
            @Override public FileChannel open(Path temporary, java.nio.file.attribute.FileAttribute<?>... attributes) throws IOException {
                FileChannel channel = AtomicConfigWriter.FileOperations.super.open(temporary, attributes);
                try {
                    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(temporary))).isEqualTo("rw-------");
                } catch (AssertionError failure) {
                    channel.close();
                    throw failure;
                }
                return channel;
            }
        };
        AtomicConfigWriter.write(target, NEW, inspect);
        assertThat(content(target)).isEqualTo(NEW);
    }

    @Test
    void danglingSymbolicLinkIsNeverReplaced() throws IOException {
        Path link = tempDir.resolve("dangling.yml");
        Files.createSymbolicLink(link, tempDir.resolve("missing.yml"));
        assertThatThrownBy(() -> AtomicConfigWriter.write(link, NEW)).isInstanceOf(IOException.class);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(tempDir.resolve("missing.yml"));
    }

    private static AtomicConfigWriter.FileOperations failingAt(String step, IOException failure) {
        return new AtomicConfigWriter.FileOperations() {
            @Override
            public FileChannel open(Path temporary, java.nio.file.attribute.FileAttribute<?>... attributes) throws IOException {
                FileChannel channel = AtomicConfigWriter.FileOperations.super.open(temporary, attributes);
                if ("create".equals(step)) {
                    channel.close();
                    throw failure;
                }
                return channel;
            }

            @Override
            public void write(FileChannel channel, ByteBuffer data) throws IOException {
                if ("write".equals(step)) {
                    ByteBuffer half = data.duplicate();
                    half.limit(half.position() + half.remaining() / 2);
                    channel.write(half);
                    throw failure;
                }
                AtomicConfigWriter.FileOperations.super.write(channel, data);
            }

            @Override
            public void force(FileChannel channel) throws IOException {
                if ("force".equals(step)) {
                    throw failure;
                }
                AtomicConfigWriter.FileOperations.super.force(channel);
            }

            @Override
            public void copyAttributes(Path from, Path to) throws IOException {
                if ("attributes".equals(step)) {
                    throw failure;
                }
                AtomicConfigWriter.FileOperations.super.copyAttributes(from, to);
            }

            @Override
            public void move(Path source, Path destination, CopyOption... options) throws IOException {
                if ("move".equals(step)) {
                    throw failure;
                }
                AtomicConfigWriter.FileOperations.super.move(source, destination, options);
            }
        };
    }

    private List<String> siblings() {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> !"shared".equals(n)).collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String content(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
