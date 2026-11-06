package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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

/** Real local POSIX files plus narrow failure seams; no physical power-loss or Windows claim. */
@Timeout(45)
class StrictYamlPublicationTest {
    private static final Logger LOGGER = Logger.getLogger(StrictYamlPublication.class.getName());
    private static final String BEFORE = "items: old\n";
    private static final String AFTER = "items: new\n";
    @TempDir
    Path directory;
    private Path file;
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }
        @Override
        public void flush() {
            // Memory capture only.
        }
        @Override
        public void close() {
            // No owned resource.
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        file = directory.resolve("kit.yml");
        Files.write(file, BEFORE.getBytes(StandardCharsets.UTF_8));
        LOGGER.addHandler(handler);
    }

    @AfterEach
    void resetSeam() {
        StrictYamlPublication.FILES.set(null);
        LOGGER.removeHandler(handler);
    }

    @Test
    void shortWritesCompleteAndForceAndClosePrecedeOnlyAtomicMove() throws Exception {
        List<String> events = new ArrayList<>();
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            private FileChannel opened;
            @Override
            public FileChannel open(Path path) throws IOException {
                assertThat(path.getParent()).isEqualTo(directory);
                assertThat(path.getFileName().toString()).matches("kit\\.yml\\.ultitools-stage-[0-9a-f]{32}\\.tmp");
                opened = StrictYamlPublication.Operations.super.open(path);
                assertThat(Files.getPosixFilePermissions(path)).isEqualTo(mode("rw-------"));
                assertThat(Files.size(path)).isZero();
                events.add("open-private");
                return opened;
            }
            @Override
            public void write(FileChannel channel, ByteBuffer bytes) throws IOException {
                int limit = bytes.limit();
                bytes.limit(Math.min(limit, bytes.position() + 2));
                StrictYamlPublication.Operations.super.write(channel, bytes);
                bytes.limit(limit);
                events.add("write");
            }
            @Override
            public void force(FileChannel channel) throws IOException {
                events.add("force");
                StrictYamlPublication.Operations.super.force(channel);
            }
            @Override
            public void close(FileChannel channel) throws IOException {
                StrictYamlPublication.Operations.super.close(channel);
                events.add("close");
            }
            @Override
            public void move(Path source, Path target) throws IOException {
                assertThat(opened.isOpen()).isFalse();
                assertThat(Files.readAllBytes(source)).isEqualTo(AFTER.getBytes(StandardCharsets.UTF_8));
                assertThat(Files.readAllBytes(target)).isEqualTo(BEFORE.getBytes(StandardCharsets.UTF_8));
                events.add("atomic-move");
                StrictYamlPublication.Operations.super.move(source, target);
            }
        });
        assertThat(edit()).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(events).startsWith("open-private", "write").endsWith("force", "close", "atomic-move");
        assertThat(Collections.frequency(events, "write")).isGreaterThan(1);
        assertThat(text()).isEqualTo(AFTER);
        assertThat(stages()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "force", "close", "permissions", "verify", "owner", "group", "atomic"})
    void preCommitFailuresPreserveOldBytesWithoutFallback(String point) throws Exception {
        IOException sentinel = "atomic".equals(point)
                ? new AtomicMoveNotSupportedException("stage", "target", "test") : new IOException("injected " + point);
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public void write(FileChannel channel, ByteBuffer bytes) throws IOException {
                if ("write".equals(point)) {
                    channel.write(ByteBuffer.wrap(new byte[]{'x'}));
                    throw sentinel;
                }
                StrictYamlPublication.Operations.super.write(channel, bytes);
            }
            @Override
            public void force(FileChannel channel) throws IOException {
                if ("force".equals(point)) {
                    throw sentinel;
                }
                StrictYamlPublication.Operations.super.force(channel);
            }
            @Override
            public void close(FileChannel channel) throws IOException {
                StrictYamlPublication.Operations.super.close(channel);
                if ("close".equals(point)) {
                    throw sentinel;
                }
            }
            @Override
            public void permissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
                if ("permissions".equals(point)) {
                    throw sentinel;
                }
                StrictYamlPublication.Operations.super.permissions(path, permissions);
            }
            @Override
            public PosixFileAttributes posix(Path path) throws IOException {
                PosixFileAttributes real = StrictYamlPublication.Operations.super.posix(path);
                if (!path.equals(file) && !path.equals(directory)) {
                    if ("owner".equals(point) || "group".equals(point)) {
                        return changedIdentity(real, "owner".equals(point));
                    }
                    if ("verify".equals(point)) {
                        PosixFileAttributes mismatch = mock(PosixFileAttributes.class, delegatesTo(real));
                        doReturn(mode("---------")).when(mismatch).permissions();
                        return mismatch;
                    }
                }
                return real;
            }
            @Override
            public void owner(Path path, UserPrincipal owner) throws IOException {
                throw sentinel;
            }
            @Override
            public void group(Path path, GroupPrincipal group) throws IOException {
                throw sentinel;
            }
            @Override
            public void move(Path source, Path target) throws IOException {
                throw sentinel;
            }
        });
        if ("verify".equals(point)) {
            assertThatThrownBy(this::edit).isInstanceOf(IOException.class).hasMessageContaining("metadata");
        } else {
            assertThatThrownBy(this::edit).isSameAs(sentinel);
        }
        assertThat(text()).isEqualTo(BEFORE);
        assertThat(stages()).isEmpty();
        try (Stream<Path> entries = Files.list(directory)) {
            assertThat(entries.count()).as("no backup, ordinary-move or truncate fallback").isEqualTo(1);
        }
    }

    @Test
    void identityTransitionNarrowsToZeroBeforeChownAndChgrpThenAppliesFinalMode() throws Exception {
        Files.setPosixFilePermissions(file, mode("-w-------"));
        // Reading still needs normal access on unprivileged hosts; use write-only owner in the captured promise only.
        Files.setPosixFilePermissions(file, mode("rw-r-----"));
        List<String> events = new ArrayList<>();
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            private boolean prepared;
            @Override
            public PosixFileAttributes posix(Path path) throws IOException {
                PosixFileAttributes real = StrictYamlPublication.Operations.super.posix(path);
                if (!path.equals(file) && !path.equals(directory) && !prepared) {
                    return changedIdentity(changedIdentity(real, true), false);
                }
                return real;
            }
            @Override
            public void permissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
                events.add(PosixFilePermissions.toString(permissions));
                StrictYamlPublication.Operations.super.permissions(path, permissions);
                if (permissions.equals(mode("rw-r-----"))) {
                    prepared = true;
                }
            }
            @Override
            public void owner(Path path, UserPrincipal owner) throws IOException {
                assertThat(Files.getPosixFilePermissions(path)).isEqualTo(mode("---------"));
                events.add("owner");
            }
            @Override
            public void group(Path path, GroupPrincipal group) throws IOException {
                assertThat(Files.getPosixFilePermissions(path)).isEqualTo(mode("---------"));
                events.add("group");
            }
        });
        assertThat(edit()).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(events).containsExactly("---------", "owner", "group", "rw-r-----");
    }

    @ParameterizedTest
    @ValueSource(strings = {"parent-posix", "target-posix", "stage-posix", "parent-acl", "target-acl", "stage-acl", "not-writable"})
    void unsupportedMetadataPolicyRefusesWithoutTouchingTarget(String policy) throws Exception {
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            private String role(Path path) {
                return path.equals(directory) ? "parent" : path.equals(file) ? "target" : "stage";
            }
            @Override
            public PosixFileAttributes posix(Path path) throws IOException {
                return policy.equals(role(path) + "-posix") ? null : StrictYamlPublication.Operations.super.posix(path);
            }
            @Override
            public boolean hasAcl(Path path) throws IOException {
                return policy.equals(role(path) + "-acl") || StrictYamlPublication.Operations.super.hasAcl(path);
            }
            @Override
            public boolean writable(Path path) {
                return !"not-writable".equals(policy) && StrictYamlPublication.Operations.super.writable(path);
            }
        });
        assertThat(edit()).isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertThat(text()).isEqualTo(BEFORE);
        assertThat(stages()).isEmpty();
    }

    @Test
    void aclLookupFailureIsNotEvidenceOfNoAcl() throws Exception {
        IOException failure = new IOException("attribute lookup failed");
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public boolean hasAcl(Path path) throws IOException {
                throw failure;
            }
        });
        assertThatThrownBy(this::edit).isSameAs(failure);
        assertThat(text()).isEqualTo(BEFORE);
        assertThat(stages()).isEmpty();
    }

    @Test
    void matchingStageOwnerAndGroupDoNotRequireIdentitySettingPrivileges() throws Exception {
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public void owner(Path path, UserPrincipal owner) throws IOException {
                throw new IOException("unnecessary chown");
            }
            @Override
            public void group(Path path, GroupPrincipal group) throws IOException {
                throw new IOException("unnecessary chgrp");
            }
        });
        assertThat(edit()).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(text()).isEqualTo(AFTER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "symlink", "directory"})
    void creationRaceNeverReplacesEntryThatAppearsAtLink(String kind) throws Exception {
        Files.delete(file);
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public void link(Path target, Path completed) throws IOException {
                assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).isFalse();
                assertThat(Files.readAllBytes(completed)).isEqualTo(AFTER.getBytes(StandardCharsets.UTF_8));
                if ("file".equals(kind)) {
                    Files.write(target, "operator".getBytes(StandardCharsets.UTF_8));
                } else if ("symlink".equals(kind)) {
                    Files.createSymbolicLink(target, directory.resolve("missing"));
                } else {
                    Files.createDirectory(target);
                }
                StrictYamlPublication.Operations.super.link(target, completed);
            }
        });
        assertThat(create()).isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        if ("file".equals(kind)) {
            assertThat(text()).isEqualTo("operator");
        } else if ("symlink".equals(kind)) {
            assertThat(Files.isSymbolicLink(file)).isTrue();
        } else {
            assertThat(Files.isDirectory(file)).isTrue();
        }
        assertThat(stages()).isEmpty();
    }

    @Test
    void creationOrdersCompleteClosedInodeLinkSyncUnlinkSync() throws Exception {
        Files.delete(file);
        List<String> events = new ArrayList<>();
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            private FileChannel channel;
            @Override
            public FileChannel open(Path path) throws IOException {
                assertThat(file).doesNotExist();
                channel = StrictYamlPublication.Operations.super.open(path);
                return channel;
            }
            @Override
            public void write(FileChannel opened, ByteBuffer bytes) throws IOException {
                assertThat(file).doesNotExist();
                StrictYamlPublication.Operations.super.write(opened, bytes);
            }
            @Override
            public void link(Path target, Path complete) throws IOException {
                assertThat(channel.isOpen()).isFalse();
                assertThat(Files.readAllBytes(complete)).isEqualTo(AFTER.getBytes(StandardCharsets.UTF_8));
                events.add("link");
                StrictYamlPublication.Operations.super.link(target, complete);
            }
            @Override
            public void delete(Path path) throws IOException {
                events.add("unlink");
                StrictYamlPublication.Operations.super.delete(path);
            }
            @Override
            public void syncDirectory(Path path) {
                events.add("sync");
            }
        });
        assertThat(create()).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(events).containsExactly("link", "sync", "unlink", "sync");
        assertThat(text()).isEqualTo(AFTER);
        assertThat(stages()).isEmpty();
    }

    @Test
    void unsupportedLinkFailsClosedWithoutMoveOrFinalEmptyClaim() throws Exception {
        Files.delete(file);
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public void link(Path target, Path complete) {
                throw new UnsupportedOperationException("links unsupported");
            }
            @Override
            public void move(Path source, Path target) {
                throw new AssertionError("create must not fall back to a move");
            }
        });
        assertThatThrownBy(this::create).isInstanceOf(IOException.class).hasCauseInstanceOf(UnsupportedOperationException.class);
        assertThat(file).doesNotExist();
        assertThat(stages()).isEmpty();
    }

    @Test
    void sameJvmConcurrentCreationHasExactlyOneCompleteWinner() throws Exception {
        Files.delete(file);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Future<OperatorFiles.WriteResult> one = workers.submit(() -> {
                ready.await();
                return create();
            });
            Future<OperatorFiles.WriteResult> two = workers.submit(() -> {
                ready.await();
                return create();
            });
            ready.countDown();
            List<OperatorFiles.WriteResult> results = new ArrayList<>();
            results.add(one.get(10, TimeUnit.SECONDS));
            results.add(two.get(10, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(OperatorFiles.WriteResult.WRITTEN, OperatorFiles.WriteResult.FILE_CHANGED);
            assertThat(text()).isEqualTo(AFTER);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void collisionRetriesANewNameAndNeverClaimsOrDeletesOldStage() throws Exception {
        String oldNonce = "0123456789abcdef0123456789abcdef";
        Path foreign = directory.resolve("kit.yml.ultitools-stage-" + oldNonce + ".tmp");
        Files.write(foreign, "not ours".getBytes(StandardCharsets.UTF_8));
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            private boolean first = true;
            @Override
            public String nonce() {
                if (first) {
                    first = false;
                    return oldNonce;
                }
                return StrictYamlPublication.Operations.super.nonce();
            }
        });
        assertThat(edit()).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(new String(Files.readAllBytes(foreign), StandardCharsets.UTF_8)).isEqualTo("not ours");
        assertThat(stages()).containsExactly(foreign);
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed-key", "symlink", "null-key", "read-failure", "delete-failure"})
    void failedWriteCleanupKeepsUnprovenOrUndeletableTempAndPreservesPrimaryFailure(String kind) throws Exception {
        IOException primary = new IOException("primary force failure");
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            private Path temp;
            private boolean failing;
            @Override
            public FileChannel open(Path path) throws IOException {
                temp = path;
                return StrictYamlPublication.Operations.super.open(path);
            }
            @Override
            public void force(FileChannel channel) throws IOException {
                failing = true;
                if ("symlink".equals(kind)) {
                    Files.delete(temp);
                    Files.createSymbolicLink(temp, file);
                }
                throw primary;
            }
            @Override
            public BasicFileAttributes basic(Path path) throws IOException {
                BasicFileAttributes real = StrictYamlPublication.Operations.super.basic(path);
                if (path.equals(temp)) {
                    if ("null-key".equals(kind) || (failing && "changed-key".equals(kind))) {
                        BasicFileAttributes altered = mock(BasicFileAttributes.class, delegatesTo(real));
                        doReturn("null-key".equals(kind) ? null : new Object()).when(altered).fileKey();
                        return altered;
                    }
                    if (failing && "read-failure".equals(kind)) {
                        throw new IOException("cleanup read failure");
                    }
                }
                return real;
            }
            @Override
            public void delete(Path path) throws IOException {
                if ("delete-failure".equals(kind)) {
                    throw new IOException("cleanup delete failure");
                }
                StrictYamlPublication.Operations.super.delete(path);
            }
        });
        assertThatThrownBy(this::edit).isSameAs(primary);
        assertThat(text()).isEqualTo(BEFORE);
        assertThat(stages()).hasSize(1);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains(stages().get(0).toString()).doesNotContain(AFTER);
        if ("read-failure".equals(kind) || "delete-failure".equals(kind)) {
            assertThat(primary.getSuppressed()).hasSize(1);
        }
    }

    @Test
    void postCommitCleanupAndDirectorySyncFailuresStillReportWrittenWithBoundedWarning() throws Exception {
        Files.delete(file);
        StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
            @Override
            public void delete(Path path) throws IOException {
                throw new IOException("cleanup delete failure");
            }
            @Override
            public void syncDirectory(Path path) {
                throw new UnsupportedOperationException("directory force unsupported");
            }
        });
        assertThat(create()).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(text()).isEqualTo(AFTER);
        assertThat(stages()).hasSize(1);
        assertThat(warnings.stream().filter(record -> record.getMessage().contains("directory"))).hasSize(1);
        assertThat(warnings.stream().filter(record -> record.getMessage().contains("Retained"))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace-before", "replace-after", "create-before", "create-after"})
    void processHaltLeavesOnlyOldAbsentOrCompleteNewAndNeverReplaysLeftover(String point) throws Exception {
        if (point.startsWith("create")) {
            Files.delete(file);
        }
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(java, "-cp", classpath, HaltChild.class.getName(), file.toString(), point)
                .inheritIO().start();
        try {
            assertThat(child.waitFor(25, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isEqualTo(23);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
        if (point.endsWith("after")) {
            assertThat(text()).isEqualTo(AFTER);
        } else if (point.startsWith("create")) {
            assertThat(file).doesNotExist();
        } else {
            assertThat(text()).isEqualTo(BEFORE);
        }
        List<Path> leftovers = stages();
        assertThat(leftovers).hasSize("replace-after".equals(point) ? 0 : 1);
        Files.write(file, "items: operator\n".getBytes(StandardCharsets.UTF_8));
        assertThat(CrashSafeYamlFiles.create(file.toFile(), Collections.singletonMap("items", "ignored")))
                .isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(CrashSafeYamlFiles.write(OperatorFiles.read(file.toFile()),
                Collections.singletonMap(Collections.singletonList("items"), "operator")))
                .isEqualTo(OperatorFiles.WriteResult.UNCHANGED);
        assertThat(text()).isEqualTo("items: operator\n");
        assertThat(stages()).containsExactlyElementsOf(leftovers);
    }

    /** A fresh JVM halts inside the actual strict gate's filesystem seam, bypassing every finally block. */
    public static final class HaltChild {
        private HaltChild() {
        }
        public static void main(String[] arguments) throws Exception {
            Path target = Paths.get(arguments[0]);
            String point = arguments[1];
            StrictYamlPublication.FILES.set(new StrictYamlPublication.Operations() {
                @Override
                public void move(Path source, Path destination) throws IOException {
                    if (point.endsWith("before")) {
                        Runtime.getRuntime().halt(23);
                    }
                    StrictYamlPublication.Operations.super.move(source, destination);
                    Runtime.getRuntime().halt(23);
                }
                @Override
                public void link(Path destination, Path completed) throws IOException {
                    if (point.endsWith("before")) {
                        Runtime.getRuntime().halt(23);
                    }
                    StrictYamlPublication.Operations.super.link(destination, completed);
                    Runtime.getRuntime().halt(23);
                }
            });
            if (point.startsWith("create")) {
                CrashSafeYamlFiles.create(target.toFile(), Collections.singletonMap("items", "new"));
            } else {
                CrashSafeYamlFiles.write(OperatorFiles.read(target.toFile()),
                        Collections.singletonMap(Collections.singletonList("items"), "new"));
            }
            throw new AssertionError("The child did not reach the requested publication point");
        }
    }

    static PosixFileAttributes changedIdentity(PosixFileAttributes real, boolean owner) {
        PosixFileAttributes altered = mock(PosixFileAttributes.class, delegatesTo(real));
        if (owner) {
            doReturn((UserPrincipal) () -> "different-owner").when(altered).owner();
        } else {
            doReturn((GroupPrincipal) () -> "different-group").when(altered).group();
        }
        return altered;
    }

    private OperatorFiles.WriteResult edit() throws Exception {
        return CrashSafeYamlFiles.write(OperatorFiles.read(file.toFile()),
                Collections.singletonMap(Collections.singletonList("items"), "new"));
    }

    private OperatorFiles.WriteResult create() throws Exception {
        return CrashSafeYamlFiles.create(file.toFile(), Collections.singletonMap("items", "new"));
    }

    private String text() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private List<Path> stages() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> found = new ArrayList<>();
            entries.filter(path -> path.getFileName().toString().contains(".ultitools-stage-")).forEach(found::add);
            return found;
        }
    }

    private static Set<PosixFilePermission> mode(String permissions) {
        return PosixFilePermissions.fromString(permissions);
    }
}
