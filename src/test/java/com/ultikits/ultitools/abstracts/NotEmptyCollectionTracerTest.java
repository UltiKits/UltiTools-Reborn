package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * Tracer of follow-up 3 batch 3 (#630, UltiKits/UltiCleaner#34; maintainer decision of 2026-10-06): a {@code @NotEmpty}
 * list, set or map that the file holds empty - or whose every entry failed to bind - runs on the field's declared
 * default in memory, with one WARNING naming the key, the value kind, the value as written and the default. The module
 * loads, and the file keeps its bytes and its modification time, at load and at reload. {@code @NotEmpty} on text keeps
 * its refusal, and a {@code @NotEmpty} collection whose declared default is itself empty is a declaration error.
 */
@DisplayName("@NotEmpty on a list or map: an empty value runs the declared default with a warning, file untouched (#630)")
class NotEmptyCollectionTracerTest {

    private static final FileTime OLD = FileTime.fromMillis(1_500_000_000_000L);
    private static final String FULL = "warn-times: [10]\nnames: {creeper: Creeper}\napi-tokens: [abc123]\ntitle: Cleaner\n";

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    @ConfigEntity("cleaner.yml")
    static class Cleaner extends AbstractConfigEntity {
        @NotEmpty
        @ConfigEntry(path = "warn-times")
        List<Integer> warnTimes = new ArrayList<>(Arrays.asList(60, 30));

        @NotEmpty
        @ConfigEntry(path = "names")
        Map<String, String> names = new LinkedHashMap<>(Collections.singletonMap("zombie", "Zombie"));

        @NotEmpty
        @ConfigEntry(path = "api-tokens")
        List<String> apiTokens = new ArrayList<>(Collections.singletonList("s3cret-default"));

        @NotEmpty
        @ConfigEntry(path = "title")
        String title = "Cleaner";

        Cleaner(String path) {
            super(path);
        }
    }

    @ConfigEntity("empty.yml")
    static class EmptyDefault extends AbstractConfigEntity {
        @NotEmpty
        @ConfigEntry(path = "types")
        List<String> types = new ArrayList<>();

        EmptyDefault(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("CleanerModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.ensureCleanState();
    }

    /**
     * Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind (17-74 gate-1 F1): with
     * one, the framework logs through the mock's {@code getLogger()}, which is {@code null}, instead of its own logger.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private Path write(String file, String text) throws Exception {
        Path path = directory.resolve(file);
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(path, OLD);
        return path;
    }

    private static void assertUntouched(Path path, byte[] before) throws Exception {
        assertThat(Files.readAllBytes(path)).as("the file keeps its bytes").isEqualTo(before);
        assertThat(Files.getLastModifiedTime(path)).as("the file keeps its modification time").isEqualTo(OLD);
    }

    @Test
    @DisplayName("an empty list at load: the declared default runs, one warning names key, kind, value and default")
    void emptyListAtLoadRunsTheDeclaredDefault() throws Exception {
        Path file = write("cleaner.yml", FULL.replace("warn-times: [10]", "warn-times: []"));
        byte[] before = Files.readAllBytes(file);
        Cleaner cleaner = new Cleaner("cleaner.yml");

        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            cleaner.init(plugin);

            assertThat(cleaner.warnTimes).containsExactly(60, 30);
            assertThat(capture.messages()).hasSize(1);
            assertThat(capture.messages().get(0)).contains("cleaner.yml").contains("'warn-times'").contains("list")
                    .contains("[]").contains("[60, 30]").contains("@NotEmpty").contains("not changed");
        }
        assertUntouched(file, before);

        cleaner.save();
        assertUntouched(file, before);
    }

    @Test
    @DisplayName("an empty list at reload: the declared default runs with one warning, the file is untouched")
    void emptyListAtReloadRunsTheDeclaredDefault() throws Exception {
        write("cleaner.yml", FULL);
        Cleaner cleaner = new Cleaner("cleaner.yml");
        cleaner.init(plugin);
        assertThat(cleaner.warnTimes).containsExactly(10);

        Path file = write("cleaner.yml", FULL.replace("warn-times: [10]", "warn-times: []"));
        byte[] before = Files.readAllBytes(file);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            cleaner.reload();

            assertThat(cleaner.warnTimes).containsExactly(60, 30);
            assertThat(capture.messagesContaining("'warn-times'")).hasSize(1);
            assertThat(capture.messages()).hasSize(1);
        }
        assertUntouched(file, before);
        cleaner.save();
        assertUntouched(file, before);
    }

    @Test
    @DisplayName("every entry failed to bind: the converter's warning, then the empty-list warning naming [abc]")
    void everyEntryUnboundNamesTheValueAsWritten() throws Exception {
        Path file = write("cleaner.yml", FULL.replace("warn-times: [10]", "warn-times: [abc]"));
        byte[] before = Files.readAllBytes(file);
        Cleaner cleaner = new Cleaner("cleaner.yml");

        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            cleaner.init(plugin);

            assertThat(cleaner.warnTimes).containsExactly(60, 30);
            assertThat(capture.messages()).hasSize(2);
            assertThat(capture.messages().get(0)).contains("'warn-times[0]'").contains("abc");
            assertThat(capture.messages().get(1)).contains("'warn-times'").contains("[abc]").contains("[60, 30]")
                    .contains("@NotEmpty");
        }
        assertUntouched(file, before);
    }

    @Test
    @DisplayName("an empty map: the declared default map runs with one warning")
    void emptyMapRunsTheDeclaredDefault() throws Exception {
        Path file = write("cleaner.yml", FULL.replace("names: {creeper: Creeper}", "names: {}"));
        byte[] before = Files.readAllBytes(file);
        Cleaner cleaner = new Cleaner("cleaner.yml");

        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            cleaner.init(plugin);

            assertThat(cleaner.names).containsExactly(entry("zombie", "Zombie"));
            assertThat(capture.messages()).hasSize(1);
            assertThat(capture.messages().get(0)).contains("'names'").contains("map").contains("{}")
                    .contains("zombie").contains("@NotEmpty");
        }
        assertUntouched(file, before);
    }

    @Test
    @DisplayName("control: a non-empty list and map are used as written, with no warning")
    void nonEmptyValuesAreUsedAsWritten() throws Exception {
        Path file = write("cleaner.yml", FULL);
        byte[] before = Files.readAllBytes(file);
        Cleaner cleaner = new Cleaner("cleaner.yml");

        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            cleaner.init(plugin);

            assertThat(cleaner.warnTimes).containsExactly(10);
            assertThat(cleaner.names).containsExactly(entry("creeper", "Creeper"));
            assertThat(capture.messages()).isEmpty();
        }
        assertUntouched(file, before);
    }

    @Test
    @DisplayName("a secret-shaped key: the warning redacts the value and the default")
    void secretShapedKeyIsRedacted() throws Exception {
        write("cleaner.yml", FULL.replace("api-tokens: [abc123]", "api-tokens: []"));
        Cleaner cleaner = new Cleaner("cleaner.yml");

        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            cleaner.init(plugin);

            assertThat(cleaner.apiTokens).containsExactly("s3cret-default");
            assertThat(capture.messages()).hasSize(1);
            assertThat(capture.messages().get(0)).contains("'api-tokens'").contains("<redacted>")
                    .doesNotContain("s3cret-default");
        }
    }

    @Test
    @DisplayName("@NotEmpty on text is unchanged: an empty title refuses the module naming the field")
    void emptyTextStillRefuses() throws Exception {
        write("cleaner.yml", FULL.replace("title: Cleaner", "title: ''"));

        assertThatThrownBy(() -> new Cleaner("cleaner.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("field 'title' must not be empty");
    }

    @Test
    @DisplayName("a @NotEmpty list whose declared default is empty is refused at load as a declaration error")
    void emptyDeclaredDefaultIsADeclarationError() throws Exception {
        Path file = write("empty.yml", "types: []\n");
        byte[] before = Files.readAllBytes(file);

        assertThatThrownBy(() -> new EmptyDefault("empty.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("CleanerModule").hasMessageContaining("types")
                .hasMessageContaining("@NotEmpty").hasMessageContaining("declared default is empty");
        assertUntouched(file, before);
    }
}
