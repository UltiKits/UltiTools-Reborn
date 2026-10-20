package com.ultikits.ultitools.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;
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
    @DisplayName("Should not notify when no updates, however many times the player joins (Behavior 3, #431)")
    void shouldNotNotifyWhenNoUpdates() {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        when(mockUpdateManager.isCheckComplete()).thenReturn(true);
        when(mockUpdateManager.hasAnyUpdates()).thenReturn(false);

        // Three joins, not one: "however many times" is the claim, and a single invocation
        // cannot distinguish it from "not on this particular join".
        listener.onPlayerJoin(new PlayerJoinEvent(player, "player joined"));
        listener.onPlayerJoin(new PlayerJoinEvent(player, "player joined"));
        listener.onPlayerJoin(new PlayerJoinEvent(player, "player joined"));

        verify(player, never()).sendMessage(anyString());
        verify(mockUpdateManager, never()).markPlayerNotified(any());
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

    /**
     * Behavior 1, end-to-end (#431): a real {@link UpdateManager} (not a mock) through the real
     * quit path -- {@link PlayerCacheManager#onPlayerQuit(UUID)} -- driving the real
     * {@link UpdateJoinListener}. Exercises the whole promise the listener's own class javadoc
     * makes ("Each player is only notified once per server session") rather than relying on the
     * class-level {@code mockUpdateManager} that the rest of this file uses.
     */
    @Nested
    @DisplayName("End-to-end with a real UpdateManager through the real quit path (#431)")
    class RealUpdateManagerIntegrationTests {

        private PlayerCacheManager liveManager;
        private MockedStatic<UltiTools> ultiToolsStatic;
        private UpdateManager realUpdateManager;

        @BeforeEach
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // set private has-an-update state directly, avoiding the heavy checkUpdatesSync() static-mock dance
        void wireRealManager() throws Exception {
            liveManager = new PlayerCacheManager();
            UltiTools mockUltiTools = mock(UltiTools.class);
            PluginManager mockPluginManager = mock(PluginManager.class);
            when(mockPluginManager.getPlayerCacheManager()).thenReturn(liveManager);
            when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);
            ultiToolsStatic = mockStatic(UltiTools.class);
            ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);

            realUpdateManager = new UpdateManager(mock(Logger.class));
            Field checkCompleteField = UpdateManager.class.getDeclaredField("checkComplete");
            checkCompleteField.setAccessible(true);
            checkCompleteField.set(realUpdateManager, true);
            Field frameworkUpdateField = UpdateManager.class.getDeclaredField("frameworkUpdate");
            frameworkUpdateField.setAccessible(true);
            frameworkUpdateField.set(realUpdateManager, new UpdateInfo());
        }

        @AfterEach
        void closeStaticMock() {
            if (ultiToolsStatic != null) {
                ultiToolsStatic.close();
            }
        }

        @Test
        @DisplayName("join notifies once; quit-and-rejoin within the same server run does not notify again")
        void joinNotifyQuitRejoinDoesNotRenotify() {
            UpdateJoinListener realListener = new UpdateJoinListener(realUpdateManager);
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.isOp()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(uuid);

            realListener.onPlayerJoin(new PlayerJoinEvent(player, "player joined"));
            verify(player, times(1)).sendMessage(anyString());

            liveManager.onPlayerQuit(uuid);

            realListener.onPlayerJoin(new PlayerJoinEvent(player, "player joined"));
            verify(player, times(1)).sendMessage(anyString());
        }
    }
}
