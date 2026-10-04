package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Tracer of the save rule (#599; maintainer decision 2026-10-04, "what code may write, by file type": no
 * whole-object incidental save; supersedes #527's overwrite-and-warn). A module's {@code save()} writes a setting
 * only when the module changed it since the last load or save, the file still holds at that path exactly what the
 * framework last read there, and that is the value the module started from - through the config write gate, so
 * every other byte of the file stays as the operator left it. Why it cannot overwrite operator content: a setting
 * the operator edited, deleted or wrote unusably is never in the writable set, and the gate refuses any byte change
 * outside the written settings.
 */
@DisplayName("A module save writes only what the module changed, where the file still holds what it read (#599)")
class ConfigSaveWriteGateTracerTest {

    private static final String PATH = "tracer.yml";
    private static final String MAP_PATH = "tracer-map.yml";
    private static final String TEXT = "# Interval in seconds\ninterval: 300\n# Greeting shown on join\nmessage: hi\n";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private Logger frameworkLogger;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Logger packageLogger = Logger.getLogger("com.ultikits.ultitools");
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
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
        @ConfigEntry(path = "message", comment = "Greeting shown on join")
        String message = "hi";

        public Tracer(String path) {
            super(path);
        }
    }

    @ConfigEntity(MAP_PATH)
    public static class Emojis extends AbstractConfigEntity {
        @ConfigEntry(path = "emojis")
        Map<String, String> emojis = new LinkedHashMap<>();

        public Emojis(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        frameworkLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> Mockito.when(ultiTools.getLogger()).thenReturn(frameworkLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("TracerModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        packageLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        packageLogger.removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    private void put(String name, String text) throws IOException {
        Files.write(tempDir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(name)), StandardCharsets.UTF_8);
    }

    /** Every WARNING logged through the framework logger or any logger of the framework's packages. */
    private List<String> warnings() {
        List<String> result = new ArrayList<>();
        ArgumentCaptor<Level> levels = ArgumentCaptor.forClass(Level.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(frameworkLogger, Mockito.atLeast(0)).log(levels.capture(), messages.capture());
        for (int i = 0; i < messages.getAllValues().size(); i++) {
            if (levels.getAllValues().get(i).intValue() >= Level.WARNING.intValue()) {
                result.add(messages.getAllValues().get(i));
            }
        }
        synchronized (records) {
            for (LogRecord record : records) {
                result.add(record.getMessage());
            }
        }
        return result;
    }

    @Test
    @DisplayName("tracer: the module's change is written and the operator's disk edit of another setting stays, byte for byte")
    void moduleSaveKeepsTheOperatorsEditOfAnotherSetting() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        put(PATH, TEXT.replace("interval: 300", "interval: 450"));

        config.message = "welcome";
        config.save();

        assertThat(read(PATH)).isEqualTo(TEXT.replace("interval: 300", "interval: 450").replace("message: hi", "message: welcome"));
        assertThat(warnings()).isEmpty();
        assertThat(config.interval).as("memory keeps what the module last read until a reload").isEqualTo(300);
    }

    @Test
    @DisplayName("a change to the setting the operator edited on disk is not written; one warning names the file and the key, never a value")
    void moduleChangeOfTheEditedSettingIsNotWrittenAndNamedOnce() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        String edited = TEXT.replace("interval: 300", "interval: 450");
        put(PATH, edited);

        config.interval = 600;
        config.save();

        assertThat(read(PATH)).isEqualTo(edited);
        assertThat(config.interval).as("the module's value stays in memory").isEqualTo(600);
        List<String> warnings = warnings();
        assertThat(warnings).hasSize(1);
        String file = tempDir.resolve(PATH).toAbsolutePath().toString();
        assertThat(warnings.get(0)).contains(file, "interval");
        assertThat(warnings.get(0).replace(file, "")).doesNotContain("600", "450", "300");
    }

    @Test
    @DisplayName("both at once: the module's other change is written, the edited setting is kept and named")
    void mixedSaveWritesOnlyTheWritableSetting() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        put(PATH, TEXT.replace("interval: 300", "interval: 450"));

        config.interval = 600;
        config.message = "welcome";
        config.save();

        assertThat(read(PATH)).isEqualTo(TEXT.replace("interval: 300", "interval: 450").replace("message: hi", "message: welcome"));
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("interval").doesNotContain("message"));
    }

    @Test
    @DisplayName("a save with no module change writes nothing, whatever the operator changed on disk")
    void saveWithoutModuleChangeWritesNothing() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        String edited = TEXT.replace("interval: 300", "interval:   450").replace("message: hi", "message: hello there");
        put(PATH, edited);

        config.save();

        assertThat(read(PATH)).isEqualTo(edited);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("P7b: a module that adds one map entry writes that entry only; a hand-added entry and a 6.2 split entry stay")
    void mapEntryAddedByTheModuleIsWrittenAloneBesideHandAddedAndSplitEntries() throws Exception {
        String original = "emojis:\n  smile: ':)'\n  o:\n    O: x\n";
        put(MAP_PATH, original);
        Emojis config = new Emojis(MAP_PATH);
        config.init(plugin);
        assertThat(config.emojis).as("the 6.2 split entry is skipped on load").containsOnlyKeys("smile");
        String handEdited = "emojis:\n  smile: ':)'\n  wave: o/\n  o:\n    O: x\n";
        put(MAP_PATH, handEdited);

        config.emojis.put("heart", "love");
        config.save();

        assertThat(read(MAP_PATH)).isEqualTo(handEdited + "  heart: love\n");
    }

    @Test
    @DisplayName("a map entry the module removes is removed alone; the hand-added and split entries stay")
    void mapEntryRemovedByTheModuleIsRemovedAlone() throws Exception {
        put(MAP_PATH, "emojis:\n  smile: ':)'\n  frown: ':('\n  o:\n    O: x\n");
        Emojis config = new Emojis(MAP_PATH);
        config.init(plugin);
        put(MAP_PATH, "emojis:\n  smile: ':)'\n  frown: ':('\n  wave: o/\n  o:\n    O: x\n");

        config.emojis.remove("frown");
        config.save();

        assertThat(read(MAP_PATH)).isEqualTo("emojis:\n  smile: ':)'\n  wave: o/\n  o:\n    O: x\n");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a map entry the operator changed on disk is not written by the module's change of that entry")
    void mapEntryTheOperatorEditedIsNotWritten() throws Exception {
        put(MAP_PATH, "emojis:\n  smile: ':)'\n");
        Emojis config = new Emojis(MAP_PATH);
        config.init(plugin);
        String edited = "emojis:\n  smile: ':D'\n";
        put(MAP_PATH, edited);

        config.emojis.put("smile", "(:");
        config.save();

        assertThat(read(MAP_PATH)).isEqualTo(edited);
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("emojis.smile")
                .doesNotContain("(:", ":D"));
    }
}
