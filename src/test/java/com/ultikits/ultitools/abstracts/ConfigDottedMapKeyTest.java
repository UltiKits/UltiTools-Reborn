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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

/**
 * UltiKits/UltiTools-Reborn#553, maintainer answer of 2026-09-30 (warn only, the write path stays as in
 * 6.2; in the maintainer's words "只警告，写入保持 6.2"; it supersedes the earlier "refuse and say so"). The configuration file uses
 * {@code '.'} as its path separator, so a key such as {@code my.rule} in a map the file stores as a
 * section is split into nested levels. The framework still writes it exactly as 6.2 did; a read-only
 * check warns, once per key, when a load finds such a key in the file and before each framework save
 * that holds one, naming the file, the entry, the map's nested path and the module, and asking for a
 * rename. A map that is a list element is plain data the file keeps whole - never warned about. The check
 * never changes a value or the file, and a failure inside it never breaks a load or a save.
 */
@DisplayName("AbstractConfigEntity - dotted map keys are warned about, written as in 6.2 (#553)")
class ConfigDottedMapKeyTest {

    private static final String PATH = "config/dotted.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    /** An object stored as a map value, with a map of its own, as UltiRecipe's recipe definitions are. */
    public static class Holder {
        private Map<String, String> ingredients = new LinkedHashMap<>();
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class RulesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.rules")
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();

        @ConfigEntry(path = "features.aliases")
        Map<String, String> aliases = new LinkedHashMap<>();

        @ConfigEntry(path = "groups")
        Map<String, Map<String, Map<String, Integer>>> groups = new LinkedHashMap<>();

        @ConfigEntry(path = "recipes")
        Map<String, Holder> recipes = new LinkedHashMap<>();

        @ConfigEntry(path = "rewards")
        List<Map<String, Integer>> rewards = new ArrayList<>();

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

    /** A map whose first {@code entrySet()} call fails - makes the read-only check fail once. */
    static final class FlakyMap extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;
        private int calls;

        @Override
        public Set<Map.Entry<String, String>> entrySet() {
            if (calls++ == 0) {
                throw new IllegalStateException("flaky entry set");
            }
            return super.entrySet();
        }
    }

    /** A map whose first {@code entrySet()} call fails to link, as a class from a missing plugin would. */
    static final class UnlinkedMap extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;
        private int calls;

