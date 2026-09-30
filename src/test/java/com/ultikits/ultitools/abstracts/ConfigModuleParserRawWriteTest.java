package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;

/**
 * Plan 17-41, #523 (orchestrator's third ruling and the maintainer's answer of 2026-09-30): the form the
 * framework writes for an enum or a collection (#523) applies only to an entry whose parser is exactly
 * the framework's default parser. A module's own parser - including one that extends {@link
 * DefaultConfigParser} - gets exactly what {@code origin/alpha} ({@code dfe71e01}) writes: the raw value
 * on the first-boot write and on a panel write ({@code config.set(path, value)}), and its own {@code
 * serialize} on {@code save()}. The expected texts are produced by the same Bukkit calls alpha makes.
 */
@DisplayName("AbstractConfigEntity - a module parser, even one extending the default, is written as alpha writes it (#523)")
class ConfigModuleParserRawWriteTest {

    private static final String PATH = "config/module-parser.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    /** A module's parser that extends the default and changes nothing. */
    public static class ExtendingParser extends DefaultConfigParser {
    }

    /** A module's parser that extends the default and writes a set as one comma-joined value. */
    public static class JoiningParser extends DefaultConfigParser {
        @Override
        public MemorySection serializeToMemorySection(Object object) {
            if (object instanceof Set) {
                MemoryConfiguration section = new MemoryConfiguration();
                StringBuilder joined = new StringBuilder();
                for (Object element : (Set<?>) object) {
                    joined.append(joined.length() == 0 ? "" : ",").append(element);
                }
                section.set("joined", joined.toString());
                return section;
            }
            return super.serializeToMemorySection(object);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    static class ExtendingConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "tags", parser = ExtendingParser.class)
        Set<String> tags = new LinkedHashSet<>(Arrays.asList("red"));

        public ExtendingConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class JoiningConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "tags", parser = JoiningParser.class)
        Set<String> tags = new LinkedHashSet<>(Arrays.asList("red", "blue"));

        public JoiningConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("ParserModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private String readFile() throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(PATH)), StandardCharsets.UTF_8);
    }

    /** What alpha writes for a file holding only {@code path}: {@code config.set(path, value)}, then save. */
    private static String alphaRaw(String path, Object value) {
        YamlConfiguration expected = new YamlConfiguration();
        expected.set(path, value);
        return expected.saveToString();
    }

    @Test
    @DisplayName("first-boot write: a parser extending the default gets the raw default, as alpha writes it")
    void firstBootWriteIsRaw() throws IOException {
        ExtendingConfig config = new ExtendingConfig(PATH);
        config.init(plugin);

        assertThat(readFile()).isEqualTo(alphaRaw("tags", new LinkedHashSet<>(Arrays.asList("red"))));
    }

    @Test
    @DisplayName("panel write: a parser extending the default gets the raw value, as alpha writes it")
    void panelWriteIsRaw() throws IOException {
        ExtendingConfig config = new ExtendingConfig(PATH);
        config.init(plugin);

        JsonObject payload = new JsonObject();
        JsonArray tags = new JsonArray();
        tags.add("green");
        tags.add("blue");
        payload.add("tags", tags);
        config.updateProperties(payload);

        assertThat(readFile()).isEqualTo(alphaRaw("tags", config.tags));
    }

    @Test
    @DisplayName("save(): a parser extending the default writes through its own serialization, as alpha does")
    void saveUsesTheModuleParser() throws IOException {
        JoiningConfig config = new JoiningConfig(PATH);
        config.init(plugin);
        config.save();

        assertThat(readFile()).isEqualTo("tags:\n  joined: red,blue\n");
    }
}
