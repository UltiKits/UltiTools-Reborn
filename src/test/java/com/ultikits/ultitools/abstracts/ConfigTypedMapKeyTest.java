package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigEntryPresenceException;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.config.EntryPresence;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * Case 52 (issue #673, "Typed scalar map keys bind back to the original document keys"): a module declaring a map keyed
 * by {@code Integer}, {@code Long} or {@code Boolean} passes preflight and reads its file, then failed to start because
 * the snapshot write refused the non-text key ("Map keys must convert to text"). A non-empty declared default of such a
 * map failed even earlier, when the defaults were captured.
 * <p>
 * The file's key text is the operator's: an unchanged save writes nothing, and an edit of one entry's value changes only
 * that value, so {@code 0x1F}, {@code 9_000_000_000} and {@code yes} keep their spelling. All content is synthetic.
 */
@DisplayName("Typed scalar map keys load, save nothing when unchanged and keep their key text (case 52)")
class ConfigTypedMapKeyTest {

    private static final String PATH = "config/typed-keys.yml";
    private static final String FIXTURE = "# synthetic typed-key fixture\n"
            + "limits:\n"
            + "  1: one\n"
            + "  0x1F: thirty-one\n"
            + "big:\n"
            + "  9_000_000_000: nine\n"
            + "flags:\n"
            + "  yes: enabled\n"
            + "tail: kept\n";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class TypedKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limits")
        Map<Integer, String> limits = new LinkedHashMap<>();

        @ConfigEntry(path = "big")
        Map<Long, String> big = new LinkedHashMap<>();

        @ConfigEntry(path = "flags")
        Map<Boolean, String> flags = new LinkedHashMap<>();

        @ConfigEntry(path = "tail")
        String tail = "default";

        TypedKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    static class DefaultedKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "motd")
        String motd = "hello";

        @ConfigEntry(path = "ports")
        Map<Integer, String> ports = new LinkedHashMap<>(Collections.singletonMap(25565, "main"));

        DefaultedKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class PortsReader extends AbstractConfigEntity {
        @ConfigEntry(path = "motd")
        String motd = "unset";

        @ConfigEntry(path = "ports")
        Map<Integer, String> ports = new LinkedHashMap<>();

        PortsReader(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Keys the operator spelled differently from their canonical text: quoted, whole dotted and exponent forms. */
    private static final String SPELLED = "# synthetic spelled-key fixture\n"
            + "limits:\n"
            + "  '01': one\n"
            + "  2: two\n"
            + "ranges:\n"
            + "  1.0: whole\n"
            + "big:\n"
            + "  '007': seven\n"
            + "  1.0e10: far\n"
            + "tail: kept\n";

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    @ConfigEntity(PATH)
    static class SpelledKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limits")
        Map<Integer, String> limits = new LinkedHashMap<>();

        @ConfigEntry(path = "ranges")
        Map<Integer, String> ranges = new LinkedHashMap<>();

        @ConfigEntry(path = "big")
        Map<Long, String> big = new LinkedHashMap<>();

        @ConfigEntry(path = "tail")
        String tail = "default";

        SpelledKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A sorted map whose file order (3, 1, 2) differs from its Java order. */
    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class SortedKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "order")
        SortedMap<Integer, String> order = new TreeMap<>();

        SortedKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class BooleanKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "flags")
        Map<Boolean, String> flags = new LinkedHashMap<>();

        BooleanKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class UuidKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "owners")
        Map<UUID, String> owners = new LinkedHashMap<>();

        UuidKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** One typed key, 1, written twice: as {@code 1} and as {@code '01'}; a secret-shaped map holds the same. */
    private static final String AMBIGUOUS = "# synthetic ambiguous-key fixture\n"
            + "limits:\n"
            + "  1: alpha-value\n"
            + "  '01': beta-value\n"
            + "tokens:\n"
            + "  1: gamma-value\n"
            + "  '01': delta-value\n"
            + "other: kept\n";

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    @ConfigEntity(PATH)
    static class AmbiguousKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limits")
        Map<Integer, String> limits = new LinkedHashMap<>();

        @ConfigEntry(path = "tokens")
        Map<Integer, String> tokens = new LinkedHashMap<>();

        @ConfigEntry(path = "other")
        String other = "default";

        AmbiguousKeyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("TypedKeyModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    @Test
    @DisplayName("Integer, Long and Boolean keyed maps initialize from the file and bind the typed keys")
    void scalarKeyMapsLoadFromTheFile() throws IOException {
        put(FIXTURE);
        TypedKeyConfig config = new TypedKeyConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).as("init of a module with typed scalar map keys")
                    .doesNotThrowAnyException();

            Map<Integer, String> limits = new LinkedHashMap<>();
            limits.put(1, "one");
            limits.put(31, "thirty-one");
            assertThat(config.limits).containsExactlyEntriesOf(limits);
            assertThat(config.big).containsExactlyEntriesOf(Collections.singletonMap(9_000_000_000L, "nine"));
            assertThat(config.flags).containsExactlyEntriesOf(Collections.singletonMap(true, "enabled"));
            assertThat(config.tail).isEqualTo("kept");
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }
        assertThat(text()).as("init leaves the file as written").isEqualTo(FIXTURE);
    }

    @Test
    @DisplayName("save() after init writes nothing: the file keeps its bytes and its modification time")
    void unchangedSaveLeavesScalarKeyFileByteIdentical() throws IOException {
        long modified = put(FIXTURE);
        TypedKeyConfig config = new TypedKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).as("init of a module with typed scalar map keys")
                .doesNotThrowAnyException();

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(FIXTURE);
        assertThat(file().lastModified()).as("modification time").isEqualTo(modified);
    }

    @Test
    @DisplayName("editing the value of key 31 changes only that value; every key text and other byte stays")
    void neighbourEditKeepsScalarKeyText() throws IOException {
        put(FIXTURE);
        TypedKeyConfig config = new TypedKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).as("init of a module with typed scalar map keys")
                .doesNotThrowAnyException();

        config.limits.put(31, "changed");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(FIXTURE.replace("  0x1F: thirty-one\n", "  0x1F: changed\n"));
    }

    @Test
    @DisplayName("a non-empty Integer-keyed default is inserted into a file lacking it and reads back as the same key")
    void nonEmptyScalarKeyDefaultsAreInserted() throws IOException {
        put("motd: kept\n");
        DefaultedKeyConfig config = new DefaultedKeyConfig(PATH);

        assertThatCode(() -> config.init(plugin)).as("init of a module with a non-empty typed-key default")
                .doesNotThrowAnyException();

        // The converter writes the key as the text "25565"; the renderer quotes a text key that would otherwise load as
        // a YAML number. The quoted key still reads back as the Integer 25565 (the reader below).
        assertThat(text()).isEqualTo("motd: kept\nports:\n  '25565': main\n");
        PortsReader reader = new PortsReader(PATH);
        assertThatCode(() -> reader.init(plugin)).doesNotThrowAnyException();
        assertThat(reader.ports).containsExactlyEntriesOf(Collections.singletonMap(25565, "main"));
        assertThat(reader.motd).isEqualTo("kept");
    }

    @Test
    @DisplayName("updating key 1, spelled '01' in the file, changes only its value; the quoted key text stays")
    void quotedKeyValueUpdateKeepsTheKey() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.limits.put(1, "uno");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("the update is written, nothing is reported unwritten").isEmpty();
        }

        assertThat(text()).isEqualTo(SPELLED.replace("  '01': one\n", "  '01': uno\n"));
    }

    @Test
    @DisplayName("updating key 1, written as the whole dotted key 1.0, keeps that whole key")
    void wholeDottedKeyValueUpdateKeepsTheKey() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.ranges.put(1, "changed");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("the update is written, nothing is reported unwritten").isEmpty();
        }

        assertThat(text()).isEqualTo(SPELLED.replace("  1.0: whole\n", "  1.0: changed\n"));
    }

    @Test
    @DisplayName("Long keys spelled '007' and 1.0e10 keep their text when their values are updated")
    void longKeysKeepTheirText() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.big.put(7L, "sept");
        config.big.put(10_000_000_000L, "loin");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("the updates are written, nothing is reported unwritten").isEmpty();
        }

        assertThat(text()).isEqualTo(SPELLED.replace("  '007': seven\n", "  '007': sept\n")
                .replace("  1.0e10: far\n", "  1.0e10: loin\n"));
    }

    @Test
    @DisplayName("a panel leaf edit of limits.01 writes only that value, under the quoted key")
    void panelLeafEditOfSpelledKey() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = new SpelledKeyConfig(PATH);
        ConfigManager manager = new ConfigManager();
        manager.register(plugin, config);

        assertThatCode(() -> manager.loadFromJson(PATH, "{\"limits.01\":\"uno\"}")).as("panel edit of limits.01")
                .doesNotThrowAnyException();

        assertThat(config.limits).containsEntry(1, "uno").hasSize(2);
        assertThat(text()).isEqualTo(SPELLED.replace("  '01': one\n", "  '01': uno\n"));
    }

    @Test
    @DisplayName("control: an unchanged save of the spelled-key file writes nothing")
    void unchangedSaveKeepsSpelledKeys() throws IOException {
        long modified = put(SPELLED);
        SpelledKeyConfig config = initialized();

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(SPELLED);
        assertThat(file().lastModified()).as("modification time").isEqualTo(modified);
    }

    @Test
    @DisplayName("control: editing neighbour key 2 leaves the '01' line byte-identical")
    void neighbourEditKeepsQuotedKeyLine() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.limits.put(2, "deux");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(SPELLED.replace("  2: two\n", "  2: deux\n"));
    }

    @Test
    @DisplayName("saveOperatorMapEntry naming key 1 by its canonical text reaches the entry the file spells '01'")
    void operatorMapEntryByCanonicalText() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.limits.put(1, "uno");
        assertThatThrownBy(() -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "limits", "1"))
                .as("the '01' entry is present").isInstanceOf(ConfigEntryPresenceException.class);
        assertThat(text()).as("a refused create writes nothing").isEqualTo(SPELLED);

        assertThatCode(() -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "limits", "1"))
                .as("the '01' entry is present, so the change is written").doesNotThrowAnyException();
        assertThat(text()).isEqualTo(SPELLED.replace("  '01': one\n", "  '01': uno\n"));

        config.limits.put(1, "eins");
        assertThatCode(() -> config.saveOperatorMapEntry("limits", "1")).doesNotThrowAnyException();
        assertThat(text()).isEqualTo(SPELLED.replace("  '01': one\n", "  '01': eins\n"));
    }

    @Test
    @DisplayName("after the module removes key 1, saveOperatorMapEntry(\"limits\", \"1\") removes exactly the '01' line")
    void operatorMapEntryRemovalBySpelledKey() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.limits.remove(1);
        assertThatCode(() -> config.saveOperatorMapEntry("limits", "1")).doesNotThrowAnyException();

        assertThat(text()).isEqualTo(SPELLED.replace("  '01': one\n", ""));
    }

    @Test
    @DisplayName("save() after the module removes key 1 deletes only the '01' line")
    void saveRemovalDeletesOnlyItsLine() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.limits.remove(1);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(SPELLED.replace("  '01': one\n", ""));
    }

    @Test
    @DisplayName("saveOperatorChange(\"limits\") after changing key 2 keeps the '01' key and its spelling")
    void wholeSettingOperatorWrite() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();

        config.limits.put(2, "deux");
        assertThatCode(() -> config.saveOperatorChange("limits")).doesNotThrowAnyException();

        assertThat(text()).isEqualTo(SPELLED.replace("  2: two\n", "  2: deux\n"));
    }

    @Test
    @DisplayName("an operator respelling '01' as 1 is adopted on reload without a conflict or a write")
    void reloadAdoptsOperatorRespelling() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = initialized();
        String respelled = SPELLED.replace("  '01': one\n", "  1: one\n");
        long modified = put(respelled);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(config::reload).doesNotThrowAnyException();
            assertThat(warnings.messages()).as("no reload conflict").isEmpty();
        }
        assertThat(text()).as("reload writes nothing").isEqualTo(respelled);
        assertThat(file().lastModified()).as("modification time").isEqualTo(modified);
        assertThat(config.limits).containsEntry(1, "one").containsEntry(2, "two").hasSize(2);

        config.limits.put(1, "uno");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }
        assertThat(text()).isEqualTo(respelled.replace("  1: one\n", "  1: uno\n"));
    }

    @Test
    @DisplayName("a sorted map keeps the file order 3, 1, 2 when key 1 is updated")
    void sortedMapKeepsFileOrder() throws IOException {
        String yaml = "order:\n  3: c\n  1: a\n  2: b\n";
        put(yaml);
        SortedKeyConfig config = new SortedKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        config.order.put(1, "x");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo("order:\n  3: c\n  1: x\n  2: b\n");
    }

    @Test
    @DisplayName("Boolean keys spelled yes and off keep their text when their values are updated")
    void booleanSpellingsSurvive() throws IOException {
        String yaml = "flags:\n  yes: enabled\n  off: disabled\n";
        put(yaml);
        BooleanKeyConfig config = new BooleanKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        config.flags.put(true, "on-value");
        config.flags.put(false, "off-value");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo("flags:\n  yes: on-value\n  off: off-value\n");
    }

    @Test
    @DisplayName("server stop - the unsaved report and the release - leaves the spelled-key file byte-identical")
    void serverStopWritesNothing() throws IOException {
        put(SPELLED);
        SpelledKeyConfig config = new SpelledKeyConfig(PATH);
        ConfigManager manager = new ConfigManager();
        manager.register(plugin, config);
        config.limits.put(1, "uno");
        config.save();
        String saved = SPELLED.replace("  '01': one\n", "  '01': uno\n");
        assertThat(text()).as("the save wrote the update under '01'").isEqualTo(saved);
        assertThat(file().setLastModified(file().lastModified() - 10_000L)).isTrue();
        long modified = file().lastModified();

        assertThat(config.isModifiedSinceSnapshot()).as("nothing unsaved after the save").isFalse();
        manager.reportUnsavedAtStop();
        manager.unregisterAll(plugin);

        assertThat(text()).isEqualTo(saved);
        assertThat(file().lastModified()).as("modification time").isEqualTo(modified);
    }

    @Test
    @DisplayName("a UUID key written in upper case keeps that spelling when its value is updated")
    void uuidKeySpellingSurvives() throws IOException {
        String key = "0A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D";
        String yaml = "owners:\n  " + key + ": synthetic-owner\n";
        put(yaml);
        UuidKeyConfig config = new UuidKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        config.owners.put(UUID.fromString(key), "renamed-owner");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("the update is written, nothing is reported unwritten").isEmpty();
        }

        assertThat(text()).isEqualTo("owners:\n  " + key + ": renamed-owner\n");
    }

    @Test
    @DisplayName("a key held as 1 and '01' loads with the warning, and save, operator writes and panel edits are refused")
    void ambiguousSpellingRefusesEveryWrite() throws IOException {
        put(AMBIGUOUS);
        AmbiguousKeyConfig config = new AmbiguousKeyConfig(PATH);
        ConfigManager manager = new ConfigManager();
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> manager.register(plugin, config)).doesNotThrowAnyException();
            assertThat(warnings.messages()).as("the existing collision warning").anySatisfy(
                    message -> assertThat(message).contains("limits"));
        }
        assertThat(config.limits).containsExactlyEntriesOf(Collections.singletonMap(1, "alpha-value"));

        config.limits.put(1, "changed-value");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("one warning names the setting and the reason").singleElement()
                    .satisfies(message -> assertThat(message).contains("'limits'", "several spellings")
                            .doesNotContain("alpha-value", "beta-value", "changed-value"));
        }
        assertThat(text()).isEqualTo(AMBIGUOUS);

        assertThatThrownBy(() -> config.saveOperatorChange("limits")).isInstanceOf(ConfigWriteRefusedException.class)
                .hasMessageContaining("several spellings").hasMessageContaining("'limits'");
        assertThat(text()).isEqualTo(AMBIGUOUS);
        assertThatThrownBy(() -> config.saveOperatorMapEntry("limits", "1")).isInstanceOf(ConfigWriteRefusedException.class)
                .hasMessageContaining("several spellings").hasMessageNotContaining("changed-value");
        assertThat(text()).isEqualTo(AMBIGUOUS);
        assertThatThrownBy(() -> manager.loadFromJson(PATH, "{\"limits.1\":\"panel-value\"}"))
                .isInstanceOf(ConfigWriteRefusedException.class).hasMessageContaining("several spellings")
                .hasMessageNotContaining("panel-value");
        assertThat(text()).isEqualTo(AMBIGUOUS);
    }

    @Test
    @DisplayName("removing key 1 while the file holds it as 1 and '01' writes nothing and is reported once")
    void ambiguousSpellingRefusesRemoval() throws IOException {
        put(AMBIGUOUS);
        AmbiguousKeyConfig config = new AmbiguousKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        config.limits.remove(1);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("one warning names the setting and the reason").singleElement()
                    .satisfies(message -> assertThat(message).contains("'limits'", "several spellings"));
        }

        assertThat(text()).isEqualTo(AMBIGUOUS);
    }

    @Test
    @DisplayName("the ambiguity is found from the file's keys: an emptied module map is still refused")
    void ambiguityFromFileKeysOnly() throws IOException {
        put(AMBIGUOUS);
        AmbiguousKeyConfig config = new AmbiguousKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        config.limits.clear();
        assertThatThrownBy(() -> config.saveOperatorChange("limits")).isInstanceOf(ConfigWriteRefusedException.class)
                .hasMessageContaining("several spellings");

        assertThat(text()).isEqualTo(AMBIGUOUS);
    }

    @Test
    @DisplayName("the refusal for a secret-shaped map shows its key as <redacted>")
    void ambiguousSecretMapRedactsKey() throws IOException {
        put(AMBIGUOUS);
        AmbiguousKeyConfig config = new AmbiguousKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        config.tokens.put(1, "changed-value");
        assertThatThrownBy(() -> config.saveOperatorChange("tokens")).isInstanceOf(ConfigWriteRefusedException.class)
                .hasMessageContaining("several spellings").hasMessageContaining("'tokens.<redacted>'")
                .hasMessageNotContaining("tokens.1").hasMessageNotContaining("tokens.01")
                .hasMessageNotContaining("gamma-value").hasMessageNotContaining("changed-value");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).singleElement().satisfies(message -> assertThat(message)
                    .contains("'tokens.<redacted>'", "several spellings").doesNotContain("tokens.1", "tokens.01"));
        }

        assertThat(text()).isEqualTo(AMBIGUOUS);
    }

    @Test
    @DisplayName("control: in the same file a change to another setting is written normally")
    void unrelatedSettingsStillWrite() throws IOException {
        put(AMBIGUOUS);
        AmbiguousKeyConfig config = new AmbiguousKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        config.other = "changed";
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(AMBIGUOUS.replace("other: kept\n", "other: changed\n"));
    }

    @Test
    @DisplayName("control: once the operator deletes one spelling and reloads, the write succeeds")
    void afterOperatorFixWritesResume() throws IOException {
        put(AMBIGUOUS);
        AmbiguousKeyConfig config = new AmbiguousKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
        String fixed = AMBIGUOUS.replace("  '01': beta-value\n", "");
        put(fixed);
        assertThatCode(config::reload).doesNotThrowAnyException();

        config.limits.put(1, "changed-value");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.save();
            assertThat(warnings.messages()).as("no warning").isEmpty();
        }

        assertThat(text()).isEqualTo(fixed.replace("  1: alpha-value\n", "  1: changed-value\n"));
    }

    private SpelledKeyConfig initialized() {
        SpelledKeyConfig config = new SpelledKeyConfig(PATH);
        assertThatCode(() -> config.init(plugin)).as("init of the spelled-key module").doesNotThrowAnyException();
        return config;
    }

    private File file() {
        return tempDir.resolve(PATH).toFile();
    }

    private long put(String yaml) throws IOException {
        Path target = tempDir.resolve(PATH);
        Files.createDirectories(target.getParent());
        Files.write(target, yaml.getBytes(StandardCharsets.UTF_8));
        File written = target.toFile();
        assertThat(written.setLastModified(written.lastModified() - 10_000L)).isTrue();
        return written.lastModified();
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(PATH)), StandardCharsets.UTF_8);
    }
}
