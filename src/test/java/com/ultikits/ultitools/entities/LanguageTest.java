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
}
