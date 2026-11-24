package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ConfiguredLocaleResolverTest {
    @Test
    void defaultsAndConfigChanges_areVisibleOnNextCall() {
        AtomicReference<String> language = new AtomicReference<>();
        AtomicReference<String> fallback = new AtomicReference<>();
        var resolver = new ConfiguredLocaleResolver(language::get, fallback::get,
                Logger.getAnonymousLogger(), () -> ZoneId.of("UTC"));
        assertThat(resolver.forced()).isEmpty();
        assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        language.set("zh");
        assertThat(resolver.forced()).contains(Locale.CHINESE);
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        fallback.set("de-DE");
        assertThat(resolver.fallback()).isEqualTo(Locale.GERMANY);
        language.set("AuTo");
        assertThat(resolver.forced()).isEmpty();
        assertThat(resolver.fallback()).isEqualTo(Locale.GERMANY);
        fallback.set("");
        assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        language.set(" ");
        assertThat(resolver.forced()).isEmpty();
    }

    @Test
    void invalidValues_warnOncePerKeyAndRawValue() {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* In-memory records need no flush. */ }
            @Override public void close() { /* No external resources. */ }
        });
        AtomicReference<String> language = new AtomicReference<>("zh-myserver");
        AtomicReference<String> fallback = new AtomicReference<>("not-a-locale");
        var resolver = new ConfiguredLocaleResolver(language::get, fallback::get, logger, () -> ZoneId.of("UTC"));
        for (int i = 0; i < 3; i++) {
            assertThat(resolver.forced()).isEmpty();
            assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        }
        language.set("invalid");
        assertThat(resolver.forced()).isEmpty();
        language.set("zh-myserver");
        assertThat(resolver.forced()).isEmpty();
        assertThat(records).hasSize(3);
        language.set("zh");
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        fallback.set("auto");
        assertThat(resolver.fallback()).isEqualTo(Locale.ENGLISH);
        assertThat(records).hasSize(3);
    }
}
