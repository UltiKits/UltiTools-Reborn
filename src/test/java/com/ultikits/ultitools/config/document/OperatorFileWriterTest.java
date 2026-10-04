package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config write gate on its own: what it refuses, what it publishes, and how (maintainer decisions of
 * 2026-10-04: operator-written configuration is never overwritten automatically; what code may write, by
 * file type).
 */
class OperatorFileWriterTest {

    private static final String OPERATOR = "operator: 1\n";

    @TempDir
    Path tempDir;
    private final List<LogRecord> warnings = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Logger gateLogger = Logger.getLogger(OperatorFileWriter.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
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

    @BeforeEach
    void setUp() {
        gateLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        gateLogger.removeHandler(capture);
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private List<String> directory() throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tempDir)) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** #602: a file that appears after the gate's last read and before the exclusive link is never replaced. */
    @Test
    void creationDoesNotReplaceAFileThatAppearsBeforeTheExclusiveLink() throws Exception {
        Path target = tempDir.resolve("created.yml");
        AtomicConfigWriter.FileOperations appearing = new AtomicConfigWriter.FileOperations() {
            @Override
            public void link(Path link, Path existing) throws IOException {
                Files.write(link, OPERATOR.getBytes(StandardCharsets.UTF_8));
                AtomicConfigWriter.FileOperations.super.link(link, existing);
            }
        };

        OperatorFileWriter.Result result = OperatorFileWriter.write(target, OwnedPaths.wholeFile(), OperatorFileWriter.ABSENT,
                document -> document.set(Collections.singletonList("framework"), 2), appearing);

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.FILE_CHANGED);
        assertThat(read(target)).isEqualTo(OPERATOR);
        assertThat(directory()).containsExactly("created.yml");
        assertThat(warnings).hasSize(1);
    }

    /** #602: with no interference the new file is published through an exclusive hard link (this machine's path). */
    @Test
    void creationWithoutInterferencePublishesThroughAnExclusiveLink() throws Exception {
        Path target = tempDir.resolve("created.yml");
        AtomicInteger links = new AtomicInteger();
        AtomicConfigWriter.FileOperations counting = new AtomicConfigWriter.FileOperations() {
            @Override
            public void link(Path link, Path existing) throws IOException {
                links.incrementAndGet();
                AtomicConfigWriter.FileOperations.super.link(link, existing);
            }
        };

        OperatorFileWriter.Result result = OperatorFileWriter.write(target, OwnedPaths.wholeFile(), OperatorFileWriter.ABSENT,
                document -> document.set(Collections.singletonList("framework"), 2), counting);

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(read(target)).isEqualTo("framework: 2\n");
        assertThat(links.get()).isEqualTo(1);
        assertThat(directory()).containsExactly("created.yml");
        assertThat(warnings).isEmpty();
    }

    /** #602: where the file system refuses links, a re-check and a non-replacing move never replace either. */
    @Test
    void creationWithoutLinksFallsBackToANonReplacingMove() throws Exception {
        Path target = tempDir.resolve("created.yml");
        AtomicConfigWriter.FileOperations noLinksAppearing = new AtomicConfigWriter.FileOperations() {
            @Override
            public void link(Path link, Path existing) {
                throw new UnsupportedOperationException("no hard links on this file system");
            }

            @Override
            public void move(Path source, Path destination, CopyOption... options) throws IOException {
                Files.write(destination, OPERATOR.getBytes(StandardCharsets.UTF_8));
                AtomicConfigWriter.FileOperations.super.move(source, destination, options);
            }
        };

        OperatorFileWriter.Result appeared = OperatorFileWriter.write(target, OwnedPaths.wholeFile(), OperatorFileWriter.ABSENT,
                document -> document.set(Collections.singletonList("framework"), 2), noLinksAppearing);

        assertThat(appeared.outcome()).isEqualTo(OperatorFileWriter.Outcome.FILE_CHANGED);
        assertThat(read(target)).isEqualTo(OPERATOR);
        assertThat(directory()).containsExactly("created.yml");

        Files.delete(target);
        AtomicConfigWriter.FileOperations noLinks = new AtomicConfigWriter.FileOperations() {
            @Override
            public void link(Path link, Path existing) {
                throw new UnsupportedOperationException("no hard links on this file system");
            }
        };
        OperatorFileWriter.Result created = OperatorFileWriter.write(target, OwnedPaths.wholeFile(), OperatorFileWriter.ABSENT,
                document -> document.set(Collections.singletonList("framework"), 2), noLinks);

        assertThat(created.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(read(target)).isEqualTo("framework: 2\n");
        assertThat(directory()).containsExactly("created.yml");
    }

    /** #602: a caller that read the file absent never replaces a file that exists when the gate reads it. */
    @Test
    void wholeFileOwnershipOfAFileThatNowExistsIsFileChanged() throws Exception {
        Path target = tempDir.resolve("created.yml");
        Files.write(target, OPERATOR.getBytes(StandardCharsets.UTF_8));

        OperatorFileWriter.Result result = OperatorFileWriter.write(target, OwnedPaths.wholeFile(), OperatorFileWriter.ABSENT,
                document -> document.set(Collections.singletonList("framework"), 2));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.FILE_CHANGED);
        assertThat(read(target)).isEqualTo(OPERATOR);
    }

    /** #600: an anchored document is refused before the edit runs and named once per file per server run. */
    @Test
    void anchoredDocumentIsRefusedBeforeTheEditAndWarnedOncePerPath() throws Exception {
        Path target = tempDir.resolve("anchored.yml");
        String text = "base: &b {x: 1}\ncopy: *b\n";
        Files.write(target, text.getBytes(StandardCharsets.UTF_8));
        AtomicInteger edits = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            OperatorFileWriter.Result result = OperatorFileWriter.write(target,
                    OwnedPaths.builder().value(Collections.singletonList("added")).build(), null, document -> {
                        edits.incrementAndGet();
                        document.set(Collections.singletonList("added"), 1);
                    });
            assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        }

        assertThat(edits.get()).as("the edit never runs on an anchored document").isZero();
        assertThat(read(target)).isEqualTo(text);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("anchors").contains("added");
    }

    /** A comment-only write changes exactly the owned comment lines. */
    @Test
    void commentOnlyWriteChangesOnlyTheOwnedCommentLines() throws Exception {
        Path target = tempDir.resolve("comments.yml");
        Files.write(target, "# old text\ninterval: 300\n# operator note\nmessage: hi\n".getBytes(StandardCharsets.UTF_8));

        OperatorFileWriter.Result result = OperatorFileWriter.write(target,
                OwnedPaths.builder().comment(Collections.singletonList("interval")).build(), null,
                document -> document.setFrameworkComment(Collections.singletonList("interval"),
                        Collections.singletonList("new text")));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(read(target)).isEqualTo("# new text\ninterval: 300\n# operator note\nmessage: hi\n");
    }

    /**
     * A key appended to a file without a final line break: the old last line gains only the separator, and
     * the file still ends without a line break.
     */
    @Test
    void keyAppendedToAFileWithoutFinalLineBreak() throws Exception {
        Path target = tempDir.resolve("no-eol.yml");
        Files.write(target, "a: 1".getBytes(StandardCharsets.UTF_8));

        OperatorFileWriter.Result result = OperatorFileWriter.write(target,
                OwnedPaths.builder().value(Collections.singletonList("b")).build(), null,
                document -> document.set(Collections.singletonList("b"), 2));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(read(target)).isEqualTo("a: 1\nb: 2");
    }

    /** An edit that changes a key it does not own is refused, whatever the renderer would produce. */
    @Test
    void editOutsideTheOwnedPathsIsRefused() throws Exception {
        Path target = tempDir.resolve("scope.yml");
        String text = "a: 1\nb: 2\n";
        Files.write(target, text.getBytes(StandardCharsets.UTF_8));

        OperatorFileWriter.Result result = OperatorFileWriter.write(target,
                OwnedPaths.builder().value(Collections.singletonList("a")).build(), null, document -> {
                    document.set(Collections.singletonList("a"), 5);
                    document.set(Collections.singletonList("b"), 6);
                });

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(read(target)).isEqualTo(text);
        assertThat(warnings.get(0).getMessage()).contains("does not own").doesNotContain(": 5").doesNotContain(": 6");
    }
}
