package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * UltiKits/UltiTools-Reborn#534: YAML reads every decimal as a {@code Double}, and {@code double} to
 * {@code float} is a narrowing conversion, so a {@code float}/{@code Float} field could not load a
 * decimal at all. Maintainer decision of 2026-09-30 ("读回来一样就接受" - accept it when it reads back
 * the same): a decimal is accepted when the float nearest to it prints back as the same decimal
 * ({@code 0.1}, {@code 0.3}, {@code 1.5}); only a value with more digits than a float holds, such as
 * {@code 0.123456789}, keeps the default with one warning. A float the framework wrote itself always
 * reads back.
 */
@DisplayName("AbstractConfigEntity - float decimals (#534)")
class ConfigFloatDecimalTest {

    private static final String PATH = "config/floats.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class FloatConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "ratio")
        float ratio = 0.25F;

        @ConfigEntry(path = "boxed")
        Float boxed = 0.1F;

        @ConfigEntry(path = "steps")
        List<Float> steps = new ArrayList<>();

        @ConfigEntry(path = "sibling")
        int sibling = 1;

        public FloatConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("FloatModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private void writeFile(String yaml) throws IOException {
        Path file = tempDir.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("decimals that read back the same load into float and Float: 1.5, 2.0, 0.1, 0.3, quoted '0.3'")
    void decimalsThatReadBackLoad() throws IOException {
        writeFile("ratio: 1.5\nboxed: 0.1\nsteps: [2.0, 0.3, '0.3']\nsibling: 7\n");
        FloatConfig config = new FloatConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(config.ratio).isEqualTo(1.5F);
            assertThat(config.boxed).isEqualTo(0.1F);
            assertThat(config.steps).containsExactly(2.0F, 0.3F, 0.3F);
            assertThat(config.sibling).isEqualTo(7);
            assertThat(warnings.messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("a decimal with more digits than a float holds keeps the default with one warning naming key and value")
    void tooManyDigitsKeepsTheDefault() throws IOException {
        writeFile("ratio: 0.123456789\nboxed: 0.1\nsteps: [1.5, 0.123456789]\nsibling: 7\n");
        FloatConfig config = new FloatConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();
            assertThat(config.ratio).isEqualTo(0.25F);
            assertThat(config.steps).containsExactly(1.5F);
            assertThat(warnings.messagesContaining("'ratio'")).hasSize(1)
                    .allSatisfy(message -> assertThat(message).contains("0.123456789"));
            assertThat(warnings.messagesContaining("'steps[1]'")).hasSize(1);
            assertThat(config.sibling).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("floats the framework wrote read back on the next start, with no warning and a clean snapshot")
    void frameworkWrittenFloatsReadBack() throws IOException {
        FloatConfig first = new FloatConfig(PATH);
        first.init(plugin);
        first.ratio = 0.3F;
        first.steps.add(0.1F);
        first.steps.add(1.0E-8F);
        first.save();

        FloatConfig second = new FloatConfig(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            second.init(plugin);
            assertThat(warnings.messages()).isEmpty();
        }
        assertThat(second.ratio).isEqualTo(0.3F);
        assertThat(second.boxed).isEqualTo(0.1F);
        assertThat(second.steps).containsExactly(0.1F, 1.0E-8F);
        assertThat(second.isModifiedSinceSnapshot()).isFalse();
    }
}
