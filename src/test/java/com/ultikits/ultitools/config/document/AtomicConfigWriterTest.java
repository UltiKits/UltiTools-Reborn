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
 * no temporary file behind; a file system without atomic moves gets a replacing move, logged once.
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
        AtomicConfigWriter.resetFallbackWarning();
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        logger.setLevel(previousLevel);
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

    @Test
    @DisplayName("a file system that refuses an atomic move gets a replacing move, logged once per JVM")
    void atomicMoveFallback() throws IOException {
        AtomicConfigWriter.FileOperations noAtomicMove = new AtomicConfigWriter.FileOperations() {
            @Override
            public void move(Path source, Path destination, CopyOption... options) throws IOException {
                for (CopyOption option : options) {
                    if (option == StandardCopyOption.ATOMIC_MOVE) {
                        throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
                    }
                }
                Files.move(source, destination, options);
            }
        };

        AtomicConfigWriter.write(target, NEW, noAtomicMove);
        AtomicConfigWriter.write(target, NEW + "third: line\n", noAtomicMove);

        assertThat(content(target)).isEqualTo(NEW + "third: line\n");
        assertThat(siblings()).containsExactly("config.yml");
        assertThat(records.stream().filter(r -> r.getLevel() == Level.WARNING).collect(Collectors.toList())).hasSize(1);
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

    private static AtomicConfigWriter.FileOperations failingAt(String step, IOException failure) {
        return new AtomicConfigWriter.FileOperations() {
            @Override
            public FileChannel open(Path temporary) throws IOException {
                FileChannel channel = AtomicConfigWriter.FileOperations.super.open(temporary);
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
