package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.exceptions.ConfigurationException;

/**
 * #595 (review F5): a reload refused by a validation annotation tells the operator that fixing the
 * value and reloading again is enough. The refusal at load is unchanged: the module did not load,
 * so a restart is what brings it back.
 */
@DisplayName("A refused reload's validation message says a reload is enough (#595)")
class ConfigReloadValidationMessageTest {

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    @ConfigEntity("limits.yml")
    static class Limits extends AbstractConfigEntity {
        @ConfigEntry(path = "cooldown")
        @Range(min = 0, max = 60)
        int cooldown = 2;

        Limits(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("LimitModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
    }

    private void write(String text) throws Exception {
        Files.write(directory.resolve("limits.yml"), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("on reload the message says the file was not reloaded and that reloading again after the fix is enough")
    void reloadRefusalSaysReloadIsEnough() throws Exception {
        write("cooldown: 2\n");
        Limits limits = new Limits("limits.yml");
        limits.init(plugin);
        write("cooldown: 9999\n");

        assertThatThrownBy(limits::reload).isInstanceOf(ConfigurationException.class)
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .contains("LimitModule").contains("limits.yml").contains("9999")
                        .contains("reload").doesNotContain("restart").doesNotContain("refused to load"));
        assertThat(limits.cooldown).as("the running value is kept").isEqualTo(2);
    }

    @Test
    @DisplayName("at load the message is unchanged: the module refused to load and a restart brings it back")
    void loadRefusalIsUnchanged() throws Exception {
        write("cooldown: 9999\n");

        assertThatThrownBy(() -> new Limits("limits.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("fix the value(s) and restart");
    }

    @Test
    @DisplayName("validation outside a reload, after a refused reload, keeps its existing wording")
    void laterValidationIsNotReloadWorded() throws Exception {
        write("cooldown: 2\n");
        Limits limits = new Limits("limits.yml");
        limits.init(plugin);
        write("cooldown: 9999\n");
        assertThatThrownBy(limits::reload).isInstanceOf(ConfigurationException.class);

        limits.cooldown = 9999;
        assertThatThrownBy(limits::validateFields).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load");
    }
}
