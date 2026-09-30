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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;
import com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser;

/**
 * UltiKits/UltiTools-Reborn#553, maintainer decision of 2026-09-30 ("拒绝并明确告知" - refuse and say
 * so plainly). The configuration file uses {@code '.'} as its path separator, so a map key containing a
 * dot ({@code my.rule}) cannot be stored as one key: before, it was silently saved as {@code my: {rule:
 * ...}} and read back as {@code my}. Now a framework write refuses such a key with one warning naming
 * it (the other entries are written), and a load that finds one in the file warns the operator to
 * rename it. The configuration layer keeps its 6.2 structure: nested sections load as before and every
 * path a module reads through {@code getConfig()} resolves as before.
 */
@DisplayName("AbstractConfigEntity - dotted map keys are refused and named (#553)")
class ConfigDottedMapKeyTest {

    private static final String PATH = "config/dotted.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class RulesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.rules")
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();

        @ConfigEntry(path = "features.aliases")
        Map<String, String> aliases = new LinkedHashMap<>();

        @ConfigEntry(path = "a.b.c")
        int nested = 3;

        public RulesConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class DottedDefaultConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "aliases")
        Map<String, String> aliases = defaults();

        public DottedDefaultConfig(String configFilePath) {
            super(configFilePath);
        }

        private static Map<String, String> defaults() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("server.ip", "play.example.org");
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
    @DisplayName("save() refuses a map key with a dot, with one warning naming it; the other entries are written")
    void saveRefusesADottedKey() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        config.rules.put("my.rule", rule("hi"));
        config.rules.put("server-ip", rule("play.example.org"));
        config.aliases.put("g.m", "gamemode");
        config.aliases.put("wave.", "hello");
        config.aliases.put("gm", "gamemode");

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(config::save).doesNotThrowAnyException();
            List<String> named = warnings.messagesContaining("'my.rule'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("autoreply.rules").contains("rename");
            assertThat(warnings.messagesContaining("'g.m'")).hasSize(1);
            assertThat(warnings.messagesContaining("'wave.'")).hasSize(1);
        }

        RulesConfig second = new RulesConfig(PATH);
        second.init(plugin);
        assertThat(second.rules).containsOnlyKeys("server-ip");
        assertThat(second.aliases).containsOnlyKeys("gm");
        assertThat(readFile()).doesNotContain("my.rule").doesNotContain("g.m").doesNotContain("wave");
    }

    @Test
    @DisplayName("a dotted key in a declared default is not written on first boot; one warning names it")
    void dottedDefaultIsRefused() throws IOException {
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new DottedDefaultConfig(PATH).init(plugin);
            assertThat(warnings.messagesContaining("'server.ip'")).hasSize(1);
        }

        DottedDefaultConfig second = new DottedDefaultConfig(PATH);
        second.init(plugin);
        assertThat(second.aliases).containsOnlyKeys("plain");
    }

    @Test
    @DisplayName("a panel write of a map with a dotted key refuses that key and writes the rest")
    void panelWriteRefusesADottedKey() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        JsonObject aliases = new JsonObject();
        aliases.addProperty("t.p", "teleport");
        aliases.addProperty("tp", "teleport");
        JsonObject payload = new JsonObject();
        payload.add("features.aliases", aliases);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.updateProperties(payload);
            assertThat(warnings.messagesContaining("'t.p'")).hasSize(1);
        }
        RulesConfig second = new RulesConfig(PATH);
        second.init(plugin);
        assertThat(second.aliases).containsOnlyKeys("tp");
    }

    @Test
    @DisplayName("the built-in StringHashMapParser refuses a dotted key the same way")
    void stringHashMapParserRefusesADottedKey() throws IOException {
        HashMapParserConfig config = new HashMapParserConfig(PATH);
        config.init(plugin);
        config.mappings.put("o.O", "surprised");
        config.mappings.put("plain", "value");

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messagesContaining("'o.O'")).hasSize(1);
        }
        HashMapParserConfig second = new HashMapParserConfig(PATH);
        second.init(plugin);
        assertThat(second.mappings).containsOnlyKeys("plain");
    }

    @Test
    @DisplayName("a dotted key an operator wrote is loaded as before (split) and one warning tells them to rename it")
    void operatorWrittenDottedKeyWarnsToRename() throws IOException {
        writeFile("autoreply:\n  rules:\n    my.rule:\n      reply: hi\n    ok:\n      reply: yo\n"
                + "features:\n  aliases: {}\na:\n  b:\n    c: 3\n");
        RulesConfig config = new RulesConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            List<String> named = warnings.messagesContaining("'my.rule'");
            assertThat(named).as("once per load, not again for the snapshot").hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("autoreply.rules").contains("rename");
        }
        assertThat(config.rules).containsOnlyKeys("my", "ok");
        assertThat(config.rules.get("ok")).containsEntry("reply", "yo");

        writeFile("autoreply:\n  rules:\n    'x.y':\n      reply: hi\nfeatures:\n  aliases: {}\na:\n  b:\n    c: 3\n");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.reload();
            assertThat(warnings.messagesContaining("'x.y'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("nested sections, getConfig() paths and a module parser's nested paths behave as in 6.2")
    void structureIsAsBefore() throws IOException {
        writeFile("autoreply:\n  rules:\n    a:\n      reply: x\n    b:\n      reply: y\n"
                + "features:\n  aliases:\n    gm: gamemode\na:\n  b:\n    c: 9\ntexts:\n  messages:\n    join: welcome\n");
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);

        assertThat(config.rules).containsOnlyKeys("a", "b");
        assertThat(config.aliases).containsOnlyKeys("gm");
        YamlConfiguration live = config.getConfig();
        assertThat(live.getString("autoreply.rules.a.reply")).isEqualTo("x");
        assertThat(live.getInt("a.b.c")).isEqualTo(9);
        assertThat(live.getKeys(true)).allSatisfy(key -> assertThat(key).doesNotContain("\u0000"));
        assertThat(live.getConfigurationSection("autoreply.rules").getCurrentPath()).isEqualTo("autoreply.rules");

        config.rules.put("c", rule("z"));
        config.save();
        assertThat(config.getConfig().getString("autoreply.rules.c.reply")).isEqualTo("z");
        JsonObject payload = config.toJsonObject();
        assertThat(payload.has("autoreply.rules.a.reply")).isTrue();
        assertThat(payload.has("a.b.c")).isTrue();
        assertThat(payload.keySet()).allSatisfy(key -> assertThat(key).doesNotContain("\u0000").doesNotStartWith("."));
        assertThat(config.isModifiedSinceSnapshot()).isFalse();

        NestedParserConfig custom = new NestedParserConfig(PATH);
        custom.init(plugin);
        assertThat(custom.texts).containsEntry("join", "welcome");
    }
}
