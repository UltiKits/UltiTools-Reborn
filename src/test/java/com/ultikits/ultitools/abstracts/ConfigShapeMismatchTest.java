package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * UltiKits/UltiTools-Reborn#526: a value whose YAML shape cannot be assigned to its field used to
 * throw {@code IllegalArgumentException} out of {@code init()}, taking the whole module down with no
 * line naming the key (UltiRecipe's {@code recipes} written as a list). Maintainer rule
 * (2026-09-29): the field keeps its declared default, one warning names the file, the key, the
 * declared type and what was found, and the rest of the configuration loads.
 */
@DisplayName("AbstractConfigEntity - a wrongly shaped value keeps the default (#526)")
class ConfigShapeMismatchTest {

    private static final String PATH = "config/shape.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class ShapeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "recipes")
        Map<String, String> recipes = new LinkedHashMap<>(singleton("starter", "bread"));

        @ConfigEntry(path = "worlds")
        List<String> worlds = new ArrayList<>(Arrays.asList("world"));

        @ConfigEntry(path = "count")
        int count = 5;

        @ConfigEntry(path = "label")
        String label = "none";

        @ConfigEntry(path = "db.password")
        int passwordLength = 8;

        @ConfigEntry(path = "sibling")
        int sibling = 1;

        public ShapeConfig(String configFilePath) {
            super(configFilePath);
        }

        private static Map<String, String> singleton(String key, String value) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put(key, value);
            return map;
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("ShapeModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private void writeFile(String yaml) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static String complete(String overrides) {
        StringBuilder yaml = new StringBuilder(overrides);
        for (String key : Arrays.asList("recipes", "worlds", "count", "label", "db", "sibling")) {
            if (!overrides.startsWith(key + ":") && !overrides.contains("\n" + key + ":")) {
                switch (key) {
                    case "recipes": yaml.append("recipes:\n  starter: bread\n"); break;
                    case "worlds": yaml.append("worlds: [world]\n"); break;
                    case "count": yaml.append("count: 5\n"); break;
                    case "label": yaml.append("label: none\n"); break;
                    case "db": yaml.append("db:\n  password: 8\n"); break;
                    default: yaml.append("sibling: 7\n"); break;
                }
            }
        }
        return yaml.toString();
    }

    @Test
    @DisplayName("a map written as a sequence keeps the default; one warning names file, key, declared and found type")
    void mapGivenSequenceKeepsDefault() throws IOException {
        writeFile(complete("recipes:\n- Custom_Sword\n- Another\n"));
        ShapeConfig config = new ShapeConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).as("the module loads").doesNotThrowAnyException();

            assertThat(config.recipes).containsExactly(entry("starter", "bread"));
            assertThat(config.sibling).isEqualTo(7);
            List<String> named = warnings.messagesContaining("'recipes'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("Map<String, String>").contains("a list")
                    .contains("Custom_Sword");
            assertThat(warnings.messages()).hasSize(1);
        }
    }

    @Test
    @DisplayName("a map written as a scalar keeps the default with one warning")
    void mapGivenScalarKeepsDefault() throws IOException {
        writeFile(complete("recipes: hello\n"));
        ShapeConfig config = new ShapeConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(config.recipes).containsExactly(entry("starter", "bread"));
            List<String> named = warnings.messagesContaining("'recipes'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains("text").contains("hello");
        }
    }

    @Test
    @DisplayName("control: a mapping binds unchanged")
    void mappingBinds() throws IOException {
        writeFile(complete("recipes:\n  a: one\n  b: two\n"));
        ShapeConfig config = new ShapeConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.recipes).containsExactly(entry("a", "one"), entry("b", "two"));
            assertThat(warnings.messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("a list written as a scalar, and a number written as text, keep their defaults")
    void otherMismatchesKeepDefaults() throws IOException {
        writeFile(complete("worlds: nether\ncount: lots\n"));
        ShapeConfig config = new ShapeConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(config.worlds).containsExactly("world");
            assertThat(config.count).isEqualTo(5);
            assertThat(warnings.messagesContaining("'worlds'")).hasSize(1);
            assertThat(warnings.messagesContaining("'count'")).hasSize(1).allSatisfy(
                    message -> assertThat(message).contains("lots").contains("int"));
        }
    }

    @Test
    @DisplayName("a value that converts exactly is bound: a quoted number into an int, a number into a String")
    void convertibleScalarsBind() throws IOException {
        writeFile(complete("count: '42'\nlabel: 123\n"));
        ShapeConfig config = new ShapeConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.count).isEqualTo(42);
            assertThat(config.label).isEqualTo("123");
            assertThat(warnings.messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("the value of a secret-shaped key is redacted in the warning; the key is still named")
    void secretValueIsRedacted() throws IOException {
        writeFile(complete("db:\n  password: hunter2\n"));
        ShapeConfig config = new ShapeConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.passwordLength).isEqualTo(8);
            List<String> named = warnings.messagesContaining("'db.password'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains("<redacted>").doesNotContain("hunter2");
        }
    }

    @Test
    @DisplayName("init leaves the operator's file untouched and the shutdown save does not overwrite it")
    void fileIsNotRewritten() throws IOException {
        writeFile(complete("recipes:\n- Custom_Sword\n"));
        byte[] before = Files.readAllBytes(file());
        ShapeConfig config = new ShapeConfig(PATH);

        config.init(plugin);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("reload with a wrongly shaped value falls back to the declared default instead of throwing")
    void reloadFallsBackToDefault() throws IOException {
        writeFile(complete("recipes:\n  a: one\n"));
        ShapeConfig config = new ShapeConfig(PATH);
        config.init(plugin);
        assertThat(config.recipes).containsExactly(entry("a", "one"));

        writeFile(complete("recipes: [x]\n"));
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(config::reload).doesNotThrowAnyException();
            assertThat(config.recipes).containsExactly(entry("starter", "bread"));
            assertThat(warnings.messagesContaining("'recipes'")).hasSize(1);
        }
    }
}
