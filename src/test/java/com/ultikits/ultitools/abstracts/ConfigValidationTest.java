package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Pattern;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;

/**
 * Tests for config validation annotations (@Range, @NotEmpty, @Size, @Pattern).
 */
@DisplayName("Config Validation Annotations")
class ConfigValidationTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() throws IOException {
        mockPlugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(mockPlugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(mockPlugin.getConfigFile(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return new File(tempDir.toFile(), path);
        });
    }

    @Nested
    @DisplayName("@Range validation")
    class RangeValidation {

        @Test
        @DisplayName("Should accept value within range")
        void shouldAcceptValueWithinRange() throws IOException {
            // Create config file with valid value
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 10".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(10);
        }

        @Test
        @DisplayName("Should reset value below minimum to default")
        void shouldResetBelowMinToDefault() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 0".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(20); // reset to default
        }

        @Test
        @DisplayName("Should reset value above maximum to default")
        void shouldResetAboveMaxToDefault() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 9999".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(20); // reset to default
        }

        @Test
        @DisplayName("Should accept value at exact minimum boundary")
        void shouldAcceptAtMinBoundary() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 1".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(1);
        }

        @Test
        @DisplayName("Should accept value at exact maximum boundary")
        void shouldAcceptAtMaxBoundary() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 1200".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.interval).isEqualTo(1200);
        }

        @Test
        @DisplayName("Should validate double fields")
        void shouldValidateDoubleFields() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 20\nrate: -0.5".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);

            assertThat(config.rate).isEqualTo(0.01); // reset to default
        }
    }

    @Nested
    @DisplayName("@NotEmpty validation")
    class NotEmptyValidation {

        @Test
        @DisplayName("Should accept non-empty string")
        void shouldAcceptNonEmptyString() throws IOException {
            File configFile = new File(tempDir.toFile(), "notempty.yml");
            Files.write(configFile.toPath(), "title: MyServer".getBytes());

            NotEmptyConfig config = new NotEmptyConfig("notempty.yml");
            config.init(mockPlugin);

            assertThat(config.title).isEqualTo("MyServer");
        }

        @Test
        @DisplayName("Should reset empty string to default")
        void shouldResetEmptyStringToDefault() throws IOException {
            File configFile = new File(tempDir.toFile(), "notempty.yml");
            Files.write(configFile.toPath(), "title: \"\"".getBytes());

            NotEmptyConfig config = new NotEmptyConfig("notempty.yml");
            config.init(mockPlugin);

            assertThat(config.title).isEqualTo("Welcome");
        }

        @Test
        @DisplayName("Should reset whitespace-only string to default")
        void shouldResetWhitespaceOnlyToDefault() throws IOException {
            File configFile = new File(tempDir.toFile(), "notempty.yml");
            Files.write(configFile.toPath(), "title: \"   \"".getBytes());

            NotEmptyConfig config = new NotEmptyConfig("notempty.yml");
            config.init(mockPlugin);

            assertThat(config.title).isEqualTo("Welcome");
        }
    }

    @Nested
    @DisplayName("@Size validation")
    class SizeValidation {

        @Test
        @DisplayName("Should accept list within size bounds")
        void shouldAcceptListWithinBounds() throws IOException {
            File configFile = new File(tempDir.toFile(), "size.yml");
            Files.write(configFile.toPath(), "lines:\n- line1\n- line2".getBytes());

            SizeConfig config = new SizeConfig("size.yml");
            config.init(mockPlugin);

            assertThat(config.lines).hasSize(2);
        }

        @Test
        @DisplayName("Should reset oversized list to default")
        void shouldResetOversizedListToDefault() throws IOException {
            File configFile = new File(tempDir.toFile(), "size.yml");
            StringBuilder sb = new StringBuilder("lines:\n");
            for (int i = 0; i < 20; i++) {
                sb.append("- line").append(i).append("\n");
            }
            Files.write(configFile.toPath(), sb.toString().getBytes());

            SizeConfig config = new SizeConfig("size.yml");
            config.init(mockPlugin);

            assertThat(config.lines).hasSize(3); // reset to 3 defaults
        }
    }

    @Nested
    @DisplayName("@Pattern validation")
    class PatternValidation {

        @Test
        @DisplayName("Should accept string matching pattern")
        void shouldAcceptMatchingPattern() throws IOException {
            File configFile = new File(tempDir.toFile(), "pattern.yml");
            Files.write(configFile.toPath(), "currency: Gold".getBytes());

            PatternConfig config = new PatternConfig("pattern.yml");
            config.init(mockPlugin);

            assertThat(config.currency).isEqualTo("Gold");
        }

        @Test
        @DisplayName("Should reset non-matching string to default")
        void shouldResetNonMatchingToDefault() throws IOException {
            File configFile = new File(tempDir.toFile(), "pattern.yml");
            Files.write(configFile.toPath(), "currency: \"$$$invalid!!!\"".getBytes());

            PatternConfig config = new PatternConfig("pattern.yml");
            config.init(mockPlugin);

            assertThat(config.currency).isEqualTo("Coin");
        }
    }

    @Nested
    @DisplayName("Validation on reload")
    class ReloadValidation {

        @Test
        @DisplayName("Should validate fields on reload")
        void shouldValidateOnReload() throws IOException {
            File configFile = new File(tempDir.toFile(), "range.yml");
            Files.write(configFile.toPath(), "interval: 20\nrate: 0.01".getBytes());

            RangeConfig config = new RangeConfig("range.yml");
            config.init(mockPlugin);
            assertThat(config.interval).isEqualTo(20);

            // Modify file to invalid value and reload
            Files.write(configFile.toPath(), "interval: -5\nrate: 0.01".getBytes());
            config.reload();

            assertThat(config.interval).isEqualTo(20); // reset to default
        }
    }

    // ==================== Test Config Classes ====================

    private static class RangeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "interval", comment = "Update interval in ticks")
        @Range(min = 1, max = 1200)
        private int interval = 20;

        @ConfigEntry(path = "rate", comment = "Interest rate")
        @Range(min = 0.0, max = 1.0)
        private double rate = 0.01;

        public RangeConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class NotEmptyConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "title", comment = "Sidebar title")
        @NotEmpty
        private String title = "Welcome";

        public NotEmptyConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class SizeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "lines", comment = "Content lines (max 15)")
        @Size(max = 15)
        private List<String> lines = new ArrayList<>(Arrays.asList("Line 1", "Line 2", "Line 3"));

        public SizeConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    private static class PatternConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "currency", comment = "Currency name")
        @Pattern(regex = "^[\\w]{1,16}$")
        private String currency = "Coin";

        public PatternConfig(String configFilePath) {
            super(configFilePath);
        }
    }
}
