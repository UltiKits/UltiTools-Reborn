package com.ultikits.plugins.chat.utils;

import com.ultikits.plugins.chat.UltiChat;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;


/**
 * Test helper for mocking UltiChat framework singletons.
 * <p>
 * Call {@link #setUp()} in @BeforeEach and {@link #tearDown()} in @AfterEach.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
public final class ChatTestHelper {

    private ChatTestHelper() {
    }

    private static UltiChat mockPlugin;
    private static PluginLogger mockLogger;
    private static Server mockServer;

    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        mockPlugin = mock(UltiChat.class);
        mockLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);
        lenient().when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mockPlugin.getDataOperator(any())).thenReturn(mock(DataOperator.class));

        mockServer = mock(Server.class);
        lenient().when(mockServer.getLogger()).thenReturn(Logger.getLogger("MockServer"));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        lenient().when(mockServer.getScheduler()).thenReturn(scheduler);

        PluginManager pluginManager = mock(PluginManager.class);
        lenient().when(mockServer.getPluginManager()).thenReturn(pluginManager);
        lenient().when(pluginManager.getPlugin(anyString())).thenReturn(null);

        lenient().doReturn(new ArrayList<>()).when(mockServer).getOnlinePlayers();
        lenient().when(mockServer.getMaxPlayers()).thenReturn(100);
        lenient().when(mockServer.getName()).thenReturn("MockServer");
        lenient().when(mockServer.getPlayer(any(UUID.class))).thenReturn(null);

        setStaticField(Bukkit.class, "server", mockServer);
    }

    public static void tearDown() throws Exception {
        mockPlugin = null;
        mockLogger = null;
    }

    public static UltiChat getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    public static Server getMockServer() {
        return mockServer;
    }

    /**
     * Create a mock Player with basic properties.
     */
    public static Player createMockPlayer(String name, UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.hasPermission(anyString())).thenReturn(false);
        lenient().when(player.getDisplayName()).thenReturn(name);

        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Location location = new Location(world, 100.5, 64.0, -200.5);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);
        lenient().when(player.getServer()).thenReturn(mockServer);

        return player;
    }

    /**
     * Create a mock Player at a specific location.
     */
    public static Player createMockPlayerAt(String name, UUID uuid, World world, double x, double y, double z) {
        Player player = createMockPlayer(name, uuid);
        Location loc = new Location(world, x, y, z);
        lenient().when(player.getLocation()).thenReturn(loc);
        lenient().when(player.getWorld()).thenReturn(world);
        return player;
    }

    /**
     * Create a mock World.
     */
    public static World createMockWorld(String name) {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn(name);
        return world;
    }

    // --- Reflection helpers ---

    public static void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD
        field.set(null, value);
    }

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true); // NOPMD
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found");
    }

    public static Object getField(Object target, String fieldName) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true); // NOPMD
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found");
    }

    public static Object getStaticField(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD
        return field.get(null);
    }
}
