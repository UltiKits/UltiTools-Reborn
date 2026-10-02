package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * #531 gate-1 CR-01: a boxed numeric {@code @ConfigEntry} field must load from real YAML.
 * <p>
 * SnakeYAML hands back an {@code Integer} for {@code 1800}. {@code Field.set} widens that into a
 * {@code long} or {@code double} primitive, but refuses it for a {@code Long}, {@code Double} or
 * {@code Float} field (measured: {@code IllegalArgumentException: Can not set java.lang.Long field
 * ... to java.lang.Integer}). So before this fix a {@code Long} field survived the first boot -- the
 * key was missing and the field default was written -- and threw on every later boot and on every
 * {@code reload()}. These tests go through the file, never through setters: write the default,
 * boot again from that file, edit the file, reload.
 * <p>
 * The fix is JLS widening only, the conversions the matching primitive field already accepts. A
 * narrowing value is still not narrowed. ({@code float}/{@code Float} decimals are covered separately
 * by {@code ConfigFloatDecimalTest}: as of #534 a decimal loads when its float reading prints back
 * the same.)
 */
@DisplayName("AbstractConfigEntity numeric widening from YAML (#531 CR-01)")
class ConfigNumericWideningTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    private static final String PATH = "config/numbers.yml";

    @SuppressWarnings("unused") // read reflectively by the config binder and by the assertions below
    static class NumbersConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limits.boxed-long")
        Long boxedLong = 1800L;

        @ConfigEntry(path = "limits.plain-long")
        long plainLong = 60L;

        @ConfigEntry(path = "limits.boxed-double")
        Double boxedDouble = 2.0D;

        @ConfigEntry(path = "limits.boxed-int")
        Integer boxedInt = 5;

        public NumbersConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class NarrowConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "small")
        Integer small = 1;

        public NarrowConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    // Deliberately verifies the frozen explicit legacy-parser binding contract.
    @SuppressWarnings("removal")
    @com.ultikits.ultitools.annotations.ConfigEntity("legacy.yml")
    public static class LegacyNumbers extends AbstractConfigEntity {
        @ConfigEntry(path = "value", parser = LegacyParser.class) Long value = 7L;
        public LegacyNumbers(String path) { super(path); }
    }

    // Integer document values exercise the prior boxed widening boundary.
    @SuppressWarnings("removal")
    public static class LegacyParser extends com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser { }

    @Test
    void explicitLegacyParserWidensOnBootReloadDefaultAndEveryPanelRoute() throws Exception {
        com.ultikits.ultitools.manager.ConfigManager manager = new com.ultikits.ultitools.manager.ConfigManager();
        LegacyNumbers first = new LegacyNumbers("legacy.yml"); manager.register(plugin, first);
        assertThat(first.value).isEqualTo(7L);
        LegacyNumbers second = new LegacyNumbers("legacy.yml"); manager.register(plugin, second);
        assertThat(second.value).isEqualTo(7L);
        Path legacy = tempDir.resolve("legacy.yml");
        Files.write(legacy, "value: 19\n".getBytes(StandardCharsets.UTF_8)); second.reload();
        assertThat(second.value).isEqualTo(19L);
        second.value = 25L;
        Files.write(legacy, "value: 19\n".getBytes(StandardCharsets.UTF_8)); second.reload();
        assertThat(second.value).as("merged reload retains unsaved value when disk is unchanged").isEqualTo(25L);
        Files.write(legacy, "value: 29\n".getBytes(StandardCharsets.UTF_8)); second.reload();
        assertThat(second.value).as("a simultaneous scalar conflict keeps the file value").isEqualTo(29L);
        manager.loadFromJson("legacy.yml", "{\"value\":31}");
        assertThat(second.value).isEqualTo(31L);
        manager.loadFromJson("{\"NumbersModule\":{\"legacy.yml\":{\"value\":33}}}");
        assertThat(second.value).isEqualTo(33L);
        assertThat(second.isModifiedSinceSnapshot()).isFalse();
        Files.write(legacy, "value: invalid\n".getBytes(StandardCharsets.UTF_8)); second.reload();
        assertThat(second.value).as("invalid load restores and rebinds the declared default").isEqualTo(7L);
    }

    @SuppressWarnings("removal")
    @com.ultikits.ultitools.annotations.ConfigEntity("legacy-vector.yml")
    public static class LegacyVector extends AbstractConfigEntity {
        @ConfigEntry(path = "vec", parser = VectorParser.class)
        org.bukkit.util.Vector vec = new org.bukkit.util.Vector(1, 2, 3);
        public LegacyVector(String path) { super(path); }
    }

    @SuppressWarnings("removal")
    public static class VectorParser extends com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<org.bukkit.util.Vector> {
        @Override public org.bukkit.util.Vector parse(Object raw) { return (org.bukkit.util.Vector) raw; }
        @Override public org.bukkit.configuration.MemorySection serializeToMemorySection(org.bukkit.util.Vector value) {
            org.bukkit.configuration.MemoryConfiguration section = new org.bukkit.configuration.MemoryConfiguration();
            section.set("==", "Vector"); section.set("x", value.getX());
            section.set("y", value.getY()); section.set("z", value.getZ()); return section;
        }
    }

    @Test
    void explicitAliasParserWorksOnRawDefaultMergedReloadAndPanelProposal() throws Exception {
        Path path = tempDir.resolve("legacy-vector.yml");
        Files.write(path, vectorYaml(4).getBytes(StandardCharsets.UTF_8));
        com.ultikits.ultitools.manager.ConfigManager manager = new com.ultikits.ultitools.manager.ConfigManager();
        LegacyVector entity = new LegacyVector("legacy-vector.yml"); manager.register(plugin, entity);
        assertThat(entity.vec).isEqualTo(bukkitVector(vectorYaml(4)));
        entity.vec = new org.bukkit.util.Vector(7, 2, 3);
        entity.reload();
        assertThat(entity.vec).as("merged reload retains an unsaved live value").isEqualTo(bukkitVector(vectorYaml(7)));
        Files.write(path, vectorYaml(9).getBytes(StandardCharsets.UTF_8)); entity.reload();
        assertThat(entity.vec).isEqualTo(bukkitVector(vectorYaml(9)));
        manager.loadFromJson("legacy-vector.yml", "{\"vec\":{\"==\":\"Vector\",\"x\":11.0,\"y\":2.0,\"z\":3.0}}");
        assertThat(entity.vec).isEqualTo(bukkitVector(vectorYaml(11)));
        manager.loadFromJson("{\"NumbersModule\":{\"legacy-vector.yml\":{\"vec\":{\"==\":\"Vector\",\"x\":13.0,\"y\":2.0,\"z\":3.0}}}}");
        assertThat(entity.vec).isEqualTo(bukkitVector(vectorYaml(13)));
        Files.write(path, "vec: invalid\n".getBytes(StandardCharsets.UTF_8)); entity.reload();
        assertThat(entity.vec).as("invalid whole value restores the serialized declared default")
                .isEqualTo(bukkitVector(vectorYaml(1)));
    }

    private static String vectorYaml(int x) {
        return "vec: {==: Vector, x: " + x + ".0, y: 2.0, z: 3.0}\n";
    }
    private static org.bukkit.util.Vector bukkitVector(String text) throws Exception {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString(text); return (org.bukkit.util.Vector) yaml.get("vec");
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("NumbersModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
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

    @Test
    @DisplayName("first boot writes the defaults; a second boot from that file loads every boxed numeric field")
    void secondBootFromTheWrittenDefaultsLoads() throws IOException {
        new NumbersConfig(PATH).init(plugin);
        assertThat(file()).exists();

        NumbersConfig secondBoot = new NumbersConfig(PATH);
        assertThatCode(() -> secondBoot.init(plugin)).as("second boot from the written defaults").doesNotThrowAnyException();

        assertThat(secondBoot.boxedLong).isEqualTo(1800L);
        assertThat(secondBoot.plainLong).isEqualTo(60L);
        assertThat(secondBoot.boxedDouble).isEqualTo(2.0D);
        assertThat(secondBoot.boxedInt).isEqualTo(5);
    }

    @Test
    @DisplayName("an operator's whole numbers load into Long and Double fields")
    void wholeNumbersFromTheFileLoadIntoBoxedFields() throws IOException {
        writeFile("limits:\n  boxed-long: 30\n  plain-long: 45\n  boxed-double: 3\n  boxed-int: 6\n");

        NumbersConfig config = new NumbersConfig(PATH);
        assertThatCode(() -> config.init(plugin)).as("boot from operator-written whole numbers").doesNotThrowAnyException();

        assertThat(config.boxedLong).isEqualTo(30L);
        assertThat(config.plainLong).isEqualTo(45L);
        assertThat(config.boxedDouble).isEqualTo(3.0D);
        assertThat(config.boxedInt).isEqualTo(6);
    }

    @Test
    @DisplayName("reload() picks up an edited whole number in a Long field")
    void reloadPicksUpAnEditedLong() throws IOException {
        NumbersConfig config = new NumbersConfig(PATH);
        config.init(plugin);

        writeFile("limits:\n  boxed-long: 900\n  plain-long: 60\n  boxed-double: 2.0\n  boxed-int: 5\n");
        assertThatCode(config::reload).as("reload of an edited whole number").doesNotThrowAnyException();

        assertThat(config.boxedLong).isEqualTo(900L);
    }

    /**
     * Round 2 of gate-1 CR-01: the #510 snapshot re-reads the file into a probe instance through a
     * third read path. Unwidened, it threw for the {@code Long} field, the snapshot was dropped with a
     * "Cannot snapshot" WARNING, and {@code isModifiedSinceSnapshot()} stayed {@code true} -- so the
     * shutdown save would overwrite an operator's edit to the file.
     */
    @Test
    @DisplayName("the #510 snapshot holds for boxed Long and Double fields after first boot, second boot and reload")
    void snapshotHoldsForBoxedNumericFields() throws IOException {
        List<LogRecord> warnings = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
                // Records are appended straight to the in-memory list.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Logger logger = Logger.getLogger(AbstractConfigEntity.class.getName());
        logger.addHandler(capture);
        try {
            NumbersConfig firstBoot = new NumbersConfig(PATH);
            firstBoot.init(plugin);
            assertThat(firstBoot.isModifiedSinceSnapshot()).as("first boot").isFalse();

            NumbersConfig secondBoot = new NumbersConfig(PATH);
            assertThatCode(() -> secondBoot.init(plugin)).doesNotThrowAnyException();
            assertThat(secondBoot.isModifiedSinceSnapshot()).as("second boot").isFalse();

            writeFile("limits:\n  boxed-long: 900\n  plain-long: 60\n  boxed-double: 3\n  boxed-int: 5\n");
            assertThatCode(secondBoot::reload).doesNotThrowAnyException();
            assertThat(secondBoot.isModifiedSinceSnapshot()).as("after reload").isFalse();

            assertThat(warnings).as("no 'Cannot snapshot' or other WARNING from the config layer")
                    .extracting(LogRecord::getMessage).isEmpty();
        } finally {
            logger.removeHandler(capture);
        }
    }

    /**
     * Codex round 3 on #536: the "last init did not complete" marker the config binding reads is kept
     * on the entity (so nothing outside it can retain a discarded entity). It is set by an init whose
     * write-back fails and cleared by the next init that completes.
     */
    @Test
    @DisplayName("isLastInitIncomplete: set by an init whose write-back failed, cleared by the next complete init")
    void lastInitIncompleteTracksTheLastInit() throws IOException {
        NumbersConfig config = new NumbersConfig(PATH);
        config.init(plugin);
        assertThat(config.isLastInitIncomplete()).as("a completed first boot").isFalse();

        writeFile("limits:\n  boxed-long: 30\n");
        // Atomic replacement can replace a read-only target in a writable parent; inject real I/O failure.
        try (org.mockito.MockedStatic<com.ultikits.ultitools.config.document.AtomicConfigWriter> writer =
                Mockito.mockStatic(com.ultikits.ultitools.config.document.AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> com.ultikits.ultitools.config.document.AtomicConfigWriter.write(
                    Mockito.eq(file()), Mockito.anyString())).thenThrow(new IOException("injected write failure"));
            assertThatThrownBy(() -> config.init(plugin)).isInstanceOf(IOException.class);
            assertThat(config.isLastInitIncomplete()).as("after a failed write-back").isTrue();
        }

        config.init(plugin);
        assertThat(config.isLastInitIncomplete()).as("after the next complete init").isFalse();
    }

    /**
     * #526 (maintainer 2026-09-29): a value that does not fit the field is still never narrowed into
     * it, but it no longer throws out of {@code init()} and takes the module down -- the field keeps
     * its declared default and one warning names the key and the value.
     */
    @Test
    @DisplayName("a value too large for an Integer field is not narrowed: the default is kept, with a warning")
    void narrowingIsStillRefused() throws IOException {
        writeFile("small: 3000000000\n");
        NarrowConfig config = new NarrowConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(config.small).isEqualTo(1);
            assertThat(warnings.messagesContaining("'small'")).hasSize(1)
                    .allSatisfy(message -> assertThat(message).contains("3000000000"));
        }
    }
}
