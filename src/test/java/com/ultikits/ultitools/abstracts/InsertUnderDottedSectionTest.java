package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

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

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #614 (plan 17-75 Task 2; maintainer decision 2026-10-06, row 01:08): a missing setting whose section the operator wrote
 * in flat dotted form ({@code a.b:} holding {@code c}) is inserted under that existing section ({@code d} under
 * {@code a.b:}), never as a second, nested copy of the section - which Bukkit's {@code YamlConfiguration} reads as
 * replacing the flat one, so a module that reads its own file with Bukkit lost the operator's values. Rule: the insert
 * goes under the longest prefix of the setting path that the file holds as a section; when the file holds that prefix in
 * several forms, under the one that holds another declared setting of the section; when none or several do, the insert
 * is refused naming the file and the setting.
 */
class InsertUnderDottedSectionTest {

    private static final String PATH = "dotted.yml";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
    private final Logger frameworkLogger = Logger.getLogger("com.ultikits.ultitools");
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage() != null) {
                warnings.add(record.getMessage());
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
    public static class Dotted extends AbstractConfigEntity {
        @ConfigEntry(path = "a.b.c", comment = "C")
        int c = 1;
        @ConfigEntry(path = "a.b.d", comment = "D")
        int d = 2;

        public Dotted(String path) {
            super(path);
        }
    }

    @ConfigEntity(PATH)
    public static class Deep extends AbstractConfigEntity {
        @ConfigEntry(path = "a.b.x", comment = "X")
        int x = 1;
        @ConfigEntry(path = "a.b.c.d", comment = "Deep")
        int deep = 4;

        public Deep(String path) {
            super(path);
        }
    }

    @ConfigEntity(PATH)
    public static class MixedDeep extends AbstractConfigEntity {
        @ConfigEntry(path = "a.b.c.x", comment = "X")
        int x = 1;
        @ConfigEntry(path = "a.b.c.y", comment = "Y")
        int y = 5;

        public MixedDeep(String path) {
            super(path);
        }
    }

    @ConfigEntity(PATH)
    public static class Three extends AbstractConfigEntity {
        @ConfigEntry(path = "a.b.c", comment = "C")
        int c = 1;
        @ConfigEntry(path = "a.b.e", comment = "E")
        int e = 3;
        @ConfigEntry(path = "a.b.d", comment = "D")
        int d = 2;

        public Three(String path) {
            super(path);
        }
    }

    @ConfigEntity(PATH)
    public static class Rules extends AbstractConfigEntity {
        @ConfigEntry(path = "a.b.rules", comment = "Rules")
        Map<String, String> rules = new LinkedHashMap<>();

        public Rules(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Logger moduleLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(u -> Mockito.when(u.getLogger()).thenReturn(moduleLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DottedModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        lenient().when(plugin.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        frameworkLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        frameworkLogger.removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private void write(String text) throws IOException {
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    private List<String> warningsNamingTheFile() {
        List<String> result = new ArrayList<>();
        synchronized (warnings) {
            for (String warning : warnings) {
                if (warning.contains(PATH)) {
                    result.add(warning);
                }
            }
        }
        return result;
    }

    @Test
    void aMissingSettingGoesUnderTheFlatSectionTheOperatorWrote() throws Exception {
        write("# hand-written\na.b:\n  c: 3\n");

        Dotted config = new Dotted(PATH);
        config.init(plugin);

        String inserted = text();
        assertThat(inserted).isEqualTo("# hand-written\na.b:\n  c: 3\n  # D\n  d: 2\n");
        YamlConfiguration bukkit = YamlConfiguration.loadConfiguration(file().toFile());
        assertThat(bukkit.getInt("a.b.c")).as("Bukkit still reads the operator's value").isEqualTo(3);
        assertThat(bukkit.getInt("a.b.d")).isEqualTo(2);
        assertThat(config.c).isEqualTo(3);
        assertThat(warningsNamingTheFile()).isEmpty();

        new Dotted(PATH).init(plugin);
        assertThat(text()).as("a second start changes nothing").isEqualTo(inserted);
        assertThat(warningsNamingTheFile()).isEmpty();
    }

    @Test
    void theLongestHeldPrefixWinsAndTheRestIsNested() throws Exception {
        write("a.b:\n  x: 1\n");

        new Deep(PATH).init(plugin);

        assertThat(text()).isEqualTo("a.b:\n  x: 1\n  c:\n    # Deep\n    d: 4\n");
        assertThat(YamlConfiguration.loadConfiguration(file().toFile()).getInt("a.b.x")).isEqualTo(1);
        assertThat(warningsNamingTheFile()).isEmpty();
    }

    @Test
    void withBothFormsTheInsertGoesUnderTheOneHoldingAnotherSettingOfTheSection() throws Exception {
        write("a.b:\n  c: 3\na:\n  b:\n    e: 1\n");

        new Dotted(PATH).init(plugin);

        assertThat(text()).isEqualTo("a.b:\n  c: 3\n  # D\n  d: 2\na:\n  b:\n    e: 1\n");
        assertThat(warningsNamingTheFile()).isEmpty();
    }

    @Test
    void whenNeitherFormHoldsAnotherSettingTheInsertIsRefusedNamingTheFileAndTheSetting() throws Exception {
        byte[] before = "a.b:\n  x: 1\na:\n  b:\n    y: 2\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), before);

        Dotted config = new Dotted(PATH);
        config.init(plugin);

        assertThat(Files.readAllBytes(file())).as("nothing is written").isEqualTo(before);
        assertThat(config.c).isEqualTo(1);
        assertThat(config.d).isEqualTo(2);
        List<String> named = warningsNamingTheFile();
        assertThat(named).hasSize(1);
        assertThat(named.get(0)).contains(file().toAbsolutePath().toString())
                .contains("'a.b.c'").contains("'a.b.d'").contains("section 'a.b' is written in several forms");
    }

    @Test
    void anOperatorChangeOfASettingDeletedUnderAFlatSectionIsWrittenThere() throws Exception {
        write("a.b:\n  c: 3\n  d: 5\n");
        Dotted config = new Dotted(PATH);
        config.init(plugin);
        write("a.b:\n  c: 3\n");

        config.d = 9;
        config.saveOperatorChange("a.b.d");

        assertThat(text()).isEqualTo("a.b:\n  c: 3\n  # D\n  d: 9\n");
    }

    @Test
    void anOperatorChangeWhoseSectionIsHeldInSeveralFormsIsRefused() throws Exception {
        write("a.b:\n  c: 3\n  d: 5\n");
        Dotted config = new Dotted(PATH);
        config.init(plugin);
        byte[] split = "a.b:\n  x: 1\na:\n  b:\n    c: 3\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), split);
        // The operator moved c into a second, nested form of the section; d now has two candidate sections, each holding
        // a key, one of them the declared c - so d goes there.
        config.d = 9;
        config.saveOperatorChange("a.b.d");
        assertThat(text()).isEqualTo("a.b:\n  x: 1\na:\n  b:\n    c: 3\n    # D\n    d: 9\n");

        byte[] neither = "a.b:\n  x: 1\na:\n  b:\n    y: 2\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), neither);
        config.d = 10;
        assertThatThrownBy(() -> config.saveOperatorChange("a.b.d")).isInstanceOf(ConfigWriteRefusedException.class)
                .hasMessageContaining("'a.b.d'").hasMessageContaining("section 'a.b' is written in several forms");
        assertThat(Files.readAllBytes(file())).isEqualTo(neither);
    }

    // ---------------------------------------------------------------------- gate-1 F4: the mixed form (maintainer decision)

    /**
     * Gate-1 F4 of plan 17-75, decided by the maintainer: when a sibling setting is written with the rest of its path as one
     * dotted key below the held section ({@code a:} / {@code b.c: 3}), the missing setting is inserted the same way beside it
     * ({@code b.d: 2}), never as a nested {@code b:} / {@code d:} second form of {@code a.b}, which Bukkit reads as shadowing
     * {@code a.b.c}. Same rule as #614: never write a second form of a section.
     */
    @Test
    void aMissingSettingMirrorsASiblingWrittenAsADottedKeyBelowTheSection() throws Exception {
        write("# hand-written\na:\n  b.c: 3\n");

        Dotted config = new Dotted(PATH);
        config.init(plugin);

        String inserted = text();
        assertThat(inserted).isEqualTo("# hand-written\na:\n  b.c: 3\n  # D\n  b.d: 2\n");
        YamlConfiguration bukkit = YamlConfiguration.loadConfiguration(file().toFile());
        assertThat(bukkit.getInt("a.b.c")).as("Bukkit still reads the operator's value").isEqualTo(3);
        assertThat(bukkit.getInt("a.b.d")).isEqualTo(2);
        assertThat(config.c).isEqualTo(3);
        assertThat(warningsNamingTheFile()).isEmpty();

        new Dotted(PATH).init(plugin);
        assertThat(text()).as("a second start changes nothing").isEqualTo(inserted);
        assertThat(warningsNamingTheFile()).isEmpty();
    }

    @Test
    void theMirrorKeepsTheSiblingsSplitAtAnyDepth() throws Exception {
        write("a:\n  b.c.x: 1\n");

        new MixedDeep(PATH).init(plugin);

        assertThat(text()).isEqualTo("a:\n  b.c.x: 1\n  # Y\n  b.c.y: 5\n");
        YamlConfiguration bukkit = YamlConfiguration.loadConfiguration(file().toFile());
        assertThat(bukkit.getInt("a.b.c.x")).isEqualTo(1);
        assertThat(bukkit.getInt("a.b.c.y")).isEqualTo(5);
        assertThat(warningsNamingTheFile()).isEmpty();
    }

    @Test
    void siblingsWrittenInDifferentSplitsAreRefusedNamingTheFileAndTheSetting() throws Exception {
        byte[] before = "a:\n  b.c: 1\na.b.e: 2\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), before);

        Three config = new Three(PATH);
        config.init(plugin);

        assertThat(Files.readAllBytes(file())).as("nothing is written").isEqualTo(before);
        assertThat(config.d).isEqualTo(2);
        List<String> named = warningsNamingTheFile();
        assertThat(named).hasSize(1);
        assertThat(named.get(0)).contains("'a.b.d'").contains("section 'a.b' is written in several forms");
    }

    @Test
    void anOperatorChangeOfASettingDeletedBesideAMixedSiblingIsWrittenThere() throws Exception {
        write("a:\n  b.c: 3\n  b.d: 5\n");
        Dotted config = new Dotted(PATH);
        config.init(plugin);
        write("a:\n  b.c: 3\n");

        config.d = 9;
        config.saveOperatorChange("a.b.d");

        assertThat(text()).isEqualTo("a:\n  b.c: 3\n  # D\n  b.d: 9\n");
    }

    /**
     * Gate 2, local Codex run 1 (plan 17-75, P2): an undecidable insert location refuses only a change that writes a value.
     * Removing an entry of a map setting the operator has deleted by hand - with its section held in two forms and no
     * declared sibling to choose by - writes nothing and completes, as it did before #614.
     */
    @Test
    void removingAnEntryOfASettingDeletedByHandIsNotRefusedForAnUndecidableLocation() throws Exception {
        write("a.b:\n  rules:\n    greeting: hi\n");
        Rules config = new Rules(PATH);
        config.init(plugin);
        byte[] split = "a.b:\n  x: 1\na:\n  b:\n    y: 2\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), split);

        config.rules.remove("greeting");
        config.saveOperatorMapEntry("a.b.rules", "greeting");

        assertThat(Files.readAllBytes(file())).as("nothing to remove, nothing written").isEqualTo(split);
    }
}
