package com.ultikits.ultitools.text;

import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.ApiStatus;

/**
 * The single decision point for recipient locale preference, separate from catalogue lookup.
 * With {@code language=auto}, a player resolves to {@code Player#locale()}; with a concrete
 * language every player resolves to that forced locale. All non-player audiences, including
 * console, command blocks and plugin audiences, resolve to {@link #fallback()} in both modes.
 *
 * <p>Thread-safe and pure with respect to its inputs: player locale is never cached, so a client
 * language change is visible on the next call. A later per-player override can replace this
 * injected service without changing its callers.
 */
@ApiStatus.NonExtendable
public interface LocaleResolver {
    /** Returns the recipient's preferred locale, without checking catalogue availability. */
    Locale resolve(Audience audience);

    /** Returns the operator's fallback language, also used for console output. */
    Locale fallback();

    /** Returns the forced language, or empty in auto mode. */
    Optional<Locale> forced();
}
