package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plan 17-56 Task 2: every file of the golden corpus (bundled module files, files the 6.2 writer produced,
 * hand-edited files; see {@code config-golden/MANIFEST.md}) preserves content, comments and style, and reads
 * into the same values Bukkit's {@code YamlConfiguration} reads wherever Bukkit can read the file at all.
 */
@DisplayName("ConfigDocument - golden corpus preserves content, comments and style")
class ConfigDocumentGoldenTest {

    static Stream<GoldenCorpus.Fixture> fixtures() throws IOException {
        return GoldenCorpus.fixtures().stream();
    }

    @Test
    @DisplayName("the manifest lists exactly the files on disk, with at least ten bundled and ten 6.2-written files")
    void manifestListsEveryFixture() throws IOException {
        List<String> listed = GoldenCorpus.fixtures().stream().map(f -> f.name).collect(Collectors.toList());

        assertThat(listed).containsExactlyInAnyOrderElementsOf(GoldenCorpus.filesOnDisk());
        assertThat(listed.stream().filter(n -> n.startsWith("bundled/")).count()).isGreaterThanOrEqualTo(10);
        assertThat(listed.stream().filter(n -> n.startsWith("written-by-6.2/")).count()).isGreaterThanOrEqualTo(10);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("the fixture's bytes are the recorded bytes")
    void fixtureBytesMatchTheManifest(GoldenCorpus.Fixture fixture) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(fixture.bytes())) {
            hex.append(String.format("%02x", b));
        }

        assertThat(hex.toString()).isEqualTo(fixture.sha256);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("renders the same content, comments, order and document style")
    void rendersContentCommentsAndStyle(GoldenCorpus.Fixture fixture) throws Exception {
        ConfigDocument document = ConfigDocument.parse(fixture.text());

        String rendered = document.render();
        GoldenCorpus.assertContent(rendered, document.toPlain());
        GoldenCorpus.assertStyle(fixture.text(), rendered);
        assertThat(GoldenCorpus.comments(rendered)).containsExactlyInAnyOrderElementsOf(GoldenCorpus.comments(fixture.text()));
    }

    static Stream<GoldenCorpus.Fixture> bukkitReadableFixtures() throws Exception {
        List<GoldenCorpus.Fixture> result = new ArrayList<>();
        for (GoldenCorpus.Fixture fixture : GoldenCorpus.fixtures()) {
            // Bukkit splits keys at '.' and builds ConfigurationSerializable objects from '==' maps; those files
            // are compared in their own tests, not against Bukkit's reading.
            if (!fixture.text().contains("==:") && !hasDottedKey(ConfigDocument.parse(fixture.text()).toPlain())) {
                result.add(fixture);
            }
        }
        return result.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bukkitReadableFixtures")
    @DisplayName("reads the values, key identities and key order Bukkit reads")
    void readsWhatBukkitReads(GoldenCorpus.Fixture fixture) throws Exception {
        YamlConfiguration bukkit = new YamlConfiguration();
        bukkit.loadFromString(fixture.text());

        Map<String, Object> expected = sectionToPlain(bukkit);
        // Bukkit's MemorySection#set(path, null) removes the key, so a "key:" or "key: null" line is absent from
        // its reading; the document keeps the key with a null value so a written null reads back.
        Map<String, Object> actual = withoutNullEntries(ConfigDocument.parse(fixture.text()).toPlain());

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.toString()).isEqualTo(expected.toString());
    }

    @Test
    @DisplayName("the comparison with Bukkit covers most of the corpus (control)")
    void bukkitComparisonIsNotVacuous() throws Exception {
        assertThat(bukkitReadableFixtures().count()).isGreaterThanOrEqualTo(25);
    }

    /** Drops null-valued entries of mappings that Bukkit stores as sections (not those inside lists). */
    private static Map<String, Object> withoutNullEntries(Map<String, Object> mapping) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) entry.getValue();
                result.put(entry.getKey(), withoutNullEntries(child));
            } else if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static boolean hasDottedKey(Object value) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (String.valueOf(entry.getKey()).contains(".") || hasDottedKey(entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof List) {
            for (Object element : (List<?>) value) {
                if (hasDottedKey(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> sectionToPlain(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            result.put(entry.getKey(), toPlain(entry.getValue()));
        }
        return result;
    }

    private static Object toPlain(Object value) {
        if (value instanceof ConfigurationSection) {
            return sectionToPlain((ConfigurationSection) value);
        }
        if (value instanceof Map) {
            // A map inside a list: Bukkit keeps the constructed key; the document's key identity is its String value.
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), toPlain(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object element : (List<?>) value) {
                result.add(toPlain(element));
            }
            return result;
        }
        return value;
    }
}
