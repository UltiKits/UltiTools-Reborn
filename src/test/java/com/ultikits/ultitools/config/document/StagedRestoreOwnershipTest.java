package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
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
 * UltiKits/UltiTools-Reborn#673 case 13 at the write gate: {@link OperatorFileWriter.Staged#restore()} acts only on bytes
 * the write still owns after its commit. Every row stages a real edit through the gate with a fault-injecting
 * {@link AtomicConfigWriter.FileOperations}, drives {@code commit()}, an optional operator action and {@code restore()}
 * (twice: the second call must agree with the first and change nothing), then asserts the exact file bytes or absence,
 * the framework backups present, that no temporary is left, the return value, and the protection of a later write: a
 * write verified against the original bytes is {@code FILE_CHANGED} exactly when the file no longer holds them.
 * <p>
 * Why it cannot overwrite operator content: the tests only exercise the rollback; every file they write is a synthetic
 * file in a per-test temporary directory.
 */
@DisplayName("A batch rollback acts only on bytes the failed or published write still owns (#673 case 13)")
class StagedRestoreOwnershipTest {

    private static final String ORIGINAL = "# Gate settings\nmotd: original-motd\nlimit: 5\n";
    private static final String WRITTEN = "# Gate settings\nmotd: new-motd\nlimit: 5\n";
    private static final String OPERATOR = "# Gate settings\nmotd: original-motd\nlimit: 8\n# saved by the operator\n";
    private static final String BACKUP_NAME = ".*\\.ultitools-backup-[0-9a-f]{16}";
    private static final Consumer<ConfigDocument> EDIT = document ->
            document.set(Collections.singletonList("motd"), "new-motd");

