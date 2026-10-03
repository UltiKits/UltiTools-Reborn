package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * UltiKits/UltiTools-Reborn#542, resolution: a {@code @ConfigEntry} comment that is exactly one
 * {@code {key}} token is resolved from the owning module's language catalogue, in the server's
 * current language, and written in that language. Any other comment is written exactly as today.
 * The panel payload carries the resolved text, not the token.
 */
@DisplayName("AbstractConfigEntity - @ConfigEntry comment as a language key (#542)")
class ConfigCommentTokenTest {

    private static final String PATH = "config/demo.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder
    static class DemoConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "demo.limit", comment = "{config.demo.limit}")
        int limit = 10;

        @ConfigEntry(path = "demo.multi", comment = " {config.demo.multi} ")
        String multi = "x";

        @ConfigEntry(path = "demo.literal", comment = "Message ({player} is the player name)")
        String literal = "hi";

        @ConfigEntry(path = "demo.missing", comment = "{config.demo.missing}")
        boolean missing = true;

        @ConfigEntry(path = "demo.plain")
        int plain = 1;

        public DemoConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DemoModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        lenient().when(plugin.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(plugin.i18n("config.demo.limit")).thenReturn("Maximum number of items");
        lenient().when(plugin.i18n("config.demo.multi")).thenReturn("First line\r\nSecond line\nThird line");
    }

    private YamlConfiguration reload() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        configuration.load(tempDir.resolve(PATH).toFile());
        return configuration;
    }

    @Test
    @DisplayName("a fresh file gets the resolved text as the token-keyed entry's comment")
    void freshFileGetsResolvedComment() throws Exception {
        new DemoConfig(PATH).init(plugin);

        YamlConfiguration file = reload();
        assertThat(file.getComments("demo.limit")).containsExactly("Maximum number of items");
        assertThat(file.getInt("demo.limit")).isEqualTo(10);
    }

    @Test
    @DisplayName("a catalogue value with line breaks becomes separate comment lines and the file stays valid YAML")
    void lineBreaksBecomeSeparateCommentLines() throws Exception {
        new DemoConfig(PATH).init(plugin);

        YamlConfiguration file = reload();
        assertThat(file.getComments("demo.multi")).containsExactly("First line", "Second line", "Third line");
        assertThat(file.getString("demo.multi")).isEqualTo("x");
        String text = new String(Files.readAllBytes(tempDir.resolve(PATH)), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("\r");
    }

    @Test
    @DisplayName("a literal comment, even one containing {player}, is written byte for byte")
    void literalCommentIsUnchanged() throws Exception {
        new DemoConfig(PATH).init(plugin);

        assertThat(reload().getComments("demo.literal")).containsExactly("Message ({player} is the player name)");
        assertThat(reload().getComments("demo.plain")).isEmpty();
    }

    @Test
    @DisplayName("a key missing from the catalogue writes the token and one warning naming module, file, path and key")
    void missingCatalogueKeyWritesTokenAndWarnsOnce() throws Exception {
        DemoConfig config = new DemoConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            config.save();
            config.save();

            List<String> named = warnings.messagesContaining("config.demo.missing");
            assertThat(named).as("once per load, not per save").hasSize(1);
            assertThat(named.get(0)).contains("DemoModule").contains(PATH).contains("demo.missing");
        }
        assertThat(reload().getComments("demo.missing")).containsExactly("{config.demo.missing}");
    }

    @Test
    @DisplayName("a catalogue lookup that throws is treated as a missing key: the load completes and the token is written")
    void throwingCatalogueLookupDoesNotFailTheLoad() throws Exception {
        lenient().when(plugin.i18n("config.demo.limit")).thenThrow(new IllegalStateException("language not loaded"));
        DemoConfig config = new DemoConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(warnings.messagesContaining("config.demo.limit")).hasSize(1);
        }
        assertThat(reload().getComments("demo.limit")).containsExactly("{config.demo.limit}");
        assertThat(config.limit).isEqualTo(10);
    }

    @Test
    @DisplayName("the panel payload carries the resolved comment, not the token")
    void panelPayloadCarriesResolvedText() throws Exception {
        DemoConfig config = new DemoConfig(PATH);
        config.init(plugin);

        assertThat(config.getComments().get("demo.limit").getAsString()).isEqualTo("Maximum number of items");
        assertThat(config.getComments().get("demo.multi").getAsString())
                .isEqualTo("First line\nSecond line\nThird line");
        assertThat(config.getComments().get("demo.literal").getAsString())
                .isEqualTo("Message ({player} is the player name)");
    }
}
