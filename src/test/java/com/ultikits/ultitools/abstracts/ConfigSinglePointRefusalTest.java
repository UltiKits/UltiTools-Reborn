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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;

/**
 * #553 route change (orchestrator ruling of 2026-09-30, keeping the maintainer's "refuse and say so"):
 * a dotted map key is refused at one point only - where a built-in serializer turns a map into a
 * configuration section - with the entity's warning sink, so every warning names the file, the entry
 * and the module, once per key per write. A serializer that extends the default one behaves the same;
 * maps inside objects and inside lists go through the same point. A map default is placed into the
 * file as plain data, as in 6.2, with values the loader reads back: a {@code UUID} as text, an enum by
 * name, a {@code ConfigurationSerializable} written by Bukkit itself.
 */
@DisplayName("AbstractConfigEntity - one refusal point for dotted map keys; map defaults that read back (#553)")
class ConfigSinglePointRefusalTest {

    private static final String PATH = "config/single.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    /** A registered Bukkit-serializable value, standing in for Location and ItemStack. */
    public static final class Point implements ConfigurationSerializable {
        final int x;

        public Point(int x) {
            this.x = x;
        }

        public static Point deserialize(Map<String, Object> map) {
            return new Point(((Number) map.get("x")).intValue());
        }

        @Override
        public Map<String, Object> serialize() {
            return Collections.<String, Object>singletonMap("x", x);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class DefaultsConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "ids")
        Map<String, UUID> ids = single("owner", UUID.fromString("00000000-0000-0000-0000-000000000001"));

        @ConfigEntry(path = "points")
        Map<String, Point> points = single("spawn", new Point(7));

        public DefaultsConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A module serializer that extends the default one and overrides only parsing. */
    public static class RulesParser extends DefaultConfigParser {
        @Override
        public Object parse(Object object) {
            return super.parse(object);
        }
    }

    @SuppressWarnings("unused")
    static class SubclassParserConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "rules", parser = RulesParser.class)
        Map<String, String> rules = new LinkedHashMap<>();

        public SubclassParserConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** An object stored as a map value or a list element, with a map of its own. */
    public static class Holder {
        private Map<String, String> ingredients = new LinkedHashMap<>();
    }

