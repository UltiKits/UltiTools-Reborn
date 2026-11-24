package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Locale;
import org.junit.jupiter.api.Test;

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
}
