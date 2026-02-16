package com.ultikits.ultitools.testing;

import static org.mockito.Mockito.*;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Factory methods for creating commonly-mocked Bukkit objects.
 * <p>
 * These factories create mock objects with sensible defaults,
 * reducing boilerplate in test setup methods.
 *
 * @since 1.0.0
 */
public final class MockFactories {

    private MockFactories() {}

    /**
     * Creates a mock Player with name, UUID, and basic stubs.
     * <p>
     * The player will have:
     * <ul>
     *   <li>UUID derived from name via {@link UUID#nameUUIDFromBytes(byte[])}</li>
     *   <li>{@code isOnline()} returns true</li>
     *   <li>{@code hasPermission(String)} returns true</li>
     *   <li>A default world "world" and location (0, 64, 0)</li>
     * </ul>
     *
     * @param name the player name
     * @return a mock Player
     */
    public static Player createMockPlayer(String name) {
        Player player = mock(Player.class);
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        lenient().when(player.hasPermission(anyString())).thenReturn(true);

        World world = createMockWorld("world");
        Location location = new Location(world, 0, 64, 0);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);
        return player;
    }

    /**
     * Creates a mock Player with a specific UUID.
     *
     * @param name the player name
     * @param uuid the specific UUID to use
     * @return a mock Player
     */
    public static Player createMockPlayer(String name, UUID uuid) {
        Player player = createMockPlayer(name);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }

    /**
     * Creates a mock World with a name.
     *
     * @param name the world name
     * @return a mock World
     */
    public static World createMockWorld(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    /**
     * Creates a mock Server with basic stubs.
     *
     * @return a mock Server
     */
    public static Server createMockServer() {
        Server server = mock(Server.class);
        when(server.getName()).thenReturn("TestServer");
        return server;
    }
}
