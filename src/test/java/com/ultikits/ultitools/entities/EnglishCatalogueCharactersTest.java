package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #555: no value in {@code lang/en.json} contains Chinese, CJK punctuation or full-width forms, so an
 * English server never prints them from the framework's own catalogue.
 * <p>
 * The keys are the Chinese source strings by this framework's convention, so only values are
 * checked. The character contract is the one the framework's CJK gate and the module language
 * guards use (Han script including its extensions and compatibility ideographs, CJK symbols and
 * punctuation U+3000-U+303F, full-width forms U+FF00-U+FFEF; kana is not part of it).
 */
@DisplayName("lang/en.json values carry no CJK or full-width characters (#555)")
class EnglishCatalogueCharactersTest {

    private static final Path EN_JSON = Paths.get("src/main/resources/lang/en.json");

    /** Whether {@code text} holds a character of the contract, iterating code points, not chars. */
    static boolean containsCjkOrFullWidth(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                || (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF00 && cp <= 0xFFEF));
    }

    @Test
    @DisplayName("no en.json value contains a CJK or full-width character")
    void noEnglishValueContainsCjkOrFullWidth() throws IOException {
        JsonObject catalogue;
        try (Reader reader = Files.newBufferedReader(EN_JSON, StandardCharsets.UTF_8)) {
            catalogue = JsonParser.parseReader(reader).getAsJsonObject();
        }
        assertTrue(catalogue.size() > 100, "control: the catalogue was read (" + catalogue.size() + " entries)");

        List<String> offending = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : catalogue.entrySet()) {
            String value = entry.getValue().getAsString();
            if (containsCjkOrFullWidth(value)) {
                offending.add(entry.getKey() + " -> " + value);
            }
        }
        assertEquals(new ArrayList<String>(), offending, "en.json values holding CJK or full-width characters");
    }

    @Test
    @DisplayName("control: the detector sees each range of the contract and ignores kana")
    void detectorSeesEveryRangeAndIgnoresKana() {
        assertTrue(containsCjkOrFullWidth("中"), "CJK Unified Ideographs");
        assertTrue(containsCjkOrFullWidth("㐀"), "Extension A");
        assertTrue(containsCjkOrFullWidth(new String(Character.toChars(0x20000))), "a supplementary ideograph");
        assertTrue(containsCjkOrFullWidth("豈"), "a compatibility ideograph");
        assertTrue(containsCjkOrFullWidth("Install Command："), "a full-width colon");
        assertTrue(containsCjkOrFullWidth("。"), "CJK punctuation");
        assertFalse(containsCjkOrFullWidth("あア"), "kana is outside the contract");
        assertFalse(containsCjkOrFullWidth("Install Command: /upm install "), "plain ASCII");
    }
}
