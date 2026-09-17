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
 * {@link ConfigManager#saveAll()} skips an entity whose path names a directory. The check must
 * resolve the entity's relative {@code configFilePath} against the module's configuration folder,
 * where {@code save()} writes, not against the JVM working directory (the server root), where a
 * module-relative path such as {@code config/homes} never exists (#510 sweep finding).
 */
@DisplayName("ConfigManager.saveAll resolves its directory check against the module config folder (#510)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConfigManagerSaveAllDirectoryResolutionTest {

    private static final String RELATIVE_PATH = "config/ultitools-reborn-510-directory-probe";

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
    @DisplayName("A changed entity whose path is a directory under the module config folder is skipped, not written")
    void saveAll_skipsEntityWhosePathIsDirectoryInModuleFolder() throws IOException {
        File target = new File(tempDir, RELATIVE_PATH);
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), "value: original\n".getBytes(StandardCharsets.UTF_8));
        ProbeConfig config = new ProbeConfig(RELATIVE_PATH);
        configManager.register(plugin, config);

        // The path becomes a directory after registration, and the entity is changed in memory.
        Files.delete(target.toPath());
        assertThat(target.mkdir()).isTrue();
        config.setValue("set-by-code");

        // Guard: the same relative path does not exist under the working directory, so a check
        // resolved there cannot tell that this entity's path is a directory.
        assertThat(new File(RELATIVE_PATH)).doesNotExist();

        configManager.saveAll();

        assertThat(target).isDirectory();
        assertThat(warnings()).noneMatch(message -> message.contains("save failed"));
    }
}
