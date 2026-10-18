package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.lenient;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.manager.ConfigManager;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Case 54 ({@code fc79b5a955b12ae551eaf0aa0e8e4bafa057e0b813c6e03fbf932dba5ab6bb5f}, UltiKits/UltiTools-Reborn#673,
 * "Sensitive map subkeys and values remain redacted in diagnostics"): a configuration diagnostic bound for the server
 * log never names a map key below a secret-shaped key. Below a secret-shaped field name, declared path segment or map
 * key, every deeper key shows as {@code <redacted>}; the secret-shaped key itself stays visible, and a diagnostic for a
 * non-secret map still names the real key and value so the operator can act on it.
 * <p>
 * Every credential here is a synthetic string ({@code tok-SYNTH-*}) that is not itself secret-shaped, so the redaction
 * is proven to come from the parent key. The fixtures hold no comments, so a reload writes nothing; each row checks that
 * the file keeps its bytes.
 */
class ConfigSensitiveDiagnosticRedactionTest {

    /** The setting under every row: one block per secret shape, plus one non-secret control map. */
    private static final String TEXT = "credentials:\n  tok-SYNTH-0001: label-base-1\n"
            + "auth:\n  labels:\n    tok-SYNTH-0002: label-base-2\n"
            + "tokenLists:\n  tok-SYNTH-0003:\n  - elem-base-3\n"
            + "servers:\n  apikeys:\n    tok-SYNTH-0004: label-base-4\n"
            + "regions:\n  spot-SYNTH-0005: label-base-5\n";

