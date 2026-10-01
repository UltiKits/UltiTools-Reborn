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

/** Every successful entity persistence owns one semantic overwrite warning (#527). */
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
    private List<String> warnings() {
        ArgumentCaptor<Level> levels = ArgumentCaptor.forClass(Level.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(frameworkLogger, Mockito.atLeast(0)).log(levels.capture(), messages.capture());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < messages.getAllValues().size(); i++) {
            String message = messages.getAllValues().get(i);
            if (levels.getAllValues().get(i) == Level.WARNING && message.contains("overwritten")) {
                result.add(message);
            }
        }
        return result;
    }

    @Test
    void explicitSaveWarnsOnceNamingEveryReplacedKeyWithoutValues() throws Exception {
        Values config = registered();
        put("a: operator-a\nb: operator-b\napiToken: secret-specimen\n");
        config.a = "code-a";
        config.b = "code-b";
        config.save();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(PATH, "a", "b", "apiToken")
                .doesNotContain("operator-a", "operator-b", "secret-specimen", "original-token");
    }

    @Test
    void cleanExplicitSaveStillWarnsWhenReplacingChangedDiskValue() throws Exception {
        Values config = registered();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        put("a: operator-a\nb: original-b\napiToken: original-token\n");
        config.save();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(PATH, "a");
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
    void shutdownSaveHasExactlyOneEntityOwnedWarning() throws Exception {
        Values config = registered();
        put("a: operator-a\nb: original-b\napiToken: original-token\n");
        config.a = "code-a";
        manager.saveAll();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(PATH, "a");
    }

    @Test
    void failedSaveDoesNotClaimOverwriteAndRetryDoes() throws Exception {
        Values config = registered();
        put("a: operator-a\nb: original-b\napiToken: original-token\n");
        byte[] before = Files.readAllBytes(file());
        config.a = "code-a";
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS)) {
            writer.when(() -> AtomicConfigWriter.write(Mockito.eq(file()), Mockito.anyString()))
                    .thenThrow(new IOException("injected write failure"));
            assertThatThrownBy(config::save).isInstanceOf(IOException.class);
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(warnings()).isEmpty();
        assertThat(config.isModifiedSinceSnapshot()).isTrue();
        config.save();
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void partialPanelWriteWarnsOnlyForTheFieldItActuallyReplaces() throws Exception {
        Values config = registered();
        put("a: operator-a\nb: operator-b\napiToken: original-token\n");
        JsonObject panel = new JsonObject();
        panel.addProperty("b", "panel-b");
        config.updateProperties(panel);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(PATH, "'b'").doesNotContain("'a'");
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).contains("a: operator-a");
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
