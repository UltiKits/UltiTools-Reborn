package com.ultikits.ultitools.text;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/** A test-only immutable mode/fallback resolver; player locale itself is read on every call. */
public final class FixedLocaleResolver implements LocaleResolver {
    private final Locale fallback;
    private final Optional<Locale> forced;

    public FixedLocaleResolver(Locale fallback, Optional<Locale> forced) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.forced = Objects.requireNonNull(forced, "forced");
    }

    @Override
    public Locale resolve(Audience audience) {
        Objects.requireNonNull(audience, "audience");
        if (audience instanceof Player player) {
            return forced.orElseGet(player::locale);
        }
        return fallback;
    }

    @Override
    public Locale fallback() {
        return fallback;
    }

    @Override
    public Optional<Locale> forced() {
        return forced;
    }
}
