package com.ultikits.ultitools.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.audience.Audience;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Shared assertions for the framework's real resolver and test doubles. */
public abstract class AbstractLocaleResolverContractTest {
    private ServerMock server;

    /**
     * Creates a resolver configured with the given fallback and auto/forced mode.
     * A production subclass supplies the real resolver with equivalent test configuration;
     * it must not snapshot player locales at construction time. No framework singleton is required.
     */
    protected abstract LocaleResolver createResolver(Locale fallback, Optional<Locale> forced);

    @BeforeEach
    void setUpResolverContract() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDownResolverContract() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void resolve_autoMode_returnsClientLocale() {
        PlayerMock player = server.addPlayer();
        player.setLocale(Locale.US);
        LocaleResolver resolver = createResolver(Locale.CHINESE, Optional.empty());
        assertThat(resolver.resolve(player)).isEqualTo(Locale.US);
        assertThat(resolver.fallback()).isEqualTo(Locale.CHINESE);
        assertThat(resolver.forced()).isEmpty();
    }

    @Test
    void resolve_afterClientLocaleChange_returnsNewLocale() {
        PlayerMock player = server.addPlayer();
        player.setLocale(Locale.US);
        LocaleResolver resolver = createResolver(Locale.CHINESE, Optional.empty());
        assertThat(resolver.resolve(player)).isEqualTo(Locale.US);
        player.setLocale(Locale.GERMANY);
        assertThat(resolver.resolve(player)).isEqualTo(Locale.GERMANY);
    }

    @Test
    void resolve_forcedMode_returnsForcedForEveryPlayer() {
        PlayerMock english = server.addPlayer();
        PlayerMock chinese = server.addPlayer();
        english.setLocale(Locale.US);
        chinese.setLocale(Locale.SIMPLIFIED_CHINESE);
        LocaleResolver resolver = createResolver(Locale.CHINESE, Optional.of(Locale.GERMANY));
        assertThat(resolver.resolve(english)).isEqualTo(Locale.GERMANY);
        assertThat(resolver.resolve(chinese)).isEqualTo(Locale.GERMANY);
        english.setLocale(Locale.FRENCH);
        assertThat(resolver.resolve(english)).isEqualTo(Locale.GERMANY);
        assertThat(resolver.forced()).contains(Locale.GERMANY);
    }

    @Test
    void resolve_nonPlayer_returnsFallback() {
        for (Optional<Locale> forced : java.util.List.of(Optional.<Locale>empty(), Optional.of(Locale.GERMANY))) {
            LocaleResolver resolver = createResolver(Locale.CHINESE, forced);
            assertThat(resolver.resolve(server.getConsoleSender())).isEqualTo(Locale.CHINESE);
            assertThat(resolver.resolve(new Audience() { })).isEqualTo(Locale.CHINESE);
        }
    }

    @Test
    void resolve_nullAudience_isRejected() {
        LocaleResolver resolver = createResolver(Locale.ENGLISH, Optional.empty());
        assertThatNullPointerException().isThrownBy(() -> resolver.resolve(null)).withMessage("audience");
    }
}