    @TempDir
    Path tempDir;
    private Path target;
    private Faults faults;
    /** Held in a field so the configured logger cannot be collected while the handler is attached. */
    private final Logger documentLogger = Logger.getLogger("com.ultikits.ultitools.config.document");
    private final List<LogRecord> logged = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                logged.add(record);
            }
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

    /**
     * Fails chosen steps of a commit and counts every hook it reached: the atomic move onto the target (a plain failure,
     * or a refusal that sends the commit to the backed in-place path), the creation of the in-place backup, the hard link
     * that publishes a new file, and the force or open of the target counted by target open.
     */
    static final class Faults implements AtomicConfigWriter.FileOperations {
        IOException moveFailure;
        boolean refuseMove;
        boolean failBackup;
        IOException linkFailure;
        final Set<Integer> forceFailures = new HashSet<>();
        final Set<Integer> openFailures = new HashSet<>();
        int moveHits;
        int backupHits;
        int linkHits;
        int forceHits;
        int openHits;
        private int targetOpens;
        private FileChannel opened;

        @Override
        public void move(Path source, Path destination, CopyOption... options) throws IOException {
            if (!destination.getFileName().toString().contains(".ultitools-backup-")) {
                if (moveFailure != null) {
                    moveHits++;
                    throw moveFailure;
                }
                if (refuseMove) {
                    moveHits++;
                    throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
                }
            }
            Files.move(source, destination, options);
        }

        @Override
        public FileChannel openBackup(Path backup, FileAttribute<?>... attributes) throws IOException {
            if (failBackup) {
                backupHits++;
                throw new IOException("injected backup creation failure");
            }
            return AtomicConfigWriter.FileOperations.super.openBackup(backup, attributes);
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            if (linkFailure != null) {
                linkHits++;
                throw linkFailure;
            }
            AtomicConfigWriter.FileOperations.super.link(link, existing);
        }

        @Override
        public FileChannel openTarget(Path file) throws IOException {
            targetOpens++;
            if (openFailures.contains(targetOpens)) {
                openHits++;
                throw new IOException("injected open failure");
            }
            opened = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return opened;
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            if (channel.equals(opened) && forceFailures.contains(targetOpens)) {
                forceHits++;
                throw new IOException("injected force failure");
            }
            channel.force(true);
        }
    }

    @BeforeEach
    void setUp() {
        target = tempDir.resolve("gate.yml");
        faults = new Faults();
        documentLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        documentLogger.removeHandler(capture);
    }

    // ------------------------------------------------------------------------------------------- unpublished writes

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"move fails", "refused move, then the backup cannot be created"})
    @DisplayName("an ordinary commit failure published nothing: an operator save made afterwards is left alone")
    void ordinaryFailureLeavesAnOperatorSaveAlone(String failure) throws Exception {
        write(ORIGINAL);
        if (failure.startsWith("move")) {
            faults.moveFailure = new IOException("injected move failure");
        } else {
            faults.refuseMove = true;
            faults.failBackup = true;
        }
        OperatorFileWriter.Staged staged = stage(ORIGINAL);

        Throwable thrown = catchThrowable(staged::commit);
        assertThat(thrown).isInstanceOf(IOException.class)
                .isNotInstanceOf(AtomicConfigWriter.RestoreFailedException.class);
        assertThat(faults.moveHits).as("the move hook was reached").isEqualTo(1);
        assertThat(faults.backupHits).as("the backup hook was reached").isEqualTo(failure.startsWith("move") ? 0 : 1);
        assertThat(read()).as("the failed commit left the file as it was").isEqualTo(ORIGINAL);
        write(OPERATOR);

        assertThat(restoreTwice(staged)).isTrue();
        assertThat(read()).as("the operator's save is kept").isEqualTo(OPERATOR);
        assertDirectory(false);
        assertThat(logged).as("nothing to report").noneMatch(record -> record.getMessage().contains("not restored"));
        assertLaterWrite(sha(ORIGINAL));
    }

    @Test
    @DisplayName("a failed creation created nothing: a file the operator created afterwards is never deleted")
    void failedCreationNeverDeletesAFileTheOperatorCreated() throws Exception {
        faults.linkFailure = new IOException("injected link failure");
        OperatorFileWriter.Staged staged = stage(OperatorFileWriter.ABSENT);

        Throwable thrown = catchThrowable(staged::commit);
        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("injected link failure");
        assertThat(faults.linkHits).as("the link hook was reached").isEqualTo(1);
        assertThat(Files.exists(target)).as("nothing was created").isFalse();
        write(OPERATOR);

        assertThat(restoreTwice(staged)).isTrue();
        assertThat(Files.exists(target)).as("the operator's file is never deleted").isTrue();
        assertThat(read()).as("the operator's file is kept").isEqualTo(OPERATOR);
        assertDirectory(false);
        assertLaterWrite(OperatorFileWriter.ABSENT);
    }

    @Test
    @DisplayName("a failed in-place write that was put back owns no byte: an operator save afterwards is left alone")
    void putBackSucceededLeavesAnOperatorSaveAlone() throws Exception {
        write(ORIGINAL);
        faults.refuseMove = true;
        faults.forceFailures.add(1);
        OperatorFileWriter.Staged staged = stage(ORIGINAL);

        Throwable thrown = catchThrowable(staged::commit);
        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("injected force failure")
                .isNotInstanceOf(AtomicConfigWriter.RestoreFailedException.class);
        assertThat(faults.forceHits).as("the force hook was reached").isEqualTo(1);
        assertThat(read()).as("put back from the backup").isEqualTo(ORIGINAL);
        assertThat(backups()).as("the backup is removed once the file is put back").isEmpty();
        write(OPERATOR);

        assertThat(restoreTwice(staged)).isTrue();
        assertThat(read()).as("the operator's save is kept").isEqualTo(OPERATOR);
        assertDirectory(false);
        assertLaterWrite(sha(ORIGINAL));
    }

    // ------------------------------------------------------------------------------------------- failed put-back

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"no operator action", "operator saves new bytes"})
    @DisplayName("a failed put-back is never written or deleted by the rollback; the backup keeps the original")
    void failedPutBackIsNeverRewritten(String action) throws Exception {
        write(ORIGINAL);
        faults.refuseMove = true;
        faults.forceFailures.add(1);
        faults.openFailures.add(2);
        OperatorFileWriter.Staged staged = stage(ORIGINAL);

        Throwable thrown = catchThrowable(staged::commit);
        assertThat(thrown).isInstanceOf(AtomicConfigWriter.RestoreFailedException.class);
        assertThat(faults.forceHits).as("the force hook was reached").isEqualTo(1);
        assertThat(faults.openHits).as("the put-back open hook was reached").isEqualTo(1);
        assertThat(read()).as("what the failed write left").isEqualTo(WRITTEN);
        assertThat(severe()).hasSize(1);
        String expected = WRITTEN;
        if (action.startsWith("operator")) {
            write(OPERATOR);
            expected = OPERATOR;
        }

        assertThat(restoreTwice(staged)).as("the file does not hold what it held before the write").isFalse();
        assertThat(read()).as("the rollback neither writes nor deletes it").isEqualTo(expected);
        List<Path> backups = backups();
        assertThat(backups).hasSize(1);
        assertThat(new String(Files.readAllBytes(backups.get(0)), StandardCharsets.UTF_8))
                .as("the backup still holds the original").isEqualTo(ORIGINAL);
        assertThat(severe()).as("no further SEVERE").hasSize(1);
        assertDirectory(true);
        assertLaterWrite(sha(ORIGINAL));
    }

    @Test
    @DisplayName("control: after a failed put-back, restore reports true only when the operator restored the read bytes")
    void failedPutBackReportsRestoredOnlyForTheReadBytes() throws Exception {
        write(ORIGINAL);
        faults.refuseMove = true;
        faults.forceFailures.add(1);
        faults.openFailures.add(2);
        OperatorFileWriter.Staged staged = stage(ORIGINAL);
        assertThat(catchThrowable(staged::commit)).isInstanceOf(AtomicConfigWriter.RestoreFailedException.class);
        write(ORIGINAL);

        assertThat(restoreTwice(staged)).isTrue();
        assertThat(read()).isEqualTo(ORIGINAL);
        assertThat(backups()).hasSize(1);
        assertDirectory(true);
        assertLaterWrite(sha(ORIGINAL));
    }

    // ------------------------------------------------------------------------------------------- controls

    @Test
    @DisplayName("control: an ordinary failure without any save leaves the original bytes; a second commit is refused")
    void ordinaryFailureWithoutSaveLeavesTheOriginal() throws Exception {
        write(ORIGINAL);
        faults.moveFailure = new IOException("injected move failure");
        OperatorFileWriter.Staged staged = stage(ORIGINAL);
        assertThat(catchThrowable(staged::commit)).isInstanceOf(IOException.class);
        assertThat(faults.moveHits).isEqualTo(1);
        assertThatThrownBy(staged::commit).isInstanceOf(IllegalStateException.class);

        assertThat(restoreTwice(staged)).isTrue();
        assertThat(read()).isEqualTo(ORIGINAL);
        assertDirectory(false);
        assertLaterWrite(sha(ORIGINAL));
    }

    @Test
    @DisplayName("control: a published write is put back while the file still holds the written bytes")
    void publishedWriteIsPutBackWhenUntouched() throws Exception {
        write(ORIGINAL);
        OperatorFileWriter.Staged staged = stage(ORIGINAL);
        assertThat(staged.commit().outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(read()).isEqualTo(WRITTEN);
        assertThatThrownBy(staged::commit).isInstanceOf(IllegalStateException.class);

        assertThat(restoreTwice(staged)).isTrue();
        assertThat(read()).isEqualTo(ORIGINAL);
        assertThat(logged).isEmpty();
        assertDirectory(false);
        assertLaterWrite(sha(ORIGINAL));
    }

    @Test
    @DisplayName("control: a published write the operator saved over is kept, with one WARNING")
    void publishedWriteSavedAfterwardsIsKeptWithOneWarning() throws Exception {
        write(ORIGINAL);
        OperatorFileWriter.Staged staged = stage(ORIGINAL);
        assertThat(staged.commit().outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        write(OPERATOR);

        assertThat(staged.restore()).isFalse();
        assertThat(read()).isEqualTo(OPERATOR);
        assertThat(logged).as("one WARNING names the kept file").hasSize(1);
        assertThat(logged.get(0).getMessage()).contains("gate.yml").contains("was not restored");
        assertThat(restoreTwice(staged)).as("later calls agree").isFalse();
        assertThat(read()).isEqualTo(OPERATOR);
        assertDirectory(false);
        assertLaterWrite(sha(ORIGINAL));
    }

    @Test
    @DisplayName("control: a FILE_CHANGED settlement restores nothing")
    void fileChangedSettlementRestoresNothing() throws Exception {
        write(ORIGINAL);
        OperatorFileWriter.Staged staged = stage(ORIGINAL);
        write(OPERATOR);
        assertThat(staged.commit().outcome()).isEqualTo(OperatorFileWriter.Outcome.FILE_CHANGED);

        assertThat(restoreTwice(staged)).isTrue();
        assertThat(read()).isEqualTo(OPERATOR);
        assertThat(faults.moveHits + faults.linkHits + faults.forceHits).isZero();
        assertDirectory(false);
        assertLaterWrite(sha(ORIGINAL));
    }

    // ------------------------------------------------------------------------------------------- helpers

    private OperatorFileWriter.Staged stage(String expectedFingerprintSource) throws IOException {
        String expected = OperatorFileWriter.ABSENT.equals(expectedFingerprintSource) ? OperatorFileWriter.ABSENT
                : sha(expectedFingerprintSource);
        OperatorFileWriter.Staged staged = OperatorFileWriter.stage(target, owned(), expected, EDIT, faults);
        assertThat(staged.isPending()).as("precondition: the edit was verified and staged").isTrue();
        return staged;
    }

    private static OwnedPaths owned() {
        return OwnedPaths.builder().value(Collections.singletonList("motd")).build();
    }

    /** Calls restore twice; the second call must return the same and leave the file's bytes as the first left them. */
    private boolean restoreTwice(OperatorFileWriter.Staged staged) throws IOException {
        boolean first = staged.restore();
        byte[] after = Files.exists(target) ? Files.readAllBytes(target) : null;
        boolean second = staged.restore();
        assertThat(second).as("restore() twice gives the same result").isEqualTo(first);
        assertThat(Files.exists(target) ? Files.readAllBytes(target) : null).as("the second restore changes nothing")
                .isEqualTo(after);
        return first;
    }

    /**
     * A later write verified against the original bytes (or absence) is FILE_CHANGED exactly when the file no longer holds
     * them, and WRITTEN otherwise; a refused write keeps the file's bytes.
     */
    private void assertLaterWrite(String originalFingerprint) throws IOException {
        byte[] before = Files.exists(target) ? Files.readAllBytes(target) : null;
        String now = before == null ? OperatorFileWriter.ABSENT : ConfigDocument.sha256(before);
        OperatorFileWriter.Result later = OperatorFileWriter.write(target, owned(), originalFingerprint, EDIT);
        if (now.equals(originalFingerprint)) {
            assertThat(later.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        } else {
            assertThat(later.outcome()).isEqualTo(OperatorFileWriter.Outcome.FILE_CHANGED);
            assertThat(Files.readAllBytes(target)).as("the later write keeps the bytes").isEqualTo(before);
        }
    }

    /** The directory holds only the target (when present) and, when expected, one framework backup: no temporary. */
    private void assertDirectory(boolean backupExpected) throws IOException {
        List<String> names;
        try (Stream<Path> paths = Files.list(tempDir)) {
            names = paths.map(path -> path.getFileName().toString()).collect(Collectors.toList());
        }
        List<String> others = names.stream().filter(name -> !name.equals("gate.yml") && !name.matches(BACKUP_NAME))
                .collect(Collectors.toList());
        assertThat(others).as("no temporary is left").isEmpty();
        assertThat(names.stream().filter(name -> name.matches(BACKUP_NAME)).count())
                .as("framework backups").isEqualTo(backupExpected ? 1L : 0L);
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> paths = Files.list(tempDir)) {
            return paths.filter(path -> path.getFileName().toString().matches(BACKUP_NAME)).collect(Collectors.toList());
        }
    }

    private List<LogRecord> severe() {
        synchronized (logged) {
            return logged.stream().filter(record -> Level.SEVERE.equals(record.getLevel())).collect(Collectors.toList());
        }
    }

    private void write(String text) throws IOException {
        Files.write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    private String read() throws IOException {
        return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
    }

    private static String sha(String text) {
        return ConfigDocument.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