        @Override
        public Set<Map.Entry<String, String>> entrySet() {
            if (calls++ == 0) {
                throw new NoClassDefFoundError("com/example/missing/SoftDependencyType");
            }
            return super.entrySet();
        }
    }

    @SuppressWarnings("unused")
    static class UnlinkedConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "unlinked")
        Map<String, String> unlinked = unlinked();

        public UnlinkedConfig(String configFilePath) {
            super(configFilePath);
        }

        private static Map<String, String> unlinked() {
            UnlinkedMap map = new UnlinkedMap();
            map.put("k", "v");
            return map;
        }
    }

    @SuppressWarnings("unused")
    static class SharedConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "shared")
        Map<String, Map<String, Integer>> shared = new LinkedHashMap<>();

        public SharedConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class FlakyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "flaky")
        Map<String, String> flaky = flaky();

        public FlakyConfig(String configFilePath) {
            super(configFilePath);
        }

        private static Map<String, String> flaky() {
            FlakyMap map = new FlakyMap();
            map.put("k", "v");
            return map;
        }
    }

    /** A module's own parser that reads a map's leaves by their full dotted paths. */
    public static class LeafPathParser extends ConfigParser<Map<String, String>> {
        @Override
        public Map<String, String> parse(Object object) {
            Map<String, String> result = new LinkedHashMap<>();
            if (object instanceof ConfigurationSection) {
                ConfigurationSection section = (ConfigurationSection) object;
                for (String key : section.getKeys(true)) {
                    if (!section.isConfigurationSection(key)) {
                        result.put(key, section.getString(key));
                    }
                }
            }
            return result;
        }

        @Override
        public MemorySection serializeToMemorySection(Map<String, String> object) {
            MemoryConfiguration section = new MemoryConfiguration();
            for (Map.Entry<String, String> entry : object.entrySet()) {
                section.set(entry.getKey(), entry.getValue());
            }
            return section;
        }
    }

    @SuppressWarnings("unused")
    static class LeafParserConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "texts", parser = LeafPathParser.class)
        Map<String, String> texts = new LinkedHashMap<>();

        public LeafParserConfig(String configFilePath) {
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
    @DisplayName("save() warns once about a dotted key and writes it split, as 6.2 does; memory is unchanged")
    void saveWarnsAndWritesAsBefore() throws Exception {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        config.rules.put("my.rule", rule("hi"));
        config.rules.put("server-ip", rule("play.example.org"));

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(config::save).doesNotThrowAnyException();
            List<String> named = warnings.messagesContaining("'my.rule'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains("DottedModule").contains(PATH).contains("'autoreply.rules'")
                    .contains("split into nested levels").contains("loaded").contains("rename");
        }
        assertThat(config.rules).containsKey("my.rule");
        YamlConfiguration written = new YamlConfiguration();
        written.loadFromString(readFile());
        assertThat(written.getString("autoreply.rules.my.rule.reply")).isEqualTo("hi");
    }

    @Test
    @DisplayName("maps nested in maps are checked with the nested path; maps inside objects and list elements are not")
    void nestedMapsAreCheckedObjectsAndListElementsAreNot() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        Map<String, Map<String, Integer>> perks = new LinkedHashMap<>();
        Map<String, Integer> speeds = new LinkedHashMap<>();
        speeds.put("fly.speed", 2);
        perks.put("perks", speeds);
        config.groups.put("vip", perks);
        Holder sword = new Holder();
        sword.ingredients.put("minecraft.diamond", "D");
        config.recipes.put("sword", sword);
        Map<String, Integer> reward = new LinkedHashMap<>();
        reward.put("minecraft.gold", 5);
        config.rewards.add(reward);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messagesContaining("'fly.speed'")).hasSize(1)
                    .allSatisfy(message -> assertThat(message).contains("'groups.vip.perks'").contains("'groups'"));
            assertThat(warnings.messagesContaining("minecraft.diamond")).as("never reflects into objects").isEmpty();
            assertThat(warnings.messagesContaining("minecraft.gold")).isEmpty();
        }
        assertThat(readFile()).contains("minecraft.gold: 5");
    }

    @Test
    @DisplayName("a dotted key an operator wrote loads split, as in 6.2, with one warning per load")
    void operatorWrittenDottedKeyWarnsOnLoad() throws IOException {
        writeFile("autoreply:\n  rules:\n    my.rule:\n      reply: hi\n    ok:\n      reply: yo\n"
                + "features:\n  aliases: {}\na:\n  b:\n    c: 3\nrewards:\n- minecraft.gold: 5\ngroups: {}\nrecipes: {}\n");
        byte[] before = Files.readAllBytes(file());
        RulesConfig config = new RulesConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            List<String> named = warnings.messagesContaining("'my.rule'");
            assertThat(named).as("once per load, not again for the snapshot").hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("'autoreply.rules'").contains("rename");
            assertThat(warnings.messagesContaining("minecraft.gold")).isEmpty();
        }
        assertThat(config.rules).containsOnlyKeys("my", "ok");
        assertThat(Files.readAllBytes(file())).as("the check never writes").isEqualTo(before);

        writeFile("autoreply:\n  rules:\n    'x.y':\n      reply: hi\nfeatures:\n  aliases: {}\na:\n  b:\n    c: 3\nrewards: []\n"
                + "groups: {}\nrecipes: {}\n");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.reload();
            assertThat(warnings.messagesContaining("'x.y'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("a dotted key in a declared default is warned about on the first-boot write")
    void dottedDefaultIsWarned() throws IOException {
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new DottedDefaultConfig(PATH).init(plugin);
            assertThat(warnings.messagesContaining("'server.ip'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("a panel write of a map with a dotted key is warned about")
    void panelWriteIsWarned() throws IOException {
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
    }

    @Test
    @DisplayName("a module parser's own map is not checked, on a panel write or a load")
    void moduleParserIsNotChecked() throws IOException {
        LeafParserConfig config = new LeafParserConfig(PATH);
        config.init(plugin);
        JsonObject texts = new JsonObject();
        texts.addProperty("lang.welcome", "Hi");
        JsonObject payload = new JsonObject();
        payload.add("texts", texts);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.updateProperties(payload);
            new LeafParserConfig(PATH).init(plugin);
            assertThat(warnings.messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("a failure inside the check is logged and never breaks the write")
    void checkFailureIsContained() throws IOException {
        FlakyConfig config = new FlakyConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(warnings.messagesContaining("could not check")).hasSize(1)
                    .allSatisfy(message -> assertThat(message).contains(PATH));
        }
        assertThat(file()).exists();
    }

    @Test
    @DisplayName("a map that fails to link while it is checked (NoClassDefFoundError) never breaks the write")
    void linkageErrorInTheCheckIsContained() throws IOException {
        UnlinkedConfig config = new UnlinkedConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(warnings.messagesContaining("could not check")).hasSize(1);
        }
        assertThat(readFile()).contains("k: v");
    }

    @Test
    @DisplayName("a sub-map shared by two keys is reported on each path")
    void sharedSubMapIsReportedOnEachPath() throws IOException {
        SharedConfig config = new SharedConfig(PATH);
        config.init(plugin);
        Map<String, Integer> shared = new LinkedHashMap<>();
        shared.put("x.y", 1);
        config.shared.put("a", shared);
        config.shared.put("b", shared);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messagesContaining("'x.y'")).hasSize(2);
            assertThat(warnings.messagesContaining("'shared.a'")).hasSize(1);
            assertThat(warnings.messagesContaining("'shared.b'")).hasSize(1);
        }
    }

    @Test
    @DisplayName("the check before a write and the check on load report the same keys for the same content")
    void loadAndPreWriteReportTheSameKeys() throws IOException {
        RulesConfig inMemory = new RulesConfig(PATH);
        inMemory.init(plugin);
        Map<String, Object> deep = new LinkedHashMap<>();
        deep.put("deep.key", "not a declared map level");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("reply.text", "hi");
        rule.put("extra", deep);
        inMemory.rules.put("my.rule", rule);
        Map<String, Map<String, Integer>> perks = new LinkedHashMap<>();
        Map<String, Integer> speeds = new LinkedHashMap<>();
        speeds.put("fly.speed", 2);
        perks.put("perks", speeds);
        inMemory.groups.put("vip", perks);
        Holder sword = new Holder();
        sword.ingredients.put("minecraft.diamond", "D");
        inMemory.recipes.put("sword", sword);

        List<String> beforeWrite;
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            inMemory.save();
            beforeWrite = keysNamed(warnings.messages());
        }

        writeFile("autoreply:\n  rules:\n    my.rule:\n      reply.text: hi\n      extra:\n        deep.key: x\n"
                + "features:\n  aliases: {}\ngroups:\n  vip:\n    perks:\n      fly.speed: 2\n"
                + "recipes:\n  sword:\n    ingredients:\n      minecraft.diamond: D\n"
                + "rewards: []\na:\n  b:\n    c: 3\n");
        List<String> onLoad;
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new RulesConfig(PATH).init(plugin);
            onLoad = keysNamed(warnings.messages());
        }
        assertThat(beforeWrite).containsExactlyInAnyOrder("my.rule@autoreply.rules", "reply.text@autoreply.rules.my.rule",
                "fly.speed@groups.vip.perks");
        assertThat(onLoad).containsExactlyInAnyOrderElementsOf(beforeWrite);
    }

    private static List<String> keysNamed(List<String> messages) {
        List<String> keys = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("map key '([^']*)' in '([^']*)'");
        for (String message : messages) {
            java.util.regex.Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                keys.add(matcher.group(1) + "@" + matcher.group(2));
            }
        }
        return keys;
    }

    @Test
    @DisplayName("a null map value is left out of save() as before, with one warning naming it")
    void nullMapValueIsNamed() throws IOException {
        RulesConfig config = new RulesConfig(PATH);
        config.init(plugin);
        config.aliases.put("gm", "gamemode");
        config.aliases.put("broken", null);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            List<String> named = warnings.messagesContaining("'broken'");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("'features.aliases'").contains("no value");
        }
        assertThat(readFile()).contains("gm: gamemode").doesNotContain("broken");
    }

    @Test
    @DisplayName("nested sections and getConfig() paths behave as in 6.2")
    void structureIsAsBefore() throws IOException {
        writeFile("autoreply:\n  rules:\n    a:\n      reply: x\n    b:\n      reply: y\n"
                + "features:\n  aliases:\n    gm: gamemode\na:\n  b:\n    c: 9\n");
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
