package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.manager.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * A failed reload is all-or-nothing: it leaves the entity exactly as it was before the attempt, so
 * the rejected value never becomes an "unsaved in-memory edit" that the next reload's three-way
 * merge would keep over a corrected file (real-server rows
 * {@code ultitools.ul.reload-module.neg-reload-failure} and {@code ultitools.ul.reload.neg-module-failure}).
 */
class ConfigReloadRollbackTest {
    private static final String VALID = "interval: 300\nname: base\nentries:\n  keep: base\n";
    private static final String INVALID = "interval: 5\nname: base\nentries:\n  keep: base\n";

    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    private ConfigManager manager;
    private Values entity;

    @BeforeEach void setup() throws Exception {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("RollbackModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        write(VALID);
        entity = new Values("rollback.yml");
        manager = new ConfigManager();
        manager.register(plugin, entity);
    }

    private void write(String text) throws Exception {
        Files.write(directory.resolve("rollback.yml"), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test void correctedFileReloadsAfterRejectedValue() throws Exception {
        write(INVALID);
        assertThatThrownBy(entity::reload).isInstanceOf(ConfigurationException.class);
        write("interval: 60\nname: base\nentries:\n  keep: base\n");
        entity.reload();
        assertThat(entity.interval).isEqualTo(60);
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
    }

    @Test void restoringTheOriginalFileValueReloadsAfterRejectedValue() throws Exception {
        write(INVALID);
        assertThatThrownBy(entity::reload).isInstanceOf(ConfigurationException.class);
        write(VALID);
        entity.reload();
        assertThat(entity.interval).isEqualTo(300);
        assertThat(entity.unsavedEntryPaths()).isEmpty();
    }

    @Test void failedReloadLeavesFieldsAndTrackingAsBefore() throws Exception {
        entity.name = "pending";
        List<String> unsavedBefore = entity.unsavedEntryPaths();
        Map<String, String> entriesBefore = new LinkedHashMap<>(entity.entries);
        write("interval: 5\nname: operator\nentries:\n  keep: disk\n  added: disk\n");
        byte[] disk = Files.readAllBytes(directory.resolve("rollback.yml"));
        assertThatThrownBy(entity::reload).isInstanceOf(ConfigurationException.class);
        assertThat(entity.interval).isEqualTo(300);
        assertThat(entity.name).isEqualTo("pending");
        assertThat(entity.entries).isEqualTo(entriesBefore);
        assertThat(entity.unsavedEntryPaths()).isEqualTo(unsavedBefore).containsExactly("name");
        assertThat(entity.isModifiedSinceSnapshot()).isTrue();
        assertThat(entity.isFileModifiedSinceSnapshot()).isTrue();
        assertThat(entity.isLastLoadUnparseable()).isFalse();
        assertThat(Files.readAllBytes(directory.resolve("rollback.yml"))).isEqualTo(disk);
    }

    @Test void pendingEditSurvivesFailedReloadAndStillWinsNextReload() throws Exception {
        entity.name = "pending";
        write(INVALID);
        assertThatThrownBy(entity::reload).isInstanceOf(ConfigurationException.class);
        write("interval: 120\nname: base\nentries:\n  keep: disk\n");
        entity.reload();
        assertThat(entity.name).isEqualTo("pending");
        assertThat(entity.interval).isEqualTo(120);
        assertThat(entity.entries).containsOnlyKeys("keep").containsEntry("keep", "disk");
        assertThat(entity.unsavedEntryPaths()).containsExactly("name");
    }

    @Test void conflictingPendingEditStillLosesToTheFileAfterFailedReload() throws Exception {
        entity.name = "pending";
        write("interval: 5\nname: operator\nentries:\n  keep: base\n");
        assertThatThrownBy(entity::reload).isInstanceOf(ConfigurationException.class);
        write("interval: 300\nname: operator\nentries:\n  keep: base\n");
        entity.reload();
        assertThat(entity.name).isEqualTo("operator");
        assertThat(entity.unsavedEntryPaths()).isEmpty();
    }

    @Test void reloadAllRecoversAfterRejectedValue() throws Exception {
        write(INVALID);
        assertThatThrownBy(() -> manager.reloadConfigs(plugin)).isInstanceOf(ConfigurationException.class);
        assertThat(entity.interval).isEqualTo(300);
        assertThat(entity.unsavedEntryPaths()).isEmpty();
        write("interval: 90\nname: base\nentries:\n  keep: base\n");
        manager.reloadConfigs(plugin);
        assertThat(entity.interval).isEqualTo(90);
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
    }

    @ConfigEntity("rollback.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry @Range(min = 10, max = 3600) int interval = 300;
        @ConfigEntry String name = "base";
        @ConfigEntry Map<String, String> entries = new LinkedHashMap<>();
        public Values(String path) { super(path); }
    }
}
