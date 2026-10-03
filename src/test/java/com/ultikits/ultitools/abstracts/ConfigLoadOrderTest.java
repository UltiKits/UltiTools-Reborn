package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.exceptions.ConfigurationException;

/** Validation must run before a missing-key write can fail (#533). */
class ConfigLoadOrderTest {
    @TempDir Path directory;
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry @Range(min = 1, max = 10) int limit = 5;
        @ConfigEntry String missing = "default";
        public Values(String path) { super(path); }
    }
    @Test void invalidValueIsRefusedBeforeUnwritableDefaultWrite() throws Exception {
        Path file = directory.resolve("values.yml");
        Files.write(file, "limit: 25\n".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(file);
        UltiToolsPlugin plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("OrderModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(directory.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                call -> new File(directory.toFile(), call.<String>getArgument(0)));
        assertThat(directory.toFile().setWritable(false)).isTrue();
        assertThat(file.toFile().setWritable(false)).isTrue();
        try {
            assumeFalse(Files.isWritable(file), "requires a non-root user");
            Values values = new Values("values.yml");
            assertThatThrownBy(() -> values.init(plugin)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("limit").hasMessageContaining("25");
            assertThat(values.isLastInitIncomplete()).isTrue();
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
        } finally {
            assertThat(directory.toFile().setWritable(true)).isTrue();
            assertThat(file.toFile().setWritable(true)).isTrue();
        }
    }
}
