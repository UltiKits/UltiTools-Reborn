package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.SpawnConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for JoinQuitListener.
 * <p>
 * Tests first-join spawn teleport logic only.
 * Chat-related join/quit messages have been moved to UltiChat.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("JoinQuitListener Tests (Mockito)")
class JoinQuitListenerMockitoTest {

    private JoinQuitListener listener;
    private EssentialsConfig config;
    private SpawnConfig spawnConfig;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        listener = new JoinQuitListener();
        config = new EssentialsConfig();
        spawnConfig = new SpawnConfig();

        EssentialsTestHelper.setField(listener, "config", config);
        EssentialsTestHelper.setField(listener, "spawnConfig", spawnConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Nested
    @DisplayName("First-Join Spawn Teleport")
    class SpawnTeleportTests {

        @Test
        @DisplayName("Should teleport first-time player to spawn when enabled")
        void shouldTeleportFirstTimePlayerToSpawn() throws Exception {
            config.setSpawnEnabled(true);
            spawnConfig.setTeleportOnFirstJoin(true);

            World world = EssentialsTestHelper.createMockWorld("world");
            when(EssentialsTestHelper.getMockServer().getWorld("world")).thenReturn(world);

            Player player = EssentialsTestHelper.createMockPlayer("NewPlayer", UUID.randomUUID());
            when(player.hasPlayedBefore()).thenReturn(false);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player).teleport(any(Location.class));
        }

        @Test
        @DisplayName("Should not teleport returning player to spawn")
        void shouldNotTeleportReturningPlayer() throws Exception {
            config.setSpawnEnabled(true);
            spawnConfig.setTeleportOnFirstJoin(true);

            Player player = EssentialsTestHelper.createMockPlayer("OldPlayer", UUID.randomUUID());
            when(player.hasPlayedBefore()).thenReturn(true);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player, never()).teleport(any(Location.class));
        }

        @Test
        @DisplayName("Should not teleport when spawn is disabled")
        void shouldNotTeleportWhenSpawnDisabled() {
            config.setSpawnEnabled(false);
            spawnConfig.setTeleportOnFirstJoin(true);

            Player player = EssentialsTestHelper.createMockPlayer("NewPlayer", UUID.randomUUID());
            when(player.hasPlayedBefore()).thenReturn(false);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player, never()).teleport(any(Location.class));
        }

        @Test
        @DisplayName("Should not teleport when teleportOnFirstJoin is disabled")
        void shouldNotTeleportWhenFirstJoinTeleportDisabled() {
            config.setSpawnEnabled(true);
            spawnConfig.setTeleportOnFirstJoin(false);

            Player player = EssentialsTestHelper.createMockPlayer("NewPlayer", UUID.randomUUID());
            when(player.hasPlayedBefore()).thenReturn(false);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player, never()).teleport(any(Location.class));
        }

        @Test
        @DisplayName("Should not modify join message (handled by UltiChat)")
        void shouldNotModifyJoinMessage() {
            config.setSpawnEnabled(false);

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerJoinEvent event = new PlayerJoinEvent(player, "Original message");

            listener.onPlayerJoin(event);

            assertThat(event.getJoinMessage()).isEqualTo("Original message");
        }
    }
}
