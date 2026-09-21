package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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
 * {@link ConfigManager#saveAll()} has no directory check (#510, gate-1 WR-01). No registered
 * {@code @ConfigEntity} path is a directory; the only way one becomes a directory is being replaced
 * by one while the server runs, and then a changed entity's save must fail loudly with the existing
 * "Configuration save failed" line rather than be skipped without a trace.
 */
@DisplayName("ConfigManager.saveAll logs, never silently skips, a changed config whose file became a directory (#510)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConfigManagerSaveAllReplacedByDirectoryTest {

    private static final String RELATIVE_PATH = "config/ultitools-reborn-510-directory-probe.yml";

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private UltiToolsPlugin plugin;
    private Logger frameworkLogger;

    @ConfigEntity(RELATIVE_PATH)
    public static class ProbeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "value", comment = "Probe value")
        private String value = "default";

        public ProbeConfig(String configFilePath) {
            super(configFilePath);
        }

        void setValue(String value) {
            this.value = value;
        }
    }

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();

        frameworkLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(
                ultiTools -> when(ultiTools.getLogger()).thenReturn(frameworkLogger));

        plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn("DirectoryProbeModule");
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ConfigFileStubs.stubConfigFolder(plugin, tempDir);

        configManager = new ConfigManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private List<String> warnings() {
        ArgumentCaptor<Level> levels = ArgumentCaptor.forClass(Level.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(frameworkLogger, atLeast(0)).log(levels.capture(), messages.capture());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < levels.getAllValues().size(); i++) {
            if (levels.getAllValues().get(i) == Level.WARNING) {
                result.add(messages.getAllValues().get(i));
            }
        }
        return result;
    }

    @Test
    @DisplayName("A changed entity whose file was replaced by a directory is attempted, and the failure is logged")
    void saveAll_logsFailureForEntityWhoseFileBecameDirectory() throws IOException {
        File target = new File(tempDir, RELATIVE_PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), "value: original\n".getBytes(StandardCharsets.UTF_8));
        ProbeConfig config = new ProbeConfig(RELATIVE_PATH);
        configManager.register(plugin, config);

        // The file is replaced by a directory while the server runs, and the entity is changed in memory.
        Files.delete(target.toPath());
        assertThat(target.mkdir()).isTrue();
        config.setValue("set-by-code");

        configManager.saveAll();

        assertThat(target).isDirectory();
        assertThat(warnings()).anyMatch(message -> message.contains("save failed") && message.contains(RELATIVE_PATH));
    }
}
