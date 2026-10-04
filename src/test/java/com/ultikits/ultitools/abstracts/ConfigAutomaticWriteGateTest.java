package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Every automatic write a module configuration entity makes goes through the config write gate, so none of
 * them can change an operator byte outside what it owns (maintainer decisions of 2026-10-04: operator-written
 * configuration is never overwritten automatically; what code may write, by file type). Each test states the
 * write path it covers and why that path cannot overwrite operator content.
 */
class ConfigAutomaticWriteGateTest {

    private static final String PATH = "gate.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);
    private static final String EN = "Interval in seconds";
    private static final String ZH = "Interval (zh catalogue)";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private String language = EN;
    private final List<LogRecord> warnings = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Logger frameworkLogger = Logger.getLogger("com.ultikits.ultitools");
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

    @ConfigEntity(PATH)
    public static class Gate extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "{gate.interval}")
        int interval = 300;
        @ConfigEntry(path = "enabled", comment = "Whether the feature is enabled")
        boolean enabled = true;

        public Gate(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Logger moduleLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(u -> Mockito.when(u.getLogger()).thenReturn(moduleLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("GateModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        lenient().when(plugin.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(plugin.i18n("gate.interval")).thenAnswer(i -> language);
        frameworkLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        frameworkLogger.removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private void put(String text) throws IOException {
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file(), OLD);
    }

    private byte[] bytes() throws IOException {
        return Files.readAllBytes(file());
    }

    private void putFixture(String name) throws IOException {
        try (java.io.InputStream in = ConfigAutomaticWriteGateTest.class.getResourceAsStream("/config-golden/hand-edited/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            Files.write(file(), out.toByteArray());
            Files.setLastModifiedTime(file(), OLD);
        }
    }

    private List<String> warningsNamingTheFile() {
        List<String> result = new ArrayList<>();
        synchronized (warnings) {
            for (LogRecord record : warnings) {
                if (record.getMessage() != null && record.getMessage().contains(PATH)) {
                    result.add(record.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * #603 (#597 review F2, second-reload probe): a comment-only write that fails changes no save state, so no
     * shutdown save follows from it and an operator's value or deleted key on disk is never written over.
     */
    @Test
    void failedCommentWriteMarksNothingAfterSecondReload() throws Exception {
        put("# " + EN + "\ninterval: 300\n# Whether the feature is enabled\nenabled: true\n");
        Gate config = new Gate(PATH);
        config.init(plugin);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();

        language = ZH;
        byte[] before = bytes();
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            // Automatic writes publish through the write gate, which stages before its last-moment re-read (17-63 IN-01).
            writer.when(() -> AtomicConfigWriter.stage(Mockito.eq(file()), Mockito.anyString()))
                    .thenThrow(new IOException("injected write failure"));

            config.reload();
            assertThat(warningsNamingTheFile()).as("one warning for the failed comment write").hasSize(1);
            assertThat(config.isModifiedSinceSnapshot()).as("after the first reload").isFalse();

            config.reload();
            assertThat(warningsNamingTheFile()).as("one more for the second failed write").hasSize(2);
            assertThat(config.isModifiedSinceSnapshot()).as("after the second reload").isFalse();
        }
        assertThat(bytes()).isEqualTo(before);
        assertThat(config.interval).isEqualTo(300);
    }

    /**
     * #602: the batch flush writes the candidate computed at {@code initForBatch} only while the file still
     * holds the bytes read then; an operator edit saved during module start-up is never written over.
     */
    @Test
    void batchFlushOfAFileEditedAfterItWasReadWritesNothing() throws Exception {
        put("# " + EN + "\ninterval: 300\n");
        Gate config = new Gate(PATH);
        config.initForBatch(plugin);
        String edited = "# " + EN + "\ninterval: 450\n";
        put(edited);

        config.flushInitializationWrite();

        assertThat(new String(bytes(), StandardCharsets.UTF_8)).isEqualTo(edited);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
        assertThat(warningsNamingTheFile()).hasSize(1);
        assertThat(warningsNamingTheFile().get(0)).contains("changed after it was read").contains("enabled");
        assertThat(config.enabled).as("the declared default runs in memory").isTrue();
    }

    /** #602: first-time creation with no interference writes every declared key with its comment. */
    @Test
    void firstTimeCreationHoldsEveryDeclaredKeyWithItsComment() throws Exception {
        Gate config = new Gate(PATH);
        config.init(plugin);

        assertThat(new String(bytes(), StandardCharsets.UTF_8))
                .isEqualTo("# " + EN + "\ninterval: 300\n# Whether the feature is enabled\nenabled: true\n");
        assertThat(warnings).isEmpty();
    }

    /**
     * #600 (inventory P5/A14): a file using anchors is never written automatically - not by the first-start
     * insert - and is named once per server run however many entities and reloads touch it.
     */
    @Test
    void anchoredFileMissingAKeyIsNeverWrittenAndWarnedOncePerRun() throws Exception {
        putFixture("anchored-missing-key.yml");
        byte[] before = bytes();

        Gate config = new Gate(PATH);
        config.init(plugin);
        assertThat(bytes()).isEqualTo(before);
        assertThat(config.enabled).as("the declared default runs in memory").isTrue();
        assertThat(warningsNamingTheFile()).hasSize(1);
        assertThat(warningsNamingTheFile().get(0)).contains("anchors").contains("enabled");

        new Gate(PATH).init(plugin);
        for (int i = 0; i < 10; i++) {
            config.reload();
        }
        assertThat(bytes()).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
        assertThat(warningsNamingTheFile()).as("one warning per file per server run").hasSize(1);
    }

    /**
     * #600 (#597 review F1, second-reload probe; #598 item 1): a comment-only write at start-up or reload after
     * a language switch never rewrites an anchored file; the anchors survive any number of reloads.
     */
    @Test
    void anchoredFileSurvivesSecondReload() throws Exception {
        put("# " + EN + "\ninterval: 300\n# Whether the feature is enabled\nenabled: true\nbase: &b {x: 1}\ncopy: *b\n");
        byte[] before = bytes();

        language = ZH;
        Gate config = new Gate(PATH);
        config.init(plugin);
        assertThat(bytes()).as("start-up after a language switch").isEqualTo(before);

        language = "Interval (a further switch)";
        config.reload();
        config.reload();
        assertThat(bytes()).as("two reloads after a further switch").isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
        assertThat(warningsNamingTheFile()).hasSize(1);
        assertThat(warningsNamingTheFile().get(0)).contains("anchors");
    }

    /** #600 (inventory A15): a multi-line string in a file without a final line break is never re-quoted. */
    @Test
    void multiLineStringWithoutFinalNewlineAndMissingKeyIsRefused() throws Exception {
        putFixture("multiline-no-final-newline.yml");
        byte[] before = bytes();

        Gate config = new Gate(PATH);
        config.init(plugin);

        assertThat(bytes()).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
        assertThat(config.enabled).isTrue();
        assertThat(warningsNamingTheFile()).hasSize(1);
        assertThat(warningsNamingTheFile().get(0)).contains("layout").contains("enabled");
    }

    /**
     * #600 (inventory P4/A13/A15): on a hand-aligned file whose list carries item comments, neither the insert
     * nor a comment-only write changes a byte; each refusal is one warning.
     */
    @Test
    void handAlignedFileWithListItemCommentsIsTouchedByNeitherInsertNorCommentWrite() throws Exception {
        putFixture("hand-aligned.yml");
        byte[] before = bytes();

        Gate config = new Gate(PATH);
        config.init(plugin);
        assertThat(bytes()).as("the insert").isEqualTo(before);
        assertThat(warningsNamingTheFile()).hasSize(1);

        language = ZH;
        config.reload();
        assertThat(bytes()).as("the comment-only write").isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
        assertThat(warningsNamingTheFile()).hasSize(2);
        assertThat(warningsNamingTheFile().get(1)).contains("layout").contains("interval (comment)");
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    /**
     * #602, review round 1 WR-01 (flush): when the gate does not write because the operator edited the file after
     * {@code initForBatch} read it, the entity records the bytes it bound as "last read", not the operator's new
     * file - so the edit is still visible as a change on disk to every later check.
     */
    @Test
    void refusedFlushDoesNotRecordTheOperatorsNewBytesAsLastRead() throws Exception {
        put("# " + EN + "\ninterval: 300\n");
        Gate config = new Gate(PATH);
        config.initForBatch(plugin);
        put("# " + EN + "\ninterval: 450\n");

        config.flushInitializationWrite();

        assertThat(config.interval).as("bound from the bytes initForBatch read").isEqualTo(300);
        assertThat(config.isFileModifiedSinceSnapshot()).as("the operator's edit is a change on disk").isTrue();
    }

    /**
     * #602, review round 1 WR-01 (load): the same for an init whose insert the gate does not write because the file
     * changed between the entity's read and the gate's read.
     */
    @Test
    void refusedInitInsertDoesNotRecordTheOperatorsNewBytesAsLastRead() throws Exception {
        put("# " + EN + "\ninterval: 300\n");
        String edited = "# " + EN + "\ninterval: 450\n";
        try (MockedStatic<com.ultikits.ultitools.config.document.OperatorFileWriter> gate =
                Mockito.mockStatic(com.ultikits.ultitools.config.document.OperatorFileWriter.class, Mockito.CALLS_REAL_METHODS)) {
            gate.when(() -> com.ultikits.ultitools.config.document.OperatorFileWriter.write(Mockito.any(Path.class),
                    Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(call -> {
                        put(edited);
                        return call.callRealMethod();
                    });
            Gate config = new Gate(PATH);
            config.init(plugin);

            assertThat(new String(bytes(), StandardCharsets.UTF_8)).isEqualTo(edited);
            assertThat(config.interval).isEqualTo(300);
            assertThat(config.isFileModifiedSinceSnapshot()).as("the operator's edit is a change on disk").isTrue();
        }
    }

    /** A refusal with no concurrent edit records the file as read: nothing changed on disk. */
    @Test
    void refusedInitInsertWithoutAnEditLeavesTheFileUnmodifiedSinceRead() throws Exception {
        putFixture("hand-aligned.yml");

        Gate config = new Gate(PATH);
        config.init(plugin);

        assertThat(config.isFileModifiedSinceSnapshot()).isFalse();
    }

    /**
     * #600, review round 1 IN-03: a write refused because the file changed after it was read names only the keys it
     * would have changed - not a token comment that already matches the language.
     */
    @Test
    void fileChangedRefusalNamesOnlyKeysThatWouldChange() throws Exception {
        put("# " + EN + "\ninterval: 300\n");
        Gate config = new Gate(PATH);
        config.initForBatch(plugin);
        put("# " + EN + "\ninterval: 450\n");

        config.flushInitializationWrite();

        assertThat(warningsNamingTheFile()).hasSize(1);
        assertThat(warningsNamingTheFile().get(0)).contains("enabled").doesNotContain("interval (comment)");
    }
}