    /** Every synthetic key that sits below a secret-shaped key in a fixture, and every value held there. */
    private static final List<String> HIDDEN = Arrays.asList("tok-SYNTH-0001", "tok-SYNTH-0002", "tok-SYNTH-0003",
            "tok-SYNTH-0004", "label-base-1", "label-base-2", "elem-base-3", "label-base-4");

    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    private ConfigManager manager;
    private final Logger framework = Logger.getLogger("com.ultikits.ultitools");
    private final List<String> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { records.add(render(record)); }
        @Override public void flush() { /* Records are appended straight to the list. */ }
        @Override public void close() { /* No resource. */ }
    };

    @BeforeEach
    void setUp() throws Exception {
        clearLeakedUltiToolsInstance();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("RedactionModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        manager = new ConfigManager();
        capture.setLevel(Level.ALL);
        framework.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        framework.removeHandler(capture);
    }

    // ---- Task 1: the reload merge (case 54) ----

    @Test
    void reloadConflictHidesCredentialMapKey() throws Exception {
        Secrets entity = register(TEXT);
        entity.credentials.put("tok-SYNTH-0001", "label-module-1");
        String operator = TEXT.replace("tok-SYNTH-0001: label-base-1", "tok-SYNTH-0001: label-operator-1");
        write(operator);

        manager.reloadConfigs(plugin);

        assertThat(entity.credentials).as("file wins, keyed by the real key").containsExactly(entry("tok-SYNTH-0001", "label-operator-1"));
        assertThat(read()).as("the reload writes nothing").isEqualTo(operator);
        assertNoRecordHolds("tok-SYNTH-0001", "label-module-1", "label-operator-1");
        assertThat(warningsContaining("'credentials.<redacted>'", "file wins")).hasSize(1);
    }

    @Test
    void reloadConflictHidesKeyBelowSecretPathSegment() throws Exception {
        Secrets entity = register(TEXT);
        entity.labels.put("tok-SYNTH-0002", "label-module-2");
        String operator = TEXT.replace("tok-SYNTH-0002: label-base-2", "tok-SYNTH-0002: label-operator-2");
        write(operator);

        manager.reloadConfigs(plugin);

        assertThat(entity.labels).containsExactly(entry("tok-SYNTH-0002", "label-operator-2"));
        assertThat(read()).isEqualTo(operator);
        assertNoRecordHolds("tok-SYNTH-0002", "label-module-2", "label-operator-2");
        assertThat(warningsContaining("'auth.labels.<redacted>'", "file wins")).hasSize(1);
    }

    @Test
    void reloadListConflictHidesKey() throws Exception {
        Secrets entity = register(TEXT);
        entity.tokenLists.put("tok-SYNTH-0003", new ArrayList<>(Collections.singletonList("elem-module-3")));
        String operator = TEXT.replace("- elem-base-3", "- elem-operator-3");
        write(operator);

        manager.reloadConfigs(plugin);

        assertThat(entity.tokenLists).containsExactly(entry("tok-SYNTH-0003", Collections.singletonList("elem-operator-3")));
        assertThat(read()).isEqualTo(operator);
        assertNoRecordHolds("tok-SYNTH-0003", "elem-module-3", "elem-operator-3");
        assertThat(warningsContaining("'tokenLists.<redacted>'", "discarded the in-memory value; file wins")).hasSize(1);
    }

    @Test
    void nestedSecretKeyHidesItsChildren() throws Exception {
        Secrets entity = register(TEXT);
        entity.servers.put("apikeys", new LinkedHashMap<>(Collections.singletonMap("tok-SYNTH-0004", "label-module-4")));
        String operator = TEXT.replace("tok-SYNTH-0004: label-base-4", "tok-SYNTH-0004: label-operator-4");
        write(operator);

        manager.reloadConfigs(plugin);

        assertThat(entity.servers.get("apikeys")).containsExactly(entry("tok-SYNTH-0004", "label-operator-4"));
        assertThat(read()).isEqualTo(operator);
        assertNoRecordHolds("tok-SYNTH-0004", "label-module-4", "label-operator-4");
        assertThat(warningsContaining("'servers.apikeys.<redacted>'", "file wins")).hasSize(1);
    }

    /** Control: a conflict in a non-secret map still names the real key and the discarded value. */
    @Test
    void reloadConflictInNonSecretMapNamesTheRealKeyAndValue() throws Exception {
        Secrets entity = register(TEXT);
        entity.regions.put("spot-SYNTH-0005", "label-module-5");
        String operator = TEXT.replace("spot-SYNTH-0005: label-base-5", "spot-SYNTH-0005: label-operator-5");
        write(operator);

        manager.reloadConfigs(plugin);

        assertThat(entity.regions).containsExactly(entry("spot-SYNTH-0005", "label-operator-5"));
        assertThat(read()).isEqualTo(operator);
        assertNoRecordHolds();
        assertThat(warningsContaining("reload conflict at 'regions.spot-SYNTH-0005': discarded in-memory value label-module-5;"
                + " file wins")).hasSize(1);
    }

    // ---- fixture ----

    private Secrets register(String text) throws Exception {
        write(text);
        Secrets entity = new Secrets("secrets.yml");
        manager.register(plugin, entity);
        assertThat(read()).as("loading writes nothing").isEqualTo(text);
        return entity;
    }

    private void write(String text) throws Exception {
        Files.write(directory.resolve("secrets.yml"), text.getBytes(StandardCharsets.UTF_8));
    }

    private String read() throws Exception {
        return new String(Files.readAllBytes(directory.resolve("secrets.yml")), StandardCharsets.UTF_8);
    }

    /** Asserts that no captured record of any level holds a fixture's hidden key or value, nor any of {@code extra}. */
    private void assertNoRecordHolds(String... extra) {
        List<String> forbidden = new ArrayList<>(HIDDEN);
        forbidden.addAll(Arrays.asList(extra));
        List<String> captured = new ArrayList<>(records);
        for (String text : forbidden) {
            assertThat(captured).as("no captured record holds %s", text).noneMatch(record -> record.contains(text));
        }
    }

    private List<String> warningsContaining(String... fragments) {
        List<String> matching = new ArrayList<>();
        for (String record : new ArrayList<>(records)) {
            boolean all = record.startsWith(Level.WARNING.getName() + ": ");
            for (String fragment : fragments) { all &= record.contains(fragment); }
            if (all) { matching.add(record); }
        }
        return matching;
    }

    /** The level, the message, its parameters and any attached failure's message: everything a log line can carry. */
    private static String render(LogRecord record) {
        StringBuilder text = new StringBuilder(record.getLevel().getName()).append(": ").append(record.getMessage());
        if (record.getParameters() != null) { text.append(' ').append(Arrays.toString(record.getParameters())); }
        if (record.getThrown() != null) { text.append(' ').append(record.getThrown()); }
        return text.toString();
    }

    /**
     * Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind (as
     * InPlaceFallbackRestoreTest does): with one, the binder reports through the mock's {@code getLogger()} instead of
     * the framework logger this test captures.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @ConfigEntity("secrets.yml")
    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions above
    public static class Secrets extends AbstractConfigEntity {
        @ConfigEntry Map<String, String> credentials = new LinkedHashMap<>();
        @ConfigEntry(path = "auth.labels") Map<String, String> labels = new LinkedHashMap<>();
        @ConfigEntry Map<String, List<String>> tokenLists = new LinkedHashMap<>();
        @ConfigEntry Map<String, Map<String, String>> servers = new LinkedHashMap<>();
        @ConfigEntry Map<String, String> regions = new LinkedHashMap<>();

        public Secrets(String path) { super(path); }
    }
}
