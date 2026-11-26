package com.ultikits.ultitools.text.internal;

import java.util.List;
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

    /** Checks the ASCII allowlist for a configured language name, not the locale-tag grammar. */
    public static boolean isSafeLanguageCode(String code) {
        if (code.isEmpty()) {
            return false;
        }
        boolean safe = true;
        for (int i = 0; i < code.length() && safe; i++) {
            char c = code.charAt(i);
            boolean asciiAlphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            boolean allowedSeparator = i > 0 && (c == '_' || c == '-');
            safe = asciiAlphanumeric || allowedSeparator;
        }
        return safe;
    }

    /** Returns the exact official code or the longest official prefix before a hyphen. */
    public static @Nullable String officialLanguageOf(@Nullable String configured, List<String> officialCodes) {
        if (configured == null || officialCodes == null) {
            return null;
        }
        if (officialCodes.contains(configured)) {
            return configured;
        }
        if (!isSafeLanguageCode(configured)) {
            return null;
        }
        String best = null;
        for (String code : officialCodes) {
            boolean prefixed = code != null && configured.startsWith(code + "-");
            if (prefixed && (best == null || code.length() > best.length())) {
                best = code;
            }
        }
        return best;
    }

    /** Keeps only a locale's language and country. */
    public static Locale normalize(Locale locale) {
        return Locale.of(locale.getLanguage(), locale.getCountry());
    }
}
