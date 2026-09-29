package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Gate-1 review of plan 17-41 (#542): a catalogue text must never make the framework write a file
 * its own loader cannot parse; the comment of a one-key entry must not make {@code reload()} report
 * a change the shutdown save would then write; and a comment-only rewrite that cannot be written (a
 * read-only file) must not fail the configuration load.
 */
@DisplayName("AbstractConfigEntity - one-key comment rewrite safety (#542)")
class ConfigCommentTokenSafetyTest {

    private static final String PATH = "config/safety.yml";
    private static final String EN_LIMIT = "Maximum number of items";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder
    static class SafetyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "demo.limit", comment = "{config.demo.limit}")
        int limit = 10;

        @ConfigEntry(path = "demo.name", comment = "Display name")
        String name = "Default";

        public SafetyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("SafetyModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        lenient().when(plugin.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(plugin.i18n("config.demo.limit")).thenReturn(EN_LIMIT);
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private void writeFile(String text) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
    }

    private YamlConfiguration parse() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        configuration.loadFromString(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8));
        return configuration;
    }

    @Test
    @DisplayName("every YAML line break in a catalogue text starts a new comment line; a control character is dropped")
    void everyLineBreakAndControlCharacterIsSafe() throws Exception {
        lenient().when(plugin.i18n("config.demo.limit"))
                .thenReturn("Max items (keep it low)\u0085third fourth\u0007");
        writeFile("demo:\n  # old\n  limit: 25\n  # Display name\n  name: Custom\n");

        new SafetyConfig(PATH).init(plugin);

        YamlConfiguration parsed = parse();
        assertThat(parsed.getComments("demo.limit")).containsExactly("Max items", "(keep it low)", "third", "fourth");
        SafetyConfig second = new SafetyConfig(PATH);
        second.init(plugin);
        assertThat(second.limit).isEqualTo(25);
        assertThat(second.name).isEqualTo("Custom");
    }

    @Test
    @DisplayName("reload after an operator edits a one-key comment on disk: the shutdown save sees no change")
    void reloadDoesNotReportACommentOnlyDifference() throws Exception {
        writeFile("demo:\n  # " + EN_LIMIT + "\n  limit: 25\n  # Display name\n  name: Custom\n");
        SafetyConfig config = new SafetyConfig(PATH);
        config.init(plugin);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();

        writeFile("demo:\n  # an operator's own words\n  limit: 25\n  # Display name\n  name: Custom\n");
        config.reload();
        assertThat(config.isModifiedSinceSnapshot()).as("comment-only difference").isFalse();
    }

    @Test
    @DisplayName("reload of a file missing the one-key entry, value at its default: the shutdown save sees no change")
    void reloadOfMissingOneKeyEntryIsNotAChange() throws Exception {
        SafetyConfig config = new SafetyConfig(PATH);
        config.init(plugin);

        writeFile("demo:\n  # Display name\n  name: Default\n");
        config.reload();
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("a comment-only rewrite that cannot be written does not fail the load: values bind, a warning names the file")
    void unwritableCommentOnlyRewriteDoesNotFailTheLoad() throws Exception {
        writeFile("demo:\n  # old\n  limit: 25\n  # Display name\n  name: Custom\n");
        byte[] before = Files.readAllBytes(file());
        assertThat(file().toFile().setWritable(false)).isTrue();
        try {
            assumeFalse(Files.isWritable(file()), "needs a non-root user so the write really fails");
            SafetyConfig config = new SafetyConfig(PATH);
            try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
                assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
                assertThat(warnings.messagesContaining(PATH)).hasSize(1);
            }
            assertThat(config.limit).isEqualTo(25);
            assertThat(config.isLastInitIncomplete()).isFalse();
            assertThat(Files.readAllBytes(file())).isEqualTo(before);
        } finally {
            assertThat(file().toFile().setWritable(true)).isTrue();
        }
    }
}
