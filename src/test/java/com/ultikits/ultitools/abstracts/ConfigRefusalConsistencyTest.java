package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;

/**
 * Confirmation review of plan 17-41 after the maintainer's answers of 2026-09-30 (#553 refuse and say
 * so, #534 float decimals, #523 file forms): every place a map is written or compared refuses a dotted
 * key in the same way and says so - including a key inside an object stored in a map, and a change the
 * shutdown save does not write - while a map's other contents are written in the form the loader reads
 * back, a module's own parser keeps its own keys, and a float the framework wrote reads back.
 */
@DisplayName("AbstractConfigEntity - dotted-key refusal and file forms stay consistent (#553, #534, #523)")
class ConfigRefusalConsistencyTest {

    private static final String PATH = "config/consistency.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    enum Mode {
        FAST, SLOW;

        @Override
        public String toString() {
            return "v1." + name();
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class EnumMapsConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "modes")
        Map<String, Mode> modes = single("a", Mode.FAST);

        @ConfigEntry(path = "byMode")
        Map<Mode, Integer> byMode = single(Mode.SLOW, 2);

        @ConfigEntry(path = "ratio")
        float ratio = Float.NaN;

        public EnumMapsConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class RulesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "rules")
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();

        public RulesConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** An object stored as a map value, with a map of its own, as UltiRecipe's recipe definitions are. */
    public static class Holder {
        private Map<String, String> ingredients = new LinkedHashMap<>();
    }

    @SuppressWarnings("unused")
    static class HoldersConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "recipes")
        Map<String, Holder> recipes = new LinkedHashMap<>();

        public HoldersConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A module's own parser that reads a map's leaves by their full dotted paths. */
    public static class LeafPathParser extends ConfigParser<Map<String, String>> {
        @Override
        public Map<String, String> parse(Object object) {
            Map<String, String> result = new LinkedHashMap<>();
            if (object instanceof ConfigurationSection) {
                ConfigurationSection section = (ConfigurationSection) object;
                for (String key : section.getKeys(true)) {
                    if (!section.isConfigurationSection(key)) {
                        result.put(key, section.getString(key));
                    }
                }
            }
            return result;
        }

        @Override
        public MemorySection serializeToMemorySection(Map<String, String> object) {
            MemoryConfiguration section = new MemoryConfiguration();
            for (Map.Entry<String, String> entry : object.entrySet()) {
                section.set(entry.getKey(), entry.getValue());
            }
            return section;
        }
    }

    @SuppressWarnings("unused")
    static class LeafParserConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "texts", parser = LeafPathParser.class)
        Map<String, String> texts = new LinkedHashMap<>();

        public LeafParserConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static <K, V> Map<K, V> single(K key, V value) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("ConsistencyModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private String readFile() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a map default with enum values and enum keys (toString with a dot) is written by name and reads back")
    void mapDefaultsWithEnumsReadBack() throws IOException {
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new EnumMapsConfig(PATH).init(plugin);
            assertThat(warnings.messages()).isEmpty();
        }
        // No Java-class tag (which the loader refuses); YAML's own `!!float 'NaN'` is fine.
        assertThat(readFile()).doesNotContainPattern("!![a-z]+\\.");

        EnumMapsConfig second = new EnumMapsConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            second.init(plugin);
            assertThat(warnings.messages()).isEmpty();
        }
        assertThat(second.isLastLoadUnparseable()).isFalse();
        assertThat(second.modes).containsEntry("a", Mode.FAST);
        assertThat(second.byMode).containsEntry(Mode.SLOW, 2);
        assertThat(second.ratio).isNaN();

        second.save();
        EnumMapsConfig third = new EnumMapsConfig(PATH);
        third.init(plugin);
        assertThat(third.byMode).containsEntry(Mode.SLOW, 2);
        assertThat(third.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("an in-memory dotted key the shutdown save will not write is named at the shutdown check")
    void shutdownCheckNamesAnUnsavedDottedKey() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("reply", "hi");
        config.rules.put("my.rule", rule);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThat(config.isModifiedSinceSnapshot()).isFalse();
            List<String> named = warnings.messagesContaining("'my.rule'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("rename");
        }
    }

    @Test
    @DisplayName("a dotted key in a map inside an object stored in a map is refused and named too")
    void dottedKeyInsideAStoredObjectIsRefused() throws IOException {
        HoldersConfig config = new HoldersConfig(PATH);
        config.init(plugin);
        Holder sword = new Holder();
        sword.ingredients.put("minecraft.diamond", "D");
        sword.ingredients.put("stick", "S");
        config.recipes.put("sword", sword);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messagesContaining("'minecraft.diamond'")).hasSize(1);
        }
        assertThat(readFile()).contains("stick").doesNotContain("diamond");
    }

    @Test
    @DisplayName("a module parser that reads dotted leaf paths is not refused on a panel write, nor warned about on load")
    void moduleParserKeysAreNotRefused() throws IOException {
        LeafParserConfig config = new LeafParserConfig(PATH);
        config.init(plugin);
        JsonObject texts = new JsonObject();
        texts.addProperty("lang.welcome", "Hi");
        JsonObject payload = new JsonObject();
        payload.add("texts", texts);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.updateProperties(payload);
            LeafParserConfig second = new LeafParserConfig(PATH);
            second.init(plugin);
            assertThat(second.texts).containsEntry("lang.welcome", "Hi");
            assertThat(warnings.messages()).isEmpty();
        }
    }
}
