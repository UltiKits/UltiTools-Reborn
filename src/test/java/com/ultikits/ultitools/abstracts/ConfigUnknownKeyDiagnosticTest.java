package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;
import com.ultikits.ultitools.manager.ConfigManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Startup ownership diagnostics are advisory, read-only, and use the binder's actual paths (#499). */
class ConfigUnknownKeyDiagnosticTest {
    private static final String PATH = "config/diagnostic.yml";
    private static final String DIAGNOSTIC = "no @ConfigEntry declaration";
    private static final FileTime ORIGINAL_TIME = FileTime.fromMillis(1_600_000_000_000L);

    @TempDir Path directory;
    private UltiToolsPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DiagnosticModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
    }

    private Path file() { return directory.resolve(PATH); }

    private void write(String text) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file(), ORIGINAL_TIME);
    }

    private List<String> diagnostics(ConfigWarningCapture capture) {
        return capture.messagesContaining(DIAGNOSTIC);
    }

    @Test
    void startupWarnsForRemovedEntryWithoutChangingFile() throws Exception {
        write("# operator note\nknown: true\noldSetting: 1234567 # keep this\n");
        byte[] before = Files.readAllBytes(file());
        Values entity = new Values(PATH);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            entity.init(plugin);
            assertThat(diagnostics(capture)).containsExactly("Module 'DiagnosticModule', file '" + PATH
                    + "', key 'oldSetting': no @ConfigEntry declaration for this key/subtree; left unchanged");
            assertThat(capture.messages()).noneMatch(message -> message.contains("1234567"));
        }
        assertThat(entity.known).isTrue();
        assertThat(entity.isPresentInFile("oldSetting")).isTrue();
        assertThat(entity.toJsonObject().has("oldSetting")).isTrue();
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(ORIGINAL_TIME);
    }

    @Test
    void nestedUnknownKeysDoNotWarnForStructuralSections() throws Exception {
        write("features:\n  chat: true\n  oldSetting: false\n");
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new DottedValues(PATH).init(plugin);
            assertThat(diagnostics(capture)).hasSize(1);
            assertThat(diagnostics(capture).get(0)).contains("key 'features.oldSetting'");
        }
    }

    @Test
    void minimalUnknownSubtreesAreReportedOnceInFileOrder() throws Exception {
        write("known: true\nretired:\n  child:\n    grandchild: hidden-value\noldList: [{item: hidden-item}]\n"
                + "oldNull: null\noldEmpty: {}\n");
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new Values(PATH).init(plugin);
            assertThat(diagnostics(capture)).hasSize(4);
            assertThat(diagnostics(capture)).satisfiesExactly(
                    message -> assertThat(message).contains("key 'retired'"),
                    message -> assertThat(message).contains("key 'oldList'"),
                    message -> assertThat(message).contains("key 'oldNull'"),
                    message -> assertThat(message).contains("key 'oldEmpty'"));
            assertThat(diagnostics(capture)).allSatisfy(message -> assertThat(message)
                    .doesNotContain("child", "grandchild", "hidden-value", "hidden-item"));
        }
    }

    @Test
    void boundMapsListsArraysAndSerializableValuesOwnTheirSubtrees() throws Exception {
        write("rules: {o.O: {wave.: [one, {inside: two}]}, empty: {}, nullable: null}\n"
                + "items: [{name: first, arbitrary: second}]\narray: [one, two]\nunique: [one, two]\n"
                + "position: {==: Vector, x: 1.0, y: 2.0, z: 3.0, ignored: {dynamic: extra}}\n"
                + "plain: {arbitrary: [{child: value}]}\n");
        byte[] before = Files.readAllBytes(file());
        Containers entity = new Containers(PATH);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            entity.init(plugin);
            assertThat(diagnostics(capture)).isEmpty();
        }
        assertThat(entity.rules).containsKey("o.O");
        assertThat(entity.items.get(0)).containsEntry("arbitrary", "second");
        assertThat(entity.array).containsExactly("one", "two");
        assertThat(entity.unique).containsExactly("one", "two");
        assertThat(entity.position).isEqualTo(new Vector(1, 2, 3));
        assertThat(entity.plain).isInstanceOf(Map.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    void aCustomConverterOwnsItsWholeSerializedValue() throws Exception {
        write("wrapped: {dynamic: {arbitrary: [one, two]}, o.O: text}\n");
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        registry.register(Wrapped.class, new ConfigConverter<Wrapped>() {
            @Override public Object toPlain(Wrapped value, ConversionContext context) { return value.data; }
            @Override public Wrapped fromPlain(Object plain, ConversionContext context) { return new Wrapped(plain); }
        }, true);
        try (MockedStatic<ConverterRegistry> registries = Mockito.mockStatic(ConverterRegistry.class, Mockito.CALLS_REAL_METHODS);
             ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            registries.when(() -> ConverterRegistry.forModule(plugin)).thenReturn(registry);
            CustomValues entity = new CustomValues(PATH);
            entity.init(plugin);
            assertThat(entity.wrapped.data).isInstanceOf(Map.class);
            assertThat(diagnostics(capture)).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("removal") // Tests the retained explicit-parser compatibility contract, not a new parser API.
    void aLegacyParserOwnsItsWholeSerializedValue() throws Exception {
        write("legacy: {joined: 'one,two', ignored: {dynamic: extra}}\n");
        LegacyValues entity = new LegacyValues(PATH);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            entity.init(plugin);
            assertThat(entity.legacy).containsExactly("one", "two");
            assertThat(diagnostics(capture)).isEmpty();
        }
    }

    @Test
    void aDeclaredScalarWithTheWrongShapeIsNotAnUnknownSubtree() throws Exception {
        write("known: {unexpected: content}\n");
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            Values entity = new Values(PATH);
            entity.init(plugin);
            assertThat(entity.known).isTrue();
            assertThat(diagnostics(capture)).isEmpty();
            assertThat(capture.messages()).anyMatch(message -> message.contains("declared as"));
        }
    }

    @Test
    void inheritedPrivateFieldsAndDefaultPathsAreRecognized() throws Exception {
        write("inherited: 7\nchild: {value: true}\nunannotated: operator-data\n");
        ChildValues entity = new ChildValues(PATH);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            entity.init(plugin);
            assertThat(((BaseValues) entity).inherited).isEqualTo(7);
            assertThat(entity.child).isTrue();
            assertThat(diagnostics(capture)).hasSize(1);
            assertThat(diagnostics(capture).get(0)).contains("key 'unannotated'");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a:\n  b:\n    c: 3\nchat:\n  emoji: {o.O: wink}\n",
            "a.b.c: 3\nchat.emoji: {o.O: wink}\n",
            "a.b:\n  c: 3\nchat:\n  emoji: {o.O: wink}\n",
            "a:\n  b.c: 3\nchat.emoji: {o.O: wink}\n"
    })
    void equivalentDottedReadingsAreKnown(String text) throws Exception {
        write(text);
        byte[] before = Files.readAllBytes(file());
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            MixedValues entity = new MixedValues(PATH);
            entity.init(plugin);
            assertThat(entity.deep).isEqualTo(3);
            assertThat(entity.emoji).containsEntry("o.O", "wink");
            assertThat(diagnostics(capture)).isEmpty();
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    void aNamePrefixDoesNotOwnAnUnrelatedWholeKey() throws Exception {
        write("features.chat: true\nfeatures.chatty: false\nfeaturesOther: true\n");
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new DottedValues(PATH).init(plugin);
            assertThat(diagnostics(capture)).hasSize(2);
            assertThat(diagnostics(capture).get(0)).contains("key 'features.chatty'");
            assertThat(diagnostics(capture).get(1)).contains("key 'featuresOther'");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a: {b: {}}\n", "a.b: {}\n", "a: null\n", "a: scalar\n", "a: [one]\n"})
    void properPrefixesRemainKnownWhenTheDeclaredLeafIsMissing(String text) throws Exception {
        write(text);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new DeepValues(PATH).init(plugin);
            assertThat(diagnostics(capture)).isEmpty();
        }
    }

    @Test
    void anUnknownSiblingUnderAnIncompleteFlatPrefixIsReported() throws Exception {
        write("a.b: {retired: preserved}\n");
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new DeepValues(PATH).init(plugin);
            assertThat(diagnostics(capture)).hasSize(1);
            assertThat(diagnostics(capture).get(0)).contains("key 'a.b.retired'");
        }
    }

    @Test
    void manyUndeclaredKeysAreCappedPerFileWithOneSummaryLine() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            text.append("retired").append(i).append(": ").append(i).append('\n');
        }
        write(text.toString());
        byte[] before = Files.readAllBytes(file());
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new EmptyValues(PATH).init(plugin);
            List<String> lines = diagnostics(capture);
            assertThat(lines).as("#829: ten keys are named, then one summary line").hasSize(11);
            for (int i = 0; i < 10; i++) {
                assertThat(lines.get(i)).contains("key 'retired" + i + "'");
            }
            assertThat(lines.get(10)).isEqualTo("Module 'DiagnosticModule', file '" + PATH
                    + "': 15 more keys have no @ConfigEntry declaration (not listed); left unchanged");
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    void exactlyTheCapIsListedWithoutASummaryLine() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            text.append("retired").append(i).append(": ").append(i).append('\n');
        }
        write(text.toString());
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new EmptyValues(PATH).init(plugin);
            assertThat(diagnostics(capture)).hasSize(10).noneMatch(line -> line.contains("more keys"));
        }
    }

    @Test
    void anEntityWithNoDeclarationsReportsItsTopLevelKeys() throws Exception {
        write("old: {child: preserved}\nother: null\n");
        byte[] before = Files.readAllBytes(file());
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new EmptyValues(PATH).init(plugin);
            assertThat(diagnostics(capture)).hasSize(2);
            assertThat(diagnostics(capture).get(0)).contains("key 'old'");
            assertThat(diagnostics(capture).get(1)).contains("key 'other'");
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# operator note\n"})
    void anEmptyDocumentWithNoDeclarationsHasNoDiagnostic(String text) throws Exception {
        write(text);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new EmptyValues(PATH).init(plugin);
            assertThat(diagnostics(capture)).isEmpty();
        }
    }

    @Test
    void onlyInitializationReadsEmitTheDiagnostic() throws Exception {
        write("known: true\nold: preserved\n");
        Values entity = new Values(PATH);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            entity.init(plugin);
            entity.reload();
            entity.save();
            assertThat(entity.toJsonObject().has("old")).isTrue();
            assertThat(entity.isPresentInFile("old")).isTrue();
            entity.updateProperties(entity.toJsonObject());
            JsonObject knownEdit = new JsonObject(); knownEdit.addProperty("known", false);
            entity.updateProperties(knownEdit);
            JsonObject unknownEdit = new JsonObject(); unknownEdit.addProperty("old", "changed");
            assertThatThrownBy(() -> entity.updateProperties(unknownEdit))
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("no declared entry");
            assertThat(diagnostics(capture)).hasSize(1);
            new Values(PATH).init(plugin);
            assertThat(diagnostics(capture)).hasSize(2);
        }
    }

    @Test
    void deferredInitializationReportsTheOriginalReadBeforeFlush() throws Exception {
        write("old: original-read\n");
        Values entity = new Values(PATH);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            entity.initForBatch(plugin);
            assertThat(diagnostics(capture)).hasSize(1);
            assertThat(diagnostics(capture).get(0)).contains("key 'old'");
            write("later: later-edit\n");
            byte[] edited = Files.readAllBytes(file());
            entity.flushInitializationWrite();
            entity.discardInitializationWrite();
            assertThat(diagnostics(capture)).hasSize(1);
            assertThat(Files.readAllBytes(file())).isEqualTo(edited);
            assertThat(entity.isPresentInFile("old")).isTrue();
            assertThat(entity.isPresentInFile("later")).isFalse();
        }
    }

    @Test
    void firstTimeCreationDoesNotDiagnoseInsertedDefaults() throws Exception {
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            new Values(PATH).init(plugin);
            assertThat(diagnostics(capture)).isEmpty();
        }
        assertThat(file()).exists();
    }

    @Test
    void failedLoadsAndValidationRefusalsDoNotAddUnknownKeyDiagnostics() throws Exception {
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            write("known: [\nold: preserved\n");
            byte[] malformed = Files.readAllBytes(file());
            new Values(PATH).init(plugin);
            assertThat(Files.readAllBytes(file())).isEqualTo(malformed);
            Files.delete(file()); Files.createDirectory(file());
            new Values(PATH).init(plugin);
            assertThat(file()).isDirectory();
            Files.delete(file());
            write("known: true\nlimit: 0\nold: preserved\n");
            assertThatThrownBy(() -> new ValidatedValues(PATH).init(plugin)).isInstanceOf(ConfigurationException.class);
            write("features.chat: true\nfeatures: {chat: false}\nold: preserved\n");
            assertThatThrownBy(() -> new DottedValues(PATH).init(plugin))
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("two forms");
            assertThat(diagnostics(capture)).isEmpty();
        }
    }

    @Test
    void offThreadInitializationDoesNotAddUnknownKeyDiagnostics() throws Exception {
        write("known: true\nold: preserved\n");
        byte[] before = Files.readAllBytes(file());
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class);
             ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            bukkit.when(Bukkit::getServer).thenReturn(Mockito.mock(Server.class));
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger(ConfigUnknownKeyDiagnosticTest.class.getName()));
            new Values(PATH).init(plugin);
            assertThat(diagnostics(capture)).isEmpty();
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    void diagnosticOutputIsRedactedSingleLineAndWarningOnly() throws Exception {
        lenient().when(plugin.getPluginName()).thenReturn("Diagnostic\nModule");
        write("vault: {token: {keep: true, SYNTH-0001: hidden-value}}\n"
                + "vault.token.SYNTH-0002: hidden-value-2\n\"old\\noption\": hidden-value-3\n");
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(AbstractConfigEntity.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().contains(DIAGNOSTIC)) { records.add(record); }
            }
            @Override public void flush() {
                // Records are stored in memory; there is no buffered output to flush.
            }
            @Override public void close() {
                // This handler owns no resources; the enclosing finally removes it from the logger.
            }
        };
        logger.addHandler(handler);
        try {
            new SecretPrefixValues(PATH).init(plugin);
            assertThat(records).hasSize(3).allSatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getThrown()).isNull();
                assertThat(record.getParameters()).isNull();
                assertThat(record.getMessage()).doesNotContain("SYNTH-0001", "SYNTH-0002", "hidden-value");
                assertThat(record.getMessage()).doesNotMatch("(?s).*[\\p{Cntrl}\\u2028\\u2029\\u0085].*");
            });
            assertThat(records.get(0).getMessage()).contains("Diagnostic Module", "vault.token.<redacted>");
            assertThat(records.get(1).getMessage()).contains("vault.token.<redacted>");
            assertThat(records.get(2).getMessage()).contains("old option");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void directoryEntitiesNameTheirConcreteFiles() throws Exception {
        Path configs = Files.createDirectory(directory.resolve("configs"));
        Files.write(configs.resolve("one.yml"), "known: true\noldOne: first\n".getBytes(StandardCharsets.UTF_8));
        Files.write(configs.resolve("two.yml"), "known: false\noldTwo: second\n".getBytes(StandardCharsets.UTF_8));
        Files.write(configs.resolve("notes.txt"), "not yaml".getBytes(StandardCharsets.UTF_8));
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        try (MockedStatic<ConverterRegistry> registries = Mockito.mockStatic(ConverterRegistry.class, Mockito.CALLS_REAL_METHODS);
             ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            registries.when(() -> ConverterRegistry.hasModule(plugin)).thenReturn(true);
            registries.when(() -> ConverterRegistry.forModule(plugin)).thenReturn(registry);
            ConfigManager manager = new ConfigManager();
            manager.register(plugin, new DirectoryValues("configs"));
            assertThat(manager.getAllConfigEntities(plugin)).containsOnlyKeys("configs/one.yml", "configs/two.yml");
            assertThat(diagnostics(capture)).hasSize(2);
            assertThat(diagnostics(capture)).anySatisfy(message -> assertThat(message).contains("configs/one.yml", "oldOne"));
            assertThat(diagnostics(capture)).anySatisfy(message -> assertThat(message).contains("configs/two.yml", "oldTwo"));
            assertThat(diagnostics(capture)).noneMatch(message -> message.contains("notes.txt"));
        }
    }

    static class Values extends AbstractConfigEntity {
        @ConfigEntry boolean known = true;
        public Values(String path) { super(path); }
    }

    static class DottedValues extends AbstractConfigEntity {
        @ConfigEntry(path = "features.chat") boolean chat = true;
        public DottedValues(String path) { super(path); }
    }

    static class DeepValues extends AbstractConfigEntity {
        @ConfigEntry(path = "a.b.c") int deep = 1;
        public DeepValues(String path) { super(path); }
    }

    static class MixedValues extends DeepValues {
        @ConfigEntry(path = "chat.emoji") Map<String, String> emoji = new LinkedHashMap<>();
        public MixedValues(String path) { super(path); }
    }

    static class BaseValues extends AbstractConfigEntity {
        @ConfigEntry private int inherited = 1;
        public BaseValues(String path) { super(path); }
    }

    static class ChildValues extends BaseValues {
        @ConfigEntry(path = "child.value") boolean child;
        String unannotated = "module-data";
        public ChildValues(String path) { super(path); }
    }

    static class EmptyValues extends AbstractConfigEntity {
        public EmptyValues(String path) { super(path); }
    }

    static class Containers extends AbstractConfigEntity {
        @ConfigEntry Map<String, Object> rules = new LinkedHashMap<>();
        @ConfigEntry List<Map<String, String>> items = new ArrayList<>();
        @ConfigEntry String[] array = new String[0];
        @ConfigEntry Set<String> unique = new LinkedHashSet<>();
        @ConfigEntry Vector position = new Vector();
        @ConfigEntry Object plain = "default";
        public Containers(String path) { super(path); }
    }

    static final class Wrapped {
        private final Object data;
        Wrapped(Object data) { this.data = data; }
    }

    static class CustomValues extends AbstractConfigEntity {
        @ConfigEntry Wrapped wrapped = new Wrapped(Collections.emptyMap());
        public CustomValues(String path) { super(path); }
    }

    @SuppressWarnings("removal") // Deliberately exercises the retained explicit-parser adapter.
    static class LegacyValues extends AbstractConfigEntity {
        @ConfigEntry(parser = JoinedParser.class) Set<String> legacy = new LinkedHashSet<>(Collections.singleton("default"));
        public LegacyValues(String path) { super(path); }
    }

    @SuppressWarnings("removal") // Deliberately exercises the retained explicit-parser adapter.
    public static class JoinedParser extends ConfigParser<Set<String>> {
        @Override public Set<String> parse(Object value) {
            return new LinkedHashSet<>(Arrays.asList(((ConfigurationSection) value).getString("joined").split(",")));
        }
        @Override public MemorySection serializeToMemorySection(Set<String> value) {
            MemoryConfiguration result = new MemoryConfiguration();
            result.set("joined", String.join(",", value));
            return result;
        }
    }

    static class ValidatedValues extends Values {
        @ConfigEntry @Range(min = 1, max = 5) int limit = 1;
        public ValidatedValues(String path) { super(path); }
    }

    static class SecretPrefixValues extends AbstractConfigEntity {
        @ConfigEntry(path = "vault.token.keep") boolean keep = true;
        public SecretPrefixValues(String path) { super(path); }
    }

    @ConfigEntity("configs")
    public static class DirectoryValues extends Values {
        public DirectoryValues(String path) { super(path); }
    }
}
