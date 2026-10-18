package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Case 52 (issue #673, "Typed scalar map keys bind back to the original document keys"): a module declaring a map keyed
 * by {@code Integer}, {@code Long} or {@code Boolean} passes preflight and reads its file, then failed to start because
 * the snapshot write refused the non-text key ("Map keys must convert to text"). A non-empty declared default of such a
 * map failed even earlier, when the defaults were captured.
 * <p>
 * The file's key text is the operator's: an unchanged save writes nothing, and an edit of one entry's value changes only
 * that value, so {@code 0x1F}, {@code 9_000_000_000} and {@code yes} keep their spelling. All content is synthetic.
 */
@DisplayName("Typed scalar map keys load, save nothing when unchanged and keep their key text (case 52)")
class ConfigTypedMapKeyTest {

    private static final String PATH = "config/typed-keys.yml";
    private static final String FIXTURE = "# synthetic typed-key fixture\n"
            + "limits:\n"
            + "  1: one\n"
            + "  0x1F: thirty-one\n"
            + "big:\n"
            + "  9_000_000_000: nine\n"
            + "flags:\n"
            + "  yes: enabled\n"
            + "tail: kept\n";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class TypedKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limits")
        Map<Integer, String> limits = new LinkedHashMap<>();

        @ConfigEntry(path = "big")
        Map<Long, String> big = new LinkedHashMap<>();

        @ConfigEntry(path = "flags")
        Map<Boolean, String> flags = new LinkedHashMap<>();

        @ConfigEntry(path = "tail")
        String tail = "default";

        TypedKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    static class DefaultedKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "motd")
        String motd = "hello";

        @ConfigEntry(path = "ports")
        Map<Integer, String> ports = new LinkedHashMap<>(Collections.singletonMap(25565, "main"));

        DefaultedKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class PortsReader extends AbstractConfigEntity {
        @ConfigEntry(path = "motd")
        String motd = "unset";

        @ConfigEntry(path = "ports")
        Map<Integer, String> ports = new LinkedHashMap<>();

        PortsReader(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("TypedKeyModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    @Test
    @DisplayName("Integer, Long and Boolean keyed maps initialize from the file and bind the typed keys")
    void scalarKeyMapsLoadFromTheFile() throws IOException {
        put(FIXTURE);
        TypedKeyConfig config = new TypedKeyConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).as("init of a module with typed scalar map keys")
                    .doesNotThrowAnyException();

            Map<Integer, String> limits = new LinkedHashMap<>();
            limits.put(1, "one");
            limits.put(31, "thirty-one");
            assertThat(config.limits).containsExactlyEntriesOf(limits);
            assertThat(config.big).containsExactlyEntriesOf(Collections.singletonMap(9_000_000_000L, "nine"));
            assertThat(config.flags).containsExactlyEntriesOf(Collections.singletonMap(true, "enabled"));
            assertThat(config.tail).isEqualTo("kept");
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }
        assertThat(text()).as("init leaves the file as written").isEqualTo(FIXTURE);
    }

    @Test
    @DisplayName("save() after init writes nothing: the file keeps its bytes and its modification time")
    void unchangedSaveLeavesScalarKeyFileByteIdentical() throws IOException {
        long modified = put(FIXTURE);
        TypedKeyConfig config = new TypedKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).as("init of a module with typed scalar map keys")
                .doesNotThrowAnyException();

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(FIXTURE);
        assertThat(file().lastModified()).as("modification time").isEqualTo(modified);
    }

    @Test
    @DisplayName("editing the value of key 31 changes only that value; every key text and other byte stays")
    void neighbourEditKeepsScalarKeyText() throws IOException {
        put(FIXTURE);
        TypedKeyConfig config = new TypedKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).as("init of a module with typed scalar map keys")
                .doesNotThrowAnyException();

        config.limits.put(31, "changed");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(FIXTURE.replace("  0x1F: thirty-one\n", "  0x1F: changed\n"));
    }

    @Test
    @DisplayName("a non-empty Integer-keyed default is inserted into a file lacking it and reads back as the same key")
    void nonEmptyScalarKeyDefaultsAreInserted() throws IOException {
        put("motd: kept\n");
        DefaultedKeyConfig config = new DefaultedKeyConfig(PATH);

        assertThatCode(() -> config.init(plugin)).as("init of a module with a non-empty typed-key default")
                .doesNotThrowAnyException();

        // The converter writes the key as the text "25565"; the renderer quotes a text key that would otherwise load as
        // a YAML number. The quoted key still reads back as the Integer 25565 (the reader below).
        assertThat(text()).isEqualTo("motd: kept\nports:\n  '25565': main\n");
        PortsReader reader = new PortsReader(PATH);
        assertThatCode(() -> reader.init(plugin)).doesNotThrowAnyException();
        assertThat(reader.ports).containsExactlyEntriesOf(Collections.singletonMap(25565, "main"));
        assertThat(reader.motd).isEqualTo("kept");
    }

    private File file() {
        return tempDir.resolve(PATH).toFile();
    }

    private long put(String yaml) throws IOException {
        Path target = tempDir.resolve(PATH);
        Files.createDirectories(target.getParent());
        Files.write(target, yaml.getBytes(StandardCharsets.UTF_8));
        File written = target.toFile();
        assertThat(written.setLastModified(written.lastModified() - 10_000L)).isTrue();
        return written.lastModified();
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(PATH)), StandardCharsets.UTF_8);
    }
}
