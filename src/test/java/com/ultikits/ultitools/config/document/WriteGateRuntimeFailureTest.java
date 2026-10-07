package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
import org.mockito.Mockito;
import org.yaml.snakeyaml.emitter.EmitterException;
import org.yaml.snakeyaml.error.YAMLException;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigEntryPresenceException;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.config.EntryPresence;
import com.ultikits.ultitools.config.OperatorFiles;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * UltiKits/UltiTools-Reborn#624, swept by class: a runtime failure of the YAML library anywhere in a gated write - while
 * the edit is applied to the document, while it is rendered, or while the rendering is parsed back for the self-check -
 * is the write gate's refusal. An explicit write throws {@link ConfigWriteRefusedException} naming the key; an automatic
 * write takes its refusal warning; the file keeps its bytes and the caller's save state is as for any refusal; no
 * {@code RuntimeException} leaves the write path, and no log line quotes a value.
 * <p>
 * Why it cannot overwrite operator content: a refusal writes nothing, so the file keeps every byte.
 */
@DisplayName("A YAML library failure in a gated write is a refusal on every write path (#624)")
class WriteGateRuntimeFailureTest {

    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);
    private static final String RULES = "autoreply.yml";
    private static final String COMMENTED = "commented.yml";
    /** A value-shaped text in every injected message; no log line or reason may carry it. */
    private static final String LEAK = "leaked-value-7f3a";
    private static final String RULES_TEXT = "# Auto replies\n"
            + "autoreply:\n"
            + "  enabled: true\n"
            + "  rules:\n"
            + "    a:\n"
            + "      keyword: hi\n"
            + "      response: hello\n";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private final List<LogRecord> logged = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                logged.add(record);
            }
        }

        @Override
        public void flush() {
            // In-memory capture.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    @ConfigEntity(RULES)
    public static class Rules extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.enabled") boolean enabled = true;
        @ConfigEntry(path = "autoreply.rules") Map<String, Map<String, String>> rules = new LinkedHashMap<>();

        public Rules(String path) {
            super(path);
        }
    }

    public static class Commented extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "{fx.interval}", previousComments = {"Old interval text"})
        int interval = 300;
        @ConfigEntry(path = "name") String name = "lobby";

        public Commented(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("GateModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.i18n("fx.interval")).thenReturn("New interval text");
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        Logger.getLogger("com.ultikits").addHandler(capture);
    }

    /**
     * Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind (gate-1 F1): with one,
     * the framework logs through the mock's {@code getLogger()}, which is {@code null}, instead of its own logger.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @AfterEach
    void tearDown() {
        OperatorFileWriter.FAULT.set(null);
        Logger.getLogger("com.ultikits").removeHandler(capture);
    }

    // ------------------------------------------------------------------------------------------- the issue as filed

    @Test
    @DisplayName("#624: an entry written into a null placeholder with an inline comment is refused by both overloads")
    void placeholderWithAnInlineCommentIsRefusedByBothOverloads() throws Exception {
        String text = "autoreply:\n  enabled: true\n  rules:\n    foo: ~  # placeholder\n";
        put(RULES, text);
        Rules config = new Rules(RULES);
        config.init(plugin);
        logged.clear();

        config.rules.put("foo", rule("hi", "reply-value-91c2"));
        Throwable twoArgument = catchThrowable(() -> config.saveOperatorMapEntry("autoreply.rules", "foo"));
        Throwable conditional = catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "foo"));

        for (Throwable thrown : new Throwable[] {twoArgument, conditional}) {
            assertThat(thrown).as("a refusal, not the library's EmitterException")
                    .isInstanceOf(ConfigWriteRefusedException.class).isNotInstanceOf(ConfigEntryPresenceException.class);
            assertThat(((ConfigWriteRefusedException) thrown).getReason())
                    .isEqualTo("the file cannot be written at autoreply.rules.foo: rendering the document failed"
                            + " (EmitterException)")
                    .doesNotContain("reply-value-91c2");
        }
        assertThat(read(RULES)).isEqualTo(text);
        assertThat(Files.getLastModifiedTime(tempDir.resolve(RULES))).isEqualTo(OLD);
    }

    // ------------------------------------------------------------------------------------ the class, every write path

    static Stream<Arguments> faults() {
        List<Arguments> cases = new ArrayList<>();
        for (OperatorFileWriter.Step step : OperatorFileWriter.Step.values()) {
            cases.add(Arguments.of(step, "YAMLException", (Supplier<RuntimeException>) () -> new YAMLException(LEAK)));
            cases.add(Arguments.of(step, "EmitterException", (Supplier<RuntimeException>) () -> new EmitterException(LEAK)));
            cases.add(Arguments.of(step, "ClassCastException", (Supplier<RuntimeException>) () -> new ClassCastException(LEAK)));
        }
        return cases.stream();
    }

    /** The step the refusal reason names for a failure injected at {@code step} (gate-1 F7). */
    private static String named(OperatorFileWriter.Step step) {
        switch (step) {
            case EDIT:
                return "editing the document failed";
            case RENDER:
                return "rendering the document failed";
            default:
                return "checking the rendered document failed";
        }
    }

    private void arm(OperatorFileWriter.Step at, Supplier<RuntimeException> failure) {
        OperatorFileWriter.FAULT.set(step -> {
            if (step == at) {
                throw failure.get();
            }
        });
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    @DisplayName("explicit operator writes and a panel edit: ConfigWriteRefusedException naming the key, file unchanged")
    void explicitWritesAreRefused(OperatorFileWriter.Step step, String type, Supplier<RuntimeException> failure)
            throws Exception {
        Rules config = loadedRules();

        config.enabled = false;
        arm(step, failure);
        assertRefused(() -> config.saveOperatorChange("autoreply.enabled"), "autoreply.enabled");
        Throwable named = catchThrowable(() -> config.saveOperatorChange("autoreply.enabled"));
        assertThat(((ConfigWriteRefusedException) named).getReason()).as("the reason names the step that failed")
                .endsWith(named(step) + " (" + type + ")");
        config.rules.put("b", rule("ip", "here"));
        assertRefused(() -> config.saveOperatorMapEntry("autoreply.rules", "b"), "autoreply.rules.b");
        assertRefused(() -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "b"),
                "autoreply.rules.b");
        JsonObject edit = new JsonObject();
        edit.addProperty("autoreply.enabled", false);
        assertRefused(() -> config.updateProperties(edit), "autoreply.enabled");

        assertThat(read(RULES)).isEqualTo(RULES_TEXT);
        assertThat(Files.getLastModifiedTime(tempDir.resolve(RULES))).isEqualTo(OLD);
        assertNoValueLogged();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    @DisplayName("panel batch: refused as a whole, every file unchanged")
    void panelBatchIsRefused(OperatorFileWriter.Step step, String type, Supplier<RuntimeException> failure)
            throws Exception {
        put(RULES, RULES_TEXT);
        ConfigManager manager = new ConfigManager();
        Rules config = new Rules(RULES);
        manager.register(plugin, config);
        logged.clear();
        JsonObject values = new JsonObject();
        values.addProperty("autoreply.enabled", false);
        JsonObject files = new JsonObject();
        files.add(RULES, values);
        JsonObject root = new JsonObject();
        root.add("GateModule", files);

        arm(step, failure);
        Throwable thrown = catchThrowable(() -> manager.loadFromJson(root.toString()));

        assertThat(thrown).isInstanceOf(ConfigWriteRefusedException.class);
        assertThat(((ConfigWriteRefusedException) thrown).getReason()).contains("autoreply.enabled");
        assertThat(config.enabled).as("the panel value is rolled back").isTrue();
        assertThat(read(RULES)).isEqualTo(RULES_TEXT);
        assertNoValueLogged();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    @DisplayName("a module save: the gate's refusal warning, the change stays unsaved, file unchanged")
    void moduleSaveIsRefused(OperatorFileWriter.Step step, String type, Supplier<RuntimeException> failure)
            throws Exception {
        Rules config = loadedRules();

        config.enabled = false;
        arm(step, failure);
        assertThatCode(config::save).doesNotThrowAnyException();

        assertThat(read(RULES)).isEqualTo(RULES_TEXT);
        assertThat(config.isModifiedSinceSnapshot()).as("the change stays unsaved").isTrue();
        assertGateWarning("autoreply.enabled");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    @DisplayName("OperatorFiles.write: REFUSED, file unchanged")
    void operatorFileWriteIsRefused(OperatorFileWriter.Step step, String type, Supplier<RuntimeException> failure)
            throws Exception {
        put("kit.yml", "# my kit\nitems: [a]\nnote: keep\n");
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(tempDir.resolve("kit.yml").toFile());

        arm(step, failure);
        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot,
                Collections.singletonMap(Collections.singletonList("items"), (Object) Collections.singletonList("b")));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertThat(read("kit.yml")).isEqualTo("# my kit\nitems: [a]\nnote: keep\n");
        assertGateWarning("items");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    @DisplayName("start-up insert: the gate's refusal warning, the declared default runs, file unchanged")
    void startUpInsertIsRefused(OperatorFileWriter.Step step, String type, Supplier<RuntimeException> failure)
            throws Exception {
        String lacking = "# Auto replies\nautoreply:\n  rules:\n    a:\n      keyword: hi\n      response: hello\n";
        put(RULES, lacking);
        Rules config = new Rules(RULES);

        arm(step, failure);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        assertThat(read(RULES)).isEqualTo(lacking);
        assertThat(config.enabled).isTrue();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        assertThat(config.isPresentInFile("autoreply.enabled")).isFalse();
        assertGateWarning("autoreply.enabled");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    @DisplayName("comment-only write at load: the gate's refusal warning, the file keeps its comments")
    void commentOnlyWriteIsRefused(OperatorFileWriter.Step step, String type, Supplier<RuntimeException> failure)
            throws Exception {
        String text = "# Old interval text\ninterval: 300\nname: lobby\n";
        put(COMMENTED, text);
        Commented config = new Commented(COMMENTED);

        arm(step, failure);
        assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

        assertThat(read(COMMENTED)).isEqualTo(text);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        assertGateWarning("interval (comment)");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("faults")
    @DisplayName("registration batch flush: the gate's refusal warning, no exception, file unchanged")
    void batchFlushIsRefused(OperatorFileWriter.Step step, String type, Supplier<RuntimeException> failure)
            throws Exception {
        String lacking = "# Auto replies\nautoreply:\n  rules:\n    a:\n      keyword: hi\n      response: hello\n";
        put(RULES, lacking);
        Rules config = new Rules(RULES);
        config.initForBatch(plugin);

        arm(step, failure);
        assertThatCode(config::flushInitializationWrite).doesNotThrowAnyException();

        assertThat(read(RULES)).isEqualTo(lacking);
        assertThat(config.isLastLoadUnparseable()).as("a refusal does not protect the entity").isFalse();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        assertGateWarning("autoreply.enabled");
    }

    // -------------------------------------------------------------------------------------------------- helpers

    private Rules loadedRules() throws IOException {
        put(RULES, RULES_TEXT);
        Rules config = new Rules(RULES);
        config.init(plugin);
        logged.clear();
        return config;
    }

    private interface Call {
        void run() throws Exception;
    }

    private void assertRefused(Call call, String key) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).as("a refusal, never the library's runtime exception")
                .isInstanceOf(ConfigWriteRefusedException.class);
        assertThat(((ConfigWriteRefusedException) thrown).getReason()).contains(key).doesNotContain(LEAK);
        assertThat(thrown.getMessage()).doesNotContain(LEAK);
    }

    private void assertGateWarning(String key) {
        List<String> messages = messages();
        assertThat(messages).as("the gate's one refusal warning")
                .anySatisfy(message -> assertThat(message).contains("was not written").contains(key));
        assertNoValueLogged();
    }

    private void assertNoValueLogged() {
        assertThat(messages()).noneMatch(message -> message.contains(LEAK));
        synchronized (logged) {
            for (LogRecord record : logged) {
                assertThat(record.getThrown()).as("no stack trace carries the value").isNull();
            }
        }
    }

    private List<String> messages() {
        List<String> messages = new ArrayList<>();
        synchronized (logged) {
            for (LogRecord record : logged) {
                messages.add(String.valueOf(record.getMessage()));
            }
        }
        return messages;
    }

    private void put(String name, String text) throws IOException {
        Files.write(tempDir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(tempDir.resolve(name), OLD);
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(name)), StandardCharsets.UTF_8);
    }

    private static Map<String, String> rule(String keyword, String response) {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("keyword", keyword);
        rule.put("response", response);
        return rule;
    }
}
