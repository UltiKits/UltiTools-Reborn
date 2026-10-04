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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * A save never writes over what the operator changed on disk (maintainer decision 2026-10-04, "what code may write, by
 * file type", which supersedes #527's overwrite-and-warn): a module change whose setting the file no longer holds as
 * it was read is not written, and one warning names those keys, never a value. A panel edit writes exactly the keys
 * the operator named in it, with their consent, and warns about nothing.
 */
class ConfigOverwriteWarningTest {
    private static final String PATH = "overwrite.yml";
    @TempDir Path tempDir;
    private UltiToolsPlugin plugin;
    private Logger frameworkLogger;
    private ConfigManager manager;

    @ConfigEntity(PATH)
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "a", comment = "Literal note") String a = "original";
        @ConfigEntry(path = "b") String b = "original-b";
        @ConfigEntry(path = "apiToken") String apiToken = "original-token";
        public Values(String path) { super(path); }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        frameworkLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> Mockito.when(ultiTools.getLogger()).thenReturn(frameworkLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("OverwriteModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        manager = new ConfigManager();
    }

    @AfterEach
    void tearDown() { MockBukkitHelper.safeUnmock(); }
    private Path file() { return tempDir.resolve(PATH); }
    private void put(String text) throws IOException { Files.write(file(), text.getBytes(StandardCharsets.UTF_8)); }
    private Values registered() throws IOException {
        Values config = new Values(PATH);
        manager.register(plugin, config);
        return config;
    }
    /** The save rule's not-written warnings (and any #527 "overwritten" warning, which must no longer occur). */
    private List<String> warnings() {
        ArgumentCaptor<Level> levels = ArgumentCaptor.forClass(Level.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(frameworkLogger, Mockito.atLeast(0)).log(levels.capture(), messages.capture());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < messages.getAllValues().size(); i++) {
            String message = messages.getAllValues().get(i);
            if (levels.getAllValues().get(i) == Level.WARNING
                    && (message.contains("overwritten") || message.contains("were not written"))) {
                result.add(message);
            }
        }
        return result;
    }

    @Test
    void explicitSaveKeepsOperatorEditsAndNamesEveryUnwrittenKeyOnceWithoutValues() throws Exception {
        Values config = registered();
        String operator = "a: operator-a\nb: operator-b\napiToken: secret-specimen\n";
        put(operator);
        config.a = "code-a";
        config.b = "code-b";
        config.apiToken = "code-token";
        config.save();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).isEqualTo(operator);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(PATH, "'a'", "'b'", "'apiToken'")
                .doesNotContain("operator-a", "operator-b", "secret-specimen", "original-token", "code-");
    }

    @Test
    void cleanExplicitSaveLeavesAChangedDiskValueWithoutWarning() throws Exception {
        Values config = registered();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        put("a: operator-a\nb: original-b\napiToken: original-token\n");
        byte[] before = Files.readAllBytes(file());
        config.save();
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void commentOnlyOperatorEditIsPreservedWithoutWarning() throws Exception {
        Values config = registered();
        put("# Operator note\na: original\nb: original-b\napiToken: original-token\n");
        byte[] before = Files.readAllBytes(file());
        config.save();
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void shutdownSaveKeepsTheOperatorEditAndHasExactlyOneEntityOwnedWarning() throws Exception {
        Values config = registered();
        String operator = "a: operator-a\nb: original-b\napiToken: original-token\n";
        put(operator);
        config.a = "code-a";
        manager.saveAll();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).isEqualTo(operator);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(PATH, "'a'");
    }

    @Test
    void failedSaveWarnsNothingAndRetryWrites() throws Exception {
        Values config = registered();
        byte[] before = Files.readAllBytes(file());
        config.a = "code-a";
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.stage(Mockito.eq(file()), Mockito.anyString()))
                    .thenThrow(new IOException("injected write failure"));
            assertThatThrownBy(config::save).isInstanceOf(IOException.class);
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(warnings()).isEmpty();
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        config.save();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).contains("a: code-a");
        assertThat(warnings()).isEmpty();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    void partialPanelWriteReplacesOnlyTheKeyTheOperatorNamedWithoutWarning() throws Exception {
        Values config = registered();
        put("a: operator-a\nb: operator-b\napiToken: original-token\n");
        JsonObject panel = new JsonObject();
        panel.addProperty("b", "panel-b");
        config.updateProperties(panel);
        // The panel edit is the operator's consent for 'b' (maintainer decision 2026-10-04, item 3); 'a' stays.
        assertThat(warnings()).isEmpty();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8))
                .isEqualTo("a: operator-a\nb: panel-b\napiToken: original-token\n");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"b: operator-b\n", "b: null\n", ""})
    void partialPanelWriteDoesNotAcknowledgeUntouchedOperatorValueOrPresence(String operatorB) throws Exception {
        Values config = registered();
        put("# Operator header\na: original\n" + operatorB + "apiToken: original-token\nunknown: kept\n");
        JsonObject panel = new JsonObject(); panel.addProperty("a", "panel-a");
        config.updateProperties(panel);
        assertThat(warnings()).isEmpty();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8))
                .contains("# Operator header", "unknown: kept");
        // The panel write saw a file the operator had edited since the load: the entity keeps the bytes it bound as
        // last read, so that edit stays visible as a change on disk (17-63 review WR-01 rule; never a fresh read).
        assertThat(config.isFileModifiedSinceSnapshot()).isTrue();
        byte[] afterPanel = Files.readAllBytes(file());
        config.b = "code-b";
        config.save();
        // The file never held the value the module started from at 'b': not written, named each time it is tried.
        assertThat(Files.readAllBytes(file())).isEqualTo(afterPanel);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("'b'").doesNotContain("'a'", "operator-b", "code-b");
        config.b = "later-code-b";
        config.save();
        assertThat(Files.readAllBytes(file())).isEqualTo(afterPanel);
        assertThat(warnings()).hasSize(2);
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
    }

    @Test
    void reloadAcknowledgesUntouchedOperatorValueAfterPartialPanelSave() throws Exception {
        Values config = registered();
        put("a: original\nb: operator-b\napiToken: original-token\n");
        JsonObject panel = new JsonObject(); panel.addProperty("a", "panel-a");
        config.updateProperties(panel);
        config.reload();
        assertThat(config.b).isEqualTo("operator-b");
        config.b = "code-b";
        config.save();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void shutdownReportsUntouchedOperatorEditAfterPartialPanelSave() throws Exception {
        Values config = registered();
        put("a: original\nb: operator-b\napiToken: original-token\n");
        JsonObject panel = new JsonObject(); panel.addProperty("a", "panel-a");
        config.updateProperties(panel);
        config.b = "code-b";
        manager.saveAll();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).contains("b: operator-b");
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("'b'").doesNotContain("'a'");
    }

    @Test
    void externalEditAlreadyEqualToCandidateDoesNotWarn() throws Exception {
        Values config = registered();
        put("a: same-change\nb: original-b\napiToken: original-token\n");
        config.a = "same-change";
        config.save();
        assertThat(warnings()).isEmpty();
    }
}
