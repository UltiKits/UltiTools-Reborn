package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.manager.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** Panel leaves resolve real whole map keys; a refused changed leaf refuses the entire payload. */
class ConfigPanelMapEntryEditTest {
    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    private ConfigManager manager;
    private Values entity;
    private byte[] original;
    private static final String YAML = "# operator header\nautoreply:\n  rules:\n    # greeting note\n"
            + "    greet:\n      reply: hello\n      enabled: true\nlimits:\n  worlds:\n    nether: 2\n"
            + "emojis:\n  mappings:\n    o.O: face\nunknown: operator\n";

    @BeforeEach
    void setup() throws Exception {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("MapPanel");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        Files.write(directory.resolve("maps.yml"), YAML.getBytes(StandardCharsets.UTF_8));
        entity = new Values("maps.yml"); manager = new ConfigManager(); manager.register(plugin, entity);
        original = Files.readAllBytes(directory.resolve("maps.yml"));
    }

    @Test
    void uniqueNestedLeafPersistsAndRestartReadsSameTypedField() throws Exception {
        manager.loadFromJson("maps.yml", "{\"autoreply.rules.greet.reply\":\"welcome\"}");
        assertThat(entity.rules.get("greet").get("reply")).isEqualTo("welcome");
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
        Values restarted = new Values("maps.yml"); restarted.init(plugin);
        assertThat(restarted.rules).isEqualTo(entity.rules);
        ConfigDocument before = ConfigDocument.parse(new String(original, StandardCharsets.UTF_8));
        before.set(Arrays.asList("autoreply", "rules", "greet", "reply"), "welcome");
        ConfigDocument after = ConfigDocument.parse(new String(Files.readAllBytes(directory.resolve("maps.yml")),
                StandardCharsets.UTF_8));
        assertThat(after.toPlain()).isEqualTo(before.toPlain());
        assertThat(after.render()).contains("# operator header", "# greeting note", "unknown: operator");
    }

