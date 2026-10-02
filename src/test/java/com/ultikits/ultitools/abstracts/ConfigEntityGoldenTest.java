package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.CommentEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.comments.CommentType;

import com.ultikits.ultitools.abstracts.golden.BundledEntities;
import com.ultikits.ultitools.abstracts.golden.Capture62Entities;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.PlainData;
import com.ultikits.ultitools.manager.ConfigManager;

/** Entity-level corpus acceptance: unchanged lifecycle never emits; edited saves retain untargeted data. */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Fixture entries are the retained capture's private/package fields.
class ConfigEntityGoldenTest {
    private static final String ALIAS = "com.ultikits.ultitools.abstracts.Capture62WriterTest$Kit";
    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    private Class<? extends ConfigurationSerializable> previousAlias;

    @BeforeEach
    void setup() {
        previousAlias = ConfigurationSerialization.getClassByAlias(ALIAS);
        ConfigurationSerialization.registerClass(Capture62Entities.Kit.class, ALIAS);
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("GoldenModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.resolve("resources").toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(directory.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                call -> new File(directory.toFile(), call.<String>getArgument(0)));
    }

    @AfterEach
    void restoreAlias() {
        ConfigurationSerialization.unregisterClass(Capture62Entities.Kit.class);
        if (previousAlias != null) { ConfigurationSerialization.registerClass(previousAlias, ALIAS); }
    }

    static Path corpus() throws Exception {
        return Paths.get(ConfigEntityGoldenTest.class.getResource("/config-golden/MANIFEST.md").toURI()).getParent();
    }

    static Stream<String> fixtures() throws Exception {
        return Files.readAllLines(corpus().resolve("MANIFEST.md"), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("| bundled/") || line.startsWith("| written-by-6.2/"))
                .map(line -> line.split(" \\| ")[0].substring(2));
    }

    static Stream<String> writtenFixtures() throws Exception {
        return fixtures().filter(name -> name.startsWith("written-by-6.2/"));
    }

    private static AbstractConfigEntity entity(String fixture) {
        if (fixture.startsWith("bundled/")) { return BundledEntities.create(fixture, "golden.yml"); }
        switch (fixture.substring("written-by-6.2/".length(), "written-by-6.2/".length() + 2)) {
            case "01": return new Capture62Entities.Booleans("golden.yml");
            case "02": return new Capture62Entities.Integers("golden.yml");
            case "03": return new Capture62Entities.Decimals("golden.yml");
            case "04": return new Capture62Entities.Strings("golden.yml");
            case "05": return new Capture62Entities.Unicode("golden.yml");
            case "06": return new Capture62Entities.Lists("golden.yml");
            case "07": return new Capture62Entities.ListOfMaps("golden.yml");
            case "08": return new Capture62Entities.NestedMaps("golden.yml");
            case "09": case "10": return new Capture62Entities.Dotted("golden.yml");
            case "11": return new Capture62Entities.TrailingDot("golden.yml");
            case "12": return new Capture62Entities.Serializable("golden.yml");
            case "13": return new Capture62Entities.Comments("golden.yml");
            default: throw new IllegalArgumentException("Unknown capture fixture: " + fixture);
        }
    }

    private static List<Field> entries(AbstractConfigEntity entity) {
        List<Field> fields = new ArrayList<>();
        for (Field field : entity.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(ConfigEntry.class)) { field.setAccessible(true); fields.add(field); }
        }
        return fields;
    }

    private static List<String> path(Field field) {
        String declared = field.getAnnotation(ConfigEntry.class).path();
        return Arrays.asList((declared.isEmpty() ? field.getName() : declared).split("\\.", -1));
    }

    private static Object at(Map<String, Object> tree, List<String> path) {
        Object value = tree;
        for (String key : path) { value = ((Map<?, ?>) value).get(key); }
        return value;
    }

