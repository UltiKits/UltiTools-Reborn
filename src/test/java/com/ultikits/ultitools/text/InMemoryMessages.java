package com.ultikits.ultitools.text;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.translation.Argument;

/** Test-only catalogue renderer, deliberately separate from any production registration or translator. */
public final class InMemoryMessages implements Messages {
    private final String namespace;
    private final LocaleResolver resolver;
    private final Map<MessageKey, Map<Locale, String>> catalogues = new ConcurrentHashMap<>();
    private final Map<String, Locale> sources = new ConcurrentHashMap<>();
    private final Set<Missing> reported = ConcurrentHashMap.newKeySet();
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();

    public InMemoryMessages(String namespace, LocaleResolver resolver) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override public void flush() { }
            @Override public void close() { }
        });
    }

    public void put(MessageKey key, Locale locale, String template) {
        catalogues.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(locale, template);
    }

    public void sourceLanguage(String catalogueNamespace, Locale sourceLanguage) {
        sources.put(catalogueNamespace, sourceLanguage);
    }

    public List<LogRecord> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public MessageKey key(String path) {
        return MessageKey.of(namespace, Objects.requireNonNull(path, "path"));
    }

    @Override
    public Component render(Text text, Locale locale) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(locale, "locale");
        if (text instanceof Text.Literal literal) {
            return literal.component();
        }
        Text.Translated translated = (Text.Translated) text;
        String template = lookup(translated.key(), locale);
        if (template == null) {
            if (reported.add(new Missing(translated.key(), locale))) {
                logger.warning("Missing message " + translated.key() + " for " + locale.toLanguageTag());
            }
            return Component.text(translated.key().translationKey());
        }
        List<TagResolver> arguments = new ArrayList<>();
        for (Arg arg : translated.args()) {
            arguments.add(argumentResolver(arg, locale));
        }
        return MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(arguments));
    }

    private TagResolver argumentResolver(Arg arg, Locale locale) {
        return switch (arg) {
            case Arg.Unparsed unparsed -> Placeholder.unparsed(unparsed.name(), unparsed.value());
            case Arg.Numeric numeric -> TagResolver.resolver(numeric.name(), (arguments, context) -> {
                NumberFormat format = arguments.hasNext()
                        ? new DecimalFormat(arguments.pop().value(), DecimalFormatSymbols.getInstance(locale))
                        : NumberFormat.getNumberInstance(locale);
                return Tag.inserting(Component.text(format.format(numeric.value())));
            });
            case Arg.Moment moment -> TagResolver.resolver(moment.name(), (arguments, context) -> {
                DateTimeFormatter format;
                if (arguments.hasNext()) {
                    format = DateTimeFormatter.ofPattern(arguments.pop().value(), locale);
                } else if (moment.value().isSupported(ChronoField.HOUR_OF_DAY)) {
                    format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale);
                } else {
                    format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
                }
                return Tag.inserting(Component.text(format.format(moment.value())));
            });
            case Arg.Localized localized -> Placeholder.component(localized.name(), render(localized.value(), locale));
        };
    }

    private String lookup(MessageKey key, Locale locale) {
        Map<Locale, String> values = catalogues.getOrDefault(key, Map.of());
        Set<Locale> candidates = new LinkedHashSet<>();
        candidates.add(locale);
        candidates.add(Locale.forLanguageTag(locale.getLanguage()));
        candidates.add(resolver.fallback());
        Locale source = sources.get(key.namespace());
        if (source != null) {
            candidates.add(source);
        }
        for (Locale candidate : candidates) {
            String value = values.get(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    public Component render(Text text, Audience viewer) {
        Objects.requireNonNull(text, "text");
        return render(text, resolver.resolve(Objects.requireNonNull(viewer, "viewer")));
    }

    @Override
    public void forEachRecipient(Audience audience, Text text,
            BiConsumer<? super Audience, ? super Component> action) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(action, "action");
        audience.forEachAudience(recipient -> action.accept(recipient, render(text, recipient)));
    }

    @Override
    public Component shared(Text text) {
        Objects.requireNonNull(text, "text");
        if (resolver.forced().isPresent()) {
            return render(text, resolver.forced().orElseThrow());
        }
        if (text instanceof Text.Literal literal) {
            return literal.component();
        }
        Text.Translated translated = (Text.Translated) text;
        List<ComponentLike> arguments = new ArrayList<>();
        for (Arg arg : translated.args()) {
            arguments.add(switch (arg) {
                case Arg.Unparsed unparsed -> Argument.string(unparsed.name(), unparsed.value());
                case Arg.Numeric numeric -> Argument.numeric(numeric.name(), numeric.value());
                case Arg.Moment moment -> Argument.tagResolver(argumentResolver(moment, resolver.fallback()));
                case Arg.Localized localized -> Argument.component(localized.name(), shared(localized.value()));
            });
        }
        return Component.translatable(translated.key().translationKey(), arguments);
    }

    @Override
    public boolean isDefined(MessageKey key, Locale locale) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(locale, "locale");
        Map<Locale, String> values = catalogues.getOrDefault(key, Map.of());
        return values.containsKey(locale) || values.containsKey(Locale.forLanguageTag(locale.getLanguage()));
    }

    private record Missing(MessageKey key, Locale locale) { }
}
