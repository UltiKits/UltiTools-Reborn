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
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void fallbackRollbackRefreshesRetainedBackupAndRestoresOriginalCheckpoint() throws Exception {
        // Constant framework nested-interface lookup; no user-controlled class name.
        // nosemgrep: java_lang_security_audit_unsafe-reflection_unsafe-reflection, java.lang.security.audit.unsafe-reflection.unsafe-reflection
        Class<?> operations = Class.forName(AtomicConfigWriter.class.getName() + "$FileOperations");
        AtomicInteger opens = new AtomicInteger();
        Object files = Mockito.mock(operations, invocation -> {
            if ("move".equals(invocation.getMethod().getName())) {
                Path destination = invocation.getArgument(1);
                // The fallback's backup refresh moves onto <file name>.ultitools-backup-<16 hex> (#601).
                if (!destination.getFileName().toString().contains(".ultitools-backup-")) {
                    throw new java.nio.file.AtomicMoveNotSupportedException("source", destination.toString(), "injected fallback");
                }
            }
            if ("openTarget".equals(invocation.getMethod().getName()) && opens.incrementAndGet() == 2) {
                throw new IOException("one transient target-open failure");
            }
            return invocation.callRealMethod();
        });
        java.lang.reflect.Method stage = AtomicConfigWriter.class.getDeclaredMethod("stage", Path.class, String.class, operations);
        java.lang.reflect.Method write = AtomicConfigWriter.class.getDeclaredMethod("write", Path.class, String.class, operations);
        stage.setAccessible(true); write.setAccessible(true);
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString()))
                    .thenAnswer(call -> invokeWriter(stage, call.getArgument(0), call.getArgument(1), files));
            writer.when(() -> AtomicConfigWriter.write(any(Path.class), anyString()))
                    .thenAnswer(call -> invokeWriter(write, call.getArgument(0), call.getArgument(1), files));
            assertThatThrownBy(() -> manager.loadFromJson(payload()))
                    .isInstanceOf(IOException.class).hasMessage("one transient target-open failure")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        }
        assertThat(opens.get()).as("two commits attempted and both attempted files restored").isEqualTo(4);
        for (int i = 0; i < entities.size(); i++) {
            Values value = entities.get(i);
            assertThat(Files.readAllBytes(directory.resolve(value.getConfigFilePath()))).isEqualTo(originals.get(i));
            assertThat(state(value)).isEqualTo(checkpoints.get(i));
            assertThat(value.value).isEqualTo(i + 1); assertThat(value.other).isEqualTo("unsaved");
        }
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            List<Path> backups = paths.filter(path -> path.getFileName().toString().matches(".*\\.ultitools-backup-[0-9a-f]{16}"))
                    .collect(Collectors.toList());
            assertThat(backups).hasSize(2);
            for (Path backup : backups) {
                String name = backup.getFileName().toString();
                Path target = backup.resolveSibling(name.substring(0, name.indexOf(".ultitools-backup-")));
                assertThat(ConfigDocument.load(target).state())
                        .isEqualTo(com.ultikits.ultitools.config.document.ConfigLoadResult.State.LOADED);
                assertThat(Files.exists(backup)).isFalse();
            }
        }
        assertNoTemporaries();
    }

    private static Object invokeWriter(java.lang.reflect.Method method, Path target, String text, Object files) throws Throwable {
        try { return method.invoke(null, target, text, files); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
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
    void preparedCandidateNeverPublishesFieldsBeforeAllFilesCommit() throws Exception {
        Values value = entities.get(0);
        JsonObject edit = new JsonObject(); edit.addProperty("value", 10);
        AbstractConfigEntity.PanelWrite pending = value.preparePanelWrite(edit);
        try {
            assertThat(value.value).as("pending panel candidate is not live state").isEqualTo(1);
            assertThat(state(value)).isEqualTo(checkpoints.get(0));
            pending.commit();
            assertThat(value.value).as("commit alone does not acknowledge fields").isEqualTo(1);
            pending.acknowledge();
            assertThat(value.value).isEqualTo(10);
            assertThat(value.other).isEqualTo("unsaved");
        } finally { pending.discard(); }
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

    @ParameterizedTest
    @ValueSource(strings = {"update", "prepare", "validate", "named", "batch"})
    void protectedTargetRefusesEveryPanelBoundaryAndRecoversAfterLoad(String route) throws Exception {
        Values protectedValue = entities.get(1);
        Path target = directory.resolve(protectedValue.getConfigFilePath());
        byte[] malformed = "value: [unterminated\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, malformed);
        protectedValue.reload();
        Map<String, Object> protectedState = state(protectedValue);
        JsonObject edit = new JsonObject(); edit.addProperty("value", 10);
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(
                AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            assertThatThrownBy(() -> applyPanelRoute(route, protectedValue, edit))
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("file2.yml");
            writer.verify(() -> AtomicConfigWriter.stage(any(Path.class), anyString()), Mockito.never());
            assertThat(Files.readAllBytes(target)).isEqualTo(malformed);
            assertThat(state(protectedValue)).isEqualTo(protectedState);
            for (int i : new int[] {0, 2}) {
                assertThat(Files.readAllBytes(directory.resolve(entities.get(i).getConfigFilePath())))
                        .isEqualTo(originals.get(i));
                assertThat(state(entities.get(i))).isEqualTo(checkpoints.get(i));
                assertThat(entities.get(i).value).isEqualTo(i + 1);
            }
        }
        Files.write(target, originals.get(1)); protectedValue.reload();
        manager.loadFromJson(payload());
        for (Values entity : entities) { assertThat(entity.value).isEqualTo(10); }
        assertNoTemporaries();
    }

    private void applyPanelRoute(String route, Values entity, JsonObject edit) throws IOException {
        if (route.equals("update")) { entity.updateProperties(edit); }
        else if (route.equals("prepare")) {
            AbstractConfigEntity.PanelWrite write = entity.preparePanelWrite(edit);
            if (write != null) { write.discard(); }
        } else if (route.equals("validate")) { entity.validateProposedProperties(edit); }
        else if (route.equals("named")) { manager.loadFromJson("file2.yml", edit.toString()); }
        else { manager.loadFromJson(payload()); }
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

    @ParameterizedTest
    @ValueSource(strings = {"save", "update"})
    @org.junit.jupiter.api.Timeout(20)
    void completePanelTransactionSerializesDirectEntityPersistence(String operation) throws Exception {
        Values entity = entities.get(0);
        java.util.concurrent.CountDownLatch attempted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        Thread direct = new Thread(() -> {
            attempted.countDown();
            try {
                synchronized (entity) {
                    if (operation.equals("save")) { entity.other = "direct"; entity.save(); }
                    else { JsonObject edit = new JsonObject(); edit.addProperty("other", "direct"); entity.updateProperties(edit); }
                }
            } catch (Throwable failure) { outcome.set(failure); }
        }, "direct-config-writer");
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(
                AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            AtomicInteger staged = new AtomicInteger();
            writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString())).thenAnswer(call -> {
                AtomicConfigWriter.StagedWrite observed = Mockito.spy(
                        (AtomicConfigWriter.StagedWrite) call.callRealMethod());
                if (staged.incrementAndGet() == 1) {
                    Mockito.doAnswer(commit -> {
                        direct.start();
                        assertThat(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                        while (direct.isAlive() && direct.getState() != Thread.State.BLOCKED
                                && System.nanoTime() < deadline) { Thread.yield(); }
                        assertThat(direct.getState()).as("direct writer waits for the complete panel transaction")
                                .isEqualTo(Thread.State.BLOCKED);
                        return commit.callRealMethod();
                    }).when(observed).commit();
                }
                return observed;
            });
            // A server-lane caller may already hold a different touched entity; reentry must be safe.
            synchronized (entities.get(2)) { manager.loadFromJson(payload()); }
        } finally { direct.join(3000); }
        assertThat(direct.isAlive()).isFalse(); assertThat(outcome.get()).isNull();
        assertThat(entity.value).isEqualTo(10); assertThat(entity.other).isEqualTo("direct");
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
        ConfigDocument disk = ConfigDocument.parse(new String(Files.readAllBytes(directory.resolve("file1.yml")),
                StandardCharsets.UTF_8));
        assertThat(disk.get(Arrays.asList("value"))).isEqualTo(10);
        assertThat(disk.get(Arrays.asList("other"))).isEqualTo("direct");
        assertNoTemporaries();
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
                "savedFileFingerprint", "lastLoadUnparseable", "lastInitIncomplete",
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
