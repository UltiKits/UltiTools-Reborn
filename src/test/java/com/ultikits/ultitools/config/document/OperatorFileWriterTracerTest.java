package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.mockito.Mockito;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Tracer of the config write gate (UltiKits/UltiTools-Reborn#600): one missing key is inserted through
 * {@link OperatorFileWriter} end to end with every other byte unchanged, and a file whose unrelated lines
 * an operator aligned by hand is not written at all - the declared default runs in memory and one warning
 * names the file, the key and the layout reason (maintainer decision 2026-10-04, "what code may write, by
 * file type": insert only, never change existing content; verify, or abandon with a warning).
 */
class OperatorFileWriterTracerTest {

    private static final String PATH = "tracer.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
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
    public static class Tracer extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "Interval in seconds")
        int interval = 300;
        @ConfigEntry(path = "message")
        String message = "hello";
        @ConfigEntry(path = "list")
        List<Integer> list = new ArrayList<>(Arrays.asList(1, 2));
        @ConfigEntry(path = "enabled", comment = "Whether the feature is enabled")
        boolean enabled = true;

        public Tracer(String path) {
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
        lenient().when(plugin.getPluginName()).thenReturn("Tracer");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        lenient().when(plugin.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
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

    private void put(String text) throws Exception {
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file(), OLD);
    }

    private byte[] bytes() throws Exception {
        return Files.readAllBytes(file());
    }

    /** Splits after every line feed, keeping the terminator, so a changed line ending is a changed line. */
    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    @Test
    void missingKeyIsInsertedWithItsCommentAndEveryOtherLineIsByteIdentical() throws Exception {
        String before = "# Interval in seconds\ninterval: 300\nmessage: hi\nlist: [1, 2]\n";
        put(before);

        new Tracer(PATH).init(plugin);

        String after = new String(bytes(), StandardCharsets.UTF_8);
        List<String> added = new ArrayList<>(lines(after));
        for (String line : lines(before)) {
            assertThat(added).as("an original line, byte for byte").contains(line);
            added.remove(line);
        }
        assertThat(added).containsExactly("# Whether the feature is enabled\n", "enabled: true\n");
        assertThat(after).isEqualTo(before + "# Whether the feature is enabled\nenabled: true\n");
        assertThat(warnings).isEmpty();
    }

    @Test
    void handAlignedFileMissingAKeyIsNotWrittenAndOneWarningNamesFileKeyAndLayout() throws Exception {
        put("# Interval in seconds\ninterval: 317\nmessage:    greeting-value-x   # aligned\nlist: [ 60,30 , 10 ]\n");
        byte[] before = bytes();

        Tracer config = new Tracer(PATH);
        config.init(plugin);

        assertThat(bytes()).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
        assertThat(config.enabled).as("the declared default runs in memory").isTrue();
        assertThat(config.interval).isEqualTo(317);
        assertThat(config.message).isEqualTo("greeting-value-x");
        assertThat(warnings).hasSize(1);
        String message = warnings.get(0).getMessage();
        assertThat(message).contains(file().toAbsolutePath().toString()).contains("enabled").contains("layout");
        String withoutPath = message.replace(file().toAbsolutePath().toString(), "<file>");
        assertThat(withoutPath).doesNotContain("317").doesNotContain("greeting-value-x").doesNotContain("60,30");
        assertThat(withoutPath).doesNotContainPattern("\\btrue\\b");
    }

    @Test
    void ownedValuePathChangesExactlyItsLine() throws Exception {
        String before = "# Interval in seconds\ninterval: 300\nmessage: hi\n";
        put(before);

        OperatorFileWriter.Result result = OperatorFileWriter.write(file(),
                OwnedPaths.builder().value(Collections.singletonList("interval")).build(), null,
                document -> document.set(Collections.singletonList("interval"), 450));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(new String(bytes(), StandardCharsets.UTF_8))
                .isEqualTo("# Interval in seconds\ninterval: 450\nmessage: hi\n");
    }

    @Test
    void ownedSpanSharingALineWithAnUnownedSiblingIsRefused() throws Exception {
        put("item: {a: 1, b: 2}\nother: x\n");
        byte[] before = bytes();

        OperatorFileWriter.Result result = OperatorFileWriter.write(file(),
                OwnedPaths.builder().value(Arrays.asList("item", "a")).build(), null,
                document -> document.set(Arrays.asList("item", "a"), 5));

        assertThat(result.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(bytes()).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
        assertThat(warnings).hasSize(1);
        String withoutPath = warnings.get(0).getMessage().replace(file().toAbsolutePath().toString(), "<file>");
        assertThat(withoutPath).contains("item.a").doesNotContain("a: 1").doesNotContain("a: 5");
    }
}
