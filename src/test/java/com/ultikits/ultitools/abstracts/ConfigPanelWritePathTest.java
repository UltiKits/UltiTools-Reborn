package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
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
        BukkitMaps next = new BukkitMaps("bukkit-maps.yml"); next.init(plugin);
        assertThat(next.locations).isEqualTo(first.locations);
        assertThat(next.ids).isEqualTo(first.ids);
        assertThat(next.items.keySet()).containsExactly("o.O");
        ItemStack restored = next.items.get("o.O");
        assertThat(restored.getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(restored.getAmount()).isEqualTo(2);
        assertThat(restored.getItemMeta().getDisplayName()).isEqualTo("Panel item");
        assertThat(restored.getItemMeta().getLore()).containsExactly("first", "second");
        assertThat(next.isModifiedSinceSnapshot()).isFalse();
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
