package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
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
 * D-07/D-08/D-09: a newly-added {@code @ConfigEntry} key gets both its default value and its
 * {@code comment()} written into the yml, while every key the operator already has - value AND
 * comment - is left completely untouched.
 * <p>
 * Round-trip proof that comment parsing (D-08) is enabled before the file is read, not only
 * before it is written - otherwise an operator's existing comments would be silently dropped at
 * read time and then never survive the missing-key save that follows.
 */
@DisplayName("AbstractConfigEntity - @ConfigEntry comment written on new key (D-07/D-08/D-09)")
class ConfigEntryCommentTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() {
        mockPlugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(mockPlugin.getConfigFile(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return new File(tempDir.toFile(), path);
        });
    }

    private YamlConfiguration loadWithComments(File file) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        configuration.load(file);
        return configuration;
    }

    private static class SingleFieldConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "Update interval in ticks")
        private int interval = 20;

        SingleFieldConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class MultiLineCommentConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "value", comment = "Line one\nLine two\nLine three")
        private String value = "default";

        MultiLineCommentConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class NoCommentConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "flag")
        private boolean flag = true;

        NoCommentConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class TwoFieldConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "existing", comment = "Pre-existing key")
        private String existing = "default";

        @ConfigEntry(path = "added", comment = "Newly added key")
        private String added = "added-default";

        TwoFieldConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @Test
    @DisplayName("A missing key gets both its default value and its @ConfigEntry comment")
    void missingKeyGetsValueAndComment() throws Exception {
        SingleFieldConfig entity = new SingleFieldConfig("interval.yml");
        entity.init(mockPlugin);

        File file = new File(tempDir.toFile(), "interval.yml");
        YamlConfiguration reloaded = loadWithComments(file);

        assertThat(reloaded.getInt("interval")).isEqualTo(20);
        assertThat(reloaded.getComments("interval")).containsExactly("Update interval in ticks");
    }

    @Test
    @DisplayName("An operator-supplied value with no comment is left uncommented")
    void existingKeyWithoutCommentStaysUncommented() throws Exception {
        File file = new File(tempDir.toFile(), "interval.yml");
        Files.write(file.toPath(), "interval: 42".getBytes(StandardCharsets.UTF_8));

        SingleFieldConfig entity = new SingleFieldConfig("interval.yml");
        entity.init(mockPlugin);

        assertThat(entity.interval).isEqualTo(42);
        YamlConfiguration reloaded = loadWithComments(file);
        assertThat(reloaded.getInt("interval")).isEqualTo(42);
        assertThat(reloaded.getComments("interval")).isEmpty();
    }

    @Test
    @DisplayName("An operator-written comment on an existing key survives init() byte-identically")
    void operatorCommentSurvivesUntouched() throws Exception {
        File file = new File(tempDir.toFile(), "interval.yml");
        String content = "# My own operator comment\ninterval: 99\n";
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(file.toPath());

        SingleFieldConfig entity = new SingleFieldConfig("interval.yml");
        entity.init(mockPlugin);

        assertThat(Files.readAllBytes(file.toPath())).isEqualTo(before);

        YamlConfiguration reloaded = loadWithComments(file);
        assertThat(reloaded.getComments("interval")).containsExactly("My own operator comment");
    }

    @Test
    @DisplayName("A multi-line comment() becomes one List<String> element per line, no blank leading element")
    void multiLineCommentSplitsOnePerLine() throws Exception {
        MultiLineCommentConfig entity = new MultiLineCommentConfig("multiline.yml");
        entity.init(mockPlugin);

        File file = new File(tempDir.toFile(), "multiline.yml");
        YamlConfiguration reloaded = loadWithComments(file);

        List<String> comments = reloaded.getComments("value");
        assertThat(comments).containsExactly("Line one", "Line two", "Line three");
        assertThat(comments.get(0)).as("no blank leading element").isNotBlank();
    }

    @Test
    @DisplayName("An empty comment() adds the key with no comment")
    void emptyCommentAddsKeyWithoutComment() throws Exception {
        NoCommentConfig entity = new NoCommentConfig("nocomment.yml");
        entity.init(mockPlugin);

        File file = new File(tempDir.toFile(), "nocomment.yml");
        YamlConfiguration reloaded = loadWithComments(file);

        assertThat(reloaded.getBoolean("flag")).isTrue();
        assertThat(reloaded.getComments("flag")).isEmpty();
    }

    @Test
    @DisplayName("Round trip: a newly-added key's comment and every pre-existing comment both survive")
    void roundTripPreservesAddedAndPreExistingComments() throws Exception {
        File file = new File(tempDir.toFile(), "twofield.yml");
        String content = "# Pre-existing key\nexisting: custom-value\n";
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));

        TwoFieldConfig entity = new TwoFieldConfig("twofield.yml");
        entity.init(mockPlugin);

        YamlConfiguration reloaded = loadWithComments(file);

        assertThat(reloaded.getString("existing")).isEqualTo("custom-value");
        assertThat(reloaded.getComments("existing")).containsExactly("Pre-existing key");
        assertThat(reloaded.getString("added")).isEqualTo("added-default");
        assertThat(reloaded.getComments("added")).containsExactly("Newly added key");
    }
}
