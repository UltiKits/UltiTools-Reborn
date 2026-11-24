package com.ultikits.ultitools.services.impl;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/** Per-player notifications rendered by the text engine and owned by the framework scheduler. */
@Service
public class InMemoryNotificationService implements NotificationService {
    private static final Duration STEP = Duration.ofMillis(250);
    private final Messages messages;
    private final Scheduler scheduler;
    private final ConcurrentHashMap<UUID, ActiveBar> bars = new ConcurrentHashMap<>();

    /**
     * Creates a notification service using the framework's root services.
     * @param messages renderer capable of rendering every module namespace
     * @param scheduler framework-owned scheduler
     */
    public InMemoryNotificationService(Messages messages, Scheduler scheduler) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public String getName() {
        return "InMemoryNotificationService";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void sendMessageNotification(Player player, Text message, @Nullable Sound sound) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        player.sendMessage(messages.render(message, player));
        playSound(player, sound);
    }

    @Override
    public void sendTitleNotification(Player player, Text title, Text subtitle, @Nullable Sound sound, Title.Times times) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(subtitle, "subtitle");
        Objects.requireNonNull(times, "times");
        Component renderedTitle = messages.render(title, player);
        Component renderedSubtitle = messages.render(subtitle, player);
        player.showTitle(Title.title(renderedTitle, renderedSubtitle, times));
        playSound(player, sound);
    }

    @Override
    public void sendActionBarNotification(Player player, Text message) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        player.sendActionBar(messages.render(message, player));
    }

    @Override
    public void sendBossBarNotification(Player player, Text message, Duration duration,
            BossBar.Color color, BossBar.Overlay overlay, @Nullable Sound sound) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(overlay, "overlay");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        Component name = messages.render(message, player);
        float decrement = STEP.toNanos() / (float) duration.toNanos();
        // Unlike Executor.execute, run returns a retired handle for an already removed player.
        scheduler.entity(player).run(() -> showBossBar(player, name, color, overlay, sound, decrement));
    }

    private void showBossBar(Player player, Component name, BossBar.Color color,
            BossBar.Overlay overlay, @Nullable Sound sound, float decrement) {
        UUID uuid = player.getUniqueId();
        BossBar bar = replaceBar(player, bars.get(uuid), name, color, overlay);
        playSound(player, sound);
        TaskHandle countdown = scheduler.entity(player).runRepeating(STEP, STEP,
                handle -> tick(player, bar, handle, decrement));
        ActiveBar current = new ActiveBar(bar, countdown);
        bars.put(uuid, current);
        countdown.completion().whenComplete((ignored, failure) -> bars.remove(uuid, current));
    }

    private BossBar replaceBar(Player player, @Nullable ActiveBar previous, Component name,
            BossBar.Color color, BossBar.Overlay overlay) {
        if (previous != null) {
            previous.countdown().cancel();
            return previous.bar().name(name).color(color).overlay(overlay).progress(1f);
        }
        BossBar bar = BossBar.bossBar(name, 1f, color, overlay);
        player.showBossBar(bar);
        return bar;
    }

    private void tick(Player player, BossBar bar, TaskHandle handle, float decrement) {
        float progress = bar.progress() - decrement;
        if (progress <= 0) {
            player.hideBossBar(bar);
            handle.cancel();
        } else {
            bar.progress(progress);
        }
    }

    private void playSound(Player player, @Nullable Sound sound) {
        if (sound != null) {
            player.playSound(sound, Sound.Emitter.self());
        }
    }

    int activeBarCount() {
        return bars.size();
    }

    private record ActiveBar(BossBar bar, TaskHandle countdown) { }
}
