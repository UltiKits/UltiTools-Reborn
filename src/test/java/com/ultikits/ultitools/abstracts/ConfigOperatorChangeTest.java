package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * The operator-change API (maintainer decision 2026-10-04, "what code may write, by file type", item 3: write exactly
 * the item the operator explicitly asked to change). {@code saveOperatorChange} writes exactly the named settings and
 * {@code saveOperatorMapEntry} exactly the one named map entry, with the operator's consent overriding what the file
 * holds there; why it cannot overwrite other operator content: the write owns only those keys at the config write gate,
 * which refuses any change to another byte of the file.
 */
@DisplayName("Operator-change API: an operator's command writes exactly what it names")
class ConfigOperatorChangeTest {

    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);
    private static final String SPAWN = "spawn.yml";
    private static final String RULES = "autoreply.yml";
    private static final String SPAWN_TEXT = "spawn:\n"
            + "  # Spawn location\n"
            + "  location:\n"
            + "    world: world\n"
            + "    x: 0.5\n"
            + "    y: 64.0\n"
            + "    z: 0.5\n"
            + "    yaw: 0.0\n"
            + "    pitch: 0.0\n"
            + "  # Teleport players to spawn when they respawn\n"
            + "  teleport-on-respawn: true\n"
            + "  teleport-on-first-join: true\n";
    private static final String[] LOCATION = {"spawn.location.world", "spawn.location.x", "spawn.location.y",
        "spawn.location.z", "spawn.location.yaw", "spawn.location.pitch"};

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;

    public static class Spawn extends AbstractConfigEntity {
        @ConfigEntry(path = "spawn.location.world") String world = "world";
        @ConfigEntry(path = "spawn.location.x") double x = 0.5;
        @ConfigEntry(path = "spawn.location.y") double y = 64.0;
        @ConfigEntry(path = "spawn.location.z") double z = 0.5;
        @ConfigEntry(path = "spawn.location.yaw") float yaw = 0.0F;
        @ConfigEntry(path = "spawn.location.pitch") float pitch = 0.0F;
        @ConfigEntry(path = "spawn.teleport-on-respawn") boolean respawn = true;
        @ConfigEntry(path = "spawn.teleport-on-first-join") boolean firstJoin = true;

        public Spawn(String path) {
            super(path);
        }
    }

    public static class AutoReply extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.enabled") boolean enabled = true;
        @ConfigEntry(path = "autoreply.rules") Map<String, Map<String, String>> rules = new LinkedHashMap<>();

        public AutoReply(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("OperatorModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private void put(String name, String text) throws IOException {
        Files.write(tempDir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(tempDir.resolve(name), OLD);
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(name)), StandardCharsets.UTF_8);
    }

    private static Map<String, String> rule(String keyword, String response) {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("keyword", keyword);
        rule.put("response", response);
        return rule;
    }

    private void moveTo(Spawn spawn) {
        spawn.world = "lobby";
        spawn.x = 10.5;
        spawn.y = 70.0;
        spawn.z = -3.5;
        spawn.yaw = 90.0F;
        spawn.pitch = 15.0F;
    }

    private static String moved(String text) {
        return text.replace("world: world", "world: lobby").replace("x: 0.5", "x: 10.5").replace("y: 64.0", "y: 70.0")
                .replace("z: 0.5", "z: -3.5").replace("yaw: 0.0", "yaw: 90.0").replace("pitch: 0.0", "pitch: 15.0");
    }

    @Test
    @DisplayName("/setspawn: the six location lines take the new values; the operator's hand edit of another key stays")
    void namedSettingsAreWrittenAndEveryOtherByteStays() throws Exception {
        put(SPAWN, SPAWN_TEXT);
        Spawn spawn = new Spawn(SPAWN);
        spawn.init(plugin);
        String handEdited = SPAWN_TEXT.replace("teleport-on-respawn: true", "teleport-on-respawn: false");
        put(SPAWN, handEdited);

        moveTo(spawn);
        spawn.saveOperatorChange(LOCATION);

        assertThat(read(SPAWN)).isEqualTo(moved(handEdited));
        assertThat(spawn.isModifiedSinceSnapshot()).as("the written settings are saved").isFalse();
    }

    @Test
    @DisplayName("the command's value wins at a named key the operator also hand-edited (operator consent)")
    void commandValueWinsAtANamedKeyTheOperatorEdited() throws Exception {
        put(SPAWN, SPAWN_TEXT);
        Spawn spawn = new Spawn(SPAWN);
        spawn.init(plugin);
        put(SPAWN, SPAWN_TEXT.replace("x: 0.5", "x: 99.0"));

        moveTo(spawn);
        spawn.saveOperatorChange(LOCATION);

        assertThat(read(SPAWN)).isEqualTo(moved(SPAWN_TEXT));
    }

    @Test
    @DisplayName("an unnamed module change is not written by the operator-change call")
    void unnamedModuleChangeIsNotWritten() throws Exception {
        put(SPAWN, SPAWN_TEXT);
        Spawn spawn = new Spawn(SPAWN);
        spawn.init(plugin);

        moveTo(spawn);
        spawn.firstJoin = false;
        spawn.saveOperatorChange(LOCATION);

        assertThat(read(SPAWN)).isEqualTo(moved(SPAWN_TEXT));
        assertThat(spawn.isModifiedSinceSnapshot()).as("the unnamed change stays unsaved").isTrue();
    }

    @Test
    @DisplayName("/autoreply: one rule written; a rule the operator added by hand since the load stays")
    void namedMapEntryIsWrittenAndAHandAddedEntryStays() throws Exception {
        String text = "autoreply:\n  enabled: true\n  rules:\n    rules-info:\n      keyword: rules\n      response: See /rules\n";
        put(RULES, text);
        AutoReply config = new AutoReply(RULES);
        config.init(plugin);
        String handAdded = text + "    discord:\n      keyword: discord\n      response: discord.gg/x\n";
        put(RULES, handAdded);

        config.rules.put("server-ip", rule("ip", "play.example.com"));
        config.saveOperatorMapEntry("autoreply.rules", "server-ip");

        assertThat(read(RULES)).isEqualTo(handAdded
                + "    server-ip:\n      keyword: ip\n      response: play.example.com\n");
    }

    @Test
    @DisplayName("a rule removed in memory is removed alone; a rule name containing '.' is one key")
    void removedEntryIsRemovedAloneAndADottedNameIsOneKey() throws Exception {
        String text = "autoreply:\n  enabled: true\n  rules:\n    server-ip:\n      keyword: ip\n      response: here\n"
                + "    rules-info:\n      keyword: rules\n      response: See /rules\n";
        put(RULES, text);
        AutoReply config = new AutoReply(RULES);
        config.init(plugin);

        config.rules.remove("server-ip");
        config.saveOperatorMapEntry("autoreply.rules", "server-ip");
        assertThat(read(RULES)).isEqualTo("autoreply:\n  enabled: true\n  rules:\n"
                + "    rules-info:\n      keyword: rules\n      response: See /rules\n");

        config.rules.put("play.example", rule("play.example", "yes"));
        config.saveOperatorMapEntry("autoreply.rules", "play.example");
        assertThat(read(RULES)).isEqualTo("autoreply:\n  enabled: true\n  rules:\n"
                + "    rules-info:\n      keyword: rules\n      response: See /rules\n"
                + "    play.example:\n      keyword: play.example\n      response: 'yes'\n");
    }

    @Test
    @DisplayName("the first rule goes into an empty rules map, which keeps the flow style it was written in")
    void firstEntryGoesIntoAnEmptyMap() throws Exception {
        put(RULES, "autoreply:\n  enabled: true\n  rules: {}\n");
        AutoReply config = new AutoReply(RULES);
        config.init(plugin);

        config.rules.put("server-ip", rule("ip", "here"));
        config.saveOperatorMapEntry("autoreply.rules", "server-ip");

        assertThat(read(RULES)).isEqualTo("autoreply:\n  enabled: true\n  rules: {server-ip: {keyword: ip, response: here}}\n");
    }

    @Test
    @DisplayName("a path that is not a declared entry, or a map-entry call on a non-map setting, is refused and writes nothing")
    void undeclaredPathAndNonMapSettingAreRefused() throws Exception {
        put(SPAWN, SPAWN_TEXT);
        Spawn spawn = new Spawn(SPAWN);
        spawn.init(plugin);
        moveTo(spawn);

        assertThatThrownBy(() -> spawn.saveOperatorChange("spawn.location.world", "spawn.location"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spawn.location");
        assertThatThrownBy(() -> spawn.saveOperatorMapEntry("spawn.location.world", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spawn.location.world");
        assertThatThrownBy(() -> spawn.saveOperatorChange())
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(read(SPAWN)).isEqualTo(SPAWN_TEXT);
        assertThat(Files.getLastModifiedTime(tempDir.resolve(SPAWN))).isEqualTo(OLD);
        assertThat(spawn.world).isEqualTo("lobby");
    }

    @Test
    @DisplayName("on an anchored file the call throws ConfigWriteRefusedException naming the reason; file and memory stay")
    void anchoredFileIsRefusedWithTheReason() throws Exception {
        String anchored = "defaults: &d\n  keyword: x\n  response: y\nautoreply:\n  enabled: true\n  rules:\n    first: *d\n";
        put(RULES, anchored);
        AutoReply config = new AutoReply(RULES);
        config.init(plugin);

        config.rules.put("second", rule("a", "b"));
        assertThatThrownBy(() -> config.saveOperatorMapEntry("autoreply.rules", "second"))
                .isInstanceOf(ConfigWriteRefusedException.class).isInstanceOf(IOException.class)
                .hasMessageContaining("anchors");

        assertThat(read(RULES)).isEqualTo(anchored);
        assertThat(config.rules).containsKey("second");
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
    }

    @Test
    @DisplayName("on a hand-aligned file the call throws ConfigWriteRefusedException naming the layout reason")
    void handAlignedFileIsRefusedWithTheReason() throws Exception {
        String aligned = SPAWN_TEXT.replace("teleport-on-first-join: true", "teleport-on-first-join:   true    # aligned");
        put(SPAWN, aligned);
        Spawn spawn = new Spawn(SPAWN);
        spawn.init(plugin);

        moveTo(spawn);
        assertThatThrownBy(() -> spawn.saveOperatorChange(LOCATION))
                .isInstanceOf(ConfigWriteRefusedException.class).hasMessageContaining("layout");

        assertThat(read(SPAWN)).isEqualTo(aligned);
        assertThat(Files.getLastModifiedTime(tempDir.resolve(SPAWN))).isEqualTo(OLD);
        assertThat(spawn.world).isEqualTo("lobby");
    }

    @Test
    @DisplayName("on a file that no longer parses the call throws ConfigWriteRefusedException and writes nothing")
    void unparseableFileIsRefused() throws Exception {
        put(SPAWN, SPAWN_TEXT);
        Spawn spawn = new Spawn(SPAWN);
        spawn.init(plugin);
        put(SPAWN, "spawn: [\n");

        moveTo(spawn);
        assertThatThrownBy(() -> spawn.saveOperatorChange(LOCATION)).isInstanceOf(ConfigWriteRefusedException.class);

        assertThat(read(SPAWN)).isEqualTo("spawn: [\n");
    }
}
