package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
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

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * A Bukkit {@code Vector} (or {@code BlockVector}) whose file text holds a whole number ({@code y: 64}) loads as that
 * number (17-66 review round 1 R66-W1, with #609). Bukkit's {@code Vector#deserialize} casts each coordinate to
 * {@code Double}, so before this a hand-written whole number failed with a SEVERE stack trace and the module ran on the
 * declared default. The coordinates are widened in the converter's in-memory copy only: the file keeps its bytes (an
 * unchanged save is a no-op, a panel edit of another field keeps {@code y: 64}), nothing is logged, and no other
 * serializable type is widened (maintainer foundational rule of 2026-10-04: an operator's usable value is used as
 * written, never rewritten).
 */
class ConfigVectorWholeNumberTest {

    private static final String TEXT = "home:\n  ==: Vector\n  x: 1.0\n  y: 64\n  z: 3.0\n"
            + "block:\n  ==: BlockVector\n  x: 4\n  y: 5\n  z: 6\n"
            + "points:\n  a:\n    ==: Vector\n    x: 1\n    y: 64\n    z: 2\n  b:\n    ==: Vector\n    x: 0.5\n"
            + "    y: 1.5\n    z: 2.5\n"
            + "counter:\n  ==: ProbeWholeCount\n  x: 3\n";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;
    private final List<String> records = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record.getLevel() + " " + record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** A serializable that requires an {@code Integer} in a field named {@code x}: it must never be widened. */
    @SerializableAs("ProbeWholeCount")
    public static final class WholeCount implements ConfigurationSerializable {
        private final int count;

        public WholeCount(int count) {
            this.count = count;
        }

        public static WholeCount deserialize(Map<String, Object> map) {
            return new WholeCount((Integer) map.get("x"));
        }

