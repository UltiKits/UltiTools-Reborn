package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #589: {@link AbstractConfigEntity#reload()} of a file that cannot be read or parsed tells its
 * caller. Memory and the file stay exactly as they were (the existing all-or-nothing rollback), the
 * file stays protected from writes, and the reload throws {@link ConfigurationException} naming the
 * file and the safe cause, so {@code /ul reload <module>} reports the module as not reloaded instead
 * of a plain success. Initial load keeps its protection unchanged (log, declared defaults, never
 * overwrite; see {@code ConfigUnreadableFileTest}).
 */
@DisplayName("A reload of an unreadable or unparseable config file tells its caller (#589)")
class ConfigReloadSignalTest {
    private static final String PATH = "config/signal.yml";
    private static final String GOOD = "limit: 25\nname: running\n";

    @TempDir Path tempDir;
    private UltiToolsPlugin plugin;

    @ConfigEntity(PATH)
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "limit") int limit = 10;
        @ConfigEntry(path = "name") String name = "default";
        public Values(String path) { super(path); }
    }

    @BeforeEach
    void setUp() {
        plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("SignalModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private Path file() { return tempDir.resolve(PATH); }

    private void put(String text) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
    }

    private Values loaded() throws IOException {
        put(GOOD);
        Values config = new Values(PATH);
        config.init(plugin);
        assertThat(config.limit).isEqualTo(25);
        return config;
    }

    @Test
    @DisplayName("invalid YAML: reload throws naming the file and the location, memory and file unchanged")
    void invalidYamlReloadThrowsAndChangesNothing() throws Exception {
        Values config = loaded();
        config.limit = 35; // an unsaved in-memory change the failed reload must not touch
        AtomicInteger notified = new AtomicInteger();
        config.addChangeListener(changed -> notified.incrementAndGet());
        String broken = "name: secret-specimen\nlimit: [broken\n";
        put(broken);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatThrownBy(config::reload)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(PATH)
                    .hasMessageContaining("invalid YAML at line")
                    .hasMessageNotContaining("secret-specimen")
                    .hasMessageNotContaining("[broken")
                    .satisfies(failure -> assertThat(((ConfigurationException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.CONFIG_PARSE_FAILED));
            // The caller reports it; the entity does not log a second line for the same failure.
            assertThat(warnings.messagesContaining(PATH)).isEmpty();
        }

        assertThat(config.limit).isEqualTo(35);
        assertThat(config.name).isEqualTo("running");
        assertThat(config.isPresentInFile("limit")).isFalse();
        assertThat(notified).hasValue(0);
        assertThat(config.isLastLoadUnparseable()).as("the file stays protected until a successful load").isTrue();
        config.save();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).isEqualTo(broken);
    }

    @Test
    @DisplayName("unreadable file: reload throws naming the file and the cause class, memory unchanged")
    void unreadableReloadThrowsAndChangesNothing() throws Exception {
        Values config = loaded();
        // A directory deterministically makes the read fail, independent of chmod or running as root.
        Files.delete(file());
        Files.createDirectory(file());

        assertThatThrownBy(config::reload)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(PATH)
                .hasMessageContaining("IOException")
                .hasCauseInstanceOf(IOException.class)
                .satisfies(failure -> assertThat(((ConfigurationException) failure).getErrorCode())
                        .isEqualTo(ErrorCode.CONFIG_LOAD_FAILED));
        assertThat(config.limit).isEqualTo(25);
        assertThat(config.name).isEqualTo("running");
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        assertThat(Files.isDirectory(file())).isTrue();
    }

    @Test
    @DisplayName("after the operator fixes the file, the next reload succeeds and adopts it")
    void reloadRecoversOnceTheFileIsFixed() throws Exception {
        Values config = loaded();
        AtomicInteger notified = new AtomicInteger();
        config.addChangeListener(changed -> notified.incrementAndGet());
        put("limit: [broken\n");
        assertThatThrownBy(config::reload).isInstanceOf(ConfigurationException.class);

        put("limit: 40\nname: fixed\n");
        config.reload();

        assertThat(config.limit).isEqualTo(40);
        assertThat(config.name).isEqualTo("fixed");
        assertThat(config.isLastLoadUnparseable()).isFalse();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        assertThat(notified).hasValue(1);
    }

    @Test
    @DisplayName("initial load is unchanged: one SEVERE line, declared defaults, no exception")
    void initialLoadKeepsItsProtection() throws Exception {
        put("limit: [broken\n");
        Values config = new Values(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(warnings.messagesContaining(PATH)).hasSize(1)
                    .allSatisfy(line -> assertThat(line).contains("will not be overwritten"));
        }
        assertThat(config.limit).isEqualTo(10);
        assertThat(config.isLastLoadUnparseable()).isTrue();
    }

    @Test
    @DisplayName("ConfigManager.reloadConfigs lets the failure through instead of logging and returning")
    void managerReloadPropagatesTheFailure() throws Exception {
        ConfigManager manager = new ConfigManager();
        put(GOOD);
        Values config = new Values(PATH);
        manager.register(plugin, config);
        put("limit: [broken\n");

        assertThatThrownBy(() -> manager.reloadConfigs(plugin))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(PATH);
        assertThat(config.limit).isEqualTo(25);
    }

    @Test
    @DisplayName("an IOException from an entity's reload is reported as a ConfigurationException naming the file")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // seeds the manager's private registry with a failing entity
    void managerReloadReportsAnIOExceptionNamingTheFile() throws Exception {
        ConfigManager manager = new ConfigManager();
        AbstractConfigEntity failing = mock(AbstractConfigEntity.class);
        when(failing.getConfigFilePath()).thenReturn("config/disk.yml");
        Mockito.doThrow(new IOException("disk gone")).when(failing).reload();
        Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UltiToolsPlugin, java.util.Map<String, AbstractConfigEntity>> map =
                (java.util.Map<UltiToolsPlugin, java.util.Map<String, AbstractConfigEntity>>) mapField.get(manager);
        java.util.Map<String, AbstractConfigEntity> entities = new java.util.LinkedHashMap<>();
        entities.put("config/disk.yml", failing);
        map.put(plugin, entities);

        assertThatThrownBy(() -> manager.reloadConfigs(plugin))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("config/disk.yml")
                .hasMessageContaining("disk gone")
                .hasCauseInstanceOf(IOException.class);
    }

    /** A module overriding the original reload hook, as most modules do. */
    abstract static class HookPlugin extends UltiToolsPlugin {
        @Override protected void onReload() { /* verified not to run */ }
    }

    /** The module-level path: {@code reloadWithReport()} is what {@code /ul reload <name>} calls. */
    @DisplayName("module reload")
    @org.junit.jupiter.api.Nested
    class ModuleReload {
        private final List<LogRecord> logs = new ArrayList<>();
        private final Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() { /* records go straight to the list */ }
            @Override public void close() { /* records outlive the handler on purpose */ }
        };
        private ConfigManager manager;

        @BeforeEach
        void install() {
            manager = new ConfigManager();
            TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(manager));
            Logger.getLogger(UltiToolsPlugin.class.getName()).addHandler(capture);
        }

        @AfterEach
        void uninstall() {
            Logger.getLogger(UltiToolsPlugin.class.getName()).removeHandler(capture);
        }

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // same idiom as UltiToolsPluginPartialReloadTest
        private HookPlugin module() throws Exception {
            HookPlugin module = mock(HookPlugin.class);
            when(module.getPluginName()).thenReturn("SignalModule");
            when(module.getLogger()).thenReturn(mock(PluginLogger.class));
            lenient().when(module.getResourceFolderPath()).thenReturn(tempDir.toString());
            lenient().when(module.getConfigFolder()).thenReturn(tempDir.toString());
            lenient().when(module.getConfigFile(anyString())).thenAnswer(
                    invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
            Field resourceFolderPath = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
            resourceFolderPath.setAccessible(true);
            resourceFolderPath.set(module, System.getProperty("java.io.tmpdir"));
            doCallRealMethod().when(module).reloadSelf();
            doCallRealMethod().when(module).reloadWithReport();
            doCallRealMethod().when(module).onReload(any(ReloadReport.class));
            return module;
        }

        @Test
        @DisplayName("a broken file fails the module's reload with one SEVERE line naming the module and the file")
        void brokenFileFailsTheModuleReload() throws Exception {
            HookPlugin module = module();
            put(GOOD);
            Values config = new Values(PATH);
            manager.register(module, config);
            put("limit: [broken\n");

            assertThatThrownBy(module::reloadWithReport)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(PATH);

            assertThat(logs).filteredOn(record -> record.getLevel() == Level.INFO).isEmpty();
            assertThat(logs).filteredOn(record -> record.getLevel() == Level.SEVERE).hasSize(1)
                    .allSatisfy(record -> assertThat(record.getMessage()).contains("SignalModule", PATH));
            assertThat(config.limit).isEqualTo(25);
            verify(module, never()).onReload();
        }
    }
}
