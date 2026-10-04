package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.entities.Capability;

/**
 * The framework's own {@code plugins/UltiTools/config.yml} gets its missing panel keys through the config write gate,
 * insert only (inventory B1, UltiKits/UltiTools-Reborn#605; maintainer decision of 2026-10-04 "what code may write, by
 * file type": shipped files get missing keys and the framework's own comments, never a change to existing content).
 * <p>
 * Measured on every released 6.2.x {@code config.yml} (copied as bytes from the released jars, see
 * {@code src/test/resources/framework-config/MANIFEST.md}) and on the current one: the keys and their comments are
 * inserted and every original line keeps its bytes. A file whose other lines the renderer would change is not
 * written: one WARNING names the keys and the reason, the migration does not ask for a reload, and the built-in
 * defaults - the jar's {@code config.yml}, which {@code JavaPlugin#getConfig()} uses as defaults - answer for the
 * missing keys.
 */
class FrameworkConfigMigrationGateTest {

    /** Every key the migration inserts. */
    private static final List<List<String>> MIGRATED = migratedPaths();

    @TempDir
    Path directory;

    private final List<LogRecord> gateWarnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                gateWarnings.add(record);
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
    private final List<LogRecord> migrationLog = new ArrayList<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        Logger.getLogger("com.ultikits.ultitools.config.document.OperatorFileWriter").addHandler(capture);
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                migrationLog.add(record);
            }

            @Override
            public void flush() {
                // Records are kept in memory; there is nothing to flush.
            }

            @Override
            public void close() {
                // No resource is held; there is nothing to close.
            }
        });
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("com.ultikits.ultitools.config.document.OperatorFileWriter").removeHandler(capture);
    }

    static Stream<String> releasedFixtures() {
        return Stream.of("6.2.0", "6.2.1", "6.2.2", "6.2.3", "6.2.4", "6.2.5");
    }

    @ParameterizedTest(name = "released {0} config.yml")
    @MethodSource("releasedFixtures")
    void everyReleasedConfigGetsTheKeysWithEveryOriginalByteKept(String version) throws Exception {
        byte[] original = resource("/framework-config/config-" + version + ".yml");

        assertInsertedOnly(original);
    }

    @Test
    void theCurrentShippedConfigWithoutItsCapabilityBlockGetsTheBlockBackWithEveryOtherByteKept() throws Exception {
        String shipped = new String(resource("/config.yml"), StandardCharsets.UTF_8);
        int block = shipped.indexOf("  # 远程能力开关");
        assertThat(block).as("the shipped file carries the capability block").isPositive();
        byte[] withoutBlock = shipped.substring(0, block).getBytes(StandardCharsets.UTF_8);

        assertInsertedOnly(withoutBlock);
    }

    @Test
    void theCurrentShippedConfigNeedsNoWrite() throws Exception {
        File file = write(resource("/config.yml"));
        long modified = file.lastModified();

        assertThat(migrate(file)).isFalse();

        assertThat(Files.readAllBytes(file.toPath())).isEqualTo(resource("/config.yml"));
        assertThat(file.lastModified()).isEqualTo(modified);
        assertThat(gateWarnings).isEmpty();
    }

    @Test
    void anAlignedTrailingCommentIsNotWrittenAndTheBuiltInDefaultsAnswer() throws Exception {
        assertRefused("language: zh   # my note\ndatasource:\n  type: sqlite\n", "layout");
    }

    @Test
    void anAnchoredFileIsNotWrittenAndTheBuiltInDefaultsAnswer() throws Exception {
        assertRefused("base: &b {x: 1}\ncopy: *b\nlanguage: zh\n", "anchors");
    }

    @Test
    void aHexNumberKeepsItsTextWhenTheKeysAreInserted() throws Exception {
        byte[] original = "language: zh\nhex: 0x1F\n".getBytes(StandardCharsets.UTF_8);

        assertInsertedOnly(original);
    }

    @Test
    void aDottedKeyStaysOneKeyWhenTheKeysAreInserted() throws Exception {
        byte[] original = "language: zh\no.O: x\n".getBytes(StandardCharsets.UTF_8);

        Path written = assertInsertedOnly(original);

        Map<String, Object> plain = ConfigDocument.parse(new String(Files.readAllBytes(written), StandardCharsets.UTF_8))
                .toPlain();
        assertThat(plain).containsEntry("o.O", "x").doesNotContainKey("o");
    }

    @Test
    void anExplicitNullOperatorValueIsNeverReplaced() throws Exception {
        byte[] original = "language: zh\nultipanel:\n  capabilities:\n    commands:\n".getBytes(StandardCharsets.UTF_8);
        File file = write(original);

        migrate(file);

        String after = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> plain = ConfigDocument.parse(after).toPlain();
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) ((Map<String, Object>) plain.get("ultipanel"))
                .get("capabilities");
        assertThat(capabilities).containsEntry("commands", null);
        assertThat(after).startsWith(new String(original, StandardCharsets.UTF_8).trim());
    }

    /** Runs the migration on {@code original}; asserts written, every original line kept in order, values = original + keys. */
    private Path assertInsertedOnly(byte[] original) throws Exception {
        File file = write(original);

        assertThat(migrate(file)).as("the migration wrote the file").isTrue();

        byte[] after = Files.readAllBytes(file.toPath());
        List<String> before = lines(new String(original, StandardCharsets.UTF_8));
        List<String> now = lines(new String(after, StandardCharsets.UTF_8));
        int matched = 0;
        for (String line : now) {
            if (matched < before.size() && line.equals(before.get(matched))) {
                matched++;
            }
        }
        assertThat(matched).as("every original line is kept, byte for byte and in order").isEqualTo(before.size());
        Map<String, Object> expected = ConfigDocument.parse(new String(original, StandardCharsets.UTF_8)).toPlain();
        Map<String, Object> actual = ConfigDocument.parse(new String(after, StandardCharsets.UTF_8)).toPlain();
        for (List<String> path : MIGRATED) {
            if (!contains(expected, path)) {
                assertThat(contains(actual, path)).as("inserted %s", path).isTrue();
                remove(actual, path, expected);
            }
        }
        assertThat(actual).as("nothing but the migrated keys changed").isEqualTo(expected);
        for (Capability capability : Capability.values()) {
            if (capability.getConfigKey() != null) {
                assertThat(new String(after, StandardCharsets.UTF_8)).contains("# " + capability.getCommentLines().get(0));
            }
        }
        assertThat(gateWarnings).isEmpty();
        return file.toPath();
    }

    private void assertRefused(String text, String reasonWord) throws Exception {
        byte[] original = text.getBytes(StandardCharsets.UTF_8);
        File file = write(original);
        long modified = file.lastModified();

        assertThat(migrate(file)).as("no reload is requested after a refusal").isFalse();

        assertThat(Files.readAllBytes(file.toPath())).isEqualTo(original);
        assertThat(file.lastModified()).isEqualTo(modified);
        long warnings = gateWarnings.size() + migrationLog.stream()
                .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue()).count();
        assertThat(warnings).as("one WARNING").isEqualTo(1);
        String message = gateWarnings.isEmpty() ? "" : gateWarnings.get(0).getMessage();
        assertThat(message).contains(reasonWord).contains("ultipanel.capabilities.monitoring")
                .contains("ultipanel.commands.blocklist").contains("ultipanel.files.editable-roots")
                .contains("ultipanel.logging.action-log.max-files");

        // What JavaPlugin#getConfig() returns for the refused file: the file, with the jar's config.yml as defaults.
        YamlConfiguration effective = YamlConfiguration.loadConfiguration(file);
        try (InputStream jar = getClass().getResourceAsStream("/config.yml")) {
            effective.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(jar, StandardCharsets.UTF_8)));
        }
        for (Capability capability : Capability.values()) {
            if (capability.getConfigKey() != null) {
                assertThat(effective.get(capability.getConfigPath())).as(capability.getConfigPath())
                        .isEqualTo(capability.getDefaultEnabled());
            }
        }
        assertThat(effective.getStringList("ultipanel.commands.blocklist"))
                .containsExactly("op", "deop", "stop", "restart", "reload", "ban-ip", "pardon-ip", "whitelist", "save-off",
                        "save-all");
        assertThat(effective.getStringList("ultipanel.files.editable-roots")).containsExactly("plugins", "logs");
        assertThat(effective.getLong("ultipanel.logging.action-log.max-size-bytes")).isEqualTo(1_048_576L);
        assertThat(effective.getInt("ultipanel.logging.action-log.max-files")).isEqualTo(5);
    }

    private File write(byte[] bytes) throws IOException {
        Path file = Files.createTempDirectory(directory, "cfg").resolve("config.yml");
        Files.write(file, bytes);
        file.toFile().setLastModified(file.toFile().lastModified() - 10_000L);
        return file.toFile();
    }

    private boolean migrate(File file) throws Exception {
        Method method = UltiTools.class.getDeclaredMethod("migrateCapabilitiesConfig", File.class, Logger.class);
        trustAccess(method);
        try {
            return (boolean) method.invoke(null, file, logger);
        } catch (InvocationTargetException failure) {
            throw (Exception) failure.getCause();
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void trustAccess(Method method) {
        // The migration is a private static test seam of the plugin main class (no live server needed).
        method.setAccessible(true);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    private static List<List<String>> migratedPaths() {
        List<List<String>> paths = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            if (capability.getConfigKey() != null) {
                paths.add(Arrays.asList(capability.getConfigPath().split("\\.")));
            }
        }
        paths.add(Arrays.asList("ultipanel", "commands", "blocklist"));
        paths.add(Arrays.asList("ultipanel", "files", "editable-roots"));
        paths.add(Arrays.asList("ultipanel", "logging", "action-log", "max-size-bytes"));
        paths.add(Arrays.asList("ultipanel", "logging", "action-log", "max-files"));
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static boolean contains(Map<String, Object> tree, List<String> path) {
        Object node = tree;
        for (String key : path) {
            if (!(node instanceof Map) || !((Map<String, Object>) node).containsKey(key)) {
                return false;
            }
            node = ((Map<String, Object>) node).get(key);
        }
        return true;
    }

    /** Removes {@code path} from {@code tree}, then every mapping on the way that is now empty and absent from {@code base}. */
    @SuppressWarnings("unchecked")
    private static void remove(Map<String, Object> tree, List<String> path, Map<String, Object> base) {
        List<Map<String, Object>> chain = new ArrayList<>();
        Map<String, Object> node = tree;
        for (int i = 0; i < path.size() - 1; i++) {
            chain.add(node);
            node = (Map<String, Object>) node.get(path.get(i));
        }
        node.remove(path.get(path.size() - 1));
        for (int i = path.size() - 2; i >= 0; i--) {
            Map<String, Object> child = (Map<String, Object>) chain.get(i).get(path.get(i));
            if (child.isEmpty() && !contains(base, path.subList(0, i + 1))) {
                chain.get(i).remove(path.get(i));
            }
        }
    }
}
