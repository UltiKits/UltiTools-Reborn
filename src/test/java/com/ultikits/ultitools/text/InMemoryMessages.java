package com.ultikits.ultitools.text;

import java.text.ChoiceFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
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
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Test-only catalogue renderer, deliberately separate from any production registration or translator. */
public final class InMemoryMessages implements Messages {
    private final String namespace;
    private final LocaleResolver resolver;
    private final Map<MessageKey, Map<Locale, String>> catalogues = new ConcurrentHashMap<>();
    private final Map<String, Locale> sources = new ConcurrentHashMap<>();
    private final Set<Missing> reported = ConcurrentHashMap.newKeySet();
    private final Set<BadFormat> reportedFormats = ConcurrentHashMap.newKeySet();
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

            @Override public void flush() {
                // Records are kept in memory; there is nothing to flush.
            }
            @Override public void close() {
                // The in-memory handler owns no external resource.
            }
        });
    }

    public void put(MessageKey key, Locale locale, String template) {
        catalogues.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(locale, template);
    }

    public void sourceLanguage(String catalogueNamespace, Locale sourceLanguage) {
        sources.put(catalogueNamespace, sourceLanguage);
    }

    public List<LogRecord> warnings() { return List.copyOf(warnings); }
    @Override public String namespace() { return namespace; }

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
                LogRecord record = new LogRecord(Level.WARNING, "Missing message {0} for {1}");
                record.setParameters(new Object[] {translated.key().translationKey(), locale.toLanguageTag()});
                logger.log(record);
            }
            return Component.text(translated.key().translationKey());
        }
        List<TagResolver> arguments = new ArrayList<>();
        for (Arg arg : translated.args()) {
            arguments.add(argumentResolver(arg, locale, translated.key()));
        }
        // The resolver builder gives the last added resolver priority, ahead of built-in tags.
        return MiniMessage.builder().tags(TagResolver.resolver(TagResolver.standard(), TagResolver.resolver(arguments)))
                .build().deserialize(template);
    }

    private TagResolver argumentResolver(Arg arg, Locale locale, MessageKey key) {
        return TagResolver.resolver(arg.name(), (arguments, context) -> {
            try {
                return Tag.inserting(formatArgument(arg, locale, arguments, context));
            } catch (RuntimeException malformed) {
                if (reportedFormats.add(new BadFormat(key, locale, arg.name()))) {
                    LogRecord record = new LogRecord(Level.WARNING, "Invalid format for {0} in {1}, argument {2}");
                    record.setParameters(new Object[] {key.translationKey(), locale.toLanguageTag(), arg.name()});
                    record.setThrown(malformed);
                    logger.log(record);
                }
                return Tag.inserting(safeDefault(arg, locale));
            }
        });
    }

    private Component formatArgument(Arg arg, Locale locale, ArgumentQueue arguments, Context context) {
        if (arg instanceof Arg.Numeric numeric) {
            return number(numeric, locale, arguments, context);
        }
        String pattern = arguments.hasNext() ? arguments.pop().value() : null;
        if (arguments.hasNext()) {
            throw new IllegalArgumentException("Unexpected format argument");
        }
        if (pattern == null) {
            return defaultValue(arg, locale);
        }
        if (arg instanceof Arg.Moment moment) {
            return Component.text(DateTimeFormatter.ofPattern(pattern, locale).format(temporal(moment)));
        }
        throw new IllegalArgumentException("This argument kind takes no format");
    }

    private Component number(Arg.Numeric numeric, Locale locale, ArgumentQueue arguments, Context context) {
        if (!arguments.hasNext()) {
            return defaultValue(numeric, locale);
        }
        String pattern = arguments.pop().value();
        if ("choice".equals(pattern)) {
            if (!arguments.hasNext()) {
                throw new IllegalArgumentException("Choice pattern expected");
            }
            String choice = arguments.pop().value();
            if (arguments.hasNext()) {
                throw new IllegalArgumentException("Unexpected choice argument");
            }
            return context.deserialize(new ChoiceFormat(choice).format(numeric.value().doubleValue()));
        }
        Locale symbols = locale;
        if (arguments.hasNext()) {
            symbols = Locale.forLanguageTag(pattern);
            pattern = arguments.pop().value();
        }
        if (arguments.hasNext() || (!pattern.contains("#") && !pattern.contains("0"))) {
            throw new IllegalArgumentException("Number pattern expected");
        }
        return Component.text(new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(symbols)).format(numeric.value()));
    }

    private Component safeDefault(Arg arg, Locale locale) {
        try {
            return defaultValue(arg, locale);
        } catch (RuntimeException unusable) {
            Object value = switch (arg) {
                case Arg.Unparsed unparsed -> unparsed.value();
                case Arg.Numeric numeric -> numeric.value();
                case Arg.Moment moment -> moment.value();
                case Arg.Localized localized -> localized.value();
            };
            return Component.text(String.valueOf(value));
        }
    }

    private Component defaultValue(Arg arg, Locale locale) {
        return switch (arg) {
            case Arg.Unparsed unparsed -> Component.text(unparsed.value());
            case Arg.Numeric numeric -> Component.text(NumberFormat.getNumberInstance(locale).format(numeric.value()));
            case Arg.Moment moment -> {
                TemporalAccessor value = temporal(moment);
                boolean date = value.isSupported(ChronoField.EPOCH_DAY);
                boolean time = value.isSupported(ChronoField.HOUR_OF_DAY);
                DateTimeFormatter format = date && time ? DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                        : time ? DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
                        : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
                yield Component.text(format.withLocale(locale).format(value));
            }
            case Arg.Localized localized -> render(localized.value(), locale);
        };
    }

    private static TemporalAccessor temporal(Arg.Moment moment) {
        return moment.value() instanceof Instant instant
                ? ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()) : moment.value();
    }

    private String lookup(MessageKey key, Locale locale) {
        Map<Locale, String> values = catalogues.getOrDefault(key, Map.of());
        Set<Locale> candidates = new LinkedHashSet<>();
        addCandidate(candidates, locale);
        addCandidate(candidates, resolver.fallback());
        Locale source = sources.get(key.namespace());
        if (source != null) {
            addCandidate(candidates, source);
        }
        for (Locale candidate : candidates) {
            String value = values.get(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void addCandidate(Set<Locale> candidates, Locale locale) {
        candidates.add(locale);
        candidates.add(Locale.forLanguageTag(locale.getLanguage()));
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
                case Arg.Moment moment -> Argument.tagResolver(argumentResolver(moment, resolver.fallback(), translated.key()));
                case Arg.Localized localized -> Argument.component(localized.name(), shared(localized.value()));
            });
        }
        return Component.translatable(translated.key().translationKey(), arguments)
                .fallback(PlainTextComponentSerializer.plainText().serialize(render(text, resolver.fallback())));
    }

    @Override
    public boolean isDefined(MessageKey key, Locale locale) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(locale, "locale");
        Map<Locale, String> values = catalogues.getOrDefault(key, Map.of());
        return values.containsKey(locale) || values.containsKey(Locale.forLanguageTag(locale.getLanguage()));
    }

    private record Missing(MessageKey key, Locale locale) { }
    private record BadFormat(MessageKey key, Locale locale, String argument) { }
}
