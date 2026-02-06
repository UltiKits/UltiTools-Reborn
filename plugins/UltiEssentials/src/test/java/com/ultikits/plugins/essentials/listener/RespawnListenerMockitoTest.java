package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.SpawnConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for RespawnListener.
 * <p>
 * 测试重生监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("RespawnListener Tests (Mockito)")
class RespawnListenerMockitoTest {

    private RespawnListener listener;
    private EssentialsConfig config;
    private SpawnConfig spawnConfig;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        listener = new RespawnListener();
        config = new EssentialsConfig();
        spawnConfig = new SpawnConfig();

        EssentialsTestHelper.setField(listener, "config", config);
        EssentialsTestHelper.setField(listener, "spawnConfig", spawnConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Test
    @DisplayName("Should skip when spawn is disabled")
    void shouldSkipWhenDisabled() {
        config.setSpawnEnabled(false);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        World world = EssentialsTestHelper.createMockWorld("world");
        Location respawnLoc = new Location(world, 0, 64, 0);
        PlayerRespawnEvent event = new PlayerRespawnEvent(player, respawnLoc, false);

        listener.onPlayerRespawn(event);

        // Location should remain unchanged
        assertThat(event.getRespawnLocation()).isEqualTo(respawnLoc);
    }

    @Test
    @DisplayName("Should skip when teleport on respawn is disabled")
    void shouldSkipWhenTeleportOnRespawnDisabled() {
        config.setSpawnEnabled(true);
        spawnConfig.setTeleportOnRespawn(false);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        World world = EssentialsTestHelper.createMockWorld("world");
        Location respawnLoc = new Location(world, 0, 64, 0);
        PlayerRespawnEvent event = new PlayerRespawnEvent(player, respawnLoc, false);

        listener.onPlayerRespawn(event);

        assertThat(event.getRespawnLocation()).isEqualTo(respawnLoc);
    }

    @Test
    @DisplayName("Should set respawn location to spawn when enabled")
    void shouldSetRespawnToSpawn() throws Exception {
        config.setSpawnEnabled(true);
        spawnConfig.setTeleportOnRespawn(true);

        // Set up spawn world
        World spawnWorld = EssentialsTestHelper.createMockWorld("world");
        when(EssentialsTestHelper.getMockServer().getWorld("world")).thenReturn(spawnWorld);

        // Set spawn location fields
        spawnConfig.setWorld("world");
        spawnConfig.setX(100.0);
        spawnConfig.setY(65.0);
        spawnConfig.setZ(-200.0);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        World otherWorld = EssentialsTestHelper.createMockWorld("nether");
        Location respawnLoc = new Location(otherWorld, 0, 64, 0);
        PlayerRespawnEvent event = new PlayerRespawnEvent(player, respawnLoc, false);

        listener.onPlayerRespawn(event);

        Location newRespawn = event.getRespawnLocation();
        assertThat(newRespawn.getX()).isEqualTo(100.0);
        assertThat(newRespawn.getY()).isEqualTo(65.0);
        assertThat(newRespawn.getZ()).isEqualTo(-200.0);
    }
}
