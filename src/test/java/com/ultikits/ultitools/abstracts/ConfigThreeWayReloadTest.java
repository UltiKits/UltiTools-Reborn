package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.manager.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** Reload compares effective disk baseline, unsaved live values and incoming plain trees. */
class ConfigThreeWayReloadTest {
    @TempDir Path directory;
    private Values entity;
    private ConfigManager manager;
    private UltiToolsPlugin plugin;
    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getLogger(AbstractConfigEntity.class.getName());
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
        @Override public void flush() { /* No buffer. */ }
        @Override public void close() { /* No resource. */ }
    };

    @BeforeEach void setup() throws Exception {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("ReloadModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        write("mine: base\ntheirs: base\nrate: 1.50\nsecret: original\nentries:\n  keep: base\n  remove: base\n");
        entity = new Values("reload.yml"); manager = new ConfigManager(); manager.register(plugin, entity);
        logger.addHandler(capture);
    }
    @AfterEach void cleanup() { logger.removeHandler(capture); }
    private void write(String text) throws Exception {
        Files.write(directory.resolve("reload.yml"), text.getBytes(StandardCharsets.UTF_8));
    }
    @Test void memoryOnlyAndFileOnlyEditsMergeAndMemoryRemainsDirty() throws Exception {
        entity.mine = "memory";
        write("mine: base\ntheirs: operator\nrate: 1.5\nsecret: original\nentries:\n  keep: base\n  remove: base\n");
        manager.reloadConfigs(plugin);
        assertThat(entity.mine).isEqualTo("memory"); assertThat(entity.theirs).isEqualTo("operator");
        assertThat(entity.isModifiedSinceSnapshot()).isTrue(); assertThat(warnings).isEmpty();
        entity.save(); assertThat(entity.isModifiedSinceSnapshot()).isFalse();
    }
    @Test void conflictingValuesTakeDiskAndWarnWithoutExposingSecrets() throws Exception {
        entity.mine = "discarded"; entity.secret = "sensitive-token";
        write("mine: operator\ntheirs: base\nrate: 1.5\nsecret: replacement\nentries:\n  keep: base\n  remove: base\n");
        entity.reload();
        assertThat(entity.mine).isEqualTo("operator"); assertThat(entity.secret).isEqualTo("replacement");
        assertThat(warnings).anySatisfy(text -> assertThat(text).contains("reload.yml", "mine", "discarded"));
        assertThat(warnings).anySatisfy(text -> assertThat(text).contains("secret", "<redacted>").doesNotContain("sensitive-token"));
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
    }
    @Test void semanticEqualNumericDiskDoesNotDiscardMemoryOrWarn() throws Exception {
        entity.rate = 2.5;
        entity.reload();
        assertThat(entity.rate).isEqualTo(2.5); assertThat(warnings).isEmpty();
        assertThat(entity.isModifiedSinceSnapshot()).isTrue();
    }
    @Test void mapsMergeIndependentWholeKeysAndRespectRemovedDiskKey() throws Exception {
        entity.entries.put("keep", "memory"); entity.entries.put("o.O", "memory-dot");
        write("mine: base\ntheirs: base\nrate: 1.5\nsecret: original\nentries:\n  keep: base\n  added: disk\n");
        entity.reload();
        assertThat(entity.entries).containsEntry("keep", "memory").containsEntry("o.O", "memory-dot")
                .containsEntry("added", "disk").doesNotContainKey("remove").doesNotContainKey("o");
        assertThat(entity.isModifiedSinceSnapshot()).isTrue(); assertThat(warnings).isEmpty();
    }
    @Test void explicitNullDiffersFromMissingMapKey() throws Exception {
        entity.entries.put("remove", "memory");
        write("mine: base\ntheirs: base\nrate: 1.5\nsecret: original\nentries:\n  keep: null\n");
        entity.reload();
        assertThat(entity.entries).containsKey("keep"); assertThat(entity.entries.get("keep")).isNull();
        assertThat(entity.entries).doesNotContainKey("remove");
        assertThat(warnings).anySatisfy(text -> assertThat(text).contains("entries.remove", "memory"));
    }
    @Test void missingWholeFieldRetainsLiveWithDeclaredDefaultBaseline() throws Exception {
        entity.mine = "pending";
        write("theirs: base\nrate: 1.5\nsecret: original\nentries:\n  keep: base\n  remove: base\n");
        entity.reload();
        assertThat(entity.mine).isEqualTo("pending"); assertThat(entity.isPresentInFile("mine")).isFalse();
        assertThat(entity.isModifiedSinceSnapshot()).isTrue();
    }
    @Test void malformedReloadKeepsRunningChangesAndFileProtected() throws Exception {
        entity.mine = "pending"; write("mine: [broken\n"); entity.reload();
        assertThat(entity.mine).isEqualTo("pending"); assertThat(entity.isLastLoadUnparseable()).isTrue();
        entity.save(); assertThat(new String(Files.readAllBytes(directory.resolve("reload.yml")), StandardCharsets.UTF_8))
                .isEqualTo("mine: [broken\n");
    }
    @ConfigEntity("reload.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry String mine = "default";
        @ConfigEntry String theirs = "default";
        @ConfigEntry double rate = 1.5;
        @ConfigEntry String secret = "default";
        @ConfigEntry Map<String, String> entries = new LinkedHashMap<>();
        public Values(String path) { super(path); }
    }
}