    @SuppressWarnings("unused")
    static class NestedConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "recipes")
        Map<String, Holder> recipes = new LinkedHashMap<>();

        @ConfigEntry(path = "rewards")
        List<Map<String, Integer>> rewards = new ArrayList<>();

        @ConfigEntry(path = "groups")
        Map<String, Map<String, Map<String, Integer>>> groups = new LinkedHashMap<>();

        @ConfigEntry(path = "holders")
        List<Holder> holders = new ArrayList<>();

        @ConfigEntry(path = "counter")
        int counter = 1;

        public NestedConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A module serializer that overrides the map writer and delegates to the default one. */
    public static class OverridingParser extends DefaultConfigParser {
        @Override
        public org.bukkit.configuration.MemorySection serializeToMemorySection(Object object) {
            return super.serializeToMemorySection(object);
        }
    }

    @SuppressWarnings("unused")
    static class OverridingConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "rules", parser = OverridingParser.class)
        Map<String, String> rules = new LinkedHashMap<>();

        public OverridingConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A Bukkit-serializable value whose class is never registered. */
    public static final class Unregistered implements ConfigurationSerializable {
        private int y = 3;

        @Override
        public Map<String, Object> serialize() {
            return Collections.<String, Object>singletonMap("y", y);
        }
    }

    @SuppressWarnings("unused")
    static class UnregisteredConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "things")
        Map<String, Unregistered> things = single("one", new Unregistered());

        public UnregisteredConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static <K, V> Map<K, V> single(K key, V value) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @BeforeAll
    static void registerPoint() {
        ConfigurationSerialization.registerClass(Point.class);
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("SingleModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private String readFile() throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(PATH)), StandardCharsets.UTF_8);
    }

    private static Holder holder(String key, String value) {
        Holder holder = new Holder();
        holder.ingredients.put(key, value);
        holder.ingredients.put("stick", "S");
        return holder;
    }

    @Test
    @DisplayName("a fresh install with a Map<String, UUID> and a Map<String, ConfigurationSerializable> default loads, and so does the next start")
    void mapDefaultsWithObjectValuesLoad() throws IOException {
        DefaultsConfig first = new DefaultsConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> first.init(plugin)).doesNotThrowAnyException();
            assertThat(warnings.messages()).isEmpty();
        }
        assertThat(readFile()).doesNotContain("java.util.UUID");

        DefaultsConfig second = new DefaultsConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            second.init(plugin);
            assertThat(warnings.messages()).isEmpty();
        }
        assertThat(second.isLastLoadUnparseable()).isFalse();
        assertThat(second.ids).containsEntry("owner", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(second.points.get("spawn")).isInstanceOf(Point.class);
        assertThat(second.points.get("spawn").x).isEqualTo(7);
        assertThat(second.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("a serializer that extends the default one refuses a dotted key on save and warns on load, naming the file")
    void subclassOfTheDefaultSerializerBehavesTheSame() throws IOException {
        SubclassParserConfig config = new SubclassParserConfig(PATH);
        config.init(plugin);
        config.rules.put("vip.gold", "x");
        config.rules.put("vip-silver", "y");

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            List<String> named = warnings.messagesContaining("'vip.gold'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains("SingleModule").contains(PATH).contains("'rules'");
        }
        assertThat(readFile()).doesNotContain("gold");

        Files.write(tempDir.resolve(PATH), "rules:\n  vip.gold: x\n".getBytes(StandardCharsets.UTF_8));
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new SubclassParserConfig(PATH).init(plugin);
            assertThat(warnings.messagesContaining("'vip.gold'")).hasSize(1)
                    .allSatisfy(message -> assertThat(message).contains("rename"));
        }
    }

    @Test
    @DisplayName("a dotted key in a map inside an object in a map is refused once, naming file, entry and module")
    void mapInsideAnObjectInAMap() throws IOException {
        NestedConfig config = new NestedConfig(PATH);
        config.init(plugin);
        config.recipes.put("sword", holder("minecraft.diamond", "D"));

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            List<String> named = warnings.messagesContaining("'minecraft.diamond'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains("SingleModule").contains(PATH).contains("'recipes'");
        }
        assertThat(readFile()).contains("stick").doesNotContain("diamond");
    }

    @Test
    @DisplayName("a map that is a list element is plain data: its dotted key survives two save/load cycles, with no warning")
    void listElementMapsKeepDottedKeys() throws IOException {
        Files.createDirectories(tempDir.resolve(PATH).getParent());
        Files.write(tempDir.resolve(PATH), ("rewards:\n- minecraft.diamond: 5\n  stick: 1\n"
                + "holders:\n- ingredients:\n    minecraft.gold: G\n").getBytes(StandardCharsets.UTF_8));
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            for (int cycle = 0; cycle < 2; cycle++) {
                NestedConfig config = new NestedConfig(PATH);
                config.init(plugin);
                assertThat(config.rewards).hasSize(1);
                assertThat(config.rewards.get(0)).containsEntry("minecraft.diamond", 5).containsEntry("stick", 1);
                config.counter = cycle + 5;
                config.save();
                assertThat(readFile()).contains("minecraft.diamond: 5").contains("minecraft.gold: G");
            }
            assertThat(warnings.messagesContaining("minecraft")).isEmpty();
        }
    }

    @Test
    @DisplayName("a section-bound dotted key is refused with a warning that names its nested path")
    void sectionBoundKeyNamesItsNestedPath() throws IOException {
        NestedConfig config = new NestedConfig(PATH);
        config.init(plugin);
        Map<String, Map<String, Integer>> perks = new LinkedHashMap<>();
        perks.put("perks", single("fly.speed", 2));
        config.groups.put("vip", perks);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            List<String> named = warnings.messagesContaining("'fly.speed'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("'groups.vip.perks'").contains("SingleModule");
        }
        assertThat(readFile()).doesNotContain("fly");
    }

    @Test
    @DisplayName("a serializer that overrides the map writer and calls super refuses on save and warns on load alike")
    void overridingSubclassIsNotExempt() throws IOException {
        OverridingConfig config = new OverridingConfig(PATH);
        config.init(plugin);
        config.rules.put("vip.gold", "x");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messagesContaining("'vip.gold'")).hasSize(1);
        }
        Files.write(tempDir.resolve(PATH), "rules:\n  vip.gold: x\n".getBytes(StandardCharsets.UTF_8));
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new OverridingConfig(PATH).init(plugin);
            assertThat(warnings.messagesContaining("'vip.gold'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("an unregistered ConfigurationSerializable in a map is written as its fields, as on alpha, and the file loads")
    void unregisteredSerializableIsWrittenAsFields() throws IOException {
        UnregisteredConfig config = new UnregisteredConfig(PATH);
        config.init(plugin);
        config.save();
        assertThat(readFile()).doesNotContain("==");
        UnregisteredConfig second = new UnregisteredConfig(PATH);
        assertThatCode(() -> second.init(plugin)).doesNotThrowAnyException();
        assertThat(second.isLastLoadUnparseable()).isFalse();
    }

    @Test
    @DisplayName("at shutdown a refused key is named once: by the save when the entity changed, by the check when it did not")
    void shutdownNamesARefusedKeyOnce() throws IOException {
        NestedConfig config = new NestedConfig(PATH);
        config.init(plugin);
        config.recipes.put("sword", holder("minecraft.iron", "I"));
        config.counter = 2;

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            boolean modified = config.isModifiedSinceSnapshot();
            assertThat(modified).isTrue();
            config.save();
            assertThat(warnings.messagesContaining("'minecraft.iron'")).hasSize(1);
        }

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThat(config.isModifiedSinceSnapshot()).isFalse();
            assertThat(warnings.messagesContaining("'minecraft.iron'")).hasSize(1);
        }
    }
}
