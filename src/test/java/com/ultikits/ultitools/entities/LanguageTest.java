package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageTest {

    @Test
    void testMapConstructor() {
        Map<String, String> map = new HashMap<>();
        map.put("hello", "你好");
        Language language = new Language(map);

        assertEquals("你好", language.getLocalizedText("hello"));
        assertEquals("world", language.getLocalizedText("world"));
    }

    @Test
    void testStringConstructor() {
        String json = "{\"hello\": \"你好\"}";
        Language language = new Language(json);

        assertEquals("你好", language.getLocalizedText("hello"));
        assertEquals("world", language.getLocalizedText("world"));
    }

    @Test
    void testFileConstructor(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("lang.json").toFile();
        String json = "{\"hello\": \"你好\"}";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

        Language language = new Language(file);

        assertEquals("你好", language.getLocalizedText("hello"));
        assertEquals("world", language.getLocalizedText("world"));
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("fromYaml — #389")
    class FromYaml {

        private Language parse(String yaml) {
            return Language.fromYaml(new java.io.StringReader(yaml));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("nested YAML flattens to the dotted keys modules actually use")
        void nestedYamlFlattensToDottedKeys() {
            // The shape the eight YAML modules ship, and the keys their code asks for --
            // e.g. UltiWorlds calls i18n("world.delete.deleting").
            Language language = parse(
                    "worlds_enabled: \"§aEnabled!\"\n"
                            + "world:\n"
                            + "  delete:\n"
                            + "    deleting: \"Deleting {WORLD}...\"\n"
                            + "    success: \"Deleted.\"\n");

            assertEquals("§aEnabled!", language.getLocalizedText("worlds_enabled"));
            assertEquals("Deleting {WORLD}...", language.getLocalizedText("world.delete.deleting"));
            assertEquals("Deleted.", language.getLocalizedText("world.delete.success"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("section nodes are not entries")
        void sectionNodesAreNotEntries() {
            Language language = parse("world:\n  delete:\n    success: \"Deleted.\"\n");

            // "world" and "world.delete" are sections, not strings. Falling back to the key is
            // exactly what an absent entry should do.
            assertEquals("world", language.getLocalizedText("world"));
            assertEquals("world.delete", language.getLocalizedText("world.delete"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("an unknown key still falls back to itself")
        void unknownKeyFallsBack() {
            assertEquals("nope", parse("a: \"b\"\n").getLocalizedText("nope"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("empty YAML yields an empty dictionary, not a failure")
        void emptyYamlIsEmpty() {
            assertEquals("anything", parse("").getLocalizedText("anything"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("non-string leaves are skipped rather than coerced")
        void nonStringLeavesAreSkipped() {
            // A language file is a string dictionary; a stray number should not silently become
            // one, because getLocalizedText's contract is that a miss returns the key.
            Language language = parse("count: 5\nname: \"ok\"\n");

            assertEquals("ok", language.getLocalizedText("name"));
            assertEquals("count", language.getLocalizedText("count"));
        }
    }
}