    @Test
    void typedMapLeafUsesDeclaredIntegerConverter() throws Exception {
        manager.loadFromJson("maps.yml", "{\"limits.worlds.nether\":5}");
        assertThat(entity.worlds.get("nether")).isInstanceOf(Integer.class).isEqualTo(5);
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    void invalidTypedLeafNamesEntryAndLeavesEverythingUntouched() {
        assertThatThrownBy(() -> manager.loadFromJson("maps.yml", "{\"limits.worlds.nether\":\"five\"}"))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("limits.worlds.nether");
        assertUnchanged();
    }

    @Test
    void dottedWholeKeyStaysWhole() throws Exception {
        manager.loadFromJson("maps.yml", "{\"emojis.mappings.o.O\":\"new face\"}");
        assertThat(entity.emojis).containsEntry("o.O", "new face").doesNotContainKey("o");
        ConfigDocument after = ConfigDocument.parse(new String(Files.readAllBytes(directory.resolve("maps.yml")),
                StandardCharsets.UTF_8));
        assertThat(after.get(Arrays.asList("emojis", "mappings", "o.O"))).isEqualTo("new face");
    }

    @Test
    void ambiguousChangedLeafNamesBothReadingsAndRefusesOtherChangedKeys() throws Exception {
        Files.write(directory.resolve("maps.yml"), YAML.replace("    o.O: face", "    o:\n      O: nested\n    o.O: face")
                .getBytes(StandardCharsets.UTF_8));
        entity.reload(); original = Files.readAllBytes(directory.resolve("maps.yml"));
        assertThatThrownBy(() -> manager.loadFromJson("maps.yml",
                "{\"emojis.mappings.o.O\":\"new\",\"limits.worlds.nether\":5}"))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("emojis.mappings.o.O")
                .hasMessageContaining("[o, O]").hasMessageContaining("[o.O]");
        assertThat(Files.readAllBytes(directory.resolve("maps.yml"))).isEqualTo(original);
        assertThat(entity.worlds.get("nether")).isEqualTo(2);
    }

    @Test
    void unknownChangedLeavesAreAllNamedAndRefuseWholePayload() {
        assertThatThrownBy(() -> manager.loadFromJson("maps.yml",
                "{\"autoreply.rules.nosuch.reply\":\"new\",\"limits.worlds.absent\":5,\"limits.worlds.nether\":5}"))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("autoreply.rules.nosuch.reply")
                .hasMessageContaining("limits.worlds.absent");
        assertUnchanged();
    }

    @Test
    void fullLeafPayloadIgnoresUnchangedUnknownAndAmbiguousLeaves() throws Exception {
        Files.write(directory.resolve("maps.yml"), YAML.replace("    o.O: face", "    o:\n      O: nested\n    o.O: face")
                .getBytes(StandardCharsets.UTF_8));
        entity.reload();
        JsonObject leaves = entity.toJsonObject();
        assertThat(leaves.keySet()).contains("emojis.mappings.o.O", "unknown", "autoreply.rules.greet.reply");
        leaves.addProperty("autoreply.rules.greet.reply", "welcome");
        manager.loadFromJson("maps.yml", leaves.toString());
        assertThat(entity.rules.get("greet").get("reply")).isEqualTo("welcome");
        assertThat(entity.emojis).containsEntry("o.O", "face");
        assertThat(entity.worlds.get("nether")).isEqualTo(2);
    }

    @Test
    void fullNoOpPayloadPreservesBytesAndModificationTime() throws Exception {
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(directory.resolve("maps.yml"));
        manager.loadFromJson("maps.yml", entity.toJsonObject().toString());
        assertUnchanged();
        assertThat(Files.getLastModifiedTime(directory.resolve("maps.yml"))).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void leafEditPreservesPendingTypedSiblingAndItsDirtyBaseline(boolean staged) throws Exception {
        entity.worlds.put("overworld", 3);
        entity.save();
        entity.worlds.put("overworld", 9);
        editWorld(staged);
        assertThat(entity.worlds).containsEntry("nether", 5).containsEntry("overworld", 9);
        assertThat(entity.isModifiedSinceSnapshot()).isTrue();
        assertThat(readWorlds()).containsEntry("nether", 5).containsEntry("overworld", 3);
        entity.save();
        assertThat(readWorlds()).containsEntry("overworld", 9);
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void leafEditPreservesIndependentDiskSiblingWithoutAcknowledgingIt(boolean staged) throws Exception {
        entity.worlds.put("overworld", 3); entity.save();
        Path file = directory.resolve("maps.yml");
        Files.write(file, new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("overworld: 3", "overworld: 7").getBytes(StandardCharsets.UTF_8));
        editWorld(staged);
        assertThat(readWorlds()).containsEntry("nether", 5).containsEntry("overworld", 7);
        assertThat(entity.worlds).containsEntry("nether", 5).containsEntry("overworld", 3);
        java.lang.reflect.Field acknowledgments = AbstractConfigEntity.class.getDeclaredField("acknowledgedRaw");
        acknowledgments.setAccessible(true);
        Object acknowledgment = ((Map<?, ?>) acknowledgments.get(entity)).get(Values.class.getDeclaredField("worlds"));
        java.lang.reflect.Field value = acknowledgment.getClass().getDeclaredField("value"); value.setAccessible(true);
        assertThat((Map<?, ?>) value.get(acknowledgment)).isEqualTo(new LinkedHashMap<String, Integer>() {{
            put("nether", 5); put("overworld", 3);
        }});
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fullDeclaredMapConverterRoundTripsUntouchedSiblings(boolean staged) throws Exception {
        com.ultikits.ultitools.config.convert.ConverterRegistry registry =
                new com.ultikits.ultitools.config.convert.ConverterRegistry(
                        com.ultikits.ultitools.config.convert.ConverterRegistry.framework());
        registry.register(Map.class, new com.ultikits.ultitools.config.convert.ConfigConverter<Map<String, Integer>>() {
            @Override public Object toPlain(Map<String, Integer> value,
                    com.ultikits.ultitools.config.convert.ConversionContext context) {
                Map<String, Integer> plain = new LinkedHashMap<>();
                value.forEach((key, number) -> plain.put(key, number - 10));
                return plain;
            }
            @Override public Map<String, Integer> fromPlain(Object plain,
                    com.ultikits.ultitools.config.convert.ConversionContext context) {
                assertThat(context.declaredType().getTypeName()).contains("java.util.Map<java.lang.String, java.lang.Integer>");
                Map<String, Integer> result = new LinkedHashMap<>();
                ((Map<?, ?>) plain).forEach((key, value) -> result.put(String.valueOf(key), ((Number) value).intValue() + 10));
                return result;
            }
        }, true);
        try (org.mockito.MockedStatic<com.ultikits.ultitools.config.convert.ConverterRegistry> registries =
                Mockito.mockStatic(com.ultikits.ultitools.config.convert.ConverterRegistry.class, Mockito.CALLS_REAL_METHODS)) {
            registries.when(() -> com.ultikits.ultitools.config.convert.ConverterRegistry.forModule(plugin)).thenReturn(registry);
            Files.write(directory.resolve("custom.yml"), "limits:\n  nether: 2\n  overworld: 3\n".getBytes(StandardCharsets.UTF_8));
            TypedValues custom = new TypedValues("custom.yml"); manager.register(plugin, custom);
            custom.limits.put("overworld", 19);
            if (staged) { manager.loadFromJson("{\"MapPanel\":{\"custom.yml\":{\"limits.nether\":5}}}"); }
            else { manager.loadFromJson("custom.yml", "{\"limits.nether\":5}"); }
            assertThat(custom.limits).containsEntry("nether", 15).containsEntry("overworld", 19);
            assertThat(custom.isModifiedSinceSnapshot()).isTrue();
            ConfigDocument disk = ConfigDocument.parse(new String(Files.readAllBytes(directory.resolve("custom.yml")),
                    StandardCharsets.UTF_8));
            assertThat(disk.get(Arrays.asList("limits", "nether"))).isEqualTo(5);
            assertThat(disk.get(Arrays.asList("limits", "overworld"))).isEqualTo(3);
            custom.save();
            TypedValues restarted = new TypedValues("custom.yml"); restarted.init(plugin);
            assertThat(restarted.limits).isEqualTo(custom.limits);
            assertThat(restarted.isModifiedSinceSnapshot()).isFalse();
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> roundTripShapes() {
        return java.util.stream.Stream.of("sorted", "enums", "holders", "nested", "custom")
                .flatMap(shape -> java.util.stream.Stream.of(false, true)
                        .map(staged -> org.junit.jupiter.params.provider.Arguments.of(shape, staged)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("roundTripShapes")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void registryLeafRoutePreservesDeclaredShapesAndIndependentSiblings(String shape, boolean staged) throws Exception {
        com.ultikits.ultitools.config.convert.ConverterRegistry registry =
                new com.ultikits.ultitools.config.convert.ConverterRegistry(
                        com.ultikits.ultitools.config.convert.ConverterRegistry.framework());
        registry.register(ShiftedMap.class, new ShiftedConverter(), true);
        org.bukkit.configuration.serialization.ConfigurationSerialization.registerClass(NumericHolder.class);
        try (org.mockito.MockedStatic<com.ultikits.ultitools.config.convert.ConverterRegistry> registries =
                Mockito.mockStatic(com.ultikits.ultitools.config.convert.ConverterRegistry.class, Mockito.CALLS_REAL_METHODS)) {
            registries.when(() -> com.ultikits.ultitools.config.convert.ConverterRegistry.forModule(plugin)).thenReturn(registry);
            ShapeValues values = new ShapeValues("shapes.yml"); manager.register(plugin, values);
            java.lang.reflect.Field field = ShapeValues.class.getDeclaredField(shape); field.setAccessible(true);
            java.lang.reflect.Type type = field.getGenericType();
            java.util.List<String> target = leafPath(shape, true);
            java.util.List<String> sibling = leafPath(shape, false);
            ConfigDocument live = ConfigDocument.empty();
            live.set(Arrays.asList(shape), registry.toPlain(field.get(values), type, "shapes.yml", Arrays.asList(shape)));
            live.set(sibling, 9);
            Object pending = registry.fromPlain(live.get(Arrays.asList(shape)), type, "shapes.yml", Arrays.asList(shape));
            field.set(values, pending);
            assertThat(registry.toPlain(registry.fromPlain(live.get(Arrays.asList(shape)), type,
                    "shapes.yml", Arrays.asList(shape)), type, "shapes.yml", Arrays.asList(shape)))
                    .isEqualTo(live.get(Arrays.asList(shape)));
            Path file = directory.resolve("shapes.yml");
            ConfigDocument disk = ConfigDocument.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            disk.set(sibling, 7); Files.write(file, disk.render().getBytes(StandardCharsets.UTF_8));
            JsonObject edit = new JsonObject(); edit.addProperty(String.join(".", target), 5);
            if (staged) {
                JsonObject files = new JsonObject(); files.add("shapes.yml", edit);
                JsonObject modules = new JsonObject(); modules.add("MapPanel", files); manager.loadFromJson(modules.toString());
            } else { manager.loadFromJson("shapes.yml", edit.toString()); }
            ConfigDocument published = ConfigDocument.empty();
            published.set(Arrays.asList(shape), registry.toPlain(field.get(values), type, "shapes.yml", Arrays.asList(shape)));
            assertThat(published.get(target)).isEqualTo(5);
            assertThat(published.get(sibling)).isEqualTo(9);
            assertThat(field.get(values)).isInstanceOf(field.getType());
            if ("enums".equals(shape)) { assertThat(values.enums.keySet()).containsExactly(Mode.FIRST, Mode.SECOND); }
            if ("holders".equals(shape)) { assertThat(values.holders.get("first")).isInstanceOf(NumericHolder.class); }
            assertThat(values.isModifiedSinceSnapshot()).isTrue();
            disk = ConfigDocument.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            assertThat(disk.get(target)).isEqualTo(5); assertThat(disk.get(sibling)).isEqualTo(7);
            values.save(); assertThat(values.isModifiedSinceSnapshot()).isFalse();
            disk = ConfigDocument.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            assertThat(disk.get(sibling)).isEqualTo(9);
            ShapeValues restarted = new ShapeValues("shapes.yml"); restarted.init(plugin);
            assertThat(registry.toPlain(field.get(restarted), type, "shapes.yml", Arrays.asList(shape)))
                    .isEqualTo(published.get(Arrays.asList(shape)));
            assertThat(restarted.isModifiedSinceSnapshot()).isFalse();
        } finally {
            org.bukkit.configuration.serialization.ConfigurationSerialization.unregisterClass(NumericHolder.class);
        }
    }

    private static java.util.List<String> leafPath(String shape, boolean target) {
        String key = target ? "first" : "second";
        if ("enums".equals(shape)) { key = target ? "FIRST" : "SECOND"; }
        if ("holders".equals(shape)) { return Arrays.asList(shape, key, "value"); }
        if ("nested".equals(shape)) { return Arrays.asList(shape, "o.O", key); }
        return Arrays.asList(shape, key);
    }

    enum Mode { FIRST, SECOND }
    @org.bukkit.configuration.serialization.SerializableAs("PanelNumericHolder")
    public static class NumericHolder implements org.bukkit.configuration.serialization.ConfigurationSerializable {
        final int value;
        public NumericHolder(int value) { this.value = value; }
        public static NumericHolder deserialize(Map<String, Object> data) {
            return new NumericHolder(((Number) data.get("value")).intValue());
        }
        @Override public Map<String, Object> serialize() {
            Map<String, Object> plain = new LinkedHashMap<>(); plain.put("value", value); return plain;
        }
    }
    public static class ShiftedMap extends LinkedHashMap<String, Integer> { }
    static class ShiftedConverter implements com.ultikits.ultitools.config.convert.ConfigConverter<ShiftedMap> {
        @Override public Object toPlain(ShiftedMap value, com.ultikits.ultitools.config.convert.ConversionContext context) {
            Map<String, Integer> plain = new LinkedHashMap<>(); value.forEach((key, number) -> plain.put(key, number - 10));
            return plain;
        }
        @Override public ShiftedMap fromPlain(Object plain, com.ultikits.ultitools.config.convert.ConversionContext context) {
            ShiftedMap result = new ShiftedMap();
            ((Map<?, ?>) plain).forEach((key, value) -> result.put((String) key, ((Number) value).intValue() + 10));
            return result;
        }
    }
    @ConfigEntity("shapes.yml")
    public static class ShapeValues extends AbstractConfigEntity {
        @ConfigEntry java.util.SortedMap<String, Integer> sorted = new java.util.TreeMap<>();
        @ConfigEntry Map<Mode, Integer> enums = new LinkedHashMap<>();
        @ConfigEntry Map<String, NumericHolder> holders = new LinkedHashMap<>();
        @ConfigEntry Map<String, Map<String, Integer>> nested = new LinkedHashMap<>();
        @ConfigEntry ShiftedMap custom = new ShiftedMap();
        public ShapeValues(String path) {
            super(path);
            sorted.put("first", 2); sorted.put("second", 3);
            enums.put(Mode.FIRST, 2); enums.put(Mode.SECOND, 3);
            holders.put("first", new NumericHolder(2));
            holders.put("second", new NumericHolder(3));
            Map<String, Integer> entries = new LinkedHashMap<>(); entries.put("first", 2); entries.put("second", 3);
            nested.put("o.O", entries); custom.put("first", 12); custom.put("second", 13);
        }
    }

    @ConfigEntity("custom.yml")
    public static class TypedValues extends AbstractConfigEntity {
        @ConfigEntry(path = "limits") Map<String, Integer> limits = new LinkedHashMap<>();
        public TypedValues(String path) { super(path); }
    }

    private void editWorld(boolean staged) throws Exception {
        if (staged) { manager.loadFromJson("{\"MapPanel\":{\"maps.yml\":{\"limits.worlds.nether\":5}}}"); }
        else { manager.loadFromJson("maps.yml", "{\"limits.worlds.nether\":5}"); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readWorlds() throws Exception {
        ConfigDocument disk = ConfigDocument.parse(new String(Files.readAllBytes(directory.resolve("maps.yml")),
                StandardCharsets.UTF_8));
        return (Map<String, Object>) disk.get(Arrays.asList("limits", "worlds"));
    }

    private void assertUnchanged() {
        try { assertThat(Files.readAllBytes(directory.resolve("maps.yml"))).isEqualTo(original); }
        catch (java.io.IOException failure) { throw new AssertionError(failure); }
        assertThat(entity.rules.get("greet").get("reply")).isEqualTo("hello");
        assertThat(entity.worlds.get("nether")).isEqualTo(2);
    }

    @ConfigEntity("maps.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.rules") Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        @ConfigEntry(path = "limits.worlds") Map<String, Integer> worlds = new LinkedHashMap<>();
        @ConfigEntry(path = "emojis.mappings") Map<String, Object> emojis = new LinkedHashMap<>();
        public Values(String path) { super(path); }
    }
}
