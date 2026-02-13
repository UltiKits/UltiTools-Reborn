package com.ultikits.plugins.remotebag.listener;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.mockito.Mockito.*;

@DisplayName("BagListener Tests")
class BagListenerTest {

    private BagListener listener;
    private RemoteBagService bagService;
    private BagLockService lockService;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);

        listener = new BagListener(bagService, lockService);

        playerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== onPlayerQuit ====================

    @Nested
    @DisplayName("onPlayerQuit")
    class OnPlayerQuit {

        @Test
        @DisplayName("Should release all locks")
        void releasesAllLocks() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            verify(lockService).releaseAll(playerUuid);
        }

        @Test
        @DisplayName("Should save bag")
        void savesBag() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            verify(bagService).saveBag(playerUuid);
        }

        @Test
        @DisplayName("Should clear cache")
        void clearsCache() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            verify(bagService).clearCache(playerUuid);
        }

        @Test
        @DisplayName("Should execute all cleanup in order")
        void executesAllCleanup() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);

            listener.onPlayerQuit(event);

            // Verify all three operations were called
            verify(lockService).releaseAll(playerUuid);
            verify(bagService).saveBag(playerUuid);
            verify(bagService).clearCache(playerUuid);
        }
    }
}
