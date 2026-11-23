package com.ultikits.ultitools.text.internal;

import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Locale identifiers used by catalogue names and configuration values. */
@ApiStatus.Internal
public final class LocaleNames {
    private LocaleNames() {
        // Static utility.
    }

    /** Parses a language or language-region identifier, omitting variants. */
    public static Optional<Locale> parse(@Nullable String value) {
        if (isAutomatic(value) || !value.trim().matches("[a-zA-Z]{2,3}([_-]([a-zA-Z]{2}|[0-9]{3}))?")) {
            return Optional.empty();
        }
        String[] parts = value.trim().replace('-', '_').split("_");
        return Optional.of(Locale.of(parts[0].toLowerCase(Locale.ROOT),
                parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : ""));
    }

    /** Returns whether a configuration value selects automatic language resolution. */
    public static boolean isAutomatic(@Nullable String value) {
        return value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim());
    }

    /** Keeps only a locale's language and country. */
    public static Locale normalize(Locale locale) {
        return Locale.of(locale.getLanguage(), locale.getCountry());
    }
}
