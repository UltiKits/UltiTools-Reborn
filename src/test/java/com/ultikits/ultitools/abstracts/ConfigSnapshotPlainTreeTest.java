package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;

/** Effective plain baselines are detached from live values and from raw operator representation. */
class ConfigSnapshotPlainTreeTest {
    @TempDir Path directory;
    private UltiToolsPlugin plugin;

    public static class Values extends AbstractConfigEntity {
        @ConfigEntry double rate = 1.5;
        @ConfigEntry Map<String, List<Integer>> groups = new LinkedHashMap<>();
        @ConfigEntry String name = "default";
        public Values(String path) { super(path); groups.put("first", new ArrayList<>(Arrays.asList(1, 2))); }
    }

    @BeforeEach
    void setup() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("SnapshotModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(directory.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                call -> new File(directory.toFile(), call.<String>getArgument(0)));
    }

    private Path file() { return directory.resolve("values.yml"); }
    private Values load() throws Exception {
        Files.write(file(), "# operator\nrate: 1.50\ngroups:\n  first: [1, 2]\nname: loaded\n".getBytes(StandardCharsets.UTF_8));
        Values config = new Values("values.yml"); config.init(plugin); return config;
    }

    @Test
    void equivalentPlainDecimalDoesNotModifyOrRewriteRawSpelling() throws Exception {
        Values config = load();
        byte[] bytes = Files.readAllBytes(file()); FileTime time = Files.getLastModifiedTime(file());
        config.rate = 1.5;
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        config.save();
        assertThat(Files.readAllBytes(file())).isEqualTo(bytes);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(time);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    void nestedMutationIsDetectedAndReturningToPlainValueIsClean() throws Exception {
        Values config = load();
        config.groups.get("first").add(3);
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        config.groups.get("first").remove(Integer.valueOf(3));
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        config.groups.get("first").set(0, 9);
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        config.save();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        config.groups.get("first").set(0, 8);
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
    }

    @Test
    void panelSaveAcknowledgesOnlyTouchedFieldNotUnrelatedCodeEdit() throws Exception {
        Values config = load();
        config.groups.get("first").add(3);
        JsonObject panel = new JsonObject(); panel.addProperty("rate", 2.5);
        config.updateProperties(panel);
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        Values disk = new Values("values.yml"); disk.init(plugin);
        assertThat(disk.rate).isEqualTo(2.5);
        assertThat(disk.groups.get("first")).containsExactly(1, 2);
        config.save();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        disk.reload(); assertThat(disk.groups.get("first")).containsExactly(1, 2, 3);
    }

    @Test
    void fileFingerprintStillReportsOperatorCommentOnlyChangesUntilSuccessfulReload() throws Exception {
        Values config = load();
        assertThat(config.isFileModifiedSinceSnapshot()).isFalse();
        Files.write(file(), "# operator changed only comment\nrate: 1.50\ngroups:\n  first: [1, 2]\nname: loaded\n".getBytes(StandardCharsets.UTF_8));
        assertThat(config.isFileModifiedSinceSnapshot()).isTrue();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        config.reload();
        assertThat(config.isFileModifiedSinceSnapshot()).isFalse();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    void missingReloadFieldRetainsLiveValueWithoutAcknowledgingUnsavedEdit() throws Exception {
        Values config = load(); config.name = "pending";
        Files.write(file(), "rate: 1.50\ngroups:\n  first: [1, 2]\n".getBytes(StandardCharsets.UTF_8));
        config.reload();
        assertThat(config.name).isEqualTo("pending");
        assertThat(config.isPresentInFile("name")).isFalse();
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        config.name = "default";
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }
}
