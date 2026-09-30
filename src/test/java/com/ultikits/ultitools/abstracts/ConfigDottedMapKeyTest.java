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
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * UltiKits/UltiTools-Reborn#553, maintainer answer of 2026-09-30 (no check, documentation only; in the
 * maintainer's words "不做检查，只写文档"; it supersedes "warn only" and the earlier "refuse and say so").
 * The configuration file uses {@code '.'} as its path separator, so a key such as {@code my.rule} in a
 * map the file stores as a section is split into nested levels - on save and on load, quoted in the YAML
 * or not. The framework writes and reads it exactly as 6.2 does and logs nothing about it; the limitation
 * is documented ({@code COMPATIBILITY.md}, the developer guide). These tests pin that documented
 * behaviour, and the one warning {@code save()} still gives: a {@code null} value inside a map or an
 * object is left out of the file, where 6.2 stopped the save with a {@code NullPointerException} and left
 * the file unchanged.
 */
@DisplayName("AbstractConfigEntity - dotted map keys are split as in 6.2, documented, not checked (#553)")
class ConfigDottedMapKeyTest {

    private static final String PATH = "config/dotted.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    /** An object stored as a map value, with a map and a text of its own. */
    public static class Holder {
        private Map<String, String> ingredients = new LinkedHashMap<>();
        private String note = "n";
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class RulesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.rules")
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();

        @ConfigEntry(path = "features.aliases")
        Map<String, String> aliases = new LinkedHashMap<>();

        @ConfigEntry(path = "groups")
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();

        @ConfigEntry(path = "recipes")
        Map<String, Holder> recipes = new LinkedHashMap<>();

        @ConfigEntry(path = "a.b.c")
        int nested = 3;

        public RulesConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DottedModule");
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

    private String readFile() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> rule(String reply) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("reply", reply);
        return rule;
    }

    @Test
    @DisplayName("save() writes a dotted key split, as 6.2 does, and logs nothing about it")
    void saveSplitsWithoutWarning() throws Exception {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        config.rules.put("my.rule", rule("hi"));

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(config::save).doesNotThrowAnyException();
            assertThat(warnings.messages()).isEmpty();
        }
        assertThat(config.rules).containsKey("my.rule");
        YamlConfiguration written = new YamlConfiguration();
        written.loadFromString(readFile());
        assertThat(written.getString("autoreply.rules.my.rule.reply")).isEqualTo("hi");
    }

    @Test
    @DisplayName("a dotted key an operator wrote loads split, quoted or not, and logs nothing about it")
    void operatorDottedKeyLoadsSplitQuotedOrNot() throws IOException {
        writeFile("autoreply:\n  rules:\n    \"my.rule\":\n      reply: hi\n    plain.one:\n      reply: yo\n"
                + "features:\n  aliases: {}\ngroups: {}\nrecipes: {}\na:\n  b:\n    c: 3\n");

        RulesConfig config = new RulesConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            config.reload();
            assertThat(warnings.messages()).isEmpty();
        }
        // Quoting the key in YAML does not keep it whole: the loader splits it at the path separator.
        assertThat(config.rules).containsOnlyKeys("my", "plain");
        assertThat(config.getConfig().getString("autoreply.rules.my.rule.reply")).isEqualTo("hi");
        assertThat(config.getConfig().getString("autoreply.rules.plain.one.reply")).isEqualTo("yo");
    }

    @Test
    @DisplayName("a null value inside a map or an object is left out of save(), with one warning naming it and 6.2's failure")
    void nullValueIsLeftOutAndNamed() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        config.aliases.put("gm", "gamemode");
        config.aliases.put("broken", null);
        Map<String, Integer> perks = new LinkedHashMap<>();
        perks.put("fly", null);
        perks.put("speed", 2);
        config.groups.put("vip", perks);
        Holder sword = new Holder();
        sword.ingredients.put("stick", null);
        sword.note = null;
        config.recipes.put("sword", sword);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(config::save).doesNotThrowAnyException();
            List<String> broken = warnings.messagesContaining("'broken'");
            assertThat(broken).hasSize(1);
            assertThat(broken.get(0)).contains("DottedModule").contains(PATH).contains("'features.aliases'")
                    .contains("null").contains("left out").contains("NullPointerException").contains("unchanged");
            assertThat(warnings.messagesContaining("'vip -> fly'")).hasSize(1)
                    .allSatisfy(message -> assertThat(message).contains("'groups'"));
            assertThat(warnings.messagesContaining("'sword -> ingredients -> stick'")).hasSize(1);
            assertThat(warnings.messagesContaining("'sword -> note'")).hasSize(1);
            assertThat(warnings.messages()).hasSize(4);
        }
        String text = readFile();
        assertThat(text).contains("gm: gamemode").doesNotContain("broken").doesNotContain("fly")
                .contains("speed: 2").doesNotContain("stick").doesNotContain("note");
    }

    @Test
    @DisplayName("the shutdown comparison and a render never log the null-value warning; only save() does")
    void nullWarningOnlyOnSave() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        config.aliases.put("broken", null);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.isModifiedSinceSnapshot();
            assertThat(warnings.messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("nested sections and getConfig() paths behave as in 6.2")
    void structureIsAsBefore() throws IOException {
        writeFile("autoreply:\n  rules:\n    a:\n      reply: x\n    b:\n      reply: y\n"
                + "features:\n  aliases:\n    gm: gamemode\ngroups: {}\nrecipes: {}\na:\n  b:\n    c: 9\n");
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);

        assertThat(config.rules).containsOnlyKeys("a", "b");
        assertThat(config.aliases).containsOnlyKeys("gm");
        YamlConfiguration live = config.getConfig();
        assertThat(live.getString("autoreply.rules.a.reply")).isEqualTo("x");
        assertThat(live.getInt("a.b.c")).isEqualTo(9);
        assertThat(live.getConfigurationSection("autoreply.rules").getCurrentPath()).isEqualTo("autoreply.rules");

        config.rules.put("c", rule("z"));
        config.save();
        assertThat(config.getConfig().getString("autoreply.rules.c.reply")).isEqualTo("z");
        JsonObject payload = config.toJsonObject();
        assertThat(payload.has("autoreply.rules.a.reply")).isTrue();
        assertThat(payload.has("a.b.c")).isTrue();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }
}
