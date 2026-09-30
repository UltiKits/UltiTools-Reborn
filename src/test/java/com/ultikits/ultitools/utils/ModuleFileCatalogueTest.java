package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Every line the module file transactions write or return has an English and a Chinese catalogue
 * entry with the same format arguments (#505). These keys are passed through a variable rather
 * than an {@code i18n("...")} literal, so {@code I18nKeyContractTest} cannot see them; this test
 * does.
 */
@DisplayName("Module file transaction lines are in both language catalogues (#505)")
class ModuleFileCatalogueTest {

    private static final Pattern SPECIFIER = Pattern.compile("%(?!%)[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");
    private static final Pattern NON_ASCII_PUNCTUATION = Pattern.compile("[\\u3000-\\u303F\\uFF00-\\uFFEF\\u4E00-\\u9FFF]");

    private static Map<String, String> catalogue(String language) throws IOException {
        // The language is one of this test's own constants ("en", "zh"); the path names a file in
        // this repository and nothing is derived from input.
        // nosemgrep: java.inject.rule-SpotbugsPathTraversalAbsolute
        try (Reader reader = Files.newBufferedReader(Paths.get("src/main/resources/lang/" + language + ".json"),
                StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
        }
    }

    private static int specifiers(String text) {
        Matcher matcher = SPECIFIER.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Test
    @DisplayName("every key has an English and a Chinese entry with the same number of format arguments")
    void everyKeyIsInBothCataloguesWithTheSameArguments() throws IOException {
        Map<String, String> en = catalogue("en");
        Map<String, String> zh = catalogue("zh");
        assertThat(ModuleFileTransactions.Keys.all()).isNotEmpty();
        for (String key : ModuleFileTransactions.Keys.all()) {
            assertThat(en).as("en.json entry for %s", key).containsKey(key);
            assertThat(zh).as("zh.json entry for %s", key).containsKey(key);
            assertThat(specifiers(en.get(key))).as("arguments of en %s", key).isEqualTo(specifiers(key));
            assertThat(specifiers(zh.get(key))).as("arguments of zh %s", key).isEqualTo(specifiers(key));
            assertThat(NON_ASCII_PUNCTUATION.matcher(en.get(key)).find()).as("English value of %s is ASCII", key)
                    .isFalse();
        }
    }
}
