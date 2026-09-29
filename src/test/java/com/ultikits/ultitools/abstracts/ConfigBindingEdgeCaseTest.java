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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;

/**
 * Gate-1 review of plan 17-41 (#523, #526): the declared-type binder must not print a secret nested
 * inside a reported value, must not throw out of {@code init()} for a value its parser cannot read
 * (a YAML timestamp) or for an empty list item, must bind every standard collection and map type a
 * field may declare with one warning per bad value, and must leave a module's own parser's
 * serialization of a {@code Set} field alone.
 */
@DisplayName("AbstractConfigEntity - binder edge cases from the gate-1 review (#523, #526)")
class ConfigBindingEdgeCaseTest {

    private static final String PATH = "config/edge.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    enum Mode { FAST, SLOW }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class SecretConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "storage")
        List<String> storage = new ArrayList<>();

        @ConfigEntry(path = "apiTokens")
        Map<String, Boolean> apiTokens = new LinkedHashMap<>();

        public SecretConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class OddValuesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "label")
        String label = "none";

        @ConfigEntry(path = "numbers")
        List<Integer> numbers = new ArrayList<>();

        @ConfigEntry(path = "names")
        TreeSet<String> names = new TreeSet<>();

        @ConfigEntry(path = "sibling")
        int sibling = 1;

        public OddValuesConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class CollectionTypesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "queue")
        Queue<String> queue = new ArrayDeque<>();

        @ConfigEntry(path = "sorted")
        SortedSet<String> sorted = new TreeSet<>();

        @ConfigEntry(path = "modes")
        EnumSet<Mode> modes = EnumSet.of(Mode.FAST);

        @ConfigEntry(path = "concurrent")
        ConcurrentMap<String, String> concurrent = new ConcurrentHashMap<>();

        @ConfigEntry(path = "byMode")
        EnumMap<Mode, Integer> byMode = new EnumMap<>(Mode.class);

        @ConfigEntry(path = "ordered")
        SortedMap<String, Integer> ordered = new TreeMap<>();

        public CollectionTypesConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A module's own parser for a Set field: writes the set as one comma-joined value in a section. */
    public static class JoinedSetParser extends ConfigParser<Set<String>> {
        @Override
        public Set<String> parse(Object object) {
            Set<String> result = new LinkedHashSet<>();
            if (object instanceof ConfigurationSection) {
                String joined = ((ConfigurationSection) object).getString("joined", "");
                for (String part : joined.split(",")) {
                    if (!part.isEmpty()) {
                        result.add(part);
                    }
                }
            }
            return result;
        }

        @Override
        public MemorySection serializeToMemorySection(Set<String> object) {
            MemoryConfiguration section = new MemoryConfiguration();
            section.set("joined", String.join(",", object));
            return section;
        }
    }

    @SuppressWarnings("unused")
    static class CustomParserConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "tags", parser = JoinedSetParser.class)
        Set<String> tags = new LinkedHashSet<>();

        public CustomParserConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("EdgeModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private void writeFile(String yaml) throws IOException {
        Path file = tempDir.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
    }

    private String readFile() throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(PATH)), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a secret nested inside a reported list element or map is never printed")
    void nestedSecretsAreNotPrinted() throws IOException {
        writeFile("storage:\n- mysql:\n    password: hunter2\n- host: db\n  token: abc123\n"
                + "apiTokens:\n  sk-live-123: maybe\n");
        SecretConfig config = new SecretConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(warnings.messages()).isNotEmpty();
            assertThat(warnings.messages()).allSatisfy(message -> assertThat(message)
                    .doesNotContain("hunter2").doesNotContain("abc123").doesNotContain("sk-live-123"));
            assertThat(warnings.messagesContaining("'storage[0]'")).hasSize(1);
            assertThat(warnings.messagesContaining("'apiTokens")).hasSize(1);
        }
    }

    @Test
    @DisplayName("a YAML timestamp in a text field keeps the default with a warning instead of failing the load")
    void timestampKeepsTheDefault() throws IOException {
        writeFile("label: 2024-01-01\nsibling: 7\n");
        OddValuesConfig config = new OddValuesConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(config.label).isEqualTo("none");
            assertThat(config.sibling).isEqualTo(7);
            assertThat(warnings.messagesContaining("'label'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("an empty list item is skipped with one warning, in a List and in a TreeSet")
    void emptyListItemIsSkipped() throws IOException {
        writeFile("numbers:\n- 1\n-\n- 3\nnames:\n- b\n-\n- c\n");
        OddValuesConfig config = new OddValuesConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(config.numbers).containsExactly(1, 3);
            assertThat(config.names).containsExactly("b", "c");
            assertThat(warnings.messagesContaining("'numbers[1]'")).hasSize(1);
            assertThat(warnings.messagesContaining("'names[1]'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("Queue, SortedSet, EnumSet, ConcurrentMap, EnumMap and SortedMap fields bind and round-trip")
    void standardCollectionTypesBind() throws IOException {
        writeFile("queue: [a, b]\nsorted: [z, a]\nmodes: [SLOW, FAST]\nconcurrent:\n  x: y\n"
                + "byMode:\n  SLOW: 2\nordered:\n  b: 2\n  a: 1\n");
        CollectionTypesConfig config = new CollectionTypesConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.queue).containsExactly("a", "b");
            assertThat(config.sorted).containsExactly("a", "z");
            assertThat(config.modes).containsExactly(Mode.FAST, Mode.SLOW);
            assertThat(config.concurrent).containsEntry("x", "y");
            assertThat(config.byMode).containsEntry(Mode.SLOW, 2);
            assertThat(config.ordered.keySet()).containsExactly("a", "b");
            assertThat(warnings.messages()).isEmpty();
        }

        config.save();
        CollectionTypesConfig second = new CollectionTypesConfig(PATH);
        second.init(plugin);
        assertThat(second.modes).containsExactly(Mode.FAST, Mode.SLOW);
        assertThat(second.byMode).containsEntry(Mode.SLOW, 2);
        assertThat(second.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("a wrongly shaped collection value is reported exactly once")
    void wronglyShapedCollectionIsReportedOnce() throws IOException {
        writeFile("queue: hello\nconcurrent: [a]\n");
        CollectionTypesConfig config = new CollectionTypesConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(warnings.messagesContaining("'queue'")).hasSize(1);
            assertThat(warnings.messagesContaining("'concurrent'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("a module's own parser for a Set field keeps writing its own form")
    void customSetParserKeepsItsSerialization() throws IOException {
        CustomParserConfig config = new CustomParserConfig(PATH);
        config.init(plugin);
        config.tags.add("red");
        config.tags.add("blue");
        config.save();

        assertThat(readFile()).contains("joined: red,blue");
        CustomParserConfig second = new CustomParserConfig(PATH);
        second.init(plugin);
        assertThat(second.tags).containsExactly("red", "blue");
    }
}
