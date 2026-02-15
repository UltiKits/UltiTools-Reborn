package com.ultikits.ultitools.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.*;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.UpdateManager;

@DisplayName("UpdateJoinListener Tests")
class UpdateJoinListenerTest {

    private UpdateManager mockUpdateManager;
    private UpdateJoinListener listener;

    @BeforeEach
    void setUp() {
        mockUpdateManager = mock(UpdateManager.class);
        listener = new UpdateJoinListener(mockUpdateManager);
    }

    @Test
    @DisplayName("Should notify OP player when updates available")
    void shouldNotifyOpPlayer() {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        when(mockUpdateManager.isCheckComplete()).thenReturn(true);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(true);
        when(mockUpdateManager.isPlayerNotified(any())).thenReturn(false);

        Map<String, UpdateInfo> updates = new HashMap<>();
        updates.put("UltiChat", new UpdateInfo());
        updates.put("UltiLogin", new UpdateInfo());
        when(mockUpdateManager.getModuleUpdates()).thenReturn(updates);
        when(mockUpdateManager.getFrameworkUpdate()).thenReturn(null);

        PlayerJoinEvent event = new PlayerJoinEvent(player, "player joined");
        listener.onPlayerJoin(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(player).sendMessage(captor.capture());
        assertThat(captor.getValue()).contains("2");
        verify(mockUpdateManager).markPlayerNotified(player.getUniqueId());
    }

    @Test
    @DisplayName("Should not notify non-OP player")
    void shouldNotNotifyNonOpPlayer() {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(false);

        PlayerJoinEvent event = new PlayerJoinEvent(player, "player joined");
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("Should not notify same player twice")
    void shouldNotNotifySamePlayerTwice() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.isOp()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(uuid);

        when(mockUpdateManager.isCheckComplete()).thenReturn(true);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(true);
        when(mockUpdateManager.isPlayerNotified(uuid)).thenReturn(true);

        PlayerJoinEvent event = new PlayerJoinEvent(player, "player joined");
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("Should not notify when no updates")
    void shouldNotNotifyWhenNoUpdates() {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        when(mockUpdateManager.isCheckComplete()).thenReturn(true);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(false);

        PlayerJoinEvent event = new PlayerJoinEvent(player, "player joined");
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("Should not notify when check not complete")
    void shouldNotNotifyWhenCheckNotComplete() {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        when(mockUpdateManager.isCheckComplete()).thenReturn(false);

        PlayerJoinEvent event = new PlayerJoinEvent(player, "player joined");
        listener.onPlayerJoin(event);

        verify(player, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("Should include framework update in count")
    void shouldIncludeFrameworkUpdateInCount() {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        when(mockUpdateManager.isCheckComplete()).thenReturn(true);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(true);
        when(mockUpdateManager.isPlayerNotified(any())).thenReturn(false);
        when(mockUpdateManager.getFrameworkUpdate()).thenReturn(new UpdateInfo());
        when(mockUpdateManager.getModuleUpdates()).thenReturn(Collections.singletonMap("Chat", new UpdateInfo()));

        PlayerJoinEvent event = new PlayerJoinEvent(player, "player joined");
        listener.onPlayerJoin(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(player).sendMessage(captor.capture());
        // 1 framework + 1 module = 2
        assertThat(captor.getValue()).contains("2");
    }
}
