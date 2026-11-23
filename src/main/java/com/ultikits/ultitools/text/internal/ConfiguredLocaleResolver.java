package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.LocaleResolver;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Resolves current configuration strings without caching player preferences. */
@ApiStatus.Internal
public final class ConfiguredLocaleResolver implements LocaleResolver {
    private final Supplier<String> language;
    private final Supplier<String> fallbackLanguage;
    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Snapshot cached;

    /** Creates a resolver whose suppliers are read on every operation. */
    public ConfiguredLocaleResolver(Supplier<String> language, Supplier<String> fallbackLanguage, Logger logger) {
        this.language = Objects.requireNonNull(language, "language");
        this.fallbackLanguage = Objects.requireNonNull(fallbackLanguage, "fallbackLanguage");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Locale resolve(Audience audience) {
        Objects.requireNonNull(audience, "audience");
        Snapshot settings = snapshot();
        return audience instanceof Player player ? settings.forced().orElseGet(player::locale) : settings.fallback();
    }

    @Override
    public Locale fallback() { return snapshot().fallback(); }

    @Override
    public Optional<Locale> forced() { return snapshot().forced(); }

    private Snapshot snapshot() {
        String rawLanguage = language.get();
        String rawFallback = fallbackLanguage.get();
        Snapshot previous = cached;
        if (previous != null && Objects.equals(previous.rawLanguage(), rawLanguage)
                && Objects.equals(previous.rawFallback(), rawFallback)) {
            return previous;
        }
        Optional<Locale> forced = LocaleNames.parse(rawLanguage);
        if (forced.isEmpty() && !LocaleNames.isAutomatic(rawLanguage)) {
            warn("language", rawLanguage);
        }
        Optional<Locale> fallback = LocaleNames.parse(rawFallback);
        if (fallback.isEmpty() && rawFallback != null && !rawFallback.isBlank()) {
            warn("fallback-language", rawFallback);
        }
        Snapshot current = new Snapshot(rawLanguage, rawFallback, forced,
                fallback.orElseGet(() -> forced.orElse(Locale.ENGLISH)));
        cached = current;
        return current;
    }

    private void warn(String key, String value) {
        if (warned.add(key + '\0' + value)) {
            logger.warning("Invalid " + key + " locale: " + value);
        }
    }

    private record Snapshot(@Nullable String rawLanguage, @Nullable String rawFallback,
            Optional<Locale> forced, Locale fallback) { }
}
