package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocaleNamesTest {
    @Test
    void hyphenatedChinese_isNormalized() {
        assertThat(LocaleNames.parse("zh-CN")).contains(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    void catalogueNames_areNormalized() {
        for (String value : new String[] {"zh_cn", "zh_CN", "ZH-cn"}) {
            assertThat(LocaleNames.parse(value)).contains(Locale.SIMPLIFIED_CHINESE);
        }
        assertThat(LocaleNames.parse("zh")).contains(Locale.CHINESE);
        assertThat(LocaleNames.parse("en_us")).contains(Locale.US);
        assertThat(LocaleNames.normalize(Locale.of("en", "US", "POSIX"))).isEqualTo(Locale.US);
    }

    @Test
    void invalidAndAutomaticValues_areNotLocales() {
        for (String value : new String[] {"", "auto", "AUTO", " ", "zh-myserver", "../en", "english", "en_US_extra"}) {
            assertThat(LocaleNames.parse(value)).isEmpty();
        }
        assertThat(LocaleNames.parse(null)).isEmpty();
        assertThat(LocaleNames.isAutomatic(null)).isTrue();
        assertThat(LocaleNames.isAutomatic("AUTO")).isTrue();
        assertThat(LocaleNames.isAutomatic("en")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "en_US", "zh-CN", "zh-myserver", "1a"})
    void languageCodeAllowsAsciiLettersDigitsAndInternalSeparators(String code) {
        assertThat(LocaleNames.isSafeLanguageCode(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-en", "_en", "zh-../en", "zh-..\\en", "en.json", "zh-中文", "en US"})
    void languageCodeRejectsCharactersOutsideTheAllowlist(String code) {
        assertThat(LocaleNames.isSafeLanguageCode(code)).isFalse();
    }

    @Test
    void officialLanguageMatchesAnExactCodeOrTheLongestHyphenatedPrefix() {
        List<String> officialCodes = List.of("en", "zh", "zh-CN");
        assertThat(LocaleNames.officialLanguageOf("zh-CN", officialCodes)).isEqualTo("zh-CN");
        assertThat(LocaleNames.officialLanguageOf("zh-CN-myserver", officialCodes)).isEqualTo("zh-CN");
        assertThat(LocaleNames.officialLanguageOf("zh-myserver", officialCodes)).isEqualTo("zh");
        assertThat(LocaleNames.officialLanguageOf("en-custom", officialCodes)).isEqualTo("en");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh_custom", "zh-../en", "english", "other-custom", ""})
    void unrecognizedNamesHaveNoOfficialLanguage(String code) {
        assertThat(LocaleNames.officialLanguageOf(code, List.of("en", "zh"))).isNull();
    }

    @Test
    void absentNameOrOfficialCodesHaveNoOfficialLanguage() {
        assertThat(LocaleNames.officialLanguageOf(null, List.of("en"))).isNull();
        assertThat(LocaleNames.officialLanguageOf("en-custom", null)).isNull();
    }
}
