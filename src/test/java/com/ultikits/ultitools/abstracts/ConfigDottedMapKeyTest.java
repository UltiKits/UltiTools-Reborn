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
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;

/** Whole map keys (Follow-up 16), explicit null data, and unchanged display paths (Follow-up 14). */
class ConfigDottedMapKeyTest {
    private static final String PATH = "config/dotted.yml";
    @TempDir Path tempDir;
    private UltiToolsPlugin plugin;

    static class RulesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.rules")
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        @ConfigEntry(path = "features.aliases")
        Map<String, String> aliases = new LinkedHashMap<>();
        @ConfigEntry(path = "a.b.c") int nested = 3;
        public RulesConfig(String path) { super(path); aliases.put("g.m", "gamemode"); }
    }

    @BeforeEach void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DottedModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }
    private Path file() { return tempDir.resolve(PATH); }
    private void write(String text) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String, Object> rule(String text) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("reply", text); return result;
    }
    @Test void defaultsAndSavesKeepWholeKeys() throws IOException {
        RulesConfig config = new RulesConfig(PATH); config.init(plugin);
        config.aliases.put("wave.", "z"); config.save();
        RulesConfig second = new RulesConfig(PATH); second.init(plugin);
        assertThat(second.aliases).containsEntry("g.m", "gamemode").containsEntry("wave.", "z");
        assertThat(second.isModifiedSinceSnapshot()).isFalse();
    }
    @Test void operatorDottedKeysLoadWholeQuotedOrNot() throws IOException {
        write("autoreply:\n  rules: {}\nfeatures:\n  aliases:\n    \"o.O\": hi\n    wave.: z\na:\n  b:\n    c: 3\n");
        RulesConfig config = new RulesConfig(PATH); config.init(plugin); config.reload(); config.save();
        assertThat(config.aliases).containsOnlyKeys("o.O", "wave.");
        RulesConfig second = new RulesConfig(PATH); second.init(plugin);
        assertThat(second.aliases).containsEntry("o.O", "hi").containsEntry("wave.", "z");
    }
    @Test void oldSplitMapIsNotMergedOrRewritten() throws IOException {
        write("autoreply:\n  rules: {}\nfeatures:\n  aliases:\n    g:\n      m: x\na:\n  b:\n    c: 3\n");
        byte[] before = Files.readAllBytes(file()); RulesConfig config = new RulesConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.aliases).isEmpty();
            assertThat(warnings.messages()).hasSize(1).allSatisfy(
                    message -> assertThat(message).contains(PATH).contains("features.aliases").contains("g"));
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }
    @Test void nullInsideMapIsWrittenAndReadWithoutWarning() throws IOException {
        RulesConfig config = new RulesConfig(PATH); config.init(plugin); config.aliases.put("broken", null);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(config::save).doesNotThrowAnyException(); assertThat(warnings.messages()).isEmpty();
        }
        RulesConfig second = new RulesConfig(PATH); second.init(plugin);
        assertThat(second.aliases).containsEntry("broken", null);
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).contains("broken: null");
    }
    @Test @DisplayName("Nested section and panel display paths retain their existing shape")
    void structureIsAsBefore() throws Exception {
        write("autoreply:\n  rules:\n    a:\n      reply: x\n    b:\n      reply: y\nfeatures:\n  aliases:\n    gm: gamemode\na:\n  b:\n    c: 9\n");
        RulesConfig config = new RulesConfig(PATH); config.init(plugin);
        assertThat(config.rules).containsOnlyKeys("a", "b"); assertThat(config.aliases).containsOnlyKeys("gm");
        YamlConfiguration live = ConfigFileView.read(config);
        assertThat(live.getString("autoreply.rules.a.reply")).isEqualTo("x");
        assertThat(live.getInt("a.b.c")).isEqualTo(9);
        assertThat(live.getConfigurationSection("autoreply.rules").getCurrentPath()).isEqualTo("autoreply.rules");
        config.rules.put("c", rule("z")); config.save();
        assertThat(ConfigFileView.read(config).getString("autoreply.rules.c.reply")).isEqualTo("z");
        JsonObject payload = config.toJsonObject();
        assertThat(payload.has("autoreply.rules.a.reply")).isTrue(); assertThat(payload.has("a.b.c")).isTrue();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }
}
