package com.ultikits.ultitools.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;

/** Deterministic notification rendering, countdown and entity-lifecycle regressions. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class InMemoryNotificationServiceTest {
    private ManualScheduler.Engine engine;
    private Scheduler scheduler;
    private InMemoryMessages messages;
    private InMemoryNotificationService service;
    private Player player;
    private Text hello;

    @BeforeEach
    void setUp() {
        engine = new ManualScheduler.Engine();
        scheduler = engine.scheduler("ultitools");
        messages = new InMemoryMessages("ultitools", new FixedLocaleResolver(Locale.ENGLISH, Optional.empty()));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.locale()).thenReturn(Locale.SIMPLIFIED_CHINESE);
        MessageKey key = MessageKey.of("anothermodule", "probe.hello");
        messages.put(key, Locale.ENGLISH, "Hello <name>");
        messages.put(key, Locale.CHINESE, "你好 <name>");
        hello = Text.of(key, Arg.string("name", "Alex"));
        service = new InMemoryNotificationService(messages, scheduler);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void metadataAndServiceAnnotationArePreserved() {
        assertThat(service).isInstanceOf(NotificationService.class);
        assertThat(service.getName()).isEqualTo("InMemoryNotificationService");
        assertThat(service.getAuthor()).isEqualTo("wisdomme");
        assertThat(service.getVersion()).isEqualTo(1);
        assertThat(InMemoryNotificationService.class.isAnnotationPresent(Service.class)).isTrue();
    }

    @Test
    void message_rendersInPlayersLocale_andPlaysSoundOnlyWhenGiven() {
        service.sendMessageNotification(player, hello);
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        assertThat(plain(sent.getValue())).isEqualTo("你好 Alex");
        verify(player, never()).playSound(any(Sound.class), any(Sound.Emitter.class));

        Sound sound = sound();
        service.sendMessageNotification(player, hello, sound);
        verify(player).playSound(sound, Sound.Emitter.self());
        assertThat(engine.warnings()).isEmpty();
    }

    @Test
    void messagesFollowThePlayersNewLocaleWithoutRecreatingTheService() {
        service.sendMessageNotification(player, hello);
        when(player.locale()).thenReturn(Locale.US);
        service.sendMessageNotification(player, hello);
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player, times(2)).sendMessage(sent.capture());
        assertThat(sent.getAllValues()).extracting(this::plain).containsExactly("你好 Alex", "Hello Alex");
    }

    @Test
    void actionBar_rendersInPlayersLocale() {
        service.sendActionBarNotification(player, hello);
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(sent.capture());
        assertThat(plain(sent.getValue())).isEqualTo("你好 Alex");
    }

    @Test
    void userArgumentsStayLiteralAndLiteralComponentsKeepTheirEvents() {
        MessageKey key = messages.key("unsafe");
        messages.put(key, Locale.CHINESE, "你好 <name> tail");
        service.sendMessageNotification(player, Text.of(key, Arg.string("name", "<red>Alex</red>")));
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        assertThat(plain(sent.getValue())).isEqualTo("你好 <red>Alex</red> tail");

        Component styled = Component.text("§a literal", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/example"));
        service.sendActionBarNotification(player, Text.literal(styled));
        verify(player).sendActionBar(styled);
        assertThat(styled.clickEvent()).isEqualTo(ClickEvent.runCommand("/example"));
    }

    @Test
    void title_usesGivenTimes_andDefaultTimesEqualOldTickValues() {
        Title.Times custom = Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofMillis(750));
        service.sendTitleNotification(player, hello, hello, null, custom);
        service.sendTitleNotification(player, hello, hello);
        ArgumentCaptor<Title> sent = ArgumentCaptor.forClass(Title.class);
        verify(player, times(2)).showTitle(sent.capture());
        Title given = sent.getAllValues().getFirst();
        assertThat(plain(given.title())).isEqualTo("你好 Alex");
        assertThat(plain(given.subtitle())).isEqualTo("你好 Alex");
        assertThat(given.times()).isEqualTo(custom);
        assertThat(sent.getValue().times()).isEqualTo(NotificationService.DEFAULT_TITLE_TIMES);
        assertThat(NotificationService.DEFAULT_TITLE_TIMES.fadeIn()).isEqualTo(Duration.ofMillis(500));
        assertThat(NotificationService.DEFAULT_TITLE_TIMES.stay()).isEqualTo(Duration.ofMillis(2500));
        assertThat(NotificationService.DEFAULT_TITLE_TIMES.fadeOut()).isEqualTo(Duration.ofSeconds(1));
        verify(player, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
    }

    @Test
    void titleAndSubtitleSoundOverloadsPlayTheSuppliedSound() {
        Sound sound = sound();
        service.sendTitleNotification(player, hello, hello, sound);
        service.sendSubTitleNotification(player, hello, sound);
        verify(player, times(2)).playSound(sound, Sound.Emitter.self());
        ArgumentCaptor<Title> sent = ArgumentCaptor.forClass(Title.class);
        verify(player, times(2)).showTitle(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(title ->
                assertThat(title.times()).isEqualTo(NotificationService.DEFAULT_TITLE_TIMES));
    }

    @Test
    void subTitle_sendsEmptyTitle() {
        service.sendSubTitleNotification(player, hello);
        ArgumentCaptor<Title> sent = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(sent.capture());
        assertThat(sent.getValue().title()).isEqualTo(Component.empty());
        assertThat(plain(sent.getValue().subtitle())).isEqualTo("你好 Alex");
        verify(player, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
    }

    @Test
    void bossBar_showsOnEntityContext_notOnCallerThread() {
        service.sendBossBarNotification(player, hello, Duration.ofSeconds(1));
        verify(player, never()).showBossBar(any(BossBar.class));
        assertThat(service.activeBarCount()).isZero();
        engine.advanceTicks(1);
        BossBar bar = shownBar(player);
        assertThat(plain(bar.name())).isEqualTo("你好 Alex");
        assertThat(bar.progress()).isEqualTo(1f);
        assertThat(service.activeBarCount()).isEqualTo(1);
    }

    @Test
    void bossBar_emptiesOverDuration_thenHides() {
        service.sendBossBarNotification(player, hello, Duration.ofSeconds(1));
        engine.advanceTicks(1);
        BossBar bar = shownBar(player);
        List<Float> progress = new ArrayList<>();
        bar.addListener(new BossBar.Listener() {
            @Override
            public void bossBarProgressChanged(BossBar changed, float previous, float current) {
                progress.add(current);
            }
        });
        // Show at tick 1; Ticks.ceil(250 ms) = 5. Updates occur at 6, 11, 16 and 21.
        engine.advanceTicks(18);
        verify(player, never()).hideBossBar(any(BossBar.class));
        assertThat(progress).containsExactly(0.75f, 0.5f, 0.25f);
        engine.advanceTicks(2);
        verify(player).hideBossBar(bar);
        assertThat(service.activeBarCount()).isZero();
        engine.advanceTicks(50);
        verify(player).hideBossBar(bar);
    }

    @Test
    void secondNotificationReplacesTextAndRestartsSingleCountdown() {
        service.sendBossBarNotification(player, hello, Duration.ofSeconds(2));
        engine.advanceTicks(21);
        BossBar bar = shownBar(player);
        assertThat(bar.progress()).isEqualTo(0.5f);
        Sound sound = sound();
        service.sendBossBarNotification(player, Text.plain("Second"), Duration.ofSeconds(2),
                BossBar.Color.RED, BossBar.Overlay.NOTCHED_10, sound);
        engine.advanceTicks(1);
        assertThat(shownBar(player)).isSameAs(bar);
        assertThat(plain(bar.name())).isEqualTo("Second");
        assertThat(bar.color()).isEqualTo(BossBar.Color.RED);
        assertThat(bar.overlay()).isEqualTo(BossBar.Overlay.NOTCHED_10);
        assertThat(bar.progress()).isEqualTo(1f);
        assertThat(service.activeBarCount()).isEqualTo(1);
        verify(player).playSound(sound, Sound.Emitter.self());
        // Second show at tick 22: 1.9 s = 38 ticks still shows it; expiry is tick 62.
        engine.advanceTicks(38);
        verify(player, never()).hideBossBar(any(BossBar.class));
        engine.advanceTicks(2);
        verify(player).hideBossBar(bar);
        engine.advanceTicks(40);
        verify(player).hideBossBar(bar);
        assertThat(service.activeBarCount()).isZero();
    }

    @Test
    void playerLeaving_retiresCountdown_andForgetsBar() {
        service.sendBossBarNotification(player, hello);
        engine.advanceTicks(1);
        assertThat(service.activeBarCount()).isEqualTo(1);
        engine.retire(player);
        assertThat(service.activeBarCount()).isZero();
        verify(player, never()).hideBossBar(any(BossBar.class));

        // A reconnect is a new Player instance with the same UUID, not an unretired old entity.
        UUID reconnectingId = player.getUniqueId();
        Player reconnected = mock(Player.class);
        when(reconnected.getUniqueId()).thenReturn(reconnectingId);
        when(reconnected.locale()).thenReturn(Locale.ENGLISH);
        service.sendBossBarNotification(reconnected, hello, Duration.ofSeconds(1));
        engine.advanceTicks(1);
        assertThat(plain(shownBar(reconnected).name())).isEqualTo("Hello Alex");
        engine.advanceTicks(20);
        assertThat(service.activeBarCount()).isZero();
        assertThat(engine.warnings()).isEmpty();
    }

    @Test
    void replacedCountdownEnding_doesNotRemoveNewBar() {
        CompletableFuture<Void> staleCompletion = new CompletableFuture<>();
        AtomicBoolean first = new AtomicBoolean(true);
        TaskContext context = spy(scheduler.entity(player));
        doAnswer(invocation -> {
            TaskHandle actual = (TaskHandle) invocation.callRealMethod();
            if (first.getAndSet(false)) {
                TaskHandle delayed = spy(actual);
                doReturn(staleCompletion.minimalCompletionStage()).when(delayed).completion();
                return delayed;
            }
            return actual;
        }).when(context).runRepeating(any(Duration.class), any(Duration.class), any());
        Scheduler observed = spy(scheduler);
        doReturn(context).when(observed).entity(player);
        service = new InMemoryNotificationService(messages, observed);
        service.sendBossBarNotification(player, hello);
        engine.advanceTicks(1);
        service.sendBossBarNotification(player, Text.plain("Replacement"));
        engine.advanceTicks(1);
        assertThat(service.activeBarCount()).isEqualTo(1);

        staleCompletion.completeExceptionally(new CancellationException("Replaced countdown"));

        assertThat(service.activeBarCount()).isEqualTo(1);
        assertThat(plain(shownBar(player).name())).isEqualTo("Replacement");
    }

    @Test
    void separatePlayersAndServiceInstancesDoNotShareBars() {
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.locale()).thenReturn(Locale.ENGLISH);
        service.sendBossBarNotification(player, hello);
        service.sendBossBarNotification(other, hello);
        engine.advanceTicks(1);
        assertThat(shownBar(player)).isNotSameAs(shownBar(other));
        assertThat(plain(shownBar(other).name())).isEqualTo("Hello Alex");
        engine.retire(player);
        assertThat(service.activeBarCount()).isEqualTo(1);
        InMemoryNotificationService independent = new InMemoryNotificationService(messages, scheduler);
        assertThat(independent.activeBarCount()).isZero();
    }

    @Test
    void defaultBossBarLifetimeColorAndOverlayArePreserved() {
        service.sendBossBarNotification(player, hello);
        engine.advanceTicks(1);
        BossBar bar = shownBar(player);
        assertThat(NotificationService.DEFAULT_BOSS_BAR_DURATION).isEqualTo(Duration.ofSeconds(25));
        assertThat(bar.color()).isEqualTo(BossBar.Color.GREEN);
        assertThat(bar.overlay()).isEqualTo(BossBar.Overlay.PROGRESS);
        verify(player, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
        engine.advanceTicks(495);
        verify(player, never()).hideBossBar(any(BossBar.class));
        // Float subtraction can leave a tiny positive remainder: at most one extra 5-tick step.
        engine.advanceTicks(10);
        verify(player).hideBossBar(bar);
        assertThat(service.activeBarCount()).isZero();
    }

    @Test
    void bossBarSoundOverloadIsDeferredAlongWithTheBar() {
        Sound sound = sound();
        service.sendBossBarNotification(player, hello, Duration.ofSeconds(1), sound);
        verify(player, never()).playSound(any(Sound.class), any(Sound.Emitter.class));
        engine.advanceTicks(1);
        verify(player).playSound(sound, Sound.Emitter.self());
        assertThat(service.activeBarCount()).isEqualTo(1);
    }

    @Test
    void retiredPlayer_bossBarCallIsNoOp() {
        engine.retire(player);
        assertDoesNotThrow(() -> service.sendBossBarNotification(player, hello));
        engine.advanceTicks(100);
        verify(player, never()).showBossBar(any(BossBar.class));
        assertThat(service.activeBarCount()).isZero();
        assertThat(engine.warnings()).isEmpty();
    }

    @Test
    void playerRetiredBeforeTheFirstTickNeverGetsABar() {
        service.sendBossBarNotification(player, hello);
        engine.retire(player);
        engine.advanceTicks(100);
        verify(player, never()).showBossBar(any(BossBar.class));
        assertThat(service.activeBarCount()).isZero();
    }

    @Test
    void unloadingTheSchedulerForgetsTheCountdown() {
        service.sendBossBarNotification(player, hello);
        engine.advanceTicks(1);
        engine.unload("ultitools");
        assertThat(service.activeBarCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -1000})
    void nonPositiveDuration_throws(long millis) {
        assertThatThrownBy(() -> service.sendBossBarNotification(player, hello, Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("duration must be positive");
        assertThat(service.activeBarCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages", "scheduler", "chat.player", "chat.message", "action.player", "action.message",
            "title.player", "title.title", "title.subtitle", "title.times", "boss.player", "boss.message",
            "boss.duration", "boss.color", "boss.overlay", "subtitle.subtitle"})
    void nullArguments_throwNpeNamingTheParameter(String argument) {
        Executable call = nullCall(argument);
        NullPointerException failure = assertThrows(NullPointerException.class, call);
        assertThat(failure).hasMessage(argument.substring(argument.lastIndexOf('.') + 1));
    }

    private Executable nullCall(String argument) {
        return switch (argument) {
            case "messages" -> () -> new InMemoryNotificationService(null, scheduler);
            case "scheduler" -> () -> new InMemoryNotificationService(messages, null);
            case "chat.player" -> () -> service.sendMessageNotification(null, hello);
            case "chat.message" -> () -> service.sendMessageNotification(player, null);
            case "action.player" -> () -> service.sendActionBarNotification(null, hello);
            case "action.message" -> () -> service.sendActionBarNotification(player, null);
            case "title.player" -> () -> service.sendTitleNotification(null, hello, hello);
            case "title.title" -> () -> service.sendTitleNotification(player, null, hello);
            case "title.subtitle" -> () -> service.sendTitleNotification(player, hello, null);
            case "title.times" -> () -> service.sendTitleNotification(player, hello, hello, null, null);
            case "boss.player" -> () -> service.sendBossBarNotification(null, hello);
            case "boss.message" -> () -> service.sendBossBarNotification(player, null);
            case "boss.duration" -> () -> service.sendBossBarNotification(player, hello, null);
            case "boss.color" -> () -> service.sendBossBarNotification(player, hello, Duration.ofSeconds(1),
                    null, BossBar.Overlay.PROGRESS, null);
            case "boss.overlay" -> () -> service.sendBossBarNotification(player, hello, Duration.ofSeconds(1),
                    BossBar.Color.GREEN, null, null);
            case "subtitle.subtitle" -> () -> service.sendSubTitleNotification(player, null);
            default -> throw new IllegalArgumentException(argument);
        };
    }

    private BossBar shownBar(Player recipient) {
        ArgumentCaptor<BossBar> shown = ArgumentCaptor.forClass(BossBar.class);
        verify(recipient).showBossBar(shown.capture());
        return shown.getValue();
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private Sound sound() {
        return Sound.sound(Key.key("minecraft:entity.experience_orb.pickup"), Sound.Source.MASTER, 0.5f, 1.5f);
    }
}
