package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.TimeUnit;

import org.bukkit.event.Listener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * PlayerEventManager after the protocol v1 removal (6.4.0): the v1 {@code initialize} and its seven
 * player-event listeners are gone, so what is left is {@link PlayerEventManager#shutdown()}, which
 * {@code PluginInitiationUtils#disableCloud} still calls.
 */
@DisplayName("PlayerEventManager")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PlayerEventManagerTest {

    @Test
    @DisplayName("a new manager is a Bukkit listener that has registered nothing")
    void newManagerHasRegisteredNothing() {
        PlayerEventManager manager = new PlayerEventManager();

        assertThat(manager).isInstanceOf(Listener.class);
        assertThat(manager.isListenerRegistered()).isFalse();
    }

    @Test
    @DisplayName("shutdown leaves the manager unregistered and may be called repeatedly")
    void shutdownIsIdempotent() {
        PlayerEventManager manager = new PlayerEventManager();

        assertThatCode(() -> {
            manager.shutdown();
            manager.shutdown();
        }).doesNotThrowAnyException();
        assertThat(manager.isListenerRegistered()).isFalse();
    }
}
