package com.ultikits.plugins.sidebar.listener;

import com.ultikits.plugins.sidebar.UltiSideBarTestHelper;
import com.ultikits.plugins.sidebar.service.SideBarService;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.mockito.Mockito.*;

@DisplayName("SideBarListener Tests")
class SideBarListenerTest {

    private SideBarListener listener;
    private SideBarService service;
    private Player player;

    @BeforeEach
    void setUp() throws Exception {
        UltiSideBarTestHelper.setUp();

        service = mock(SideBarService.class);
        listener = new SideBarListener();

        // Inject service via reflection
        UltiSideBarTestHelper.setField(listener, "sideBarService", service);

        player = UltiSideBarTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiSideBarTestHelper.tearDown();
    }

    // ==================== onPlayerJoin ====================

    @Nested
    @DisplayName("onPlayerJoin")
    class OnPlayerJoin {

        @Test
        @DisplayName("Should delegate to service on player join")
        void delegatesToService() {
            PlayerJoinEvent event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerJoin(event);

            verify(service).onPlayerJoin(player);
        }

        @Test
        @DisplayName("Should handle multiple player joins")
        void multipleJoins() {
            Player player1 = UltiSideBarTestHelper.createMockPlayer("Player1", UUID.randomUUID());
            Player player2 = UltiSideBarTestHelper.createMockPlayer("Player2", UUID.randomUUID());

            PlayerJoinEvent event1 = mock(PlayerJoinEvent.class);
            when(event1.getPlayer()).thenReturn(player1);

            PlayerJoinEvent event2 = mock(PlayerJoinEvent.class);
            when(event2.getPlayer()).thenReturn(player2);

            listener.onPlayerJoin(event1);
            listener.onPlayerJoin(event2);

            verify(service).onPlayerJoin(player1);
            verify(service).onPlayerJoin(player2);
        }
    }

    // ==================== onPlayerQuit ====================

    @Nested
    @DisplayName("onPlayerQuit")
    class OnPlayerQuit {

        @Test
        @DisplayName("Should delegate to service on player quit")
        void delegatesToService() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            verify(service).onPlayerQuit(player);
        }

        @Test
        @DisplayName("Should handle multiple player quits")
        void multipleQuits() {
            Player player1 = UltiSideBarTestHelper.createMockPlayer("Player1", UUID.randomUUID());
            Player player2 = UltiSideBarTestHelper.createMockPlayer("Player2", UUID.randomUUID());

            PlayerQuitEvent event1 = mock(PlayerQuitEvent.class);
            when(event1.getPlayer()).thenReturn(player1);

            PlayerQuitEvent event2 = mock(PlayerQuitEvent.class);
            when(event2.getPlayer()).thenReturn(player2);

            listener.onPlayerQuit(event1);
            listener.onPlayerQuit(event2);

            verify(service).onPlayerQuit(player1);
            verify(service).onPlayerQuit(player2);
        }
    }

    // ==================== onWorldChange ====================

    @Nested
    @DisplayName("onWorldChange")
    class OnWorldChange {

        @Test
        @DisplayName("Should delegate to service on world change")
        void delegatesToService() {
            PlayerChangedWorldEvent event = mock(PlayerChangedWorldEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onWorldChange(event);

            verify(service).onWorldChange(player);
        }

        @Test
        @DisplayName("Should handle multiple world changes")
        void multipleChanges() {
            Player player1 = UltiSideBarTestHelper.createMockPlayer("Player1", UUID.randomUUID());
            Player player2 = UltiSideBarTestHelper.createMockPlayer("Player2", UUID.randomUUID());

            PlayerChangedWorldEvent event1 = mock(PlayerChangedWorldEvent.class);
            when(event1.getPlayer()).thenReturn(player1);

            PlayerChangedWorldEvent event2 = mock(PlayerChangedWorldEvent.class);
            when(event2.getPlayer()).thenReturn(player2);

            listener.onWorldChange(event1);
            listener.onWorldChange(event2);

            verify(service).onWorldChange(player1);
            verify(service).onWorldChange(player2);
        }
    }
}
