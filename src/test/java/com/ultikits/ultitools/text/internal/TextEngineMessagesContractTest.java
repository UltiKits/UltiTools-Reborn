package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.AbstractMessagesContractTest;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

class TextEngineMessagesContractTest extends AbstractMessagesContractTest {
    @Override
    protected Fixture createFixture(Locale fallback, Optional<Locale> forced) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) { warnings.add(record); }
            }
            @Override public void flush() { /* Memory only. */ }
            @Override public void close() { /* No owned resources. */ }
        });
        var resolver = new ConfiguredLocaleResolver(() -> forced.map(Locale::toLanguageTag).orElse("auto"),
                fallback::toLanguageTag, logger);
        TextEngine engine = new TextEngine(resolver, logger);
        Messages messages = engine.messagesFor("sample");
        return new Fixture() {
            private final Map<String, Catalogue.Builder> builders = new HashMap<>();
            private final Object owner = new Object();
            private Catalogue.Builder builder(String namespace) {
                return builders.computeIfAbsent(namespace, name -> new Catalogue.Builder(name, Locale.ENGLISH));
            }
            @Override public Messages messages() { return messages; }
            @Override public void put(MessageKey key, Locale locale, String template) {
                engine.publish(builder(key.namespace()).put(locale, key.path(), template).build(), owner);
            }
            @Override public void sourceLanguage(String namespace, Locale locale) {
                engine.publish(builder(namespace).sourceLanguage(locale).build(), owner);
            }
            @Override public List<LogRecord> warnings() { return List.copyOf(warnings); }
            @Override public void close() { engine.close(); }
        };
    }
}
