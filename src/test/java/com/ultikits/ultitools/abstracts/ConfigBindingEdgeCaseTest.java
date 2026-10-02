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

    @SuppressWarnings("removal")
    public static class InheritedMapParser extends com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser { }
    @SuppressWarnings("removal")
    public static class SuperMapParser extends InheritedMapParser {
        @Override public Object parse(Object value) { return super.parse(value); }
        @Override public MemorySection serializeToMemorySection(Object value) {
            return super.serializeToMemorySection(value);
        }
    }
    @SuppressWarnings("removal")
    static class LegacyMaps extends AbstractConfigEntity {
        @ConfigEntry(parser = InheritedMapParser.class) Map<String, Object> inherited = new LinkedHashMap<>();
        @ConfigEntry(parser = SuperMapParser.class) Map<String, Object> overridden = new LinkedHashMap<>();
        public LegacyMaps(String path) {
            super(path); inherited.put("default", "value"); overridden.put("default", "value");
        }
    }
    static class SharedPaths extends AbstractConfigEntity {
        @ConfigEntry(path = "first") List<Integer> first = new ArrayList<>();
        @ConfigEntry(path = "second") List<Integer> second = new ArrayList<>();
        @ConfigEntry(path = "owner.child") List<Integer> child = new ArrayList<>();
        @ConfigEntry(path = "owner") Map<String, List<Integer>> owner = new LinkedHashMap<>();
        public SharedPaths(String path) { super(path); }
    }
    @Test void inheritedAndSuperLegacyEntityMapsKeepDefaultSaveLoadAndPanelRoutes() throws Exception {
        LegacyMaps first = new LegacyMaps(PATH); first.init(plugin);
        assertThat(first.inherited).containsEntry("default", "value");
        assertThat(first.overridden).containsEntry("default", "value");
        first.inherited.put("saved", "yes"); first.overridden.put("saved", "yes"); first.save();
        LegacyMaps fresh = new LegacyMaps(PATH); fresh.init(plugin);
        assertThat(fresh.inherited).isEqualTo(first.inherited);
        assertThat(fresh.overridden).isEqualTo(first.overridden);
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        com.google.gson.JsonObject map = new com.google.gson.JsonObject(); map.addProperty("panel", "value");
        payload.add("inherited", map); payload.add("overridden", map.deepCopy()); fresh.updateProperties(payload);
        LegacyMaps panel = new LegacyMaps(PATH); panel.init(plugin);
        assertThat(panel.inherited).containsOnlyKeys("panel").containsEntry("panel", "value");
        assertThat(panel.overridden).isEqualTo(panel.inherited);
    }
    @Test void sharedBadChildAndOverlappingOwnersHaveSeparateLocatedWarnings() throws Exception {
        writeFile("first: &bad [wrong, 2]\nsecond: *bad\nowner:\n  child: *bad\n");
        SharedPaths values = new SharedPaths(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            values.init(plugin);
            assertThat(values.first).containsExactly(2); assertThat(values.second).containsExactly(2);
            assertThat(values.child).containsExactly(2); assertThat(values.owner.get("child")).containsExactly(2);
            assertThat(warnings.messagesContaining("'first[0]'" )).hasSize(1);
            assertThat(warnings.messagesContaining("'second[0]'" )).hasSize(1);
            assertThat(warnings.messagesContaining("'owner.child[0]'" )).hasSize(2);
        }
    }

    static class NullableElements extends AbstractConfigEntity {
        @ConfigEntry List<String> strings = new ArrayList<>();
        @ConfigEntry Integer[] array = new Integer[]{1};
        @ConfigEntry Set<String> tags = new LinkedHashSet<>();
        @ConfigEntry Map<String, List<Integer>> nested = new LinkedHashMap<>();
        @ConfigEntry Map<String, String> map = new LinkedHashMap<>();
        @ConfigEntry int[] primitive = new int[]{1, 3};
        @ConfigEntry String whole = null;
        public NullableElements(String path) { super(path); strings.add("first"); tags.add("first"); }
    }

    @Test
    void savingTypedNullElementsOmitsThemWithOneLocatedWarningPerField() throws Exception {
        NullableElements values = new NullableElements(PATH); values.init(plugin);
        values.strings = java.util.Arrays.asList(null, "first", null, null);
        values.array = new Integer[]{null, 1, null, 3};
        values.tags.add(null);
        values.nested.put("o.O", java.util.Arrays.asList(null, 1, null));
        values.nested.put("other", java.util.Arrays.asList(2, null));
        values.map.put("empty", null);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            values.isModifiedSinceSnapshot();
            assertThat(warnings.messages()).isEmpty();
            values.save();
            assertThat(warnings.messagesContaining("'strings")).hasSize(1);
            assertThat(warnings.messagesContaining("'array")).hasSize(1);
            assertThat(warnings.messagesContaining("'tags")).hasSize(1);
            assertThat(warnings.messagesContaining("'nested")).hasSize(1);
            assertThat(warnings.messages()).hasSize(4).allSatisfy(message -> assertThat(message).contains(PATH));
            assertThat(warnings.messagesContaining("'nested").get(0)).contains("o.O", "other");
        }
        com.ultikits.ultitools.config.document.ConfigDocument disk =
                com.ultikits.ultitools.config.document.ConfigDocument.parse(
                        new String(Files.readAllBytes(tempDir.resolve(PATH)), StandardCharsets.UTF_8));
        assertThat(disk.get(java.util.Collections.singletonList("strings"))).isEqualTo(java.util.Collections.singletonList("first"));
        assertThat(disk.get(java.util.Collections.singletonList("array"))).isEqualTo(java.util.Arrays.asList(1, 3));
        assertThat(disk.get(java.util.Collections.singletonList("tags"))).isEqualTo(java.util.Collections.singletonList("first"));
        assertThat(disk.get(java.util.Arrays.asList("nested", "o.O"))).isEqualTo(java.util.Collections.singletonList(1));
        assertThat(disk.contains(java.util.Arrays.asList("map", "empty"))).isTrue();
        assertThat(disk.get(java.util.Arrays.asList("map", "empty"))).isNull();
        assertThat(disk.contains(java.util.Collections.singletonList("whole"))).isTrue();
        assertThat(disk.get(java.util.Collections.singletonList("whole"))).isNull();
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            values.reload();
            assertThat(values.strings).containsExactly("first"); assertThat(values.array).containsExactly(1, 3);
            assertThat(values.tags).containsExactly("first");
            assertThat(values.nested.get("o.O")).containsExactly(1);
            assertThat(values.nested.get("other")).containsExactly(2);
            assertThat(values.map).containsEntry("empty", null);
            assertThat(values.whole).isNull(); assertThat(values.primitive).containsExactly(1, 3);
            assertThat(values.isModifiedSinceSnapshot()).isFalse();
            assertThat(warnings.messages()).isEmpty();
        }
    }

    static class ObjectElements extends AbstractConfigEntity {
        @ConfigEntry List<Object> objects = new ArrayList<>();
        @ConfigEntry Set<Object> objectSet = new LinkedHashSet<>();
        @ConfigEntry Object[] references = new Object[]{"first"};
        @ConfigEntry Object raw = java.util.Arrays.asList("first", null);
        public ObjectElements(String path) { super(path); objects.add("first"); objectSet.add("first"); }
    }

    @Test
    void explicitObjectContainersOmitNullButDeclaredObjectPlainDataDoesNot() throws Exception {
        ObjectElements values = new ObjectElements(PATH); values.init(plugin);
        values.objects = java.util.Arrays.asList("first", null, null);
        values.objectSet.add(null); values.references = new Object[]{null, "first", null};
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            values.isModifiedSinceSnapshot(); assertThat(warnings.messages()).isEmpty();
            values.save();
            assertThat(warnings.messages()).hasSize(3);
            assertThat(warnings.messagesContaining("'objects")).hasSize(1);
            assertThat(warnings.messagesContaining("'objectSet")).hasSize(1);
            assertThat(warnings.messagesContaining("'references")).hasSize(1);
        }
        values.reload();
        assertThat(values.objects).containsExactly("first");
        assertThat(values.objectSet).containsExactly("first");
        assertThat(values.references).containsExactly("first");
        assertThat(values.raw).isEqualTo(java.util.Arrays.asList("first", null));
        assertThat(values.isModifiedSinceSnapshot()).isFalse();
        com.ultikits.ultitools.config.convert.ConverterRegistry registry =
                com.ultikits.ultitools.config.convert.ConverterRegistry.framework();
        assertThat(registry.fromPlainResult(java.util.Arrays.asList("first", null), Object[].class, PATH,
                java.util.Collections.singletonList("references")).value()).isEqualTo(new Object[]{"first", null});
    }

    @Test
    void panelNullElementsRemainRefusedAndMapNullValuesStillRoundTrip() throws Exception {
        NullableElements values = new NullableElements(PATH); values.init(plugin);
        byte[] before = Files.readAllBytes(tempDir.resolve(PATH));
        com.google.gson.JsonObject invalid = new com.google.gson.JsonObject();
        invalid.add("strings", com.google.gson.JsonParser.parseString("[\"first\",null]"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> values.updateProperties(invalid))
                .isInstanceOf(com.ultikits.ultitools.exceptions.ConfigurationException.class);
        assertThat(Files.readAllBytes(tempDir.resolve(PATH))).isEqualTo(before);
        com.google.gson.JsonObject valid = new com.google.gson.JsonObject();
        valid.add("map", com.google.gson.JsonParser.parseString("{\"empty\":null}"));
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            values.updateProperties(valid);
            values.reload();
            assertThat(values.map).containsEntry("empty", null);
            assertThat(values.strings).containsExactly("first");
            assertThat(values.isModifiedSinceSnapshot()).isFalse();
            assertThat(warnings.messages()).isEmpty();
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
    void customSetParserKeepsItsSerialization() throws Exception {
        CustomParserConfig config = new CustomParserConfig(PATH);
        config.init(plugin);
        config.tags.add("red");
        config.tags.add("blue");
        config.save();

        // Follow-up 21 permits emitter quote normalization, not a different parser value.
        assertThat(ConfigFileView.read(config).getString("tags.joined")).isEqualTo("red,blue");
        CustomParserConfig second = new CustomParserConfig(PATH);
        second.init(plugin);
        assertThat(second.tags).containsExactly("red", "blue");
    }
}
