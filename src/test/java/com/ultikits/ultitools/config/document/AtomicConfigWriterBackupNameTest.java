package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UltiKits/UltiTools-Reborn#601 (inventory P9/A17/A18): the atomic writer's fallback backup has a name no
 * operator uses, and the writer deletes only a backup it created during this server run whose bytes still
 * match its record. An operator's own {@code <file>.bak}, or any other file, is never read, written or deleted.
 */
class AtomicConfigWriterBackupNameTest {

    private static final Pattern BACKUP = Pattern.compile("fixture\\.yml\\.ultitools-backup-[0-9a-f]{16}");
    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);

    @TempDir
    Path dir;
    private Path target;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Logger logger = Logger.getLogger(AtomicConfigWriter.class.getName());
    private Level previousLevel;
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // In-memory capture.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    /** The atomic move onto the target is refused as on a bind mount (EXDEV); every other step is real. */
    private final AtomicConfigWriter.FileOperations crossDevice = new AtomicConfigWriter.FileOperations() {
        @Override
        public void move(Path source, Path destination, CopyOption... options) throws IOException {
            if (destination.equals(target)) {
                throw new FileSystemException(source.toString(), destination.toString(), "EXDEV");
            }
            AtomicConfigWriter.FileOperations.super.move(source, destination, options);
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        target = dir.resolve("fixture.yml").toAbsolutePath();
        write(target, "a: 1\n");
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        logger.setLevel(previousLevel);
    }

    private static void write(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private List<Path> frameworkBackups() throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (BACKUP.matcher(entry.getFileName().toString()).matches()) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    private List<String> names() throws IOException {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                result.add(entry.getFileName().toString());
            }
        }
        Collections.sort(result);
        return result;
    }

    private List<String> infoNaming(Path file) {
        List<String> result = new ArrayList<>();
        synchronized (records) {
            for (LogRecord record : records) {
                if (Level.INFO.equals(record.getLevel()) && record.getMessage() != null
                        && record.getMessage().contains(file.getFileName().toString())) {
                    result.add(record.getMessage());
                }
            }
        }
        return result;
    }

    /** P9: an operator's own backup keeps its bytes and time through load, write, fallback and the next load. */
    @Test
    void operatorBakKeepsItsBytesAndTimeThroughLoadWriteFallbackAndNextLoad() throws Exception {
        Path operatorBackup = dir.resolve("fixture.yml.bak");
        write(operatorBackup, "# operator's own backup from last week\na: 0\n");
        Files.setLastModifiedTime(operatorBackup, OLD);

        assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        AtomicConfigWriter.write(target, "a: 2\n", crossDevice);
        assertThat(frameworkBackups()).hasSize(1);
        assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);

        assertThat(read(operatorBackup)).isEqualTo("# operator's own backup from last week\na: 0\n");
        assertThat(Files.getLastModifiedTime(operatorBackup)).isEqualTo(OLD);
        assertThat(names()).containsExactly("fixture.yml", "fixture.yml.bak");
    }

    /** The fallback backup gets a framework-only name and is created exclusively. */
    @Test
    void fallbackBackupHasAFrameworkOnlyNameCreatedExclusively() throws Exception {
        List<Path> opened = new ArrayList<>();
        AtomicConfigWriter.FileOperations observing = new AtomicConfigWriter.FileOperations() {
            @Override
            public void move(Path source, Path destination, CopyOption... options) throws IOException {
                crossDevice.move(source, destination, options);
            }

            @Override
            public FileChannel openBackup(Path backup, FileAttribute<?>... attributes) throws IOException {
                assertThat(Files.exists(backup)).as("a fresh name").isFalse();
                opened.add(backup);
                return AtomicConfigWriter.FileOperations.super.openBackup(backup, attributes);
            }
        };

        AtomicConfigWriter.write(target, "a: 2\n", observing);

        assertThat(opened).hasSize(1);
        assertThat(opened.get(0).getFileName().toString()).matches(BACKUP.pattern());
        assertThat(read(opened.get(0))).isEqualTo("a: 1\n");
        assertThat(read(target)).isEqualTo("a: 2\n");
        Path taken = dir.resolve("fixture.yml.ultitools-backup-0123456789abcdef");
        write(taken, "taken\n");
        assertThatThrownBy(() -> new AtomicConfigWriter.FileOperations() { }.openBackup(taken).close())
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(read(taken)).isEqualTo("taken\n");
    }

    /** The next strict load deletes the backup this run recorded, while its bytes still match the record. */
    @Test
    void nextStrictLoadDeletesTheRecordedBackup() throws Exception {
        AtomicConfigWriter.write(target, "a: 2\n", crossDevice);
        assertThat(frameworkBackups()).hasSize(1);

        assertThat(ConfigDocument.load(target).state()).isEqualTo(ConfigLoadResult.State.LOADED);

        assertThat(frameworkBackups()).isEmpty();
        assertThat(names()).containsExactly("fixture.yml");
    }

    /** A recorded backup whose bytes changed since the writer recorded them is kept and named once at INFO. */
    @Test
    void recordedBackupWhoseBytesChangedIsKeptAndNamedOnceAtInfo() throws Exception {
        AtomicConfigWriter.write(target, "a: 2\n", crossDevice);
        Path backup = frameworkBackups().get(0);
        write(backup, "edited by someone\n");

        ConfigDocument.load(target);
        ConfigDocument.load(target);

        assertThat(read(backup)).isEqualTo("edited by someone\n");
        assertThat(infoNaming(backup)).hasSize(1);
    }

    /** A file of the framework's backup pattern that this run did not create is kept and named once at INFO. */
    @Test
    void leftoverBackupThisRunDidNotCreateIsKeptAndNamedOnceAtInfo() throws Exception {
        Path leftover = dir.resolve("fixture.yml.ultitools-backup-0123456789abcdef");
        write(leftover, "a: 0\n");

        ConfigDocument.load(target);
        AtomicConfigWriter.write(target, "a: 2\n");
        ConfigDocument.load(target);

        assertThat(read(leftover)).isEqualTo("a: 0\n");
        assertThat(infoNaming(leftover)).hasSize(1);
    }

    /** Names outside the writer's two patterns are never read, written or deleted. */
    @Test
    void otherNamesAreNeverTouched() throws Exception {
        String[] others = {"fixture.yml.bak", "fixture.yml.old", "fixture.yml.ultitools-backup-xyz",
            "fixture.yml.ultitools-backup-0123456789ABCDEF", "fixture.yml.ultitools-backup-0123456789abcdef0"};
        for (String name : others) {
            write(dir.resolve(name), name + "\n");
            Files.setLastModifiedTime(dir.resolve(name), OLD);
        }

        ConfigDocument.load(target);
        AtomicConfigWriter.write(target, "a: 2\n", crossDevice);
        ConfigDocument.load(target);

        for (String name : others) {
            assertThat(read(dir.resolve(name))).isEqualTo(name + "\n");
            assertThat(Files.getLastModifiedTime(dir.resolve(name))).isEqualTo(OLD);
            assertThat(infoNaming(dir.resolve(name))).isEmpty();
        }
        assertThat(frameworkBackups()).isEmpty();
    }
}
