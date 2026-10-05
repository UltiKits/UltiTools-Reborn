package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.manager.ConfigManager;

/** Panel JSON and explicit saves must share conversion and persisted forms (#560). */
class ConfigPanelWritePathTest {
    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    enum Mode { FAST, SLOW }
    @ConfigEntity("panel.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry List<Integer> numbers = Arrays.asList(1);
        @ConfigEntry List<Material> materials = Arrays.asList(Material.STONE);
        @ConfigEntry Set<String> tags = new LinkedHashSet<>(Arrays.asList("old"));
        @ConfigEntry Map<String, UUID> ids = new LinkedHashMap<>();
        @ConfigEntry UUID id = new UUID(0, 1);
        @ConfigEntry Mode mode = Mode.SLOW;
        @ConfigEntry float ratio = 0.5F;
        @ConfigEntry ItemStack item = new ItemStack(Material.STONE);
        public Values(String path) { super(path); ids.put("old", id); }
    }
    @BeforeEach void setup() {
        MockBukkit.mock();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("PanelModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(directory.toString());
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                call -> new File(directory.toFile(), call.<String>getArgument(0)));
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    private static ItemStack item() {
        ItemStack value = new ItemStack(Material.DIAMOND_SWORD, 2);
        ItemMeta meta = value.getItemMeta();
        meta.setDisplayName("Panel item"); meta.setLore(Arrays.asList("first", "second"));
        value.setItemMeta(meta); return value;
    }
    private static void edit(Values value) {
        value.numbers = Arrays.asList(5, 9);
        value.materials = Arrays.asList(Material.DIAMOND, Material.OAK_LOG);
        value.tags = new LinkedHashSet<>(Arrays.asList("red", "blue"));
        value.id = new UUID(0, 2); value.ids = new LinkedHashMap<>();
        value.ids.put("new", value.id); value.mode = Mode.FAST;
        value.ratio = 0.25F; value.item = item();
    }
    public static class BukkitMaps extends AbstractConfigEntity {
        @ConfigEntry Map<String, org.bukkit.Location> locations = new LinkedHashMap<>();
        @ConfigEntry Map<String, ItemStack> items = new LinkedHashMap<>();
        @ConfigEntry Map<String, UUID> ids = new LinkedHashMap<>();
        public BukkitMaps(String path) {
            super(path);
            locations.put("g.m", new org.bukkit.Location(org.bukkit.Bukkit.getWorld("world"), 1.25, 2, -3.5, 30, -15));
            items.put("o.O", item());
            ids.put("wave.", new UUID(0, 3));
        }
    }
    @Test void bukkitAndUuidMapDefaultsSaveAndFreshLoadThroughEntity() throws Exception {
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).addSimpleWorld("world");
        BukkitMaps first = new BukkitMaps("bukkit-maps.yml"); first.init(plugin);
        assertThat(Files.readAllBytes(directory.resolve("bukkit-maps.yml"))).isNotEmpty();
        first.save();
        Path file = directory.resolve("bukkit-maps.yml");
        String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("!!", UUID.class.getName());
        com.ultikits.ultitools.config.document.ConfigLoadResult raw =
                com.ultikits.ultitools.config.document.ConfigDocument.load(file);
        assertThat(raw.state()).isEqualTo(com.ultikits.ultitools.config.document.ConfigLoadResult.State.LOADED);
        assertThat(raw.document().get(Arrays.asList("locations"))).isInstanceOf(Map.class);
        assertThat(raw.document().get(Arrays.asList("items"))).isInstanceOf(Map.class);
        assertThat(raw.document().get(Arrays.asList("ids"))).isInstanceOf(Map.class);
        assertThat(raw.document().contains(Arrays.asList("locations", "g.m"))).isTrue();
        assertThat(raw.document().contains(Arrays.asList("items", "o.O"))).isTrue();
        assertThat(raw.document().contains(Arrays.asList("ids", "wave."))).isTrue();
        BukkitMaps next = new BukkitMaps("bukkit-maps.yml");
        next.locations.put("g.m", new org.bukkit.Location(org.bukkit.Bukkit.getWorld("world"), 99, 99, 99));
        next.items.put("o.O", new ItemStack(Material.STONE));
        next.ids.put("wave.", new UUID(0, 99));
        assertThat(next.locations).isNotEqualTo(first.locations);
        assertThat(next.items).isNotEqualTo(first.items);
        assertThat(next.ids).isNotEqualTo(first.ids);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            next.init(plugin);
            assertThat(warnings.messages()).isEmpty();
        }
        assertBukkitMapsLoaded(next, first);
    }
    private static void assertBukkitMapsLoaded(BukkitMaps actual, BukkitMaps expected) {
        assertThat(actual.isLastLoadUnparseable()).as("fresh entity must not be protected").isFalse();
        assertThat(actual.isPresentInFile("locations")).isTrue();
        assertThat(actual.isPresentInFile("items")).isTrue();
        assertThat(actual.isPresentInFile("ids")).isTrue();
        assertThat(actual.locations).isEqualTo(expected.locations);
        assertThat(actual.ids).isEqualTo(expected.ids);
        assertThat(actual.items.keySet()).containsExactly("o.O");
        ItemStack restored = actual.items.get("o.O");
        assertThat(restored.getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(restored.getAmount()).isEqualTo(2);
        assertThat(restored.getItemMeta().getDisplayName()).isEqualTo("Panel item");
        assertThat(restored.getItemMeta().getLore()).containsExactly("first", "second");
        assertThat(actual.isModifiedSinceSnapshot()).isFalse();
    }
    @Test void malformedNonemptyFileCannotPassBukkitMapLoadOracle() throws Exception {
        ((org.mockbukkit.mockbukkit.ServerMock) org.bukkit.Bukkit.getServer()).addSimpleWorld("world");
        BukkitMaps expected = new BukkitMaps("malformed-bukkit-maps.yml");
        Path file = directory.resolve("malformed-bukkit-maps.yml");
        byte[] malformed = "locations: [\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, malformed);
        assertThat(malformed).isNotEmpty();
        assertThat(com.ultikits.ultitools.config.document.ConfigDocument.load(file).state())
                .isEqualTo(com.ultikits.ultitools.config.document.ConfigLoadResult.State.UNPARSEABLE);
        BukkitMaps fresh = new BukkitMaps("malformed-bukkit-maps.yml");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            fresh.init(plugin);
            assertThat(warnings.messages()).hasSize(1);
            assertThat(warnings.messages().get(0)).contains("malformed-bukkit-maps.yml", "will not be overwritten");
        }
        assertThat(fresh.isLastLoadUnparseable()).isTrue();
        assertThat(fresh.isPresentInFile("locations")).isFalse();
        assertThat(fresh.isPresentInFile("items")).isFalse();
        assertThat(fresh.isPresentInFile("ids")).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(malformed);
        assertThatThrownBy(() -> assertBukkitMapsLoaded(fresh, expected)).isInstanceOf(AssertionError.class);
    }
    @ConfigEntity("gated.yml")
    public static class Gated extends AbstractConfigEntity {
        @ConfigEntry(path = "limits") Map<String, Integer> limits = new LinkedHashMap<>();
        @ConfigEntry(path = "name") String name = "server";
        public Gated(String path) { super(path); limits.put("a", 1); limits.put("b", 2); }
    }

    /**
     * #600 / maintainer decision 2026-10-04 (item 3 of "what code may write, by file type"): a panel edit writes exactly
     * the leaf it touches through the config write gate; the operator's edit of another leaf since the load, and every
     * other byte, stay.
     */
    @Test void panelLeafEditWritesThatLeafOnlyAndKeepsEveryOtherByte() throws Exception {
        Path file = directory.resolve("gated.yml");
        String text = "# Limits per group\nlimits:\n  a: 1\n  b: 2\n# Server name\nname: server\n";
        Files.write(file, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Gated gated = new Gated("gated.yml");
        ConfigManager manager = new ConfigManager(); manager.register(plugin, gated);
        String edited = text.replace("b: 2", "b: 3").replace("name: server", "name: hand-edited");
        Files.write(file, edited.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        manager.loadFromJson("gated.yml", "{\"limits.a\": 5}");

        assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(edited.replace("a: 1", "a: 5"));
        assertThat(gated.limits.get("a")).isEqualTo(5);
    }

    /** #600: on a file the gate cannot write (anchors), the panel edit is refused with the reason; nothing changes. */
    @Test void panelEditOfAnAnchoredFileIsRefusedWithTheReason() throws Exception {
        Path file = directory.resolve("gated.yml");
        String anchored = "base: &b 1\nlimits:\n  a: *b\n  b: 2\nname: server\n";
        Files.write(file, anchored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Gated gated = new Gated("gated.yml");
        ConfigManager manager = new ConfigManager(); manager.register(plugin, gated);

        assertThatThrownBy(() -> manager.loadFromJson("gated.yml", "{\"name\": \"panel\"}"))
                .isInstanceOf(com.ultikits.ultitools.config.ConfigWriteRefusedException.class)
                .hasMessageContaining("anchors").hasMessageContaining("gated.yml");

        assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(anchored);
        assertThat(gated.name).as("a refused panel edit leaves the entity as it was").isEqualTo("server");
    }

    /** #600: a panel edit on a hand-aligned file is refused with the layout reason instead of normalizing it. */
    @Test void panelEditOfAHandAlignedFileIsRefusedInsteadOfNormalized() throws Exception {
        Path file = directory.resolve("gated.yml");
        String aligned = "limits:\n  a:   1    # aligned\n  b: 2\nname: server\n";
        Files.write(file, aligned.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Gated gated = new Gated("gated.yml");
        ConfigManager manager = new ConfigManager(); manager.register(plugin, gated);

        JsonObject edit = new JsonObject(); edit.addProperty("name", "panel");
        assertThatThrownBy(() -> gated.updateProperties(edit))
                .isInstanceOf(com.ultikits.ultitools.config.ConfigWriteRefusedException.class).hasMessageContaining("layout");

        assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(aligned);
        assertThat(gated.name).isEqualTo("server");
    }

    @Test void panelAndSaveProduceIdenticalLoadableText() throws Exception {
        Values saved = new Values("save.yml"); saved.init(plugin); edit(saved); saved.save();
        Values panel = new Values("panel.yml");
        ConfigManager manager = new ConfigManager(); manager.register(plugin, panel);
        JsonObject payload = new JsonObject(); Gson gson = new Gson();
        for (java.lang.reflect.Field field : Values.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                Object plain = ConverterRegistry.framework().toPlain(field.get(saved), field.getGenericType(),
                        "save.yml", java.util.Collections.singletonList(field.getName()), field.getAnnotation(ConfigEntry.class));
                payload.add(field.getName(), gson.toJsonTree(plain));
            }
        }
        manager.loadFromJson("panel.yml", payload.toString());
        assertThat(Files.readAllBytes(directory.resolve("panel.yml")))
                .isEqualTo(Files.readAllBytes(directory.resolve("save.yml")));
        assertThat(panel.numbers.get(0)).isInstanceOf(Integer.class).isEqualTo(5);
        Values next = new Values("panel.yml"); next.init(plugin);
        assertThat(next.numbers).containsExactly(5, 9);
        assertThat(next.materials).containsExactly(Material.DIAMOND, Material.OAK_LOG);
        assertThat(next.tags).containsExactly("red", "blue");
        assertThat(next.ids).isEqualTo(saved.ids); assertThat(next.id).isEqualTo(saved.id);
        assertThat(next.mode).isEqualTo(Mode.FAST); assertThat(next.ratio).isEqualTo(0.25F);
        assertThat(next.item.getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(next.item.getAmount()).isEqualTo(2);
        assertThat(next.item.getItemMeta().getDisplayName()).isEqualTo("Panel item");
        assertThat(next.item.getItemMeta().getLore()).containsExactly("first", "second");
        assertThat(next.isModifiedSinceSnapshot()).isFalse();
    }
}
