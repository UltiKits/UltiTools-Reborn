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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * UltiKits/UltiTools-Reborn#523: a collection {@code @ConfigEntry} receives elements of its declared
 * element type. Before the fix {@code DefaultConfigParser} turned every element into a
 * {@code String}, so UltiCleaner's {@code List<Integer> item.warn-times} held {@code "30"}, and its
 * {@code contains(Integer)} check never matched - silently.
 * <p>
 * Maintainer rule (2026-09-29): an element that cannot be converted is skipped with one warning
 * naming the file, the key, the raw value and the declared type, and the rest of the configuration
 * loads. Quoted numbers the old parser wrote ({@code "30"}) still load as numbers.
 */
@DisplayName("AbstractConfigEntity - typed collection elements (#523)")
class ConfigTypedCollectionTest {

    private static final String PATH = "config/typed.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    enum Mode { FAST, SLOW }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class TypedConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "item.warn-times")
        List<Integer> warnTimes = new ArrayList<>(Arrays.asList(60, 30));

        @ConfigEntry(path = "sibling")
        int sibling = 1;

        @ConfigEntry(path = "longs")
        List<Long> longs = new ArrayList<>();

        @ConfigEntry(path = "doubles")
        List<Double> doubles = new ArrayList<>();

        @ConfigEntry(path = "flags")
        List<Boolean> flags = new ArrayList<>();

        @ConfigEntry(path = "modes")
        List<Mode> modes = new ArrayList<>();

        @ConfigEntry(path = "unique")
        Set<Integer> unique = new HashSet<>();

        @ConfigEntry(path = "names")
        List<String> names = new ArrayList<>();

        @ConfigEntry(path = "limits")
        Map<String, Integer> limits = new LinkedHashMap<>();

        public TypedConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("TypedModule");
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
    @DisplayName("List<Integer> from [30, \"60\", abc] holds [30, 60] with one warning; a sibling field still loads")
    void integerListSkipsTheUnconvertibleElement() throws IOException {
        writeFile("item:\n  warn-times: [30, \"60\", abc]\nsibling: 7\n");
        TypedConfig config = new TypedConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            assertThatCode(() -> config.init(plugin)).doesNotThrowAnyException();

            assertThat(config.warnTimes).containsExactly(30, 60);
            assertThat(config.warnTimes).allSatisfy(element -> assertThat(element).isInstanceOf(Integer.class));
            assertThat(config.warnTimes.contains(30)).as("the UltiCleaner lookup").isTrue();
            assertThat(config.sibling).isEqualTo(7);

            List<String> named = warnings.messagesContaining("item.warn-times");
            assertThat(named).as("exactly one warning for the bad element").hasSize(1);
            assertThat(named.get(0)).contains(PATH).contains("abc").contains("Integer");
            assertThat(warnings.messages()).as("no other warning").hasSize(1);
        }
    }

    @Test
    @DisplayName("quoted numbers the old parser wrote still load as numbers")
    void quotedNumbersLoadAsNumbers() throws IOException {
        writeFile("item:\n  warn-times:\n  - '30'\n  - '60'\n");
        TypedConfig config = new TypedConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.warnTimes).containsExactly(30, 60);
            assertThat(warnings.messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("Long, Double, Boolean, enum and Set element types convert the same way")
    void otherElementTypesConvert() throws IOException {
        writeFile("longs: [1, '3000000000']\n"
                + "doubles: [1, 2.5, '3.5']\n"
                + "flags: [true, 'false']\n"
                + "modes: [FAST, SLOW]\n"
                + "unique: [1, 2, '2']\n");
        TypedConfig config = new TypedConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.longs).containsExactly(1L, 3000000000L);
            assertThat(config.doubles).containsExactly(1.0D, 2.5D, 3.5D);
            assertThat(config.flags).containsExactly(true, false);
            assertThat(config.modes).containsExactly(Mode.FAST, Mode.SLOW);
            assertThat(config.unique).containsExactlyInAnyOrder(1, 2);
            assertThat(warnings.messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("an unconvertible Boolean or enum element is skipped with a warning naming it")
    void unconvertibleBooleanAndEnumElementsAreSkipped() throws IOException {
        writeFile("flags: [true, maybe]\nmodes: [FAST, WARP]\n");
        TypedConfig config = new TypedConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.flags).containsExactly(true);
            assertThat(config.modes).containsExactly(Mode.FAST);
            assertThat(warnings.messagesContaining("maybe")).hasSize(1);
            assertThat(warnings.messagesContaining("WARP")).hasSize(1);
        }
    }

    @Test
    @DisplayName("List<String> is unchanged: every scalar element becomes its text")
    void stringListIsUnchanged() throws IOException {
        writeFile("names: [a, 1, true]\n");
        TypedConfig config = new TypedConfig(PATH);

        config.init(plugin);
        assertThat(config.names).containsExactly("a", "1", "true");
    }

    @Test
    @DisplayName("map values of a declared simple type convert the same way; a bad value is skipped and named")
    void typedMapValuesConvert() throws IOException {
        writeFile("limits:\n  a: 1\n  b: '2'\n  c: lots\n");
        TypedConfig config = new TypedConfig(PATH);

        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(config.limits).containsExactly(
                    org.assertj.core.api.Assertions.entry("a", 1), org.assertj.core.api.Assertions.entry("b", 2));
            List<String> named = warnings.messagesContaining("limits");
            assertThat(named).hasSize(1);
            assertThat(named.get(0)).contains("lots").contains("Integer");
        }
    }

    @Test
    @DisplayName("the typed list round-trips: saved, reloaded, still Integers, and the snapshot is clean")
    void typedListRoundTrips() throws IOException {
        TypedConfig first = new TypedConfig(PATH);
        first.init(plugin);
        first.save();

        TypedConfig second = new TypedConfig(PATH);
        second.init(plugin);
        assertThat(second.warnTimes).containsExactly(60, 30);
        assertThat(second.isModifiedSinceSnapshot()).isFalse();
    }
}
