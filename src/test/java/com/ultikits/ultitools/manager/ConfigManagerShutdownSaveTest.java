package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Shutdown persistence contract for {@link ConfigManager#saveAll()} (#510).
 * <p>
 * {@code UltiTools#onDisable()} calls {@code saveAll()}. Before #510 it rewrote every registered
 * configuration file from memory, silently discarding any edit an operator made to a file while
 * the server was running. Since #510 each entity keeps a serialized snapshot of the state it last
 * loaded or saved, and shutdown writes only the entities whose current state differs from it.
 * <p>
 * Every test that asserts "the file was written" also proves, in the same run, that
 * {@code saveAll()} is selective (an untouched sibling keeps its operator edit, or the written
 * value differs from what an unconditional write would produce); otherwise "it was saved" would
 * pass vacuously against the pre-#510 unconditional implementation.
 */
@DisplayName("ConfigManager shutdown save only writes configurations changed in memory (#510)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConfigManagerShutdownSaveTest {

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private UltiToolsPlugin plugin;
    private Logger frameworkLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();

        frameworkLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(
                ultiTools -> when(ultiTools.getLogger()).thenReturn(frameworkLogger));

        plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn("ShutdownSaveTestModule");
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ConfigFileStubs.stubConfigFolder(plugin, tempDir);

        configManager = new ConfigManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // ==================== Fixtures ====================

    /** A plain scalar configuration, like UltiCleaner's {@code item.interval}. */
    @ConfigEntity("config/scalar.yml")
    public static class ScalarConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "value", comment = "A scalar value")
        private String value = "default";

        public ScalarConfig(String configFilePath) {
            super(configFilePath);
        }

        String getValue() {
            return value;
        }

        void setValue(String value) {
            this.value = value;
        }
    }

    /** A second scalar configuration, used as the untouched control sibling. */
    @ConfigEntity("config/control.yml")
    public static class ControlConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "value", comment = "A control value")
        private String value = "control-default";

        public ControlConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A map-typed configuration mutated in place, like UltiChat's auto-reply {@code rules}. */
    @ConfigEntity("config/rules.yml")
    public static class RulesConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "rules", comment = "Keyword rules")
        private Map<String, String> rules = new LinkedHashMap<>();

        public RulesConfig(String configFilePath) {
            super(configFilePath);
        }

        Map<String, String> getRules() {
            return rules;
        }
    }

    /** Two independent keys, so a partial write or a partial file can leave one of them out. */
    @ConfigEntity("config/two.yml")
    public static class TwoKeyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "a", comment = "First")
        private String a = "a-default";

        @ConfigEntry(path = "b", comment = "Second")
        private String b = "b-default";

        public TwoKeyConfig(String configFilePath) {
            super(configFilePath);
        }

        void setA(String a) {
            this.a = a;
        }
    }

    /** A list-typed configuration whose on-disk integers come back from the parser as strings. */
    @ConfigEntity("config/list.yml")
    public static class ListConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "ids", comment = "Some ids")
        private List<Integer> ids = new ArrayList<>();

        public ListConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    // ==================== Helpers ====================

    private File file(String relativePath) {
        return new File(tempDir, relativePath);
    }

    private static void write(File target, String content) throws IOException {
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File target) throws IOException {
        return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
    }

    private List<String> loggedMessages(Level level) {
        ArgumentCaptor<Level> levels = ArgumentCaptor.forClass(Level.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        // atLeast(0) never fails; it only collects every log(Level, String) call made so far.
        Mockito.verify(frameworkLogger, atLeast(0)).log(levels.capture(), messages.capture());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < levels.getAllValues().size(); i++) {
            if (levels.getAllValues().get(i) == level) {
                result.add(messages.getAllValues().get(i));
            }
        }
        return result;
    }

    private List<String> overwriteWarnings() {
        List<String> result = new ArrayList<>();
        for (String message : loggedMessages(Level.WARNING)) {
            if (message.contains("overwritten")) {
                result.add(message);
            }
        }
        return result;
    }

    // ==================== 1. operator edit survives ====================

    @Test
    @DisplayName("1. A file edited on disk while its entity is untouched keeps the operator's edit")
    void saveAll_keepsOperatorEditOfUntouchedConfig() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "# A scalar value\nvalue: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        String operatorEdit = "# operator comment\nvalue: operator-edit\n";
        write(scalarFile, operatorEdit);
        // Guard: memory still holds a different value, so any write would change the bytes.
        assertThat(config.getValue()).isEqualTo("original");

        configManager.saveAll();

        assertThat(read(scalarFile)).isEqualTo(operatorEdit);
    }

    // ==================== 2. in-place collection mutation ====================

    @Test
    @DisplayName("2. An in-place map mutation is detected as a change and saved at shutdown")
    void saveAll_savesInPlaceMapMutation() throws IOException {
        File rulesFile = file("config/rules.yml");
        write(rulesFile, "rules:\n  hello: world\n");
        File controlFile = file("config/control.yml");
        write(controlFile, "value: control-original\n");
        RulesConfig rules = new RulesConfig("config/rules.yml");
        ControlConfig control = new ControlConfig("config/control.yml");
        configManager.register(plugin, rules);
        configManager.register(plugin, control);

        rules.getRules().put("added", "by-command");
        String controlEdit = "value: control-operator-edit\n";
        write(controlFile, controlEdit);

        configManager.saveAll();

        assertThat(read(rulesFile)).contains("added: by-command").contains("hello: world");
        // Selectivity guard: the untouched sibling was not rewritten.
        assertThat(read(controlFile)).isEqualTo(controlEdit);
    }

    // ==================== 3. snapshot refreshed after explicit save ====================

    @Test
    @DisplayName("3. After an explicit save() shutdown does not write again, and a later operator edit survives")
    void saveAll_afterExplicitSaveKeepsLaterOperatorEdit() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        config.setValue("set-by-code");
        config.save();
        assertThat(read(scalarFile)).contains("value: set-by-code");

        String operatorEdit = "value: operator-after-save\n";
        write(scalarFile, operatorEdit);

        configManager.saveAll();

        assertThat(read(scalarFile)).isEqualTo(operatorEdit);
        assertThat(overwriteWarnings()).isEmpty();
    }

    @Test
    @DisplayName("3b. After a panel write (updateProperties) shutdown does not write again")
    void saveAll_afterPanelWriteKeepsLaterOperatorEdit() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        configManager.loadFromJson("config/scalar.yml", "{\"value\":\"set-by-panel\"}");
        assertThat(read(scalarFile)).contains("value: set-by-panel");

        String operatorEdit = "value: operator-after-panel\n";
        write(scalarFile, operatorEdit);

        configManager.saveAll();

        assertThat(read(scalarFile)).isEqualTo(operatorEdit);
    }

    // ==================== 4. snapshot refreshed after reload ====================

    @Test
    @DisplayName("4. After reload() shutdown does not write back the reloaded state")
    void saveAll_afterReloadKeepsLaterOperatorEdit() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        write(scalarFile, "value: reloaded\n");
        config.reload();
        assertThat(config.getValue()).isEqualTo("reloaded");

        String operatorEdit = "value: operator-after-reload\n";
        write(scalarFile, operatorEdit);

        configManager.saveAll();

        assertThat(read(scalarFile)).isEqualTo(operatorEdit);
    }

    @Test
    @DisplayName("4b. After ConfigManager.reloadConfigs() shutdown does not write back the reloaded state")
    void saveAll_afterReloadConfigsKeepsLaterOperatorEdit() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        write(scalarFile, "value: reloaded\n");
        configManager.reloadConfigs(plugin);
        assertThat(config.getValue()).isEqualTo("reloaded");

        String operatorEdit = "value: operator-after-reload\n";
        write(scalarFile, operatorEdit);

        configManager.saveAll();

        assertThat(read(scalarFile)).isEqualTo(operatorEdit);
    }

    // ==================== 5. failed save stays dirty ====================

    @Test
    @DisplayName("5. A failed save leaves the entity dirty, so shutdown retries it")
    void saveAll_retriesChangeWhoseExplicitSaveFailed() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        File controlFile = file("config/control.yml");
        write(controlFile, "value: control-original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        ControlConfig control = new ControlConfig("config/control.yml");
        configManager.register(plugin, config);
        configManager.register(plugin, control);

        config.setValue("set-by-code");
        assumeThat(scalarFile.setWritable(false)).as("file permissions are enforceable here").isTrue();
        try {
            assumeThat(scalarFile.canWrite()).as("not running with permission-bypassing privileges").isFalse();
            assertThatThrownBy(config::save).isInstanceOf(IOException.class);
        } finally {
            assertThat(scalarFile.setWritable(true)).isTrue();
        }
        assertThat(read(scalarFile)).isEqualTo("value: original\n");
        String controlEdit = "value: control-operator-edit\n";
        write(controlFile, controlEdit);

        configManager.saveAll();

        assertThat(read(scalarFile)).contains("value: set-by-code");
        // Selectivity guard: the untouched sibling was not rewritten.
        assertThat(read(controlFile)).isEqualTo(controlEdit);
        // The failed save changed nothing on disk, so there is nothing to warn about.
        assertThat(overwriteWarnings()).isEmpty();
    }

    // ==================== 6. dirty entity + externally changed file ====================

    @Test
    @DisplayName("6. A dirty entity whose file was also edited on disk is saved and a WARNING names the file")
    void saveAll_warnsWhenInMemoryChangeOverwritesOperatorEdit() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        config.setValue("set-by-code");
        write(scalarFile, "value: operator-edit\n");

        configManager.saveAll();

        assertThat(read(scalarFile)).contains("value: set-by-code");
        List<String> warnings = overwriteWarnings();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains(scalarFile.getAbsolutePath());
    }

    @Test
    @DisplayName("6b. A dirty entity whose file was not edited on disk is saved without a WARNING")
    void saveAll_savesWithoutWarningWhenFileUnchanged() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        config.setValue("set-by-code");

        configManager.saveAll();

        assertThat(read(scalarFile)).contains("value: set-by-code");
        assertThat(overwriteWarnings()).isEmpty();
    }

    // ==================== 7. first boot ====================

    @Test
    @DisplayName("7. First boot writes defaults at init() and shutdown does not rewrite the file")
    void saveAll_afterFirstBootDefaultsKeepsOperatorEdit() throws IOException {
        File scalarFile = file("config/scalar.yml");
        assertThat(scalarFile).doesNotExist();
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);
        assertThat(read(scalarFile)).contains("value: default");

        String operatorEdit = "value: operator-after-first-boot\n";
        write(scalarFile, operatorEdit);

        configManager.saveAll();

        assertThat(read(scalarFile)).isEqualTo(operatorEdit);
    }

    @Test
    @DisplayName("7b. A first-boot defaults write is part of the snapshot, so a later code change saves without a WARNING")
    void saveAll_afterFirstBootDefaultsSavesCodeChangeWithoutWarning() throws IOException {
        File scalarFile = file("config/scalar.yml");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        config.setValue("set-by-code");

        configManager.saveAll();

        assertThat(read(scalarFile)).contains("value: set-by-code");
        assertThat(overwriteWarnings()).isEmpty();
    }

    // ==================== 8. parsed-type quirks do not look like changes ====================

    @Test
    @DisplayName("8. Integers in a list, which the parser reads back as strings, do not make an untouched entity dirty")
    void saveAll_doesNotRewriteUntouchedIntegerList() throws IOException {
        File listFile = file("config/list.yml");
        String onDisk = "ids:\n- 60\n- 70\n";
        write(listFile, onDisk);
        ListConfig config = new ListConfig("config/list.yml");
        configManager.register(plugin, config);

        configManager.saveAll();

        assertThat(read(listFile)).isEqualTo(onDisk);
    }

    // ==================== 9. explicit save() stays unconditional ====================

    @Test
    @DisplayName("9. An explicit save() still writes unconditionally, even for an untouched entity")
    void save_explicitCallStillWritesUntouchedEntity() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        write(scalarFile, "value: operator-edit\n");
        config.save();

        assertThat(read(scalarFile)).contains("value: original").doesNotContain("operator-edit");
    }

    // ==================== 10. partial writes never absorb an unsaved change (gate-1 BL-01) ====================

    @Test
    @DisplayName("10. A panel write of one key does not mark another key's unsaved code change as saved")
    void saveAll_keepsCodeChangeAcrossPartialPanelWrite() throws IOException {
        File twoFile = file("config/two.yml");
        write(twoFile, "a: a1\nb: b1\n");
        TwoKeyConfig config = new TwoKeyConfig("config/two.yml");
        configManager.register(plugin, config);

        config.setA("a-by-code");
        configManager.loadFromJson("config/two.yml", "{\"b\":\"b-by-panel\"}");
        // Guard: the panel write did not itself write the code change.
        assertThat(read(twoFile)).contains("a: a1").contains("b: b-by-panel");

        configManager.saveAll();

        assertThat(read(twoFile)).contains("a: a-by-code").contains("b: b-by-panel");
    }

    @Test
    @DisplayName("10b. A reload of a file missing a key does not mark that key's unsaved code change as saved")
    void saveAll_keepsCodeChangeWhenReloadFindsKeyMissing() throws IOException {
        File twoFile = file("config/two.yml");
        write(twoFile, "a: a1\nb: b1\n");
        TwoKeyConfig config = new TwoKeyConfig("config/two.yml");
        configManager.register(plugin, config);

        config.setA("a-by-code");
        write(twoFile, "b: b1\n");
        config.reload();
        // Guard: reload() kept the in-memory value for the absent key and wrote nothing.
        assertThat(read(twoFile)).isEqualTo("b: b1\n");

        configManager.saveAll();

        assertThat(read(twoFile)).contains("a: a-by-code").contains("b: b1");
    }

    // ==================== 11. the comparison never touches the live configuration ====================

    @Test
    @DisplayName("11. Checking for changes and the shutdown save leave the panel-visible configuration unchanged")
    void saveAll_leavesPanelVisibleConfigurationUnchanged() throws IOException {
        File listFile = file("config/list.yml");
        write(listFile, "ids:\n- 60\n- 70\n");
        ListConfig config = new ListConfig("config/list.yml");
        configManager.register(plugin, config);
        String before = config.toJsonObject().toString();
        // Guard: the panel sees numbers, which a parser-serialized render would turn into strings.
        assertThat(before).contains("[60,70]");

        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        configManager.saveAll();

        assertThat(config.toJsonObject().toString()).isEqualTo(before);
    }

    // ==================== 12. a failed shutdown save claims no overwrite ====================

    @Test
    @DisplayName("12. A shutdown save that fails logs the failure and no 'overwritten' WARNING")
    void saveAll_failedSaveLogsNoOverwriteWarning() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        configManager.register(plugin, config);

        config.setValue("set-by-code");
        write(scalarFile, "value: operator-edit\n");
        assumeThat(scalarFile.setWritable(false)).as("file permissions are enforceable here").isTrue();
        try {
            assumeThat(scalarFile.canWrite()).as("not running with permission-bypassing privileges").isFalse();
            configManager.saveAll();
        } finally {
            assertThat(scalarFile.setWritable(true)).isTrue();
        }

        assertThat(read(scalarFile)).isEqualTo("value: operator-edit\n");
        assertThat(overwriteWarnings()).isEmpty();
        assertThat(loggedMessages(Level.WARNING)).anyMatch(message -> message.contains("save failed"));
    }

    // ==================== 13. a change made by a reload listener ====================

    @Test
    @DisplayName("13. A change a config listener makes in memory while the entity loads is saved at shutdown")
    void saveAll_savesChangeMadeByChangeListener() throws IOException {
        File scalarFile = file("config/scalar.yml");
        write(scalarFile, "value: original\n");
        ScalarConfig config = new ScalarConfig("config/scalar.yml");
        config.addChangeListener(changed -> ((ScalarConfig) changed).setValue("set-by-listener"));
        configManager.register(plugin, config);
        // Guard: the listener ran and changed memory only.
        assertThat(config.getValue()).isEqualTo("set-by-listener");
        assertThat(read(scalarFile)).isEqualTo("value: original\n");

        configManager.saveAll();

        assertThat(read(scalarFile)).contains("value: set-by-listener");
    }
}
