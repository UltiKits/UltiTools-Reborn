package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * UltiKits/UltiTools-Reborn#553: a map key containing a dot survives save and reload. Measured in
 * UltiKits/UltiChat#25: the in-memory rules map held {@code my.rule}; after a save and a fresh
 * {@code init} it held {@code my} -> {@code {rule={...}}}, because the configuration layer treats a
 * dot as its path separator both when the map is written and when the file is read.
 * <p>
 * Module-visible {@code @ConfigEntry} paths - and paths into the configuration a module reads through
 * {@code getConfig()} - must resolve exactly as before.
 */
@DisplayName("AbstractConfigEntity - dotted map keys round-trip (#553)")
class ConfigDottedMapKeyTest {

    private static final String PATH = "config/dotted.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class RulesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.rules")
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();

        @ConfigEntry(path = "aliases")
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

    private static Map<String, Object> rule(String reply) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("reply", reply);
        return rule;
    }

    @Test
    @DisplayName("a map holding my.rule, saved and re-initialised, holds my.rule (UltiChat#25 shape)")
    void dottedKeySurvivesSaveAndReload() throws IOException {
        RulesConfig first = new RulesConfig(PATH);
        first.init(plugin);
        first.rules.put("my.rule", rule("hi"));
        first.rules.put("server-ip", rule("play.example.org"));
        first.aliases.put("g.m", "gamemode");
        first.save();

        RulesConfig second = new RulesConfig(PATH);
        second.init(plugin);
        assertThat(second.rules).containsOnlyKeys("my.rule", "server-ip");
        assertThat(second.rules.get("my.rule")).containsEntry("reply", "hi");
        assertThat(second.aliases).containsOnlyKeys("g.m");
        assertThat(second.aliases.get("g.m")).isEqualTo("gamemode");
        assertThat(second.isModifiedSinceSnapshot()).as("snapshot after reload").isFalse();
    }

    @Test
    @DisplayName("a dotted key an operator wrote in the file loads as written")
    void operatorWrittenDottedKeyLoads() throws IOException {
        writeFile("autoreply:\n  rules:\n    my.rule:\n      reply: hi\n    'quoted.rule':\n      reply: yo\n"
                + "aliases: {}\na:\n  b:\n    c: 3\n");
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);

        assertThat(config.rules).containsOnlyKeys("my.rule", "quoted.rule");
        assertThat(config.rules.get("quoted.rule")).containsEntry("reply", "yo");
    }

    @Test
    @DisplayName("genuinely nested sections still load as before")
    void nestedSectionsLoadAsBefore() throws IOException {
        writeFile("autoreply:\n  rules:\n    a:\n      reply: x\n    b:\n      reply: y\n"
                + "aliases:\n  gm: gamemode\na:\n  b:\n    c: 9\n");
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);

        assertThat(config.rules).containsOnlyKeys("a", "b");
        assertThat(config.rules.get("a")).containsEntry("reply", "x");
        assertThat(config.aliases).containsOnlyKeys("gm");
        assertThat(config.nested).isEqualTo(9);
        assertThat(config.getConfig().getString("autoreply.rules.a.reply")).isEqualTo("x");
        assertThat(config.getConfig().getInt("a.b.c")).isEqualTo(9);
    }

    @Test
    @DisplayName("an unrelated write (a missing key) does not rename a dotted key already in the file")
    void unrelatedWriteKeepsDottedKey() throws IOException {
        writeFile("autoreply:\n  rules:\n    my.rule:\n      reply: hi\naliases: {}\n");
        RulesConfig first = new RulesConfig(PATH);
        first.init(plugin); // a.b.c is missing, so init writes the file

        RulesConfig second = new RulesConfig(PATH);
        second.init(plugin);
        assertThat(second.rules).containsOnlyKeys("my.rule");
    }

    @Test
    @DisplayName("a dotted key in a declared default is written on first boot and read back unchanged")
    void dottedDefaultRoundTrips() throws IOException {
        new DottedDefaultConfig(PATH).init(plugin);

        DottedDefaultConfig second = new DottedDefaultConfig(PATH);
        second.init(plugin);
        assertThat(second.aliases).containsOnlyKeys("server.ip", "plain");
    }

    @Test
    @DisplayName("after a save, module paths into the configuration and the panel payload resolve as before")
    void pathsResolveAfterSave() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        config.rules.put("a", rule("x"));
        config.rules.put("my.rule", rule("hi"));
        config.save();

        YamlConfiguration live = config.getConfig();
        assertThat(live.getString("autoreply.rules.a.reply")).isEqualTo("x");
        assertThat(live.getInt("a.b.c")).isEqualTo(3);

        JsonObject payload = config.toJsonObject();
        assertThat(payload.has("autoreply.rules.a.reply")).isTrue();
        assertThat(payload.has("a.b.c")).isTrue();
        assertThat(payload.keySet()).allSatisfy(key -> assertThat(key).doesNotContain("\u0000").doesNotStartWith("."));
    }
}
