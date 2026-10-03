package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.manager.ConfigManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * A panel write whose file replacement fails is all-or-nothing: the entity is left exactly as it
 * was before the call and the caller still sees the failure, so memory never claims a value the
 * file does not hold.
 */
class ConfigUpdateRollbackTest {
    private static final String VALID = "interval: 300\nname: base\nentries:\n  keep: base\n";

    @TempDir Path directory;
    private Values entity;

    @BeforeEach void setup() throws Exception {
        UltiToolsPlugin plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("UpdateRollbackModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        Files.write(file(), VALID.getBytes(StandardCharsets.UTF_8));
        entity = new Values("update-rollback.yml");
        new ConfigManager().register(plugin, entity);
    }

    private Path file() {
        return directory.resolve("update-rollback.yml");
    }

    private static JsonObject panel(int interval, String keep) {
        JsonObject json = new JsonObject();
        json.addProperty("interval", interval);
        JsonObject entries = new JsonObject();
        entries.addProperty("keep", keep);
        json.add("entries", entries);
        return json;
    }

    @Test void failedWriteLeavesFieldsTrackingAndFileAsBefore() throws Exception {
        entity.name = "pending";
        List<String> unsavedBefore = entity.unsavedEntryPaths();
        Map<String, String> entriesBefore = new LinkedHashMap<>(entity.entries);
        byte[] disk = Files.readAllBytes(file());
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.write(Mockito.any(Path.class), Mockito.anyString()))
                    .thenThrow(new IOException("injected write failure"));
            assertThatThrownBy(() -> entity.updateProperties(panel(600, "panel")))
                    .isInstanceOf(IOException.class).hasMessageContaining("injected write failure");
        }
        assertThat(entity.interval).isEqualTo(300);
        assertThat(entity.entries).isEqualTo(entriesBefore);
        assertThat(entity.name).isEqualTo("pending");
        assertThat(entity.unsavedEntryPaths()).isEqualTo(unsavedBefore).containsExactly("name");
        assertThat(entity.isModifiedSinceSnapshot()).isTrue();
        assertThat(entity.isFileModifiedSinceSnapshot()).isFalse();
        assertThat(Files.readAllBytes(file())).isEqualTo(disk);
    }

    @Test void retryAfterFailedWritePersistsAndAcknowledges() throws Exception {
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.write(Mockito.any(Path.class), Mockito.anyString()))
                    .thenThrow(new IOException("injected write failure"));
            assertThatThrownBy(() -> entity.updateProperties(panel(600, "panel"))).isInstanceOf(IOException.class);
        }
        assertThat(entity.unsavedEntryPaths()).isEmpty();
        entity.updateProperties(panel(600, "panel"));
        assertThat(entity.interval).isEqualTo(600);
        assertThat(entity.entries).containsEntry("keep", "panel");
        assertThat(entity.unsavedEntryPaths()).isEmpty();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8))
                .contains("interval: 600").contains("keep: panel");
    }

    @ConfigEntity("update-rollback.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry @Range(min = 10, max = 3600) int interval = 300;
        @ConfigEntry String name = "base";
        @ConfigEntry Map<String, String> entries = new LinkedHashMap<>();
        public Values(String path) { super(path); }
    }
}
