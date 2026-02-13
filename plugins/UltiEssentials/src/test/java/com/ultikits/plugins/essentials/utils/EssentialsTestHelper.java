package com.ultikits.plugins.essentials.utils;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiEssentials framework singletons.
 * <p>
 * UltiTools is a final class and cannot be mocked. This helper mocks
 * UltiEssentials (extends abstract UltiToolsPlugin) and sets up
 * Bukkit.getServer() via reflection for static method access.
 * <p>
 * Call {@link #setUp()} in @BeforeEach and {@link #tearDown()} in @AfterEach.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test helper requires reflection
public final class EssentialsTestHelper {

    private EssentialsTestHelper() {}

    private static UltiEssentials mockPlugin;
    private static PluginLogger mockLogger;
    private static Server mockServer;

    /**
     * Set up UltiEssentials mock and Bukkit server.
     * <p>
     * Note: UltiEssentials v6.2.0+ uses constructor injection instead of singletons,
     * so we just create a mock instance without setting a static field.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Mock UltiEssentials (no longer a singleton after v6.2.0 migration)
        mockPlugin = mock(UltiEssentials.class);

        // Mock logger
        mockLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);

        // Mock i18n to return the key as-is
        lenient().when(mockPlugin.i18n(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock getDataOperator
        lenient().when(mockPlugin.getDataOperator(any()))
                .thenReturn(mock(DataOperator.class));

        // Set up Bukkit.server via reflection
        mockServer = mock(Server.class);
        lenient().when(mockServer.getLogger()).thenReturn(Logger.getLogger("MockServer"));

        // Mock common server methods
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        lenient().when(mockServer.getScheduler()).thenReturn(scheduler);

        PluginManager pluginManager = mock(PluginManager.class);
        lenient().when(mockServer.getPluginManager()).thenReturn(pluginManager);
        lenient().when(pluginManager.getPlugin(anyString())).thenReturn(null);

        // Default: no online players
        lenient().when(mockServer.getOnlinePlayers()).thenReturn(new ArrayList<>());

        // Default server info
        lenient().when(mockServer.getMaxPlayers()).thenReturn(100);
        lenient().when(mockServer.getName()).thenReturn("MockServer");

        // Default: no player by UUID
        lenient().when(mockServer.getPlayer(any(UUID.class))).thenReturn(null);

        // Set Bukkit.server
        setStaticField(org.bukkit.Bukkit.class, "server", mockServer);
    }

    /**
     * Clean up test state.
     */
    public static void tearDown() throws Exception {
        // UltiEssentials v6.2.0+ doesn't use a singleton pattern
        // Just null out our mock reference
        mockPlugin = null;
        mockLogger = null;
        // Don't null out Bukkit.server - other tests may need it
    }

    public static UltiEssentials getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    public static Server getMockServer() {
        return mockServer;
    }

    /**
     * Create a default EssentialsConfig with all features enabled.
     */
    public static EssentialsConfig createDefaultConfig() {
        return new EssentialsConfig();
    }

    /**
     * Create a mock Player with basic properties.
     */
    public static Player createMockPlayer(String name, UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.getLevel()).thenReturn(30);
        lenient().when(player.getExp()).thenReturn(0.5f);
        lenient().when(player.hasPermission(anyString())).thenReturn(false);
        lenient().when(player.getHealth()).thenReturn(20.0);
        lenient().when(player.getFoodLevel()).thenReturn(20);
        lenient().when(player.getDisplayName()).thenReturn(name);

        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Location location = new Location(world, 100.5, 64.0, -200.5);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);

        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        lenient().when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        lenient().when(inventory.getItemInOffHand()).thenReturn(null);
        lenient().when(player.getInventory()).thenReturn(inventory);

        Inventory enderChest = mock(Inventory.class);
        lenient().when(enderChest.getContents()).thenReturn(new ItemStack[27]);
        lenient().when(player.getEnderChest()).thenReturn(enderChest);

        // Player.getServer() -- needed by PlayerEvent constructors
        lenient().when(player.getServer()).thenReturn(mockServer);

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

    public static void setStaticField(Class<?> clazz, String fieldName, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD - test reflection
        field.set(null, value);
    }

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true); // NOPMD - test reflection
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found in " + target.getClass().getName());
    }

    public static Object getField(Object target, String fieldName) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true); // NOPMD - test reflection
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found in " + target.getClass().getName());
    }

    public static Object getStaticField(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD - test reflection
        return field.get(null);
    }
}
