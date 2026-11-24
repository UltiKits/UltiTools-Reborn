package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class ConfiguredLocaleResolverTimeZoneTest {
    @ParameterizedTest
    @ValueSource(strings = {"Asia/Shanghai", "Asia/Urumqi", "Asia/Hong_Kong", "Asia/Macau", "PRC",
            "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin", "Asia/Kashgar", "Hongkong", "Asia/Macao"})
    void everyChineseZoneAndAlias_selectsChinese(String zone) {
        var resolver = resolver("auto", "auto", zone);
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        assertThat(resolver.forced()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Singapore", "Asia/Tokyo", "America/New_York", "Etc/GMT-8"})
    void otherZones_selectEnglishRegardlessOfOffset(String zone) {
        assertThat(resolver("auto", "auto", zone).fallback()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void explicitFallback_doesNotConsultTheTimeZone() {
        AtomicInteger reads = new AtomicInteger();
        for (String code : List.of("en", "zh", "de-DE")) {
            var resolver = new ConfiguredLocaleResolver(() -> "auto", () -> code, Logger.getAnonymousLogger(), () -> {
                reads.incrementAndGet();
                return ZoneId.of(code.equals("en") ? "Asia/Shanghai" : "UTC");
            });
            assertThat(resolver.fallback()).isEqualTo(Locale.forLanguageTag(code));
        }
        assertThat(reads).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"en,auto,Asia/Shanghai,en", "zh,auto,UTC,zh", "auto,auto,Asia/Shanghai,zh",
            "auto,auto,UTC,en", "en,zh,Asia/Shanghai,zh", "zh-myserver,auto,UTC,zh",
            "en,,Asia/Shanghai,en", "en,' ',Asia/Shanghai,en", ",auto,Asia/Shanghai,zh"})
    void automaticFallback_prefersAForcedLanguageBeforeTheTimeZone(String language, String fallback,
            String zone, String expected) {
        assertThat(resolver(language, fallback, zone).fallback()).isEqualTo(Locale.forLanguageTag(expected));
    }

    @Test
    void automaticFallback_isCachedUntilTheConfigurationIsReloaded() {
        AtomicReference<ZoneId> zone = new AtomicReference<>(ZoneId.of("Asia/Shanghai"));
        AtomicInteger reads = new AtomicInteger();
        var resolver = new ConfiguredLocaleResolver(() -> "auto", () -> "auto", Logger.getAnonymousLogger(), () -> {
            reads.incrementAndGet();
            return zone.get();
        });
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        zone.set(ZoneId.of("UTC"));
        for (int call = 0; call < 10; call++) {
            assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
            assertThat(resolver.forced()).isEmpty();
        }
        assertThat(reads).hasValue(1);
        resolver.reload();
        assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        assertThat(reads).hasValue(2);
    }

    @Test
    void configStringChanges_doNotResampleTheZoneBeforeAReload() {
        AtomicReference<String> language = new AtomicReference<>("auto");
        AtomicReference<String> fallback = new AtomicReference<>("auto");
        AtomicReference<ZoneId> zone = new AtomicReference<>(ZoneId.of("Asia/Shanghai"));
        AtomicInteger reads = new AtomicInteger();
        var resolver = new ConfiguredLocaleResolver(language::get, fallback::get, Logger.getAnonymousLogger(), () -> {
            reads.incrementAndGet();
            return zone.get();
        });
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        zone.set(ZoneId.of("UTC"));
        language.set("en");
        assertThat(resolver.forced()).contains(Locale.ENGLISH);
        assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        language.set("auto");
        fallback.set("de");
        assertThat(resolver.fallback()).isEqualTo(Locale.GERMAN);
        fallback.set("auto");
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        assertThat(reads).hasValue(1);
        resolver.reload();
        assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        assertThat(reads).hasValue(2);
    }

    @Test
    void absentOrBlankFallback_inAutoModeUsesTheTimeZone() {
        assertThat(resolver("auto", null, "Asia/Shanghai").fallback()).isEqualTo(Locale.CHINESE);
        assertThat(resolver(null, " ", "Asia/Shanghai").fallback()).isEqualTo(Locale.CHINESE);
        assertThat(resolver("auto", null, "UTC").fallback()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void existingForcedConfigurations_withoutANewFallbackKeepTheirLanguage() {
        for (String code : List.of("zh", "en")) {
            var resolver = resolver(code, null, code.equals("zh") ? "UTC" : "Asia/Shanghai");
            Locale expected = Locale.forLanguageTag(code);
            assertThat(resolver.fallback()).isEqualTo(expected);
            assertThat(resolver.forced()).contains(expected);
        }
    }

    @Test
    void playerPreference_isNotReplacedOrCachedByTheAutomaticFallback() {
        var resolver = resolver("auto", "auto", "Asia/Shanghai");
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.US, Locale.GERMANY);
        assertThat(resolver.resolve(player)).isEqualTo(Locale.US);
        assertThat(resolver.resolve(player)).isEqualTo(Locale.GERMANY);
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        assertThat(resolver("en", "auto", "Asia/Shanghai").resolve(player)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void autoIsAValidFallback_notAnInvalidLocaleWarning() {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* No external buffer. */ }
            @Override public void close() { /* In-memory capture only. */ }
        });
        var resolver = new ConfiguredLocaleResolver(() -> "auto", () -> "AuTo", logger, () -> ZoneId.of("UTC"));
        assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        assertThat(records).isEmpty();
    }

    private static ConfiguredLocaleResolver resolver(String language, String fallback, String zone) {
        return new ConfiguredLocaleResolver(() -> language, () -> fallback,
                Logger.getAnonymousLogger(), () -> ZoneId.of(zone));
    }
}
