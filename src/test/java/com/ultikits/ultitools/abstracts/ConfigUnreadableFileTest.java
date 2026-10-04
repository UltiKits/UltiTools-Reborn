package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.manager.ConfigManager;

/** Entity-level protection across every write entry point (#511, #470, #574). */
class ConfigUnreadableFileTest {
    private static final String PATH = "protected.yml";
    @TempDir Path tempDir;
    private UltiToolsPlugin plugin;

    @ConfigEntity(PATH)
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "limit", comment = "{config.limit}") int limit = 10;
        @ConfigEntry(path = "name") String name = "default";
        public Values(String path) { super(path); }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("ProtectedModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        lenient().when(plugin.i18n("config.limit")).thenReturn("Maximum");
    }

    private Path file() { return tempDir.resolve(PATH); }
    private void put(byte[] bytes) throws IOException { Files.write(file(), bytes); }
    private void put(String text) throws IOException { put(text.getBytes(StandardCharsets.UTF_8)); }
    private JsonObject panel() {
        JsonObject panel = new JsonObject();
        panel.addProperty("limit", 99);
        return panel;
    }

    private void assertProtected(byte[] broken) throws Exception {
        put(broken);
        Values config = new Values(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(warnings.messagesContaining(PATH)).hasSize(1);
            assertThat(warnings.messages().get(0)).contains("will not be overwritten");
        }
        assertThat(config.limit).isEqualTo(10);
        assertThat(config.isLastLoadUnparseable()).isTrue();
        assertThat(config.isLastInitIncomplete()).isFalse();
        assertThat(config.isPresentInFile("limit")).isFalse();
        config.save();
        assertThatThrownBy(() -> config.updateProperties(panel()))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(PATH);
        assertThat(Files.readAllBytes(file())).isEqualTo(broken);
        assertThat(config.limit).isEqualTo(10);

        // A disk repair does not itself clear the latch; only a successful load does.
        put("limit: 25\nname: running\n");
        config.save();
        assertThat(Files.readAllBytes(file())).isEqualTo("limit: 25\nname: running\n".getBytes(StandardCharsets.UTF_8));
        config.reload();
        assertThat(config.limit).isEqualTo(25);
        assertThat(config.isLastLoadUnparseable()).isFalse();
        config.limit = 35;
        put(broken);
        config.reload();
        assertThat(config.limit).isEqualTo(35);
        config.save();
        assertThatThrownBy(() -> config.updateProperties(panel()))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(PATH);
        assertThat(Files.readAllBytes(file())).isEqualTo(broken);
    }

    @Test
    void syntaxFailureProtectsAllEntityWritePaths() throws Exception {
        assertProtected("limit: [broken\nname: default\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void globalClassTagProtectsAllEntityWritePaths() throws Exception {
        assertProtected("limit: !!java.util.UUID 'secret-specimen'\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void malformedUtf8ProtectsAllEntityWritePaths() throws Exception {
        assertProtected(new byte[]{'l', 'i', 'm', 'i', 't', ':', ' ', (byte) 0xC3, (byte) 0x28});
    }

    @Test
    void parserDiagnosticNamesLocationWithoutLeakingSource() throws Exception {
        put("name: secret-specimen\nlimit: [broken\n");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new Values(PATH).init(plugin);
            assertThat(warnings.messages()).hasSize(1);
            assertThat(warnings.messages().get(0)).contains(PATH, "YAML", "line", "column")
                    .doesNotContain("secret-specimen", "[broken");
        }
    }

    @Test
    void parserDiagnosticCannotEchoAdversarialScalarOrForgedMetadata() throws Exception {
        put("name: |\n  secret-specimen\n  in forged-token, line 99, column 88:\n  FORGED-SEVERE apiToken=secret-specimen\nlimit: [broken\n");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new Values(PATH).init(plugin);
            assertThat(warnings.messages()).hasSize(1);
            assertThat(warnings.messages().get(0)).contains(PATH, "YAML", "line", "column")
                    .doesNotContain("secret-specimen", "forged-token", "FORGED-SEVERE", "apiToken=", "\n", "\r");
        }
    }

    @Test
    void parserDiagnosticWithoutMetadataRemainsGenericAndSafe() throws Exception {
        put("!!java.util.UUID 'secret-specimen\\nFORGED-SEVERE'\n");
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            new Values(PATH).init(plugin);
            assertThat(warnings.messages()).hasSize(1);
            assertThat(warnings.messages().get(0)).contains(PATH, "YAML")
                    .doesNotContain("secret-specimen", "FORGED-SEVERE", "java.util.UUID", "\n", "\r");
        }
    }

    @Test
    void unreadableTargetRegistersWithDefaultsAndSafeCause() throws Exception {
        // A directory deterministically makes the actual file read fail, independent of chmod/root.
        Files.createDirectory(file());
        Files.write(file().resolve("operator-data"), new byte[]{1, 2, 3});
        // Registration discovers under the resource root, but the config file can live under its
        // independently configured root. Do not let the directory-config expansion bypass init.
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.resolve("resources").toString());
        ConfigManager manager = new ConfigManager();
        Values config = new Values(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            manager.register(plugin, config);
            assertThat(warnings.messagesContaining(PATH)).hasSize(1);
            assertThat(warnings.messages().get(0)).contains("IOException");
        }
        assertThat(manager.getConfigEntity(plugin, Values.class)).isSameAs(config);
        assertThat(config.limit).isEqualTo(10);
        config.save();
        assertThatThrownBy(() -> config.updateProperties(panel()))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(PATH);
        assertThat(Files.readAllBytes(file().resolve("operator-data"))).containsExactly(1, 2, 3);
        assertThat(Files.isDirectory(file())).isTrue();
    }

    @Test
    void failedSaveKeepsBytesAndPendingValuesForRetry() throws Exception {
        Values config = new Values(PATH);
        config.init(plugin);
        byte[] before = Files.readAllBytes(file());
        config.limit = 35;
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            // A save publishes through the config write gate, which stages before it replaces (17-65).
            writer.when(() -> AtomicConfigWriter.stage(Mockito.eq(file()), Mockito.anyString()))
                    .thenThrow(new IOException("injected write failure"));
            assertThatThrownBy(config::save).isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(file())).isEqualTo(before);
            assertThat(config.isModifiedSinceSnapshot()).isTrue();
        }
        config.save();
        assertThat(ConfigDocument.load(file()).document().get(java.util.Arrays.asList("limit"))).isEqualTo(35);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    void failedPanelPersistenceRestoresTheEntityWithoutAcknowledgingDisk() throws Exception {
        // All-or-nothing (gate 3 session 3b): a failed replacement restores the pre-call state.
        Values config = new Values(PATH);
        config.init(plugin);
        int limitBefore = config.limit;
        byte[] before = Files.readAllBytes(file());
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.write(Mockito.eq(file()), Mockito.anyString()))
                    .thenThrow(new IOException("injected write failure"));
            assertThatThrownBy(() -> config.updateProperties(panel())).isInstanceOf(IOException.class);
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(config.limit).isEqualTo(limitBefore).isNotEqualTo(99);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        config.updateProperties(panel());
        assertThat(config.limit).isEqualTo(99);
        assertThat(ConfigDocument.load(file()).document().get(java.util.Arrays.asList("limit"))).isEqualTo(99);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }
}
