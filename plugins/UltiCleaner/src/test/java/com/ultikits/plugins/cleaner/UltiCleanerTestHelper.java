package com.ultikits.plugins.cleaner;

import com.ultikits.plugins.cleaner.config.CleanerConfig;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiTools framework singletons.
 * <p>
 * UltiCleaner is a {@code extends UltiToolsPlugin} — we can mock it.
 * This helper mocks the UltiCleaner singleton and avoids any code paths
 * that call {@code UltiTools.getInstance()}.
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
public final class UltiCleanerTestHelper {

    private UltiCleanerTestHelper() {}

    private static UltiCleaner mockPlugin;
    private static PluginLogger mockLogger;
    private static Server mockServer;
    private static BukkitScheduler mockScheduler;
    private static PluginManager mockPluginManager;
    private static List<World> mockWorlds;

    /**
     * Set up UltiCleaner mock. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Mock UltiCleaner (no singleton — plugin instance is injected via @Autowired)
        mockPlugin = mock(UltiCleaner.class);

        // Mock logger
        mockLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);

        // Mock i18n to return the key as-is
        lenient().when(mockPlugin.i18n(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock getDataOperator
        lenient().when(mockPlugin.getDataOperator(any()))
                .thenReturn(mock(DataOperator.class));

        // Mock Bukkit static methods
        mockServer = mock(Server.class);
        setStaticField(Bukkit.class, "server", mockServer);

        // Mock scheduler
        mockScheduler = mock(BukkitScheduler.class);
        lenient().when(mockServer.getScheduler()).thenReturn(mockScheduler);

        // Mock scheduler methods to return dummy tasks
        BukkitTask mockTask = mock(BukkitTask.class);
        lenient().when(mockScheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mockTask);
        lenient().when(mockScheduler.runTask(any(), any(Runnable.class)))
                .thenReturn(mockTask);
        lenient().when(mockScheduler.runTaskAsynchronously(any(), any(Runnable.class)))
                .thenReturn(mockTask);

        // Mock plugin manager
        mockPluginManager = mock(PluginManager.class);
        lenient().when(mockServer.getPluginManager()).thenReturn(mockPluginManager);

        // Mock worlds - empty list by default
        mockWorlds = new ArrayList<>();
        lenient().when(mockServer.getWorlds()).thenReturn(mockWorlds);

        // Mock online players - empty list by default
        lenient().when(mockServer.getOnlinePlayers()).thenReturn(Collections.emptyList());

        // Mock ServerTypeUtil to avoid reflection on getClass()
        // Set hasTpsMethod to false so it uses fallback TPS calculation
        try {
            setStaticField(Class.forName("com.ultikits.plugins.cleaner.utils.ServerTypeUtil"), "hasTpsMethod", false);
        } catch (ClassNotFoundException e) {
            // Ignore if class not found
        }
    }

    /**
     * Clean up state.
     */
    public static void tearDown() throws Exception {
        mockPlugin = null;
        setStaticField(Bukkit.class, "server", null);
        if (mockWorlds != null) {
            mockWorlds.clear();
        }
        // Reset ServerTypeUtil cached fields
        try {
            Class<?> serverTypeUtil = Class.forName("com.ultikits.plugins.cleaner.utils.ServerTypeUtil");
            setStaticField(serverTypeUtil, "hasTpsMethod", null);
            setStaticField(serverTypeUtil, "getTpsMethod", null);
            setStaticField(serverTypeUtil, "isPaper", null);
            setStaticField(serverTypeUtil, "isModernPaper", null);
            setStaticField(serverTypeUtil, "getChunkAtAsyncMethod", null);
            setStaticField(serverTypeUtil, "isEntitiesLoadedMethod", null);
        } catch (Exception e) {
            // Ignore if class or field not found
        }
    }

    public static UltiCleaner getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    public static Server getMockServer() {
        return mockServer;
    }

    public static BukkitScheduler getMockScheduler() {
        return mockScheduler;
    }

    public static List<World> getMockWorlds() {
        return mockWorlds;
    }

    /**
     * Add a mock world to the server's world list.
     */
    public static void addMockWorld(World world) {
        if (mockWorlds != null) {
            mockWorlds.add(world);
        }
    }

    /**
     * Create a default CleanerConfig mock with typical settings.
     */
    public static CleanerConfig createDefaultConfig() {
        CleanerConfig config = mock(CleanerConfig.class);
        lenient().when(config.isItemCleanEnabled()).thenReturn(true);
        lenient().when(config.getItemCleanInterval()).thenReturn(300);
        lenient().when(config.isEntityCleanEnabled()).thenReturn(true);
        lenient().when(config.getEntityCleanInterval()).thenReturn(600);
        lenient().when(config.isSmartCleanEnabled()).thenReturn(false);
        lenient().when(config.getCleanBatchSize()).thenReturn(50);
        lenient().when(config.isShowCleanProgress()).thenReturn(false);
        lenient().when(config.isTpsAdaptiveEnabled()).thenReturn(true);
        lenient().when(config.isChunkUnloadEnabled()).thenReturn(false);
        lenient().when(config.getMaxChunkDistance()).thenReturn(20);
        lenient().when(config.getChunkUnloadBatchSize()).thenReturn(5);

        // Mock message methods
        lenient().when(config.getItemCleanedMessage()).thenReturn("&a[Clean] Cleaned {COUNT} items");
        lenient().when(config.getEntityCleanedMessage()).thenReturn("&a[Clean] Cleaned {COUNT} entities");
        lenient().when(config.getCleanCancelledMessage()).thenReturn("&c[Clean] Clean cancelled");
        lenient().when(config.getWarnMessage()).thenReturn("&e[Clean] Items will be cleaned in {TIME} seconds");
        lenient().when(config.getEntityWarnMessage()).thenReturn("&e[Clean] Entities will be cleaned in {TIME} seconds");
        lenient().when(config.getSmartCleanTriggeredMessage()).thenReturn("&e[Clean] Smart clean triggered");
        lenient().when(config.getCleanProgressMessage()).thenReturn("&e[Clean] Progress: {CURRENT}/{TOTAL}");

        return config;
    }

    /**
     * Create a mock Player with basic properties.
     */
    public static Player createMockPlayer(String name, UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.hasPermission(anyString())).thenReturn(true);

        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Location location = new Location(world, 100.5, 64.0, -200.5);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);

        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        lenient().when(player.getInventory()).thenReturn(inventory);

        return player;
    }

    /**
     * Create a mock World.
     */
    public static World createMockWorld(String name) {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn(name);
        lenient().when(world.getLoadedChunks()).thenReturn(new Chunk[0]);
        return world;
    }

    /**
     * Create a mock Chunk.
     */
    public static Chunk createMockChunk(World world, int x, int z) {
        Chunk chunk = mock(Chunk.class);
        lenient().when(chunk.getWorld()).thenReturn(world);
        lenient().when(chunk.getX()).thenReturn(x);
        lenient().when(chunk.getZ()).thenReturn(z);
        lenient().when(chunk.isLoaded()).thenReturn(true);
        lenient().when(chunk.isForceLoaded()).thenReturn(false);
        lenient().when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[0]);
        return chunk;
    }

    // --- Reflection ---

    public static void setStaticField(Class<?> clazz, String fieldName, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
