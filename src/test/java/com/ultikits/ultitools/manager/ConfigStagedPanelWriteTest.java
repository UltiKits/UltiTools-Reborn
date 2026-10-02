package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Multi-file panel persistence must restore disk and every entity view on a physical refusal. */
class ConfigStagedPanelWriteTest {
    @TempDir Path directory;
    private ConfigManager manager;
    private final List<Values> entities = new ArrayList<>();
    private final List<byte[]> originals = new ArrayList<>();
    private final List<Map<String, Object>> checkpoints = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        UltiToolsPlugin plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("PanelBatch");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        manager = new ConfigManager();
        for (int i = 1; i <= 3; i++) {
            String path = "file" + i + ".yml";
            Files.write(directory.resolve(path), ("# operator header\nvalue: " + i
                    + "\nother: disk\nunknown: keep\n").getBytes(StandardCharsets.UTF_8));
            Values value = new Values(path);
            manager.register(plugin, value);
            value.other = "unsaved";
            entities.add(value);
            originals.add(Files.readAllBytes(directory.resolve(path)));
            checkpoints.add(state(value));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stage2", "move2", "move3"})
    void physicalFailureRestoresEveryFileFieldAndCheckpoint(String fault) throws Exception {
        AtomicInteger staged = new AtomicInteger();
        AtomicInteger moved = new AtomicInteger();
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(
                AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString())).thenAnswer(call -> {
                int index = staged.incrementAndGet();
                if (fault.equals("stage" + index)) { throw new IOException("injected " + fault); }
                AtomicConfigWriter.StagedWrite real = (AtomicConfigWriter.StagedWrite) call.callRealMethod();
                AtomicConfigWriter.StagedWrite observed = Mockito.spy(real);
                Mockito.doAnswer(commit -> {
                    assertThat(staged.get()).as("every file staged before first move").isEqualTo(3);
                    int move = moved.incrementAndGet();
                    if (fault.equals("move" + move)) { throw new IOException("injected " + fault); }
                    return commit.callRealMethod();
                }).when(observed).commit();
                return observed;
            });
            assertThatThrownBy(() -> manager.loadFromJson(payload()))
                    .isInstanceOf(IOException.class).hasMessageContaining("injected " + fault);
            assertRestored();
        }
    }

    @Test
    void successStagesEveryFileBeforeCommittingAndAcknowledgesOnlyTouchedFields() throws Exception {
        AtomicInteger staged = new AtomicInteger();
        AtomicInteger moved = new AtomicInteger();
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(
                AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString())).thenAnswer(call -> {
                staged.incrementAndGet();
                AtomicConfigWriter.StagedWrite observed = Mockito.spy(
                        (AtomicConfigWriter.StagedWrite) call.callRealMethod());
                Mockito.doAnswer(commit -> {
                    assertThat(staged.get()).isEqualTo(3);
                    for (Values value : entities) {
                        assertThat(state(value).get("savedSnapshot"))
                                .as("no baseline acknowledged before all moves succeed")
                                .isEqualTo(checkpoints.get(entities.indexOf(value)).get("savedSnapshot"));
                    }
                    moved.incrementAndGet();
                    return commit.callRealMethod();
                }).when(observed).commit();
                return observed;
            });
            manager.loadFromJson(payload());
            assertThat(staged.get()).isEqualTo(3);
            assertThat(moved.get()).isEqualTo(3);
        }
        for (Values value : entities) {
            assertThat(value.value).isEqualTo(10);
            assertThat(value.other).isEqualTo("unsaved");
            assertThat(value.isModifiedSinceSnapshot()).isTrue();
            ConfigDocument saved = ConfigDocument.parse(new String(Files.readAllBytes(
                    directory.resolve(value.getConfigFilePath())), StandardCharsets.UTF_8));
            assertThat(saved.get(Arrays.asList("value"))).isEqualTo(10);
            assertThat(saved.get(Arrays.asList("other"))).isEqualTo("disk");
            assertThat(saved.get(Arrays.asList("unknown"))).isEqualTo("keep");
        }
        assertNoTemporaries();
    }

    @Test
    void semanticallyUnchangedPayloadWritesNothing() throws Exception {
        JsonObject files = new JsonObject();
        List<java.nio.file.attribute.FileTime> times = new ArrayList<>();
        for (Values value : entities) {
            JsonObject entry = new JsonObject(); entry.addProperty("value", value.value);
            files.add(value.getConfigFilePath(), entry);
            times.add(Files.getLastModifiedTime(directory.resolve(value.getConfigFilePath())));
        }
        JsonObject root = new JsonObject(); root.add("PanelBatch", files);
        manager.loadFromJson(root.toString());
        for (int i = 0; i < entities.size(); i++) {
            Path target = directory.resolve(entities.get(i).getConfigFilePath());
            assertThat(Files.readAllBytes(target)).isEqualTo(originals.get(i));
            assertThat(Files.getLastModifiedTime(target)).isEqualTo(times.get(i));
        }
        assertNoTemporaries();
    }

    @Test
    void validationRefusalNeverStagesAnyFile() throws Exception {
        entities.get(1).refuse = true;
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(
                AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            assertThatThrownBy(() -> manager.loadFromJson(payload())).isInstanceOf(ConfigurationException.class);
            writer.verify(() -> AtomicConfigWriter.stage(any(Path.class), anyString()), Mockito.never());
            assertRestored();
        }
    }

    private String payload() {
        JsonObject files = new JsonObject();
        for (Values entity : entities) {
            JsonObject value = new JsonObject(); value.addProperty("value", 10);
            files.add(entity.getConfigFilePath(), value);
        }
        JsonObject root = new JsonObject(); root.add("PanelBatch", files); return root.toString();
    }

    private void assertRestored() throws Exception {
        for (int i = 0; i < entities.size(); i++) {
            Values value = entities.get(i);
            assertThat(Files.readAllBytes(directory.resolve(value.getConfigFilePath()))).isEqualTo(originals.get(i));
            assertThat(value.value).isEqualTo(i + 1);
            assertThat(value.other).isEqualTo("unsaved");
            assertThat(state(value)).isEqualTo(checkpoints.get(i));
        }
        assertNoTemporaries();
    }

    private void assertNoTemporaries() throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            assertThat(paths.map(path -> path.getFileName().toString()).collect(Collectors.toList()))
                    .containsExactlyInAnyOrder("file1.yml", "file2.yml", "file3.yml");
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Map<String, Object> state(Values value) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : Arrays.asList("document", "savedSnapshot", "lastLoadedPresence", "acknowledgedRaw",
                "savedFileFingerprint", "lastLoadUnparseable", "lastInitIncomplete", "pendingCommentWrite",
                "pendingInitialization", "deferInitialization", "warnedCommentKeys")) {
            Field field = AbstractConfigEntity.class.getDeclaredField(name); field.setAccessible(true);
            Object data = field.get(value);
            if (data instanceof ConfigDocument) { data = ((ConfigDocument) data).render(); }
            else if (data instanceof Map) { data = new LinkedHashMap<>((Map<?, ?>) data); }
            else if (data instanceof java.util.Set) { data = new java.util.LinkedHashSet<>((java.util.Set<?>) data); }
            result.put(name, data);
        }
        return result;
    }

    @ConfigEntity("panel.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "value") int value = 0;
        @ConfigEntry(path = "other") String other = "default";
        boolean refuse;
        public Values(String path) { super(path); }
        @Override protected void validateFields() {
            if (refuse && value == 10) { throw new ConfigurationException("injected validation refusal"); }
        }
    }
}
