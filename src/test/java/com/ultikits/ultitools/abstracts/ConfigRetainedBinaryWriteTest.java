package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.OperatorFiles;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * A file that merely holds a retained YAML {@code !!binary} value must stay writable (issue #673, "Retained binary
 * values compare by content"). The document layer reads a binary as a {@code byte[]} and hands out defensive copies,
 * so the write gate's self-check compares two different arrays holding the same bytes; it must compare them by
 * content, or every gated write to such a file is refused. Every test leaves the binary line byte-identical.
 * <p>
 * The rows that expect a write use a quoted or block-literal binary, which the document renders byte for byte. An
 * unquoted {@code !!binary} scalar and a block-form {@code !!set} are re-rendered in another style ({@code !!binary 'YWI='},
 * {@code a:}), so a write next to them is refused by the layout check and the file is left byte-identical: a documented
 * limitation (maintainer decision 2026-10-18), pinned here so it stays a fail-closed refusal.
 */
@DisplayName("A retained !!binary value does not block a write of another key")
class ConfigRetainedBinaryWriteTest {

    private static final String ENTITY_FILE = "kit.yml";
    private static final String ENTITY_TEXT = "# header\n" + "blob: !!binary 'YWI='\n" + "motd: old\ntitle: first\n";
    private static final String BLOB_LINE = "blob: !!binary 'YWI='\n";
    private static final String KIT = "# header\n" + BLOB_LINE + "motd: old\n";
    private static final String LAYOUT_REFUSAL = "the file's layout outside the keys this write owns would change";

    @TempDir
    Path directory;

    private Path file;
    /** Held so the logger, and the handler on it, cannot be collected while a test runs. */
    private final Logger framework = Logger.getLogger("com.ultikits.ultitools");
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
            // Records are kept in memory; there is nothing to flush.
        }

        @Override
        public void close() {
            // No resource is held; there is nothing to close.
        }
    };

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        file = directory.resolve("kits").resolve("binary.yml");
        framework.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        framework.removeHandler(capture);
    }

    @Test
    @DisplayName("OperatorFiles.write of a neighbour key is written and the binary line stays byte-identical")
    void retainedBinaryDoesNotBlockANeighbourWrite() throws Exception {
        put(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), "new"));

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(result).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(text()).isEqualTo("# header\n" + BLOB_LINE + "motd: new\n");
    }

    @Test
    @DisplayName("writing the value a key already holds settles as unchanged and leaves the file untouched")
    void retainedBinaryUnchangedWriteSettlesUnchanged() throws Exception {
        long modified = put(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), "old"));

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(result).isEqualTo(OperatorFiles.WriteResult.UNCHANGED);
        assertThat(text()).isEqualTo(KIT);
        assertThat(file.toFile().lastModified()).isEqualTo(modified);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"blob: !!binary \"YWI=\"\n", "blob: !!binary |\n  YWI=\n", "blob: !!binary |-\n  YWI=\n"})
    @DisplayName("a double-quoted or block-literal binary does not block a neighbour write either")
    void otherRenderableBinarySpellingsDoNotBlockANeighbourWrite(String blob) throws Exception {
        put("# header\n" + blob + "motd: old\n");
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), "new"));

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(result).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(text()).isEqualTo("# header\n" + blob + "motd: new\n");
    }

    static Stream<Arguments> unrenderableSpellings() {
        return Stream.of(
                Arguments.of("unquoted binary", "blob: !!binary YWI=\n", LAYOUT_REFUSAL + " (line 2)"),
                Arguments.of("nested unquoted binary", "outer:\n  blob: !!binary YWI=\n", LAYOUT_REFUSAL + " (line 3)"),
                Arguments.of("block-form set", "blob: !!set\n  ? a\nother: x\n", LAYOUT_REFUSAL),
                // The parser ends the set's last null value at the start of the next line, so a write of the key that
                // directly follows a block-form set is refused by the shared-line check instead.
                Arguments.of("block-form set right above the written key", "blob: !!set\n  ? a\n",
                        "a key this write owns shares line 4 with a key it does not own"));
    }

    /**
     * Documented limitation (maintainer decision 2026-10-18): the document re-renders an unquoted {@code !!binary}
     * scalar as {@code !!binary 'YWI='} and a block-form {@code !!set} entry {@code ? a} as {@code a:}, so a neighbour
     * write is refused by the gate's layout checks - never by the value comparison - and the file keeps every byte.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unrenderableSpellings")
    @DisplayName("an unquoted binary or a block-form set refuses a neighbour write for its layout and keeps the file")
    void unrenderableSpellingRefusesANeighbourWriteForItsLayout(String spelling, String blob, String reason)
            throws Exception {
        String original = "# header\n" + blob + "motd: old\n";
        long modified = put(original);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), "new"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertThat(warnings).extracting(LogRecord::getMessage).singleElement().asString().contains(reason);
        assertThat(text()).isEqualTo(original);
        assertThat(file.toFile().lastModified()).isEqualTo(modified);
    }

    @Test
    @DisplayName("entity save() of a changed field writes only its line; the binary line stays byte-identical")
    void entitySaveWritesBesideARetainedBinary() throws Exception {
        Kit kit = entity();
        kit.motd = "new";

        kit.save();

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(entityText()).isEqualTo(ENTITY_TEXT.replace("motd: old", "motd: new"));
        assertThat(kit.isModifiedSinceSnapshot()).as("the change is saved").isFalse();
    }

    @Test
    @DisplayName("saveOperatorChange of a declared field writes only its line; the binary line stays byte-identical")
    void operatorChangeWritesBesideARetainedBinary() throws Exception {
        Kit kit = entity();
        kit.title = "second";

        assertThatCode(() -> kit.saveOperatorChange("title")).doesNotThrowAnyException();

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(entityText()).isEqualTo(ENTITY_TEXT.replace("title: first", "title: second"));
    }

    @Test
    @DisplayName("a panel batch writes only its key line beside a retained binary; the entity changes only after the write")
    void panelBatchWritesBesideARetainedBinary() throws Exception {
        Files.write(directory.resolve(ENTITY_FILE), ENTITY_TEXT.getBytes(StandardCharsets.UTF_8));
        UltiToolsPlugin plugin = plugin();
        Kit kit = new Kit(ENTITY_FILE);
        ConfigManager manager = new ConfigManager();
        manager.register(plugin, kit);
        assertThat(kit.motd).isEqualTo("old");
        JsonObject edit = new JsonObject();
        edit.addProperty("motd", "panel");
        JsonObject files = new JsonObject();
        files.add(ENTITY_FILE, edit);
        JsonObject payload = new JsonObject();
        payload.add(plugin.getPluginName(), files);
        List<String> atCommit = new ArrayList<>();

        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString())).thenAnswer(call -> {
                AtomicConfigWriter.StagedWrite observed = Mockito.spy((AtomicConfigWriter.StagedWrite) call.callRealMethod());
                Mockito.doAnswer(commit -> {
                    atCommit.add(kit.motd);
                    return commit.callRealMethod();
                }).when(observed).commit();
                return observed;
            });
            assertThatCode(() -> manager.loadFromJson(payload.toString())).doesNotThrowAnyException();
        }

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(entityText()).isEqualTo(ENTITY_TEXT.replace("motd: old", "motd: panel"));
        assertThat(atCommit).as("entity value while the file is committed").containsExactly("old");
        assertThat(kit.motd).isEqualTo("panel");
    }

    @Test
    @DisplayName("control: a new byte[] value is still refused at the plain-data boundary, before the file is read again")
    void newBinaryValueIsStillRefused() throws Exception {
        put(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        Files.delete(file);

        assertThatThrownBy(() -> OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[motd]")
                .hasMessageContaining("only plain data");
        assertThat(file).as("the deleted file is not read or written again").doesNotExist();
    }

    @Test
    @DisplayName("control: a binary the operator changed on disk after the read is kept and the write reports FILE_CHANGED")
    void binaryChangedOnDiskAfterTheReadIsKept() throws Exception {
        put(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        String edited = KIT.replace("'YWI='", "'Y2Q='");
        Files.write(file, edited.getBytes(StandardCharsets.UTF_8));

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), "new"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(text()).isEqualTo(edited);
    }

    /**
     * Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind, as
     * {@code ConfigSensitiveDiagnosticRedactionTest} does: with one, the binder reports unwritten keys through the mock's
     * {@code getLogger()} instead of the framework logger this test captures, so a warning assertion would depend on
     * test order.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private UltiToolsPlugin plugin() {
        UltiToolsPlugin plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("BinaryKit");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        return plugin;
    }

    private Kit entity() throws Exception {
        Files.write(directory.resolve(ENTITY_FILE), ENTITY_TEXT.getBytes(StandardCharsets.UTF_8));
        Kit kit = new Kit(ENTITY_FILE);
        kit.init(plugin());
        assertThat(kit.motd).isEqualTo("old");
        return kit;
    }

    private String entityText() throws Exception {
        return new String(Files.readAllBytes(directory.resolve(ENTITY_FILE)), StandardCharsets.UTF_8);
    }

    /** Two declared settings; the binary is an operator key the entity does not declare. */
    @ConfigEntity(ENTITY_FILE)
    public static class Kit extends AbstractConfigEntity {
        @ConfigEntry(path = "motd") String motd = "default";
        @ConfigEntry(path = "title") String title = "default";

        public Kit(String path) {
            super(path);
        }
    }

    private static Map<List<String>, Object> values(List<String> path, Object value) {
        Map<List<String>, Object> values = new LinkedHashMap<>();
        values.put(path, value);
        return values;
    }

    private long put(String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        File target = file.toFile();
        assertThat(target.setLastModified(target.lastModified() - 10_000L)).isTrue();
        return target.lastModified();
    }

    private String text() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
