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
        // Two commits attempted; the first file is restored, the second still holds its original bytes after its failed
        // open, so the gated restore has nothing to put back (17-65 review round 1 R65-I3).
        assertThat(opens.get()).as("two commits attempted and the replaced file restored").isEqualTo(3);
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

    /**
     * #600: the staged batch keeps its prepare-then-commit shape through the config write gate, and each file's bytes are
     * checked again immediately before its move: a file the operator saved between staging and commit is kept, the files
     * already committed are restored, and every entity is left as it was. The batch's commit order is not assumed.
     */
    @Test
    void fileChangedBetweenStagingAndCommitRollsTheWholeBatchBack() throws Exception {
        List<Path> stagedTargets = new ArrayList<>();
        List<Path> movedTargets = new ArrayList<>();
        Path[] edited = new Path[1];
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(
                AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString())).thenAnswer(call -> {
                Path target = call.getArgument(0);
                stagedTargets.add(target);
                AtomicConfigWriter.StagedWrite observed = Mockito.spy((AtomicConfigWriter.StagedWrite) call.callRealMethod());
                Mockito.doAnswer(commit -> {
                    Object result = commit.callRealMethod();
                    movedTargets.add(target);
                    if (movedTargets.size() == 1) {
                        // The operator saves another staged file after the first move and before its own commit.
                        for (Path other : stagedTargets) {
                            if (!other.equals(target)) { edited[0] = other; break; }
                        }
                        Files.write(edited[0], operatorEdit(edited[0]));
                    }
                    return result;
                }).when(observed).commit();
                return observed;
            });
            assertThatThrownBy(() -> manager.loadFromJson(payload()))
                    .isInstanceOf(com.ultikits.ultitools.config.ConfigWriteRefusedException.class)
                    .hasMessageContaining(edited[0].getFileName().toString());
        }
        assertThat(stagedTargets).as("every file staged before the first move").hasSize(3);
        assertThat(movedTargets).as("only the first file was moved").hasSize(1);
        for (int i = 0; i < entities.size(); i++) {
            Path target = directory.resolve(entities.get(i).getConfigFilePath());
            if (target.equals(edited[0])) {
                assertThat(Files.readAllBytes(target)).as("the operator's save is kept").isEqualTo(operatorEdit(target));
            } else {
                assertThat(Files.readAllBytes(target)).as("restored or untouched").isEqualTo(originals.get(i));
            }
            assertThat(entities.get(i).value).isEqualTo(i + 1);
            assertThat(state(entities.get(i))).isEqualTo(checkpoints.get(i));
        }
        assertNoTemporaries();
    }

    /**
     * 17-65 review round 1 R65-I3: the batch rollback restores a file it already replaced only while that file still holds
     * exactly what this write put there; a file the operator saved after its commit is kept, and one warning names it.
     */
    @Test
    void rollbackKeepsAFileTheOperatorSavedAfterItsCommit() throws Exception {
        List<Path> moved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("com.ultikits.ultitools");
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) { warnings.add(record.getMessage()); }
            }
            @Override public void flush() { /* No buffer. */ }
            @Override public void close() { /* No resource. */ }
        };
        logger.addHandler(capture);
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(
                AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString())).thenAnswer(call -> {
                Path target = call.getArgument(0);
                AtomicConfigWriter.StagedWrite observed = Mockito.spy((AtomicConfigWriter.StagedWrite) call.callRealMethod());
                Mockito.doAnswer(commit -> {
                    if (moved.size() == 1) { throw new IOException("injected move2"); }
                    Object result = commit.callRealMethod();
                    moved.add(target);
                    // The operator saves this file after the batch replaced it and before the batch fails.
                    Files.write(target, operatorEdit(target));
                    return result;
                }).when(observed).commit();
                return observed;
            });
            assertThatThrownBy(() -> manager.loadFromJson(payload()))
                    .isInstanceOf(IOException.class).hasMessageContaining("injected move2");
        } finally { logger.removeHandler(capture); }
        assertThat(moved).hasSize(1);
        Path kept = moved.get(0);
        for (int i = 0; i < entities.size(); i++) {
            Path target = directory.resolve(entities.get(i).getConfigFilePath());
            if (target.equals(kept)) {
                assertThat(Files.readAllBytes(target)).as("the operator's save after the commit is kept").isEqualTo(operatorEdit(target));
            } else {
                assertThat(Files.readAllBytes(target)).isEqualTo(originals.get(i));
            }
            assertThat(state(entities.get(i))).isEqualTo(checkpoints.get(i));
        }
        assertThat(warnings).filteredOn(text -> text.contains(kept.getFileName().toString()) && text.contains("not restored"))
                .hasSize(1);
        assertNoTemporaries();
    }

    private static byte[] operatorEdit(Path target) {
        return ("# operator header\nvalue: 7\nother: edited after staging " + target.getFileName() + "\nunknown: keep\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** #600: one file the gate refuses (anchors) refuses the whole batch with the reason, before any file is written. */
    @Test
    void anchoredFileRefusesTheWholeBatchBeforeAnyWrite() throws Exception {
        byte[] anchored = "# operator header\nbase: &v 2\nvalue: *v\nother: disk\nunknown: keep\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("file2.yml"), anchored);
        entities.get(1).reload();
        Map<String, Object> anchoredState = state(entities.get(1));
        assertThatThrownBy(() -> manager.loadFromJson(payload()))
                .isInstanceOf(com.ultikits.ultitools.config.ConfigWriteRefusedException.class)
                .hasMessageContaining("anchors").hasMessageContaining("file2.yml");
        assertThat(Files.readAllBytes(directory.resolve("file1.yml"))).isEqualTo(originals.get(0));
        assertThat(Files.readAllBytes(directory.resolve("file2.yml"))).isEqualTo(anchored);
        assertThat(Files.readAllBytes(directory.resolve("file3.yml"))).isEqualTo(originals.get(2));
        assertThat(state(entities.get(0))).isEqualTo(checkpoints.get(0));
        assertThat(state(entities.get(1))).isEqualTo(anchoredState);
        assertThat(state(entities.get(2))).isEqualTo(checkpoints.get(2));
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
