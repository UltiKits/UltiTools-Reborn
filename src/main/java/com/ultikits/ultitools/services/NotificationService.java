package com.ultikits.ultitools.services;

import java.time.Duration;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.ultikits.ultitools.interfaces.BaseService;
import com.ultikits.ultitools.text.Text;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * Sends short-lived notifications to one player: chat, title, action bar and a
 * self-expiring boss bar. Text is rendered in the player's resolved locale.
 * Methods may be called from any thread.
 *
 * <p>Required arguments reject {@code null} with a {@link NullPointerException}
 * naming the parameter; an optional sound may be {@code null}.
 */
public interface NotificationService extends BaseService {

    /** Default boss bar lifetime used by the short overloads. */
    Duration DEFAULT_BOSS_BAR_DURATION = Duration.ofSeconds(25);

    /** Default title times, equivalent to the former 10/50/20 tick defaults. */
    Title.Times DEFAULT_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(500), Duration.ofMillis(2500), Duration.ofSeconds(1));

    /**
     * Sends a chat message and optionally plays a sound to the player.
     * @param player recipient
     * @param message deferred text
     * @param sound optional sound
     */
    void sendMessageNotification(Player player, Text message, @Nullable Sound sound);

    /**
     * Sends a chat message without a sound.
     * @param player recipient
     * @param message deferred text
     */
    default void sendMessageNotification(Player player, Text message) {
        sendMessageNotification(player, message, null);
    }

    /**
     * Shows a title and subtitle with the given times and optionally plays a sound.
     * @param player recipient
     * @param title deferred title
     * @param subtitle deferred subtitle
     * @param sound optional sound
     * @param times fade-in, stay and fade-out durations
     */
    void sendTitleNotification(Player player, Text title, Text subtitle, @Nullable Sound sound, Title.Times times);

    /**
     * Shows a title and subtitle with the default times, without a sound.
     * @param player recipient
     * @param title deferred title
     * @param subtitle deferred subtitle
     */
    default void sendTitleNotification(Player player, Text title, Text subtitle) {
        sendTitleNotification(player, title, subtitle, null, DEFAULT_TITLE_TIMES);
    }

    /**
     * Shows a title and subtitle with the default times and an optional sound.
     * @param player recipient
     * @param title deferred title
     * @param subtitle deferred subtitle
     * @param sound optional sound
     */
    default void sendTitleNotification(Player player, Text title, Text subtitle, @Nullable Sound sound) {
        sendTitleNotification(player, title, subtitle, sound, DEFAULT_TITLE_TIMES);
    }

    /**
     * Shows a subtitle and an empty title, without a sound.
     * @param player recipient
     * @param subtitle deferred subtitle
     */
    default void sendSubTitleNotification(Player player, Text subtitle) {
        sendSubTitleNotification(player, subtitle, null);
    }

    /**
     * Shows a subtitle and an empty title with an optional sound.
     * @param player recipient
     * @param subtitle deferred subtitle
     * @param sound optional sound
     */
    default void sendSubTitleNotification(Player player, Text subtitle, @Nullable Sound sound) {
        sendTitleNotification(player, Text.literal(Component.empty()), subtitle, sound, DEFAULT_TITLE_TIMES);
    }

    /**
     * Shows text above the hotbar.
     * @param player recipient
     * @param message deferred text
     */
    void sendActionBarNotification(Player player, Text message);

    /**
     * Shows a boss bar that empties over {@code duration} and then disappears. A player has at most
     * one such bar: a new call replaces its text, colour and overlay and restarts the countdown.
     * The bar also disappears when the player leaves.
     *
     * @param player recipient
     * @param message deferred text
     * @param duration positive lifetime, rounded to the next 250 ms countdown step
     * @param color bar colour
     * @param overlay bar overlay
     * @param sound optional sound
     * @throws IllegalArgumentException if {@code duration} is zero or negative
     */
    void sendBossBarNotification(Player player, Text message, Duration duration,
            BossBar.Color color, BossBar.Overlay overlay, @Nullable Sound sound);

    /**
     * Shows a green, continuous boss bar for the default lifetime without a sound.
     * @param player recipient
     * @param message deferred text
     */
    default void sendBossBarNotification(Player player, Text message) {
        sendBossBarNotification(player, message, DEFAULT_BOSS_BAR_DURATION);
    }

    /**
     * Shows a green, continuous boss bar for the given lifetime without a sound.
     * @param player recipient
     * @param message deferred text
     * @param duration positive lifetime
     */
    default void sendBossBarNotification(Player player, Text message, Duration duration) {
        sendBossBarNotification(player, message, duration, null);
    }

    /**
     * Shows a green, continuous boss bar with an optional sound.
     * @param player recipient
     * @param message deferred text
     * @param duration positive lifetime
     * @param sound optional sound
     */
    default void sendBossBarNotification(Player player, Text message, Duration duration, @Nullable Sound sound) {
        sendBossBarNotification(player, message, duration, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS, sound);
    }
}
