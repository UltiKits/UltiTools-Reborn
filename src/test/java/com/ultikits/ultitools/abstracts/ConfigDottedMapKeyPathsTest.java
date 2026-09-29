package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;
import com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser;

/**
 * Gate-1 review of plan 17-41 (#553): keeping a dotted map key whole must not change what a module
 * sees through {@code getConfig()} - every path {@code getKeys(true)} returns carries its parents and
 * {@code '.'} separators, and a section reports its own path - and must not let the framework write a
 * key its own loader cannot read back. The built-in {@code StringHashMapParser} keeps dotted keys too,
 * and a module's own parser reading nested paths of a map without dotted keys reads what it read before.
 */
@DisplayName("AbstractConfigEntity - dotted map keys and getConfig() paths (#553)")
class ConfigDottedMapKeyPathsTest {

    private static final String PATH = "config/paths.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class AliasConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "features.aliases")
        Map<String, String> aliases = defaults();

        @ConfigEntry(path = "features.other")
        int other = 1;

        public AliasConfig(String configFilePath) {
            super(configFilePath);
        }

        private static Map<String, String> defaults() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("g.m", "gamemode");
            map.put("plain", "value");
            return map;
        }
    }

    @SuppressWarnings("unused")
    static class HashMapParserConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "mappings", parser = StringHashMapParser.class)
        HashMap<String, String> mappings = new HashMap<>();

        public HashMapParserConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A module's own parser that reads one nested value of the section by a dotted path. */
    public static class NestedPathParser extends ConfigParser<Map<String, String>> {
        @Override
        public Map<String, String> parse(Object object) {
            Map<String, String> result = new LinkedHashMap<>();
            if (object instanceof ConfigurationSection) {
                result.put("join", ((ConfigurationSection) object).getString("messages.join"));
            }
            return result;
        }

        @Override
        public MemorySection serializeToMemorySection(Map<String, String> object) {
            MemoryConfiguration section = new MemoryConfiguration();
            section.set("messages.join", object.get("join"));
            return section;
        }
    }

    @SuppressWarnings("unused")
    static class NestedParserConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "texts", parser = NestedPathParser.class)
        Map<String, String> texts = new LinkedHashMap<>();

        public NestedParserConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("PathsModule");
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

    private static void assertPathsAreWellFormed(YamlConfiguration live) {
        Set<String> keys = live.getKeys(true);
        assertThat(keys).contains("features", "features.aliases", "features.other");
        assertThat(keys).allSatisfy(key -> assertThat(key)
                .doesNotContain("\u0000").doesNotStartWith(".").startsWith("features"));
        assertThat(live.getValues(true).keySet()).allSatisfy(key -> assertThat(key)
                .doesNotContain("\u0000").startsWith("features"));
        ConfigurationSection aliases = live.getConfigurationSection("features.aliases");
        assertThat(aliases).isNotNull();
        assertThat(aliases.getCurrentPath()).isEqualTo("features.aliases");
        assertThat(aliases.getRoot()).isSameAs(live);
        assertThat(live.getString("features.aliases.plain")).isEqualTo("value");
    }

    @Test
    @DisplayName("getConfig() paths are well formed after a first boot, a second boot and a save")
    void getConfigPathsStayWellFormed() throws IOException {
        AliasConfig first = new AliasConfig(PATH);
        first.init(plugin);
        assertPathsAreWellFormed(first.getConfig());

        AliasConfig second = new AliasConfig(PATH);
        second.init(plugin);
        assertThat(second.aliases).containsOnlyKeys("g.m", "plain");
        assertPathsAreWellFormed(second.getConfig());

        second.aliases.put("t.p", "teleport");
        second.save();
        assertPathsAreWellFormed(second.getConfig());

        AliasConfig third = new AliasConfig(PATH);
        third.init(plugin);
        assertThat(third.aliases).containsOnlyKeys("g.m", "plain", "t.p");
        assertThat(third.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("a map key the loader cannot read back (an empty path segment) is refused on save; the file is untouched")
    void keyWithEmptySegmentIsRefused() throws IOException {
        AliasConfig config = new AliasConfig(PATH);
        config.init(plugin);
        byte[] before = Files.readAllBytes(file());

        config.aliases.put("wave.", "hello");
        assertThatThrownBy(config::save).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wave.");
        assertThat(Files.readAllBytes(file())).isEqualTo(before);

        AliasConfig reloaded = new AliasConfig(PATH);
        reloaded.init(plugin);
        assertThat(reloaded.aliases).containsOnlyKeys("g.m", "plain");
    }

    @Test
    @DisplayName("the built-in StringHashMapParser keeps a dotted key through save and reload")
    void stringHashMapParserKeepsDottedKey() throws IOException {
        HashMapParserConfig config = new HashMapParserConfig(PATH);
        config.init(plugin);
        config.mappings.put("o.O", "surprised");
        config.mappings.put("plain", "value");
        config.save();

        HashMapParserConfig second = new HashMapParserConfig(PATH);
        second.init(plugin);
        assertThat(second.mappings).containsOnlyKeys("o.O", "plain");
        assertThat(second.mappings.get("o.O")).isEqualTo("surprised");
    }

    @Test
    @DisplayName("a module parser reading a nested dotted path of a map without dotted keys reads what it read before")
    void customParserReadsNestedPaths() throws IOException {
        writeFile("texts:\n  messages:\n    join: welcome\n");
        NestedParserConfig config = new NestedParserConfig(PATH);
        config.init(plugin);

        assertThat(config.texts).containsEntry("join", "welcome");
    }
}