        @Override
        public Map<String, Object> serialize() {
            return Collections.<String, Object>singletonMap("x", count);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the config binder and by the assertions below
    public static class Vectors extends AbstractConfigEntity {
        @ConfigEntry(path = "home")
        Vector home = new Vector(9, 9, 9);
        @ConfigEntry(path = "block")
        BlockVector block = new BlockVector(9, 9, 9);
        @ConfigEntry(path = "points")
        Map<String, Vector> points = new LinkedHashMap<>();
        @ConfigEntry(path = "counter")
        WholeCount counter = new WholeCount(9);

        public Vectors(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        ConfigurationSerialization.registerClass(WholeCount.class);
        Logger frameworkLogger = Mockito.mock(Logger.class);
        Mockito.doAnswer(call -> records.add("FW " + call.getArgument(0))).when(frameworkLogger).warning(anyString());
        Mockito.doAnswer(call -> {
            Level level = call.getArgument(0);
            if (level.intValue() >= Level.WARNING.intValue()) {
                records.add("FW " + call.getArgument(1));
            }
            return null;
        }).when(frameworkLogger).log(Mockito.any(Level.class), anyString());
        TestHelper.mockUltiToolsInstance(u -> Mockito.when(u.getLogger()).thenReturn(frameworkLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("Probe");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString()))
                .thenAnswer(call -> new File(tempDir.toFile(), call.<String>getArgument(0)));
        Logger.getLogger("").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").removeHandler(capture);
        ConfigurationSerialization.unregisterClass(WholeCount.class);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void wholeNumbersInAVectorSettingABlockVectorAndAMapEntryLoadWithoutAnyWarning() throws Exception {
        Vectors config = load();

        assertThat(config.home).isEqualTo(new Vector(1, 64, 3));
        assertThat(config.block).isEqualTo(new BlockVector(4, 5, 6));
        assertThat(config.points.get("a")).isEqualTo(new Vector(1, 64, 2));
        assertThat(config.points.get("b")).isEqualTo(new Vector(0.5, 1.5, 2.5));
        assertThat(config.counter.count).as("a serializable that needs an Integer still gets one").isEqualTo(3);
        assertThat(records).isEmpty();
    }

    @Test
    void anUnchangedSaveLeavesTheWholeNumbersAsWritten() throws Exception {
        Vectors config = load();

        config.save();

        assertThat(read()).isEqualTo(TEXT);
        assertThat(records).isEmpty();
    }

    @Test
    void aModuleChangeOfTheSettingIsWritten() throws Exception {
        Vectors config = load();
        config.home = new Vector(5, 6, 7);

        config.save();

        assertThat(read()).contains("home:\n  ==: Vector\n  x: 5.0\n  y: 6.0\n  z: 7.0\n")
                .contains("    x: 1\n    y: 64\n    z: 2\n");
    }

    @Test
    void aPanelEditOfAMapEntryKeepsItsOtherWholeNumbers() throws Exception {
        Vectors config = load();
        JsonObject edit = new JsonObject();
        edit.addProperty("points.a.x", 7.5);

        config.updateProperties(edit);

        assertThat(config.points.get("a")).isEqualTo(new Vector(7.5, 64, 2));
        assertThat(read()).isEqualTo(TEXT.replace("    x: 1\n    y: 64\n", "    x: 7.5\n    y: 64\n"));
    }

    @Test
    void aReloadOfAnUnchangedFileOrOfAValueEqualEditChangesNothingAndWritesNothing() throws Exception {
        // 17-66 review round 2 R66-R2-I3: 64 and 64.0 are the same value for the reload merge.
        Vectors config = load();

        config.reload();
        assertThat(config.unsavedEntryPaths()).isEmpty();
        assertThat(read()).isEqualTo(TEXT);

        String operatorText = TEXT.replace("  x: 1.0\n  y: 64\n", "  x: 1.0\n  y: 64.0\n")
                .replace("    x: 1\n    y: 64\n", "    x: 1\n    y: 64.00\n");
        write(operatorText);
        config.reload();
        config.save();

        assertThat(config.home).isEqualTo(new Vector(1, 64, 3));
        assertThat(config.unsavedEntryPaths()).isEmpty();
        assertThat(read()).as("the operator's 64.0 and 64.00 stay as written").isEqualTo(operatorText);
        assertThat(records).isEmpty();
    }

    @Test
    void aReloadKeepsAModuleChangeOverAValueEqualDiskEditWithoutAConflict() throws Exception {
        Vectors config = load();
        config.home = new Vector(5, 6, 7);
        write(TEXT.replace("  x: 1.0\n  y: 64\n", "  x: 1.0\n  y: 64.0\n"));

        config.reload();

        assertThat(config.home).as("the module's change survives as unsaved").isEqualTo(new Vector(5, 6, 7));
        assertThat(config.unsavedEntryPaths()).containsExactly("home");
        assertThat(records).as("no conflict line: the file's value did not change").isEmpty();
    }

    @Test
    void aReloadAdoptsADiskChangeAndASaveThenWritesOnlyTheModulesAddedEntry() throws Exception {
        Vectors config = load();
        config.points.put("c", new Vector(0.5, 0.5, 0.5));
        String operatorText = TEXT.replace("  x: 1.0\n  y: 64\n", "  x: 1.0\n  y: 70\n");
        write(operatorText);

        config.reload();
        config.save();

        assertThat(config.home).isEqualTo(new Vector(1, 70, 3));
        assertThat(config.points).containsKeys("a", "b", "c");
        assertThat(read()).startsWith(operatorText.substring(0, operatorText.indexOf("counter:")))
                .contains("  c:\n").contains("  y: 70\n").contains("    x: 1\n    y: 64\n    z: 2\n");
        assertThat(records).isEmpty();
    }

    @Test
    void aPanelEditOfASettingRecordsTheLoadedValueSoALaterModuleChangeIsWritten() throws Exception {
        // PR #611 local Codex run 1 (P2): the panel writes the file's value with one field changed (y: 64 kept as
        // written), but the setting's baseline must be the value the module holds (64.0), or the setting stays
        // "unsaved" and the module's next change of it is refused as not started from the file.
        Vectors config = load();
        JsonObject edit = new JsonObject();
        edit.addProperty("home.x", 2);

        config.updateProperties(edit);

        assertThat(read()).contains("home:\n  ==: Vector\n  x: 2.0\n  y: 64\n  z: 3.0\n");
        assertThat(config.unsavedEntryPaths()).isEmpty();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();

        config.home = new Vector(5, 6, 7);
        config.save();

        assertThat(read()).contains("home:\n  ==: Vector\n  x: 5.0\n  y: 6.0\n  z: 7.0\n");
        assertThat(config.unsavedEntryPaths()).isEmpty();
        assertThat(records).isEmpty();
    }

    @Test
    void aPanelEditOfAMapEntryRecordsTheLoadedValueSoALaterModuleChangeIsWritten() throws Exception {
        // Same finding, map-entry branch: points.a keeps y: 64 / z: 2 on disk; the baseline holds the module's doubles.
        Vectors config = load();
        JsonObject edit = new JsonObject();
        edit.addProperty("points.a.x", 7.5);

        config.updateProperties(edit);

        assertThat(config.unsavedEntryPaths()).isEmpty();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();

        config.points.put("a", new Vector(8, 8, 8));
        config.save();

        assertThat(read()).contains("  a:\n    ==: Vector\n    x: 8.0\n    y: 8.0\n    z: 8.0\n");
        assertThat(config.unsavedEntryPaths()).isEmpty();
        assertThat(records).isEmpty();
    }

    private void write(String text) throws Exception {
        Files.write(tempDir.resolve("v.yml"), text.getBytes(StandardCharsets.UTF_8));
    }

    private Vectors load() throws Exception {
        Files.write(tempDir.resolve("v.yml"), TEXT.getBytes(StandardCharsets.UTF_8));
        Vectors config = new Vectors("v.yml");
        config.init(plugin);
        return config;
    }

    private String read() throws Exception {
        return new String(Files.readAllBytes(tempDir.resolve("v.yml")), StandardCharsets.UTF_8);
    }
}
