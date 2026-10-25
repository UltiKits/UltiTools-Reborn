package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.config.document.ConfigLoadResult;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.manager.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Entry typing and the per-key check: every declared type maps to exactly one contract entry type, a
 * value the contract cannot carry is left out rather than truncated, and a change that does not match
 * its entry is refused with a reason while the other keys of the same request are unaffected.
 */
class RemoteConfigEntriesTest {

    private static final String CONFIG = "settings:\n"
            + "  text: hello\n"
            + "  count: 3\n"
            + "  total: 12\n"
            + "  ratio: 0.5\n"
            + "  enabled: true\n"
            + "  words:\n"
            + "    - a\n"
            + "  mode: fast\n"
            + "  numbers:\n"
            + "    - 1\n"
            + "  mapping:\n"
            + "    a: b\n";

    @TempDir
    Path root;
    private Path file;
    private Settings entity;
    private List<RemoteConfigEntries.Entry> entries;

    @BeforeEach
    void setUp() throws Exception {
        Path folder = root.resolve("module");
        Files.createDirectories(folder);
        file = folder.resolve("settings.yml");
        Files.write(file, CONFIG.getBytes(StandardCharsets.UTF_8));

        UltiToolsPlugin module = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(module.getPluginName()).thenReturn("Settings");
        lenient().when(module.getResourceFolderPath()).thenReturn(folder.toString());
        ConfigFileStubs.stubConfigFolder(module, folder.toFile());

        entity = new Settings("settings.yml");
        new ConfigManager().register(module, entity);
        ConfigLoadResult loaded = ConfigDocument.load(file);
        assertThat(loaded.state()).isEqualTo(ConfigLoadResult.State.LOADED);
        entries = RemoteConfigEntries.describe(entity, loaded.document());
    }

    @Test
    void eachDeclaredTypeMapsToItsContractType() {
        assertThat(entry("settings.text").kind()).isEqualTo(RemoteConfigEntries.Kind.STRING);
        assertThat(entry("settings.count").kind()).isEqualTo(RemoteConfigEntries.Kind.INTEGER);
        assertThat(entry("settings.total").kind()).isEqualTo(RemoteConfigEntries.Kind.INTEGER);
        assertThat(entry("settings.ratio").kind()).isEqualTo(RemoteConfigEntries.Kind.NUMBER);
        assertThat(entry("settings.enabled").kind()).isEqualTo(RemoteConfigEntries.Kind.BOOLEAN);
        assertThat(entry("settings.words").kind()).isEqualTo(RemoteConfigEntries.Kind.STRING_LIST);
        assertThat(entry("settings.mode").kind()).isEqualTo(RemoteConfigEntries.Kind.ENUM);
        // A map setting, a list of numbers and a polymorphic field are not editable through the panel.
        assertThat(keys()).doesNotContain("settings.mapping", "settings.numbers", "settings.any");
    }

    @Test
    void enumOptionsAndRangeBoundsBecomeConstraints() {
        JsonObject mode = entry("settings.mode").toWire().getAsJsonObject("constraints");
        assertThat(mode.getAsJsonArray("options")).extracting(JsonElement::getAsString)
                .containsExactly("fast", "slow");
        JsonObject count = entry("settings.count").toWire().getAsJsonObject("constraints");
        assertThat(count.get("min").getAsLong()).isZero();
        assertThat(count.get("max").getAsLong()).isEqualTo(60L);
        assertThat(entry("settings.total").toWire().has("constraints")).isFalse();
        JsonObject ratio = entry("settings.ratio").toWire().getAsJsonObject("constraints");
        assertThat(ratio.get("min").getAsDouble()).isEqualTo(0.25d);
        assertThat(ratio.has("max")).isFalse();
    }

