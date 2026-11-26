package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.LocaleResolver;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Resolves current configuration strings without caching player preferences. */
@ApiStatus.Internal
public final class ConfiguredLocaleResolver implements LocaleResolver {
    // Match tzdata IDs, including historical aliases, never an offset shared with unrelated regions.
    private static final Set<String> CHINESE_TIME_ZONES = Set.of("Asia/Shanghai", "Asia/Urumqi", "Asia/Hong_Kong",
            "Asia/Macau", "PRC", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin", "Asia/Kashgar", "Hongkong", "Asia/Macao");

    private final Supplier<String> language;
    private final Supplier<String> fallbackLanguage;
    private final Logger logger;
    private final Supplier<ZoneId> timeZone;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Snapshot cached;
    private @Nullable ZoneId automaticTimeZone;

    /** Creates a resolver whose suppliers are read on every operation. */
    public ConfiguredLocaleResolver(Supplier<String> language, Supplier<String> fallbackLanguage, Logger logger) {
        this(language, fallbackLanguage, logger, ZoneId::systemDefault);
    }

    ConfiguredLocaleResolver(Supplier<String> language, Supplier<String> fallbackLanguage, Logger logger,
            Supplier<ZoneId> timeZone) {
        this.language = Objects.requireNonNull(language, "language");
        this.fallbackLanguage = Objects.requireNonNull(fallbackLanguage, "fallbackLanguage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.timeZone = Objects.requireNonNull(timeZone, "timeZone");
    }

    /** Re-evaluates the fallback once after a successful framework configuration reload. */
    public synchronized void reload() {
        cached = null;
        automaticTimeZone = null;
        snapshot();
    }

    /**
     * Reads operator values without inheriting the newly shipped defaults for an existing configuration.
     * This method never saves or otherwise changes the operator's configuration file.
     * @param configuration the current configuration, replaced by Bukkit on reload
     * @param logger the framework logger
     * @return a resolver over explicit configuration values
     */
    public static ConfiguredLocaleResolver forConfig(Supplier<? extends ConfigurationSection> configuration,
            Logger logger) {
        return forConfig(configuration, logger, ZoneId::systemDefault);
    }

    static ConfiguredLocaleResolver forConfig(Supplier<? extends ConfigurationSection> configuration,
            Logger logger, Supplier<ZoneId> timeZone) {
        Objects.requireNonNull(configuration, "configuration");
        return new ConfiguredLocaleResolver(() -> configuration.get().getString("language", null),
                () -> configuration.get().getString("fallback-language", null), logger, timeZone);
    }

    Optional<ZoneId> automaticZone() {
        return snapshot().selection().zone();
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

    private synchronized Snapshot snapshot() {
        String rawLanguage = language.get();
        String rawFallback = fallbackLanguage.get();
        Snapshot previous = cached;
        if (previous != null && Objects.equals(previous.rawLanguage(), rawLanguage)
                && Objects.equals(previous.rawFallback(), rawFallback)) {
            return previous;
        }
        Optional<Locale> forced = configuredLanguage(rawLanguage);
        Fallback selection = selectFallback(rawFallback, forced);
        Snapshot current = new Snapshot(rawLanguage, rawFallback, forced, selection);
        cached = current;
        return current;
    }

    private Optional<Locale> configuredLanguage(@Nullable String raw) {
        Optional<Locale> explicit = LocaleNames.parse(raw);
        if (explicit.isPresent() || LocaleNames.isAutomatic(raw)) {
            return explicit;
        }
        if (LocaleNames.isSafeLanguageCode(raw)) {
            // Retain 6.x custom names without interpreting their files as new catalogues.
            String base = LocaleNames.officialLanguageOf(raw, List.of("en", "zh"));
            return Optional.of(base == null ? Locale.ENGLISH : Locale.forLanguageTag(base));
        }
        warn("language", raw);
        return Optional.empty();
    }

    private Fallback selectFallback(@Nullable String raw, Optional<Locale> forced) {
        Optional<Locale> explicit = LocaleNames.parse(raw);
        if (explicit.isPresent()) {
            return new Fallback(explicit.orElseThrow(), Optional.empty());
        }
        if (!LocaleNames.isAutomatic(raw)) {
            warn("fallback-language", raw);
            return new Fallback(forced.orElse(Locale.ENGLISH), Optional.empty());
        }
        // Automatic fallback follows a concrete language first, including retained 6.x custom-name prefixes.
        if (forced.isPresent()) {
            return new Fallback(forced.orElseThrow(), Optional.empty());
        }
        if (automaticTimeZone == null) {
            automaticTimeZone = Objects.requireNonNull(timeZone.get(), "system time zone");
        }
        Locale locale = CHINESE_TIME_ZONES.contains(automaticTimeZone.getId()) ? Locale.CHINESE : Locale.ENGLISH;
        return new Fallback(locale, Optional.of(automaticTimeZone));
    }

    private void warn(String key, String value) {
        if (warned.add(key + '\0' + value)) {
            logger.warning("Invalid " + key + " locale: " + value);
        }
    }

    private record Fallback(Locale locale, Optional<ZoneId> zone) { }

    private record Snapshot(@Nullable String rawLanguage, @Nullable String rawFallback,
            Optional<Locale> forced, Fallback selection) {
        Locale fallback() { return selection.locale(); }
    }
}
