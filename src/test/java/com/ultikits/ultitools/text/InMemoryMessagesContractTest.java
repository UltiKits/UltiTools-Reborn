package com.ultikits.ultitools.text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.LogRecord;

class InMemoryMessagesContractTest extends AbstractMessagesContractTest {
    @Override
    protected Fixture createFixture(Locale fallback, Optional<Locale> forced) {
        InMemoryMessages service = new InMemoryMessages("sample", new FixedLocaleResolver(fallback, forced));
        return new Fixture() {
            @Override public Messages messages() { return service; }
            @Override public void put(MessageKey key, Locale locale, String template) { service.put(key, locale, template); }
            @Override public void sourceLanguage(String namespace, Locale locale) { service.sourceLanguage(namespace, locale); }
            @Override public List<LogRecord> warnings() { return service.warnings(); }
        };
    }
}
