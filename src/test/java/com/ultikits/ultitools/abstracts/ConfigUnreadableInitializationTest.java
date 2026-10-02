package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/** Real unreadable regular-file registration contract (#470), retrospectively proven against the integration parent. */
class ConfigUnreadableInitializationTest {
    private static final String PATH = "unreadable.yml";
    @TempDir Path tempDir;
    private UltiToolsPlugin plugin;

    @ConfigEntity(PATH)
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "limit") int limit = 10;
        public Values(String path) { super(path); }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Logger frameworkLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(framework -> when(framework.getLogger()).thenReturn(frameworkLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn("UnreadableModule");
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        when(plugin.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
    }

    @AfterEach
    void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test
    void unreadableRegularFileRegistersDefaultsAndStaysProtectedThroughShutdown() throws Exception {
        Path file = tempDir.resolve(PATH);
        byte[] original = "# operator content\nlimit: 25\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        ConfigManager manager = new ConfigManager();
        Values config = new Values(PATH);
        List<String> diagnostics;
        Files.setPosixFilePermissions(file, EnumSet.noneOf(PosixFilePermission.class));
        java.util.List<java.util.logging.Level> levels = new java.util.ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { levels.add(record.getLevel()); }
            @Override public void flush() { /* In-memory capture needs no flush. */ }
            @Override public void close() { /* Removed explicitly in finally. */ }
        };
        Logger entityLogger = Logger.getLogger(AbstractConfigEntity.class.getName());
        entityLogger.addHandler(capture);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThat(Files.isReadable(file)).as("the real read failure must be active, not skipped").isFalse();
            manager.register(plugin, config);
            diagnostics = warnings.messages();
            assertThat(levels).containsExactly(java.util.logging.Level.SEVERE);
        } finally {
            entityLogger.removeHandler(capture);
            Files.setPosixFilePermissions(file, permissions);
        }
        assertThat(manager.getConfigEntity(plugin, Values.class)).isSameAs(config);
        assertThat(config.limit).isEqualTo(10);
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(config.isLastLoadUnparseable()).as("a failed read protects every later write until a successful load").isTrue();
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0)).contains(PATH, "AccessDeniedException", "will not be overwritten");
        config.limit = 99;
        config.save();
        manager.saveAll();
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(config.isLastLoadUnparseable()).isTrue();
        config.reload();
        assertThat(config.limit).isEqualTo(25);
        assertThat(config.isLastLoadUnparseable()).isFalse();
    }
}
