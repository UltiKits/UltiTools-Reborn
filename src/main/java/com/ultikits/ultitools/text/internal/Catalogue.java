package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.MessageKey;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/** Immutable, normalized catalogue snapshot for one namespace. */
@ApiStatus.Internal
public final class Catalogue {
    private final String namespace;
    private final Locale sourceLocale;
    private final Map<Locale, Map<String, String>> entries;

    private Catalogue(Builder builder) {
        namespace = builder.namespace;
        sourceLocale = builder.sourceLocale;
        Map<Locale, Map<String, String>> copy = new LinkedHashMap<>();
        builder.entries.forEach((locale, values) -> copy.put(locale, Map.copyOf(values)));
        entries = Map.copyOf(copy);
    }

    /** Returns the namespace. */
    public String namespace() { return namespace; }

    /** Returns the module's last-resort source language. */
    public Locale sourceLocale() { return sourceLocale; }

    /** Returns the first exact candidate defining this path. */
    public Optional<String> lookup(String path, Iterable<Locale> candidates) {
        for (Locale candidate : candidates) {
            String value = entries.getOrDefault(LocaleNames.normalize(candidate), Map.of()).get(path);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** Returns the templates that define a path, sorted by language tag. */
    List<String> templates(String path) {
        return entries.keySet().stream().sorted(Comparator.comparing(Locale::toLanguageTag))
                .map(locale -> entries.get(locale).get(path)).filter(Objects::nonNull).toList();
    }

    /** Checks the exact locale and its language without other fallbacks. */
    public boolean isDefined(String path, Locale locale) {
        return entries.getOrDefault(LocaleNames.normalize(locale), Map.of()).containsKey(path)
                || entries.getOrDefault(Locale.of(locale.getLanguage()), Map.of()).containsKey(path);
    }

    /** Checks whether any language defines a path. */
    public boolean contains(String path) {
        return entries.values().stream().anyMatch(values -> values.containsKey(path));
    }

    /** Checks whether a source-language file was supplied, including an empty one. */
    public boolean hasLocale(Locale locale) {
        return entries.containsKey(LocaleNames.normalize(locale));
    }

    /** Returns a new snapshot with the same-language override entries taking precedence. */
    public Catalogue withOverrides(Catalogue overrides) {
        Builder builder = new Builder(namespace, sourceLocale);
        entries.forEach(builder::putAll);
        overrides.entries.forEach(builder::putAll);
        return builder.build();
    }

    /** Mutable assembly confined to a loader or a test fixture. */
    @ApiStatus.Internal
    public static final class Builder {
        private final String namespace;
        private Locale sourceLocale;
        private final Map<Locale, Map<String, String>> entries = new LinkedHashMap<>();

        /** Creates a builder for a namespace and source language. */
        public Builder(String namespace, Locale sourceLocale) {
            this.namespace = namespace;
            this.sourceLocale = LocaleNames.normalize(sourceLocale);
        }

        /** Changes the source language for subsequent snapshots. */
        public Builder sourceLanguage(Locale locale) {
            sourceLocale = LocaleNames.normalize(locale);
            return this;
        }

        /** Adds one validated message path. */
        public Builder put(Locale locale, String path, String template) {
            new MessageKey(namespace, path);
            entries.computeIfAbsent(LocaleNames.normalize(locale), ignored -> new LinkedHashMap<>()).put(path, template);
            return this;
        }

        /** Adds a language, even when its catalogue is empty. */
        public Builder putAll(Locale locale, Map<String, String> values) {
            entries.computeIfAbsent(LocaleNames.normalize(locale), ignored -> new LinkedHashMap<>()).putAll(values);
            return this;
        }

        /** Freezes the current entries into an independent snapshot. */
        public Catalogue build() { return new Catalogue(this); }
    }
}
