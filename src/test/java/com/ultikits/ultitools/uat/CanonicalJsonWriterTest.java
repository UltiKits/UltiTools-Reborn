package com.ultikits.ultitools.uat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the canonical {@code surface.json} shape (Phase 10, D-10-07): sorted keys at every
 * object level, items sorted by {@code id}, two-space indent, LF, UTF-8, one trailing newline,
 * no HTML escaping, and byte-identical output across two writes of the same content.
 */
@DisplayName("CanonicalJsonWriter")
class CanonicalJsonWriterTest {

    @Test
    @DisplayName("sorts items by id regardless of insertion order")
    void sortsItemsById() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(row("COM-zzzz0000"));
        items.add(row("COM-aaaa0000"));

        String json = CanonicalJsonWriter.toJsonString(1, items);

        assertThat(json.indexOf("COM-aaaa0000")).isLessThan(json.indexOf("COM-zzzz0000"));
    }

    @Test
    @DisplayName("sorts object keys alphabetically at every level")
    void sortsObjectKeys() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "COM-aaaa0000");
        item.put("zzz_last", "value");
        item.put("aaa_first", "value");
        List<Map<String, Object>> items = Collections.singletonList(item);

        String json = CanonicalJsonWriter.toJsonString(1, items);

        assertThat(json.indexOf("\"aaa_first\"")).isLessThan(json.indexOf("\"id\""));
        assertThat(json.indexOf("\"id\"")).isLessThan(json.indexOf("\"zzz_last\""));
        // top-level keys: "items" sorts before "schema_version"
        assertThat(json.indexOf("\"items\"")).isLessThan(json.indexOf("\"schema_version\""));
    }

    @Test
    @DisplayName("uses two-space indent and never escapes angle brackets")
    void prettyPrintsWithoutHtmlEscaping() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "COM-aaaa0000");
        item.put("format", "send <player>");
        String json = CanonicalJsonWriter.toJsonString(1, Collections.singletonList(item));

        assertThat(json).contains("send <player>");
        assertThat(json).doesNotContain("\\u003c");
        assertThat(json).contains("\n  \"items\": [");
    }

    @Test
    @DisplayName("ends with exactly one trailing LF newline, no CR")
    void trailingNewlineOnly() {
        String json = CanonicalJsonWriter.toJsonString(1, Collections.emptyList());

        assertThat(json).endsWith("\n");
        assertThat(json).doesNotContain("\r");
        assertThat(json.substring(0, json.length() - 1)).doesNotEndWith("\n");
    }

    @Test
    @DisplayName("an empty item list yields items: [] and only schema_version besides it")
    void emptyIndexYieldsEmptyItems() {
        String json = CanonicalJsonWriter.toJsonString(1, Collections.emptyList());

        assertThat(json).contains("\"items\": []");
        assertThat(json).contains("\"schema_version\": 1");
    }

    @Test
    @DisplayName("writing the same document twice via write(Path,...) yields byte-identical files")
    void writingTwiceIsByteIdentical(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("surface.json");
        List<Map<String, Object>> items = Collections.singletonList(row("COM-aaaa0000"));

        CanonicalJsonWriter.write(output, 1, items);
        byte[] first = Files.readAllBytes(output);
        CanonicalJsonWriter.write(output, 1, items);
        byte[] second = Files.readAllBytes(output);

        assertThat(second).isEqualTo(first);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("\r");
    }

    private static Map<String, Object> row(String id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", "command");
        return row;
    }
}
