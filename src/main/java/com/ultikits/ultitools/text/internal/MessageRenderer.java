package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** The single locale-aware lookup and argument formatting implementation. */
@ApiStatus.Internal
public final class MessageRenderer {
    private final Function<String, @Nullable Catalogue> catalogues;
    private final LocaleResolver resolver;
    private final Logger logger;
    private final Set<Missing> reported = ConcurrentHashMap.newKeySet();
    private final Set<BadFormat> reportedFormats = ConcurrentHashMap.newKeySet();

    /** Creates a renderer over immutable catalogue snapshots. */
    public MessageRenderer(Function<String, @Nullable Catalogue> catalogues, LocaleResolver resolver, Logger logger) {
        this.catalogues = catalogues;
        this.resolver = resolver;
        this.logger = logger;
    }

    /** Renders literal or translated text in the requested locale. */
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
                log("Missing message {0} for {1}", translated.key(), locale, null, null);
            }
            return Component.text(translated.key().translationKey());
        }
        List<TagResolver> arguments = new ArrayList<>();
        for (Arg arg : translated.args()) {
            arguments.add(argumentResolver(arg, locale, translated.key()));
        }
        try {
            // Adventure gives the last-added resolver priority, including over built-in tags.
            return MiniMessage.builder().tags(TagResolver.resolver(TagResolver.standard(), TagResolver.resolver(arguments)))
                    .build().deserialize(template);
        } catch (RuntimeException malformed) {
            reportFormat(translated.key(), locale, "template", malformed);
            return Component.text(template);
        }
    }

    private TagResolver argumentResolver(Arg arg, Locale locale, MessageKey key) {
        return TagResolver.resolver(arg.name(), (arguments, context) -> {
            try {
                return Tag.inserting(formatArgument(arg, locale, arguments, context));
            } catch (RuntimeException malformed) {
                reportFormat(key, locale, arg.name(), malformed);
                return Tag.inserting(safeDefault(arg, locale));
            }
        });
    }

    private void reportFormat(MessageKey key, Locale locale, String argument, RuntimeException malformed) {
        if (reportedFormats.add(new BadFormat(key, locale, argument))) {
            log("Invalid format for {0} in {1}, argument {2}", key, locale, argument, malformed);
        }
    }

    private void log(String message, MessageKey key, Locale locale, @Nullable String argument,
            @Nullable Throwable failure) {
        LogRecord record = new LogRecord(Level.WARNING, message);
        record.setParameters(new Object[] {key.translationKey(), locale.toLanguageTag(), argument});
        record.setThrown(failure);
        logger.log(record);
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
            return choice(numeric, arguments, context);
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

    private static Component choice(Arg.Numeric numeric, ArgumentQueue arguments, Context context) {
        if (!arguments.hasNext()) {
            throw new IllegalArgumentException("Choice pattern expected");
        }
        String pattern = arguments.pop().value();
        if (arguments.hasNext()) {
            throw new IllegalArgumentException("Unexpected choice argument");
        }
        return context.deserialize(new ChoiceFormat(pattern).format(numeric.value().doubleValue()));
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
            case Arg.Moment moment -> Component.text(defaultTimeFormat(temporal(moment)).withLocale(locale).format(temporal(moment)));
            case Arg.Localized localized -> render(localized.value(), locale);
        };
    }

    private static DateTimeFormatter defaultTimeFormat(TemporalAccessor value) {
        boolean date = value.isSupported(ChronoField.EPOCH_DAY);
        boolean time = value.isSupported(ChronoField.HOUR_OF_DAY);
        if (date && time) {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);
        }
        return time ? DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
                : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
    }

    private static TemporalAccessor temporal(Arg.Moment moment) {
        return moment.value() instanceof Instant instant
                ? ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()) : moment.value();
    }

    private @Nullable String lookup(MessageKey key, Locale locale) {
        Catalogue catalogue = catalogues.apply(key.namespace());
        if (catalogue == null) {
            return null;
        }
        Set<Locale> candidates = new LinkedHashSet<>();
        addCandidate(candidates, locale);
        addCandidate(candidates, resolver.fallback());
        addCandidate(candidates, catalogue.sourceLocale());
        return catalogue.lookup(key.path(), candidates).orElse(null);
    }

    private static void addCandidate(Set<Locale> candidates, Locale locale) {
        candidates.add(LocaleNames.normalize(locale));
        candidates.add(Locale.of(locale.getLanguage()));
    }

    private record Missing(MessageKey key, Locale locale) { }
    private record BadFormat(MessageKey key, Locale locale, String argument) { }
}
