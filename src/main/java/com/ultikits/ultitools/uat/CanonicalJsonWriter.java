package com.ultikits.ultitools.uat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Serializes a module surface to the canonical {@code surface.json} shape (Phase 10, D-10-07):
 * every object's keys sorted, items sorted by {@code id}, two-space indent, LF, UTF-8, one
 * trailing newline, and no HTML escaping — so a literal angle-bracket placeholder inside a
 * {@code @CmdMapping} format survives unescaped.
 * <p>
 * Follows this repository's own established convention for hand-built, deterministic JSON
 * output ({@code RegistryLedger.toJson()}: {@code new GsonBuilder().setPrettyPrinting()
 * .disableHtmlEscaping()...create()}, trailing newline appended explicitly since Gson does not
 * add one).
 *
 * @since 6.3.0
 */
public final class CanonicalJsonWriter {

    private CanonicalJsonWriter() {
    }

    /**
     * Renders {@code items} (each a row's field map) as the canonical document string:
     * {@code {"items": [...], "schema_version": N}} with sorted keys throughout, items sorted by
     * their {@code id} field, two-space indent, and a single trailing newline. Never escapes
     * {@code <}/{@code >}.
     *
     * @param schemaVersion the integer {@code schema_version} to stamp
     * @param items         each row's field map, in any order
     * @return the canonical JSON document text, including its trailing newline
     */
    public static String toJsonString(int schemaVersion, List<Map<String, Object>> items) {
        Map<String, Object> document = new TreeMap<>();
        document.put("schema_version", schemaVersion);
        document.put("items", canonicalItems(items));

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(document) + "\n";
    }

    /**
     * Writes {@link #toJsonString(int, List)}'s output to {@code output}, via a sibling temp
     * file plus an atomic rename, so a concurrent reader never observes a half-written file.
     *
     * @param output        the destination path
     * @param schemaVersion the integer {@code schema_version} to stamp
     * @param items         each row's field map, in any order
     * @throws IOException if the temp file cannot be written or the rename fails
     */
    public static void write(Path output, int schemaVersion, List<Map<String, Object>> items) throws IOException {
        String json = toJsonString(schemaVersion, items);
        Path absoluteOutput = output.toAbsolutePath();
        Path parent = absoluteOutput.getParent();
        if (parent == null) {
            parent = Paths.get(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);
        Path tempFile = Files.createTempFile(parent, "surface-", ".json.tmp");
        try {
            Files.write(tempFile, json.getBytes(StandardCharsets.UTF_8));
            Files.move(tempFile, absoluteOutput, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static List<Object> canonicalItems(List<Map<String, Object>> items) {
        List<Map<String, Object>> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(row -> String.valueOf(row.get("id"))));
        List<Object> canonical = new ArrayList<>(sorted.size());
        for (Map<String, Object> item : sorted) {
            canonical.add(canonicalize(item));
        }
        return canonical;
    }

    @SuppressWarnings("unchecked")
    private static Object canonicalize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sortedMap = new TreeMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                sortedMap.put(entry.getKey(), canonicalize(entry.getValue()));
            }
            return sortedMap;
        }
        if (value instanceof List) {
            List<Object> canonicalList = new ArrayList<>();
            for (Object element : (List<Object>) value) {
                canonicalList.add(canonicalize(element));
            }
            return canonicalList;
        }
        return value;
    }
}
