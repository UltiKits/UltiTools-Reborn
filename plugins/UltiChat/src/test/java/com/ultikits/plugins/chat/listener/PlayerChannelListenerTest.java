package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.ChannelConfig;
import com.ultikits.plugins.chat.service.ChannelService;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerChannelListener Tests")
class PlayerChannelListenerTest {

    private ChannelService channelService;
    private ChannelConfig channelConfig;
    private PlayerChannelListener listener;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();

        channelService = mock(ChannelService.class);
        channelConfig = mock(ChannelConfig.class);
        lenient().when(channelConfig.getDefaultChannel()).thenReturn("global");

        listener = new PlayerChannelListener();
        ChatTestHelper.setField(listener, "channelService", channelService);
        ChatTestHelper.setField(listener, "channelConfig", channelConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
    }

    // ==================== onPlayerJoin Tests ====================

    @Nested
    @DisplayName("onPlayerJoin Tests")
    class OnPlayerJoinTests {

        @Test
        @DisplayName("Should set default channel for joining player")
        void shouldSetDefaultChannel() {
            UUID uuid = UUID.randomUUID();
            Player player = ChatTestHelper.createMockPlayer("TestPlayer", uuid);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");

            listener.onPlayerJoin(event);

            verify(channelService).setPlayerChannel(uuid, "global");
        }

        @Test
        @DisplayName("Should use configured default channel")
        void shouldUseConfiguredDefault() {
            when(channelConfig.getDefaultChannel()).thenReturn("local");

            UUID uuid = UUID.randomUUID();
            Player player = ChatTestHelper.createMockPlayer("Player2", uuid);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");

            listener.onPlayerJoin(event);

            verify(channelService).setPlayerChannel(uuid, "local");
        }

        @Test
        @DisplayName("Should handle multiple players joining")
        void shouldHandleMultiplePlayers() {
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();
            Player player1 = ChatTestHelper.createMockPlayer("Player1", uuid1);
            Player player2 = ChatTestHelper.createMockPlayer("Player2", uuid2);

            listener.onPlayerJoin(new PlayerJoinEvent(player1, "joined"));
            listener.onPlayerJoin(new PlayerJoinEvent(player2, "joined"));

            verify(channelService).setPlayerChannel(uuid1, "global");
            verify(channelService).setPlayerChannel(uuid2, "global");
        }
    }

    // ==================== onPlayerQuit Tests ====================

    @Nested
    @DisplayName("onPlayerQuit Tests")
    class OnPlayerQuitTests {

        @Test
        @DisplayName("Should remove player from channel service on quit")
        void shouldRemovePlayerOnQuit() {
            UUID uuid = UUID.randomUUID();
            Player player = ChatTestHelper.createMockPlayer("TestPlayer", uuid);
            PlayerQuitEvent event = new PlayerQuitEvent(player, "left");

            listener.onPlayerQuit(event);

            verify(channelService).removePlayer(uuid);
        }

        @Test
        @DisplayName("Should handle multiple player quits")
        void shouldHandleMultipleQuits() {
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();
            Player player1 = ChatTestHelper.createMockPlayer("Player1", uuid1);
            Player player2 = ChatTestHelper.createMockPlayer("Player2", uuid2);

            listener.onPlayerQuit(new PlayerQuitEvent(player1, "left"));
            listener.onPlayerQuit(new PlayerQuitEvent(player2, "left"));

            verify(channelService).removePlayer(uuid1);
            verify(channelService).removePlayer(uuid2);
        }
    }

    // ==================== Join + Quit Lifecycle Tests ====================

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Should set channel on join and remove on quit")
        void shouldSetAndRemove() {
            UUID uuid = UUID.randomUUID();
            Player player = ChatTestHelper.createMockPlayer("TestPlayer", uuid);

            listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
            verify(channelService).setPlayerChannel(uuid, "global");

            listener.onPlayerQuit(new PlayerQuitEvent(player, "left"));
            verify(channelService).removePlayer(uuid);
        }
    }
}
