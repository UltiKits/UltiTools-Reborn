package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * A panel edit of one field inside a composite value - a Bukkit {@code Vector} or {@code Location}, or such a value in a
 * map entry - after the 17-65 route change (UltiKits/UltiTools-Reborn#609 and the 17-65 round-4 follow-ups):
 * <ul>
 *   <li>a whole number sent by the panel, or already in the file in another field, does not refuse the edit: the value
 *       converted for the module is widened where the module's own value holds a floating-point number (#609, R4-I1),
 *       and the edited field is written in that type, so the file the edit leaves behind loads; an untouched whole
 *       number the operator wrote stays as written;</li>
 *   <li>the value written is the whole value as the file holds it with only the edited field changed, so every untouched
 *       field keeps its bytes - a hand-written {@code y: 64} or {@code pitch: 0} is never re-rendered as {@code 64.0}
 *       (R4-I2; maintainer foundational rule of 2026-10-04: operator-written configuration is never overwritten);</li>
 *   <li>an edit below a map group the module removed in memory is refused with a reason, never a runtime error (R4-I3).</li>
 * </ul>
 */
class ConfigPanelCompositeFieldTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the config binder and by the assertions below
    public static class Composites extends AbstractConfigEntity {
        @ConfigEntry(path = "home")
        Vector home = new Vector(9, 9, 9);
        @ConfigEntry(path = "spawn")
        Location spawn;
        @ConfigEntry(path = "points")
        Map<String, Vector> points = new LinkedHashMap<>();
        @ConfigEntry(path = "nv")
        Map<String, Map<String, Integer>> nv = new LinkedHashMap<>();
        @ConfigEntry(path = "spots")
        Map<String, Location> spots = new LinkedHashMap<>();

        public Composites(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.getMock().addSimpleWorld("world");
        Logger frameworkLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(u -> Mockito.when(u.getLogger()).thenReturn(frameworkLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("Probe");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString()))
                .thenAnswer(call -> new File(tempDir.toFile(), call.<String>getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void aWholeNumberSentByThePanelIntoAVectorFieldIsAccepted() throws Exception {
        String text = "home:\n  ==: Vector\n  x: 1.0\n  y: 2.0\n  z: 3.0\nspawn: null\npoints: {}\nnv: {}\nspots: {}\n";
        Composites config = load("a.yml", text);

        config.updateProperties(edit("home.y", 7));

        assertThat(config.home).isEqualTo(new Vector(1, 7, 3));
        assertThat(read("a.yml")).isEqualTo(text.replace("  y: 2.0\n", "  y: 7.0\n"));
        assertThat(load("a.yml", read("a.yml")).home).as("the file the edit wrote loads").isEqualTo(new Vector(1, 7, 3));
    }

    @Test
    void aWholeNumberAlreadyInTheFileInAnotherFieldDoesNotBlockTheEdit() throws Exception {
        String text = "home:\n  ==: Vector\n  x: 1\n  y: 2.0\n  z: 3.0\nspawn: null\npoints: {}\nnv: {}\nspots: {}\n";
        Composites config = load("b.yml", text);

        config.updateProperties(edit("home.y", 7.5));

        assertThat(config.home).isEqualTo(new Vector(1, 7.5, 3));
        assertThat(read("b.yml")).isEqualTo(text.replace("  y: 2.0\n", "  y: 7.5\n"));
    }

    @Test
    void untouchedFieldsOfAnEditedLocationKeepTheirBytes() throws Exception {
        String text = "spawn:\n  ==: org.bukkit.Location\n  world: world\n  x: 100\n  y: 64\n  z: 2\n  pitch: 0\n  yaw: 0\n"
                + "home:\n  ==: Vector\n  x: 1.0\n  y: 2.0\n  z: 3.0\npoints: {}\nnv: {}\nspots: {}\n";
        Composites config = load("c.yml", text);

        config.updateProperties(edit("spawn.x", 9.5));

        assertThat(config.spawn.getX()).isEqualTo(9.5);
        assertThat(config.spawn.getY()).isEqualTo(64.0);
        assertThat(read("c.yml")).isEqualTo(text.replace("  x: 100\n", "  x: 9.5\n"));
    }

    @Test
    void untouchedFieldsOfAnEditedLocationInAMapEntryKeepTheirBytes() throws Exception {
        String text = "spots:\n  a:\n    ==: org.bukkit.Location\n    world: world\n    x: 4\n    y: 5\n    z: 6\n"
                + "    pitch: 0\n    yaw: 0\n  b:\n    ==: org.bukkit.Location\n    world: world\n    x: 0.5\n"
                + "    y: 0.5\n    z: 0.5\n    pitch: 0.0\n    yaw: 0.0\nhome:\n  ==: Vector\n  x: 1.0\n  y: 2.0\n  z: 3.0\n"
                + "spawn: null\npoints: {}\nnv: {}\n";
        Composites config = load("d.yml", text);

        config.updateProperties(edit("spots.a.y", 6.5));

        assertThat(config.spots.get("a").getY()).isEqualTo(6.5);
        assertThat(config.spots.get("a").getX()).isEqualTo(4.0);
        assertThat(read("d.yml")).isEqualTo(text.replace("    y: 5\n", "    y: 6.5\n"));
    }

    @Test
    void anEditBelowAGroupTheModuleRemovedInMemoryIsRefusedWithAReason() throws Exception {
        String text = "nv:\n  g:\n    a: 1\nhome:\n  ==: Vector\n  x: 1.0\n  y: 2.0\n  z: 3.0\nspawn: null\npoints: {}\nspots: {}\n";
        Composites config = load("e.yml", text);
        config.nv.remove("g");

        assertThatThrownBy(() -> config.updateProperties(edit("nv.g.a", 2)))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'nv.g.a'").hasMessageContaining("not found in memory");

        assertThat(read("e.yml")).isEqualTo(text);
        assertThat(config.nv).doesNotContainKey("g");
    }

    private Composites load(String name, String text) throws Exception {
        Files.write(tempDir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
        Composites config = new Composites(name);
        config.init(plugin);
        return config;
    }

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(tempDir.resolve(name)), StandardCharsets.UTF_8);
    }

    private static JsonObject edit(String key, Number value) {
        JsonObject edit = new JsonObject();
        edit.addProperty(key, value);
        return edit;
    }
}
