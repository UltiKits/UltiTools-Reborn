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
            writer.when(() -> AtomicConfigWriter.write(Mockito.eq(file()), Mockito.anyString()))
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
}
