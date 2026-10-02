package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Releasing an unloaded module must not pin or write it; shutdown saves before that release. */
class ConfigRegistryReleaseTest {
    @TempDir Path directory;
    private ConfigManager configs;
    private PluginManager plugins;
    private UltiToolsPlugin owner;
    private Values entity;

    @BeforeEach void setup() throws Exception {
        MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); MockBukkit.createMockPlugin();
        configs = new ConfigManager(); plugins = new PluginManager();
        TestHelper.mockUltiToolsInstance(core -> lenient().when(core.getConfigManager()).thenReturn(configs));
        owner = mock(UltiToolsPlugin.class);
        lenient().when(owner.getPluginName()).thenReturn("ReleasedModule");
        lenient().when(owner.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(owner, directory.toFile());
        Files.write(directory.resolve("release.yml"), "value: disk\n".getBytes(StandardCharsets.UTF_8));
        entity = new Values("release.yml"); configs.register(owner, entity); entity.value = "pending";
        plugins.getPluginList().add(owner);
    }
    @AfterEach void cleanup() { MockBukkitHelper.safeUnmock(); }
    private String disk() throws Exception {
        return new String(Files.readAllBytes(directory.resolve("release.yml")), StandardCharsets.UTF_8);
    }
    @Test void runtimeUnregisterReleasesEntitiesAndLaterShutdownCannotWriteThem() throws Exception {
        plugins.unregister(owner);
        assertThat(configs.getAllConfigEntities(owner)).isNull();
        configs.saveAll(); assertThat(disk()).contains("value: disk");
    }
    @Test void throwingUnloadStillReleasesConfigurationRegistry() throws Exception {
        doThrow(new IllegalStateException("unload refusal")).when(owner).unregisterSelf();
        assertThatThrownBy(() -> plugins.unregister(owner)).isInstanceOf(IllegalStateException.class);
        assertThat(configs.getAllConfigEntities(owner)).isNull();
        configs.saveAll(); assertThat(disk()).contains("value: disk");
    }
    @Test void closeSavesBeforeUnloadAndLaterSaveCannotUndoIt() throws Exception {
        doAnswer(call -> {
            assertThat(disk()).contains("value: pending");
            assertThat(configs.getAllConfigEntities(owner)).containsValue(entity);
            return null;
        }).when(owner).unregisterSelf();
        plugins.close();
        verify(owner).unregisterSelf();
        assertThat(configs.getAllConfigEntities(owner)).isNull();
        entity.value = "after-release"; configs.saveAll(); assertThat(disk()).contains("value: pending");
    }
    @ConfigEntity("release.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry String value = "default";
        public Values(String path) { super(path); }
    }
}