    @Test
    void valuesOutsideTheContractBoundsAreOmittedNotTruncated() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 8193; index++) {
            longText.append('x');
        }
        StringBuilder manyWords = new StringBuilder();
        for (int index = 0; index < 501; index++) {
            manyWords.append("    - w").append(index).append('\n');
        }
        Files.write(file, ("settings:\n"
                + "  text: \"" + longText + "\"\n"
                + "  count: 3\n"
                + "  total: 99999999999999999999\n"
                + "  ratio: 0.5\n"
                + "  enabled: true\n"
                + "  words:\n"
                + manyWords
                + "  mode: fast\n"
                + "  numbers:\n"
                + "    - 1\n"
                + "  mapping:\n"
                + "    a: b\n").getBytes(StandardCharsets.UTF_8));
        ConfigDocument document = ConfigDocument.load(file).document();
        // The entity keeps its running values; describing reads the file's own bytes only.
        List<RemoteConfigEntries.Entry> fresh = RemoteConfigEntries.describe(entity, document);
        List<String> keys = new ArrayList<>();
        for (RemoteConfigEntries.Entry entry : fresh) {
            keys.add(entry.key());
        }
        assertThat(keys).doesNotContain("settings.text", "settings.total", "settings.words");
        assertThat(keys).contains("settings.count", "settings.ratio", "settings.mode");
    }

    @Test
    void aValueThatDoesNotMatchItsEntryIsRefusedWithAReason() {
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("\"5\""), entry("settings.count")))
                .contains("whole number");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("3.5"), entry("settings.count")))
                .contains("whole number");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("61"), entry("settings.count")))
                .contains("between 0 and 60");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("\"b\""), entry("settings.mode")))
                .contains("fast").contains("slow");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("[\"a\",1]"), entry("settings.words")))
                .contains("list of texts");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("true"), entry("settings.text")))
                .contains("text");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("\"x\""), null))
                .contains("not an editable setting");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("\"b\""), entry("settings.mode"))).isNotNull();
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("\"slow\""), entry("settings.mode"))).isNull();
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("0.1"), entry("settings.ratio")))
                .contains("at least 0.25");
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("[\"a\"]"), entry("settings.words"))).isNull();
        assertThat(RemoteConfigEntries.check(JsonParser.parseString("42"), entry("settings.count"))).isNull();
    }

    @Test
    void theBuilderCannotProduceTheShapesTheSchemaRejects() {
        JsonArray wire = new JsonArray();
        for (RemoteConfigEntries.Entry entry : entries) {
            wire.add(entry.toWire());
        }
        JsonObject frame = JsonParser.parseString("{\"v\":2,\"id\":\"01JB2Z8Y3K4M5N6P7Q8R9S0TVW\","
                + "\"type\":\"config.entries.result\",\"ts\":1761274800000,"
                + "\"replyTo\":\"01JB2Z8Y3K4M5N6P7Q8R9S0TVX\",\"payload\":{\"status\":\"ok\",\"data\":{"
                + "\"path\":\"module/settings.yml\",\"entries\":[],"
                + "\"version\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}")
                .getAsJsonObject();
        frame.getAsJsonObject("payload").getAsJsonObject("data").add("entries", wire);
        assertThat(ContractSchemas.isValid(frame)).isTrue();

        // The frozen invalid fixtures stay invalid: a boolean entry carrying text, an enum without options.
        List<ContractFixtures.Fixture> invalid = ContractFixtures.invalidFrames("config.entries.result");
        assertThat(invalid).hasSize(3);
        for (ContractFixtures.Fixture fixture : invalid) {
            assertThat(ContractSchemas.isValid(fixture.frame())).as(fixture.path()).isFalse();
        }
    }

    private List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (RemoteConfigEntries.Entry entry : entries) {
            keys.add(entry.key());
        }
        return keys;
    }

    private RemoteConfigEntries.Entry entry(String key) {
        RemoteConfigEntries.Entry found = RemoteConfigEntries.find(entries, key);
        assertThat(found).as(key).isNotNull();
        return found;
    }

    /** One module setting of every kind, plus the kinds the panel must not offer. */
    @ConfigEntity("settings.yml")
    public static final class Settings extends AbstractConfigEntity {
        @ConfigEntry(path = "settings.text", comment = "Free text")
        public String text = "hello";
        @ConfigEntry(path = "settings.count")
        @Range(min = 0, max = 60)
        public int count = 3;
        @ConfigEntry(path = "settings.total")
        public Long total = 12L;
        @ConfigEntry(path = "settings.ratio")
        @Range(min = 0.25)
        public double ratio = 0.5d;
        @ConfigEntry(path = "settings.enabled")
        public boolean enabled = true;
        @ConfigEntry(path = "settings.words")
        public List<String> words = new ArrayList<>();
        @ConfigEntry(path = "settings.mode")
        public Speed mode = Speed.fast;
        @ConfigEntry(path = "settings.numbers")
        public List<Integer> numbers = new ArrayList<>();
        @ConfigEntry(path = "settings.mapping")
        public Map<String, String> mapping = new java.util.LinkedHashMap<>();
        @ConfigEntry(path = "settings.any")
        public Object any;

        public Settings(String configFilePath) {
            super(configFilePath);
        }
    }

    /** The enum entry's allowed values. */
    public enum Speed {
        fast,
        slow
    }
}
