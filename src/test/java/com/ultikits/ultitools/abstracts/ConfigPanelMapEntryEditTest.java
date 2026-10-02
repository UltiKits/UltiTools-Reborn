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
