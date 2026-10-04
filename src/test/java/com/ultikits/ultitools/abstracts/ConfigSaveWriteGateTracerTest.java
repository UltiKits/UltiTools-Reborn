package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Tracer of the save rule (#599; maintainer decision 2026-10-04, "what code may write, by file type": no
 * whole-object incidental save; supersedes #527's overwrite-and-warn). A module's {@code save()} writes a setting
 * only when the module changed it since the last load or save, the file still holds at that path exactly what the
 * framework last read there, and that is the value the module started from - through the config write gate, so
 * every other byte of the file stays as the operator left it. Why it cannot overwrite operator content: a setting
 * the operator edited, deleted or wrote unusably is never in the writable set, and the gate refuses any byte change
 * outside the written settings.
 */
@DisplayName("A module save writes only what the module changed, where the file still holds what it read (#599)")
class ConfigSaveWriteGateTracerTest {

    private static final String PATH = "tracer.yml";
    private static final String MAP_PATH = "tracer-map.yml";
    private static final String TEXT = "# Interval in seconds\ninterval: 300\n# Greeting shown on join\nmessage: hi\n";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private Logger frameworkLogger;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Logger packageLogger = Logger.getLogger("com.ultikits.ultitools");
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
            // In-memory capture.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    @ConfigEntity(PATH)
    public static class Tracer extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "Interval in seconds")
        int interval = 300;
        @ConfigEntry(path = "message", comment = "Greeting shown on join")
        String message = "hi";

        public Tracer(String path) {
            super(path);
        }
    }

    public static class Composite extends AbstractConfigEntity {
        @ConfigEntry(path = "home")
        org.bukkit.util.Vector home = new org.bukkit.util.Vector(1, 2, 3);
        @ConfigEntry(path = "points")
        Map<String, org.bukkit.util.Vector> points = new LinkedHashMap<>();

        public Composite(String path) {
            super(path);
        }
    }

    public static class SpawnPoint extends AbstractConfigEntity {
        @ConfigEntry(path = "spawn")
        org.bukkit.Location spawn = null;

        public SpawnPoint(String path) {
            super(path);
        }
    }

    public static class BlockScalar extends AbstractConfigEntity {
        @ConfigEntry(path = "motd")
        String motd = "a\nb\n";
        @ConfigEntry(path = "other")
        int other = 1;

        public BlockScalar(String path) {
            super(path);
        }
    }

    public static class SectionNote extends AbstractConfigEntity {
        @ConfigEntry(path = "a.x")
        int x = 1;
        @ConfigEntry(path = "b")
        int b = 2;

        public SectionNote(String path) {
            super(path);
        }
    }

    @ConfigEntity(MAP_PATH)
    public static class Emojis extends AbstractConfigEntity {
        @ConfigEntry(path = "emojis")
        Map<String, String> emojis = new LinkedHashMap<>();

        public Emojis(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        frameworkLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> Mockito.when(ultiTools.getLogger()).thenReturn(frameworkLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("TracerModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        packageLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        packageLogger.removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    private void put(String name, String text) throws IOException {
        Files.write(tempDir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(name)), StandardCharsets.UTF_8);
    }

    /** Forgets every warning logged so far (a load's conversion warnings), so only the save's own are counted. */
    private void countFromHere() {
        records.clear();
        Mockito.clearInvocations(frameworkLogger);
    }

    /** Every WARNING logged through the framework logger or any logger of the framework's packages. */
    private List<String> warnings() {
        List<String> result = new ArrayList<>();
        ArgumentCaptor<Level> levels = ArgumentCaptor.forClass(Level.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(frameworkLogger, Mockito.atLeast(0)).log(levels.capture(), messages.capture());
        for (int i = 0; i < messages.getAllValues().size(); i++) {
            if (levels.getAllValues().get(i).intValue() >= Level.WARNING.intValue()) {
                result.add(messages.getAllValues().get(i));
            }
        }
        synchronized (records) {
            for (LogRecord record : records) {
                result.add(record.getMessage());
            }
        }
        return result;
    }

    @Test
    @DisplayName("tracer: the module's change is written and the operator's disk edit of another setting stays, byte for byte")
    void moduleSaveKeepsTheOperatorsEditOfAnotherSetting() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        put(PATH, TEXT.replace("interval: 300", "interval: 450"));

        config.message = "welcome";
        countFromHere();
        config.save();

        assertThat(read(PATH)).isEqualTo(TEXT.replace("interval: 300", "interval: 450").replace("message: hi", "message: welcome"));
        assertThat(warnings()).isEmpty();
        assertThat(config.interval).as("memory keeps what the module last read until a reload").isEqualTo(300);
    }

    @Test
    @DisplayName("a change to the setting the operator edited on disk is not written; one warning names the file and the key, never a value")
    void moduleChangeOfTheEditedSettingIsNotWrittenAndNamedOnce() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        String edited = TEXT.replace("interval: 300", "interval: 450");
        put(PATH, edited);

        config.interval = 600;
        countFromHere();
        config.save();

        assertThat(read(PATH)).isEqualTo(edited);
        assertThat(config.interval).as("the module's value stays in memory").isEqualTo(600);
        List<String> warnings = warnings();
        assertThat(warnings).hasSize(1);
        String file = tempDir.resolve(PATH).toAbsolutePath().toString();
        assertThat(warnings.get(0)).contains(file, "interval");
        assertThat(warnings.get(0).replace(file, "")).doesNotContain("600", "450", "300");
    }

    @Test
    @DisplayName("both at once: the module's other change is written, the edited setting is kept and named")
    void mixedSaveWritesOnlyTheWritableSetting() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        put(PATH, TEXT.replace("interval: 300", "interval: 450"));

        config.interval = 600;
        config.message = "welcome";
        countFromHere();
        config.save();

        assertThat(read(PATH)).isEqualTo(TEXT.replace("interval: 300", "interval: 450").replace("message: hi", "message: welcome"));
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("interval").doesNotContain("message"));
    }

    @Test
    @DisplayName("a save with no module change writes nothing, whatever the operator changed on disk")
    void saveWithoutModuleChangeWritesNothing() throws Exception {
        put(PATH, TEXT);
        Tracer config = new Tracer(PATH);
        config.init(plugin);
        String edited = TEXT.replace("interval: 300", "interval:   450").replace("message: hi", "message: hello there");
        put(PATH, edited);

        countFromHere();
        config.save();

        assertThat(read(PATH)).isEqualTo(edited);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("P7b: a module that adds one map entry writes that entry only; a hand-added entry and a 6.2 split entry stay")
    void mapEntryAddedByTheModuleIsWrittenAloneBesideHandAddedAndSplitEntries() throws Exception {
        String original = "emojis:\n  smile: ':)'\n  o:\n    O: x\n";
        put(MAP_PATH, original);
        Emojis config = new Emojis(MAP_PATH);
        config.init(plugin);
        assertThat(config.emojis).as("the 6.2 split entry is skipped on load").containsOnlyKeys("smile");
        String handEdited = "emojis:\n  smile: ':)'\n  wave: o/\n  o:\n    O: x\n";
        put(MAP_PATH, handEdited);

        config.emojis.put("heart", "love");
        countFromHere();
        config.save();

        assertThat(read(MAP_PATH)).isEqualTo(handEdited + "  heart: love\n");
    }

    @Test
    @DisplayName("a map entry the module removes is removed alone; the hand-added and split entries stay")
    void mapEntryRemovedByTheModuleIsRemovedAlone() throws Exception {
        put(MAP_PATH, "emojis:\n  smile: ':)'\n  frown: ':('\n  o:\n    O: x\n");
        Emojis config = new Emojis(MAP_PATH);
        config.init(plugin);
        put(MAP_PATH, "emojis:\n  smile: ':)'\n  frown: ':('\n  wave: o/\n  o:\n    O: x\n");

        config.emojis.remove("frown");
        countFromHere();
        config.save();

        assertThat(read(MAP_PATH)).isEqualTo("emojis:\n  smile: ':)'\n  wave: o/\n  o:\n    O: x\n");
        assertThat(warnings()).isEmpty();
    }

    private static final String COMPOSITE = "home:\n  ==: Vector\n  x: 1.0\n  y: 2.0\n  z: 3.0\n"
            + "points:\n  a:\n    ==: Vector\n    x: 1.0\n    y: 1.0\n    z: 1.0\n";

    private static double coordinate(String text, java.util.List<String> path) throws Exception {
        Object value = com.ultikits.ultitools.config.document.ConfigDocument.parse(text).get(path);
        return ((Number) value).doubleValue();
    }

    /**
     * R65-01 (17-65 review round 1; save rule revision 1): a value whose declared type is not a {@link Map} - a
     * {@code ConfigurationSerializable} such as a Bukkit {@code Vector} - is one value, like a list. When the operator
     * edited one of its coordinates on disk, the module's whole new value is not written (no value mixed from both);
     * the operator's edit stays and one warning names the setting.
     */
    @Test
    @DisplayName("R65-01: a serializable value is written whole or not at all; never mixed with the operator's edit")
    void serializableValueIsNeverMixedWithTheOperatorsEdit() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        String edited = COMPOSITE.replace("y: 2.0", "y: 99.0");
        put("composite.yml", edited);

        config.home = new org.bukkit.util.Vector(10, 20, 30);
        countFromHere();
        config.save();

        assertThat(read("composite.yml")).isEqualTo(edited);
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("'home'").doesNotContain("home.y"));
    }

    @Test
    @DisplayName("R65-01: without an operator edit the module's whole serializable value is written")
    void serializableValueIsWrittenWhole() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);

        config.home = new org.bukkit.util.Vector(10, 20, 30);
        countFromHere();
        config.save();

        String text = read("composite.yml");
        assertThat(coordinate(text, java.util.Arrays.asList("home", "x"))).isEqualTo(10.0);
        assertThat(coordinate(text, java.util.Arrays.asList("home", "y"))).isEqualTo(20.0);
        assertThat(coordinate(text, java.util.Arrays.asList("home", "z"))).isEqualTo(30.0);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("R65-01: a typed map is split by entry only; a serializable entry the operator edited is not written, another entry is")
    void typedMapEntryWithASerializableValueIsOneValue() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        String edited = COMPOSITE.replace("    y: 1.0\n", "    y: 7.0\n");
        put("composite.yml", edited);

        config.points.put("a", new org.bukkit.util.Vector(5, 5, 5));
        config.points.put("b", new org.bukkit.util.Vector(2, 2, 2));
        countFromHere();
        config.save();

        String text = read("composite.yml");
        assertThat(text).startsWith(edited);
        assertThat(coordinate(text, java.util.Arrays.asList("points", "a", "x"))).isEqualTo(1.0);
        assertThat(coordinate(text, java.util.Arrays.asList("points", "a", "y"))).isEqualTo(7.0);
        assertThat(coordinate(text, java.util.Arrays.asList("points", "b", "x"))).isEqualTo(2.0);
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("'points.a'").doesNotContain("points.a.y"));
    }

    /**
     * R2-01 (17-65 review round 2, sweep of R65-01's class): the reload merge treats a serializable value as one value
     * too. The module changed {@code home} without saving and the operator edited one coordinate on disk: the reload
     * takes the file's whole value (file wins, one conflict line naming the key, no value) - never a value mixed from
     * both - and the next save writes nothing.
     */
    @Test
    @DisplayName("R2-01: a reload never merges a serializable value field by field; the next save writes nothing mixed")
    void reloadNeverMixesASerializableValue() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        config.home = new org.bukkit.util.Vector(10, 20, 30);
        String edited = COMPOSITE.replace("y: 2.0", "y: 99.0");
        put("composite.yml", edited);

        countFromHere();
        config.reload();

        assertThat(config.home).isEqualTo(new org.bukkit.util.Vector(1, 99, 3));
        String file = tempDir.resolve("composite.yml").toAbsolutePath().toString();
        assertThat(warnings()).filteredOn(warning -> warning.contains("'home'")).hasSize(1)
                .allSatisfy(warning -> assertThat(warning.replace(file, "")).doesNotContain("10.0", "20.0", "30.0"));
        assertThat(warnings()).noneMatch(warning -> warning.contains("home.y"));
        countFromHere();
        config.save();
        assertThat(read("composite.yml")).isEqualTo(edited);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("R2-01: a Location moved by the module and re-worlded by the operator is never merged into the operator's world")
    void reloadNeverMixesALocation() throws Exception {
        org.mockbukkit.mockbukkit.ServerMock server = MockBukkit.getMock();
        org.bukkit.World world = server.addSimpleWorld("world");
        server.addSimpleWorld("nether");
        String text = "spawn:\n  ==: org.bukkit.Location\n  world: world\n  x: 0.5\n  y: 64.0\n  z: 0.5\n"
                + "  pitch: 0.0\n  yaw: 0.0\n";
        put("spawn.yml", text);
        SpawnPoint config = new SpawnPoint("spawn.yml");
        config.init(plugin);
        config.spawn = new org.bukkit.Location(world, 100, 70, 100);
        String edited = text.replace("world: world", "world: nether");
        put("spawn.yml", edited);

        countFromHere();
        config.reload();

        assertThat(config.spawn.getWorld().getName()).isEqualTo("nether");
        assertThat(config.spawn.getX()).isEqualTo(0.5);
        assertThat(config.spawn.getY()).isEqualTo(64.0);
        assertThat(warnings()).filteredOn(warning -> warning.contains("'spawn'")).hasSize(1);
        countFromHere();
        config.save();
        assertThat(read("spawn.yml")).isEqualTo(edited);
        assertThat(warnings()).isEmpty();
    }

    /**
     * R2-01 sweep, panel site: a panel edit of one field inside a serializable value edits that value as a whole - the
     * value as the panel shows it (the file's) with the edited field - so the module's unsaved change of the same value
     * is not mixed in, neither in memory nor by a later save.
     */
    @Test
    @DisplayName("R2-01: a panel edit inside a serializable value replaces the shown value whole; no later save mixes it")
    void panelEditInsideASerializableValueIsAWholeValueEdit() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        config.home = new org.bukkit.util.Vector(10, 20, 30);

        com.google.gson.JsonObject edit = new com.google.gson.JsonObject();
        edit.addProperty("home.y", 7.5);
        countFromHere();
        config.updateProperties(edit);

        String expected = COMPOSITE.replace("y: 2.0", "y: 7.5");
        assertThat(read("composite.yml")).isEqualTo(expected);
        assertThat(config.home).isEqualTo(new org.bukkit.util.Vector(1, 7.5, 3));
        config.save();
        assertThat(read("composite.yml")).isEqualTo(expected);
        assertThat(warnings()).isEmpty();
    }

    /**
     * Route change of 17-65 (round 3 R3-01, prediction to observation): a panel edit of one field of a composite value is
     * written as that whole value only while the file still holds the whole value the entity last read. The operator
     * hand-edited another field of it on disk (probe A): the edit is refused naming the setting, the file and memory stay.
     */
    @Test
    @DisplayName("R3-01: a panel edit inside a composite the operator hand-edited since it was read is refused, nothing overwritten")
    void panelEditInsideACompositeTheOperatorEditedIsRefused() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        String edited = COMPOSITE.replaceFirst("x: 1.0", "x: 5.0");
        put("composite.yml", edited);

        com.google.gson.JsonObject edit = new com.google.gson.JsonObject();
        edit.addProperty("home.y", 7.5);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.updateProperties(edit))
                .isInstanceOf(com.ultikits.ultitools.config.ConfigWriteRefusedException.class)
                .hasMessageContaining("'home'").hasMessageContaining("reload");

        assertThat(read("composite.yml")).isEqualTo(edited);
        assertThat(config.home).isEqualTo(new org.bukkit.util.Vector(1, 2, 3));
    }

    @Test
    @DisplayName("R3-01: the same through a typed map entry (probe F5)")
    void panelEditInsideATypedMapEntryTheOperatorEditedIsRefused() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        String edited = COMPOSITE.replace("    x: 1.0\n", "    x: 4.0\n");
        put("composite.yml", edited);

        com.google.gson.JsonObject edit = new com.google.gson.JsonObject();
        edit.addProperty("points.a.y", 6.5);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.updateProperties(edit))
                .isInstanceOf(com.ultikits.ultitools.config.ConfigWriteRefusedException.class)
                .hasMessageContaining("'points.a'");

        assertThat(read("composite.yml")).isEqualTo(edited);
    }

    @Test
    @DisplayName("R3-01 pin: a hand edit of a different composite does not block the panel edit; other fields stay as on disk")
    void panelEditInsideACompositeWithAnUnrelatedHandEditIsWritten() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        String edited = COMPOSITE.replace("    x: 1.0\n", "    x: 4.0\n");
        put("composite.yml", edited);

        com.google.gson.JsonObject edit = new com.google.gson.JsonObject();
        edit.addProperty("home.y", 7.5);
        config.updateProperties(edit);

        assertThat(read("composite.yml")).isEqualTo(edited.replace("y: 2.0", "y: 7.5"));
    }

    /** R2-01 sweep, operator-change site: map keys may not reach inside a value that is not a map. */
    @Test
    @DisplayName("R2-01: saveOperatorMapEntry refuses keys inside a serializable entry and writes nothing")
    void operatorMapEntryKeysStopAtTheDeclaredMaps() throws Exception {
        put("composite.yml", COMPOSITE);
        Composite config = new Composite("composite.yml");
        config.init(plugin);
        config.points.put("a", new org.bukkit.util.Vector(5, 5, 5));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.saveOperatorMapEntry("points", "a", "y"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("points");
        assertThat(read("composite.yml")).isEqualTo(COMPOSITE);
        config.saveOperatorMapEntry("points", "a");
        assertThat(coordinate(read("composite.yml"), java.util.Arrays.asList("points", "a", "y"))).isEqualTo(5.0);
    }

    /**
     * Two layouts the renderer cannot reproduce byte for byte - a block scalar followed by a blank line, and a section's
     * deeper-indented trailing comment after a blank line (carried obligations 2 and 3 of plans 17-63/17-64) - make the
     * gate refuse every write to the file, whichever setting it changes: the file keeps its bytes and the gate names the
     * key and the reason. Documented as a refusal in COMPATIBILITY.md (the renderer is not changed by this plan).
     */
    @Test
    @DisplayName("a file with a block scalar followed by a blank line is not written by a save; the gate names the key")
    void blockScalarFollowedByABlankLineRefusesTheSave() throws Exception {
        String text = "motd: |\n  a\n  b\n\nother: 1\n";
        put("block.yml", text);
        BlockScalar config = new BlockScalar("block.yml");
        config.init(plugin);

        config.other = 5;
        countFromHere();
        config.save();

        assertThat(read("block.yml")).isEqualTo(text);
        assertThat(config.other).isEqualTo(5);
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("block.yml", "other", "layout"));
    }

    @Test
    @DisplayName("a file with a section's trailing comment after a blank line is not written by a save; the gate names the key")
    void sectionTrailingCommentAfterABlankLineRefusesTheSave() throws Exception {
        String text = "a:\n  x: 1\n\n  # trailing section note\nb: 2\n";
        put("section.yml", text);
        SectionNote config = new SectionNote("section.yml");
        config.init(plugin);

        config.b = 3;
        countFromHere();
        config.save();

        assertThat(read("section.yml")).isEqualTo(text);
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("section.yml", "b", "layout"));
    }

    @Test
    @DisplayName("a map entry the operator changed on disk is not written by the module's change of that entry")
    void mapEntryTheOperatorEditedIsNotWritten() throws Exception {
        put(MAP_PATH, "emojis:\n  smile: ':)'\n");
        Emojis config = new Emojis(MAP_PATH);
        config.init(plugin);
        String edited = "emojis:\n  smile: ':D'\n";
        put(MAP_PATH, edited);

        config.emojis.put("smile", "(:");
        countFromHere();
        config.save();

        assertThat(read(MAP_PATH)).isEqualTo(edited);
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("emojis.smile")
                .doesNotContain("(:", ":D"));
    }
}