    /** Independent plain projection of fixture objects, not the registry or entity snapshot under test. */
    private static Object plain(Object value) {
        if (value instanceof ConfigurationSerializable) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((ConfigurationSerializable) value).serialize().forEach((key, item) -> result.put(key, plain(item)));
            result.put("==", ALIAS);
            return result;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> result.put((String) key, plain(item)));
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) { result.add(plain(item)); }
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String text) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    private static List<String> comments(String text) {
        LoaderOptions options = new LoaderOptions(); options.setProcessComments(true);
        List<String> result = new ArrayList<>();
        for (Event event : new Yaml(options).parse(new StringReader(text))) {
            if (event instanceof CommentEvent && ((CommentEvent) event).getCommentType() != CommentType.BLANK_LINE) {
                result.add(((CommentEvent) event).getValue());
            }
        }
        return result;
    }

    private static void unchanged(Path file, byte[] bytes, FileTime time) throws Exception {
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(time);
    }

    @Test
    void corpusCoverageIsExactlyFourteenBundledAndThirteenCapturedFiles() throws Exception {
        assertThat(fixtures().filter(name -> name.startsWith("bundled/")).count()).isEqualTo(14);
        assertThat(writtenFixtures().count()).isEqualTo(13);
    }

    @ParameterizedTest(name = "unchanged {0}")
    @MethodSource("fixtures")
    void initReloadExplicitAndShutdownNoOpKeepEveryByteAndMtime(String fixture) throws Exception {
        byte[] bytes = Files.readAllBytes(corpus().resolve(fixture));
        Path file = directory.resolve("golden.yml"); Files.write(file, bytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(946684800000L));
        FileTime time = Files.getLastModifiedTime(file);
        Map<String, Object> disk = read(new String(bytes, StandardCharsets.UTF_8));
        AbstractConfigEntity config = entity(fixture);
        Map<Field, Object> expected = new LinkedHashMap<>();
        for (Field field : entries(config)) {
            expected.put(field, fixture.startsWith("bundled/") ? at(disk, path(field)) : plain(field.get(config)));
        }
        // Follow-up 16: never recombine old split maps; bad String elements are skipped, valid siblings survive.
        if (fixture.endsWith("10-dotted-keys-save.yml")) {
            Map<String, String> retained = new LinkedHashMap<>(); retained.put(":heart:", "❤");
            expected.put(entries(config).get(0), retained);
        }
        ConfigManager manager = new ConfigManager();
        manager.register(plugin, config);
        assertThat(manager.getConfigEntity(plugin, config.getClass())).isSameAs(config);
        assertFields(config, expected);
        unchanged(file, bytes, time);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        // Capture bound objects, then prove reload preserves unsaved memory-only changes across every corpus field.
        Map<Field, Object> bound = new LinkedHashMap<>();
        for (Field field : entries(config)) { bound.put(field, field.get(config)); }
        for (Field field : entries(config)) {
            if (field.getType().isPrimitive()) {
                Object value = field.get(config);
                if (value instanceof Boolean) { field.set(config, !((Boolean) value)); }
                else if (value instanceof Integer) { field.set(config, ((Integer) value) - 1); }
                else if (value instanceof Long) { field.set(config, ((Long) value) - 1L); }
                else if (value instanceof Double) { field.set(config, ((Double) value) + 7.0); }
                else { throw new IllegalStateException("Uncovered capture primitive: " + field); }
            } else { field.set(config, null); }
        }
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        Map<Field, Object> pending = new LinkedHashMap<>();
        for (Field field : entries(config)) { pending.put(field, plain(field.get(config))); }
        config.reload();
        assertFields(config, pending);
        unchanged(file, bytes, time);
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        // Restore the original bound values to independently retain the exact no-op save corpus control.
        for (Map.Entry<Field, Object> entry : bound.entrySet()) { entry.getKey().set(config, entry.getValue()); }
        assertFields(config, expected);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        manager.saveAll();
        unchanged(file, bytes, time);
        Map<String, Object> serialized = read(new String(bytes, StandardCharsets.UTF_8));
        for (Map.Entry<Field, Object> entry : expected.entrySet()) { put(serialized, path(entry.getKey()), entry.getValue()); }
        boolean semanticNoOp = PlainData.plainEquals(disk, serialized);
        if (!semanticNoOp) {
            assertThat(fixture).as("the sole accepted raw/effective difference").isEqualTo("written-by-6.2/10-dotted-keys-save.yml");
            assertThat(new ArrayList<Object>(((Map<?, ?>) at(disk, Arrays.asList("emojis", "mappings"))).keySet()))
                    .contains("o", "g");
        }
        config.save();
        if (semanticNoOp) { unchanged(file, bytes, time); }
        else {
            // Explicit save replaces the old split raw data with the already accepted typed binding.
            assertThat(read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))).isEqualTo(serialized);
            assertThat(comments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)))
                    .containsExactlyInAnyOrderElementsOf(comments(new String(bytes, StandardCharsets.UTF_8)));
        }
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    public static class MergedToken extends AbstractConfigEntity {
        @ConfigEntry(path = "group.setting", comment = "{setting.note}") String setting = "default";
        public MergedToken(String path) { super(path); }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"init", "reload", "save", "panel"})
    void mergedTokenOwnsOnlyItsEntryAndSubsequentSavesAreByteNoOps(String operation) throws Exception {
        Path file = directory.resolve("merge.yml");
        Mockito.when(plugin.i18n("setting.note")).thenReturn("Owned setting note");
        MergedToken config = new MergedToken("merge.yml");
        if (!operation.equals("init")) {
            Files.write(file, "group:\n  setting: inherited\n".getBytes(StandardCharsets.UTF_8));
            config.init(plugin);
        }
        String original = "# Operator header\n# Anchor owner note\ndefaults: &defaults\n  setting: inherited\n"
                + "  sibling: kept\ngroup:\n  <<: *defaults\n  # Local note\n  local: retained\n"
                + "# Tail note\ntail: intact\n";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> expected = read(original);
        if (operation.equals("init")) { config.init(plugin); }
        else if (operation.equals("reload")) { config.reload(); }
        else if (operation.equals("save")) { config.save(); }
        else {
            com.google.gson.JsonObject panel = new com.google.gson.JsonObject();
            panel.addProperty("group.setting", "inherited");
            config.updateProperties(panel);
        }
        assertThat(config.setting).isEqualTo("inherited");
        String rendered = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertThat(PlainData.plainEquals(read(rendered), expected)).isTrue();
        assertOrderOutsideTarget(read(rendered), expected, new ArrayList<>(), Arrays.asList("group", "setting"));
        assertThat(comments(rendered)).containsAll(comments(original));
        com.ultikits.ultitools.config.document.ConfigDocument document =
                com.ultikits.ultitools.config.document.ConfigDocument.load(file).document();
        assertThat(document.blockComment(Arrays.asList("group", "setting"))).containsExactly("Owned setting note");
        assertThat(document.blockComment(Arrays.asList("defaults"))).contains("Anchor owner note");
        assertThat(document.blockComment(Arrays.asList("defaults", "setting"))).isEmpty();
        assertThat(document.blockComment(Arrays.asList("defaults", "sibling"))).isEmpty();
        byte[] bytes = Files.readAllBytes(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(946684800000L));
        FileTime time = Files.getLastModifiedTime(file);
        config.save(); unchanged(file, bytes, time);
        config.reload(); unchanged(file, bytes, time);
        com.google.gson.JsonObject panel = new com.google.gson.JsonObject();
        panel.addProperty("group.setting", "inherited");
        config.updateProperties(panel); unchanged(file, bytes, time);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    private static void assertFields(AbstractConfigEntity config, Map<Field, Object> expected) throws Exception {
        for (Map.Entry<Field, Object> entry : expected.entrySet()) {
            assertThat(plain(entry.getKey().get(config))).as(entry.getKey().getName()).isEqualTo(entry.getValue());
        }
    }

    @ParameterizedTest(name = "edited {0}")
    @MethodSource("writtenFixtures")
    void editedSaveKeepsUntargetedContentCommentsOrderAndStyle(String fixture) throws Exception {
        String original = new String(Files.readAllBytes(corpus().resolve(fixture)), StandardCharsets.UTF_8);
        Path file = directory.resolve("golden.yml"); Files.write(file, original.getBytes(StandardCharsets.UTF_8));
        AbstractConfigEntity config = entity(fixture); config.init(plugin);
        Field field = entries(config).get(0);
        Object current = field.get(config);
        if (current instanceof Boolean) { field.set(config, !((Boolean) current)); }
        else if (current instanceof Integer) { field.set(config, ((Integer) current) + 1); }
        else if (current instanceof Double) { field.set(config, ((Double) current) + 1.0); }
        else if (current instanceof String) { field.set(config, current + " changed"); }
        else if (current instanceof List<?>) { ((List<?>) current).remove(((List<?>) current).size() - 1); }
        else if (current instanceof Map<?, ?>) {
            List<?> keys = new ArrayList<>(((Map<?, ?>) current).keySet());
            ((Map<?, ?>) current).remove(keys.get(keys.size() - 1));
        } else { throw new IllegalStateException("Uncovered fixture field: " + field); }
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        Map<String, Object> expected = read(original);
        put(expected, path(field), plain(field.get(config)));
        config.save();
        String rendered = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Map<String, Object> actual = read(rendered);
        assertThat(PlainData.plainEquals(actual, expected)).isTrue();
        assertOrderOutsideTarget(actual, expected, new ArrayList<>(), path(field));
        assertThat(comments(rendered)).containsExactlyInAnyOrderElementsOf(comments(original));
        assertThat(rendered.startsWith("﻿")).isEqualTo(original.startsWith("﻿"));
        assertThat(rendered.endsWith("\n") || rendered.endsWith("\r")).isEqualTo(original.endsWith("\n") || original.endsWith("\r"));
        assertThat(rendered).doesNotContain("\r"); // Every captured fixture uses LF; layout elsewhere may normalize.
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        config.reload();
        assertThat(plain(field.get(config))).isEqualTo(at(expected, path(field)));
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> tree, List<String> path, Object value) {
        Map<String, Object> owner = tree;
        for (int i = 0; i < path.size() - 1; i++) { owner = (Map<String, Object>) owner.get(path.get(i)); }
        owner.put(path.get(path.size() - 1), value);
    }

    private static void assertOrderOutsideTarget(Object actual, Object expected, List<String> current, List<String> target) {
        if (current.equals(target)) { return; }
        if (expected instanceof Map<?, ?>) {
            Map<?, ?> a = (Map<?, ?>) actual; Map<?, ?> e = (Map<?, ?>) expected;
            assertThat(new ArrayList<Object>(a.keySet())).containsExactlyElementsOf(new ArrayList<Object>(e.keySet()));
            for (Object key : e.keySet()) {
                List<String> next = new ArrayList<>(current); next.add((String) key);
                assertOrderOutsideTarget(a.get(key), e.get(key), next, target);
            }
        } else if (expected instanceof List<?>) {
            List<?> a = (List<?>) actual; List<?> e = (List<?>) expected;
            for (int i = 0; i < e.size(); i++) {
                List<String> next = new ArrayList<>(current); next.add(Integer.toString(i));
                assertOrderOutsideTarget(a.get(i), e.get(i), next, target);
            }
        }
    }
}
