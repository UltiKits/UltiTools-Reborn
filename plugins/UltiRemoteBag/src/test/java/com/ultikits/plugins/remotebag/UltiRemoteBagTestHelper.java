package com.ultikits.plugins.remotebag;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiTools framework singletons.
 * <p>
 * UltiTools is a {@code final class extends JavaPlugin} — it cannot be mocked.
 * This helper mocks only UltiRemoteBag (extends abstract UltiToolsPlugin) and
 * avoids any code paths that call {@code UltiTools.getInstance()}.
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
public final class UltiRemoteBagTestHelper {

    private UltiRemoteBagTestHelper() {}

    private static UltiRemoteBag mockPlugin;
    private static PluginLogger mockLogger;

    /**
     * Set up UltiRemoteBag mock. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Mock UltiRemoteBag (abstract UltiToolsPlugin — mockable)
        mockPlugin = mock(UltiRemoteBag.class);

        // Mock logger
        mockLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);

        // Mock i18n to return the key as-is
        lenient().when(mockPlugin.i18n(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock getDataOperator
        lenient().when(mockPlugin.getDataOperator(any()))
                .thenReturn(mock(DataOperator.class));
    }

    /**
     * Clean up resources.
     */
    public static void tearDown() throws Exception {
        // No cleanup needed since we removed the singleton pattern
    }

    public static UltiRemoteBag getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    /**
     * Create a default RemoteBagConfig mock with all features enabled.
     */
    public static RemoteBagConfig createDefaultConfig() {
        RemoteBagConfig config = mock(RemoteBagConfig.class);
        lenient().when(config.getDefaultPages()).thenReturn(1);
        lenient().when(config.getMaxPages()).thenReturn(10);
        lenient().when(config.getRowsPerPage()).thenReturn(6);
        lenient().when(config.getGuiTitle()).thenReturn("&6远程背包 &7第 {PAGE}/{MAX} 页");
        lenient().when(config.isPermissionBasedPages()).thenReturn(true);
        lenient().when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
        lenient().when(config.getAutoSaveInterval()).thenReturn(300);
        lenient().when(config.isSaveOnClose()).thenReturn(true);
        lenient().when(config.isEconomyEnabled()).thenReturn(true);
        lenient().when(config.getBasePrice()).thenReturn(10000);
        lenient().when(config.isPriceIncreaseEnabled()).thenReturn(true);
        lenient().when(config.getPriceIncreaseRate()).thenReturn(0.1);
        lenient().when(config.isSoundEnabled()).thenReturn(true);
        lenient().when(config.getOpenSound()).thenReturn("BLOCK_CHEST_OPEN");
        lenient().when(config.getCloseSound()).thenReturn("BLOCK_CHEST_CLOSE");
        lenient().when(config.getPurchaseSound()).thenReturn("ENTITY_PLAYER_LEVELUP");
        lenient().when(config.getErrorSound()).thenReturn("ENTITY_VILLAGER_NO");
        lenient().when(config.getSoundVolume()).thenReturn(1.0);
        lenient().when(config.getSoundPitch()).thenReturn(1.0);
        lenient().when(config.getLockTimeout()).thenReturn(300);
        lenient().when(config.isNotifyReadonlyViewers()).thenReturn(true);
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
