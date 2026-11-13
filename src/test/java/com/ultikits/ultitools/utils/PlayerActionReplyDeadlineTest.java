package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerActionReplyDeadlineTest {
    @Test
    void stuckTeleportAnswersOnceAndLateSuccessCannotSendAnotherResult() {
        CompletableFuture<Boolean> teleport = new CompletableFuture<>();
        AtomicReference<Runnable> expiry = new AtomicReference<>();
        ScheduledExecutorService timer = timer(expiry, mock(ScheduledFuture.class));
        RecordingChannel replies = handle(teleport, timer);
        assertThat(replies.frames()).isEmpty();

        assertThat(expiry.get()).as("a stuck teleport must have a local reply deadline").isNotNull();
        expiry.get().run();
        assertThat(replies.frames()).hasSize(1);
        assertThat(replies.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("internal_error");
        assertThat(replies.frames().get(0).toString()).contains("teleport did not complete in time");
        assertThat(teleport.isCancelled()).as("a reply deadline never interrupts the server operation").isFalse();

        teleport.complete(Boolean.TRUE);
        assertThat(replies.frames()).hasSize(1);
    }

    @Test
    void completedTeleportCancelsItsDeadlineAndKeepsItsSingleSuccess() {
        CompletableFuture<Boolean> teleport = new CompletableFuture<>();
        AtomicReference<Runnable> expiry = new AtomicReference<>();
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        RecordingChannel replies = handle(teleport, timer(expiry, scheduled));

        teleport.complete(Boolean.TRUE);
        verify(scheduled).cancel(false);
        expiry.get().run();

        assertThat(replies.frames()).hasSize(1);
        assertThat(replies.frames().get(0).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
    }

    @Test
    void alreadyCompletedTeleportAlsoCancelsItsDeadline() {
        AtomicReference<Runnable> expiry = new AtomicReference<>();
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);

        RecordingChannel replies = handle(CompletableFuture.completedFuture(Boolean.TRUE), timer(expiry, scheduled));

        verify(scheduled).cancel(false);
        expiry.get().run();
        assertThat(replies.frames()).hasSize(1);
        assertThat(replies.frames().get(0).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
    }

    @Test
    void stoppedTimerStillProducesAnErrorReply() {
        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        doThrow(new RejectedExecutionException("stopping")).when(timer)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        RecordingChannel replies = handle(new CompletableFuture<>(), timer);

        assertThat(replies.frames()).hasSize(1);
        assertThat(replies.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("internal_error");
        assertThat(replies.frames().get(0).toString()).doesNotContain("stopping");
    }

    private static ScheduledExecutorService timer(AtomicReference<Runnable> expiry, ScheduledFuture<?> future) {
        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        when(timer.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(1, Long.class)).isEqualTo(25_000L);
            assertThat(invocation.getArgument(2, TimeUnit.class)).isEqualTo(TimeUnit.MILLISECONDS);
            expiry.set(invocation.getArgument(0));
            return future;
        });
        return timer;
    }

    private static RecordingChannel handle(CompletableFuture<Boolean> teleport, ScheduledExecutorService timer) {
        Player subject = mock(Player.class);
        Player target = mock(Player.class);
        when(subject.isOnline()).thenReturn(true);
        when(target.isOnline()).thenReturn(true);
        when(target.getLocation()).thenReturn(new Location(mock(World.class), 1, 65, 1));
        when(subject.teleportAsync(any(Location.class))).thenReturn(teleport);
        JsonObject frame = ContractFixtures.object("fixtures/agent/player.action/valid/teleport-to-player.json");
        RecordingChannel replies = new RecordingChannel();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(subject);
            bukkit.when(() -> Bukkit.getPlayerExact(".Steve")).thenReturn(target);
            new PlayerActionHandler(Runnable::run, null, timer).handle(new CloudRequest(
                    frame.get("id").getAsString(), "player.action", frame.get("ts").getAsLong(), null,
                    frame.getAsJsonObject("payload")), replies);
        }
        return replies;
    }
}
