package com.ultikits.plugins.worlds;

import com.ultikits.plugins.worlds.config.WorldConfig;
import com.ultikits.plugins.worlds.entity.WorldInventory;
import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
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
 * Test helper for mocking UltiTools framework dependencies.
 * <p>
 * Since UltiWorlds no longer uses a static singleton, this helper creates
 * a mock UltiToolsPlugin that can be injected into services and commands
 * via reflection (simulating @Autowired injection).
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
public final class UltiWorldsTestHelper {

    private UltiWorldsTestHelper() {}

    private static UltiToolsPlugin mockPlugin;
    private static PluginLogger mockLogger;

    /**
     * Set up mock dependencies. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Mock UltiToolsPlugin (not UltiWorlds -- no more singleton)
        mockPlugin = mock(UltiToolsPlugin.class);

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
     * Clean up state.
     */
    public static void tearDown() throws Exception {
        mockPlugin = null;
    }

    public static UltiToolsPlugin getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    /**
     * Create a default WorldConfig mock with all features enabled.
     */
    public static WorldConfig createDefaultConfig() {
        WorldConfig config = mock(WorldConfig.class);
        lenient().when(config.getDefaultWorld()).thenReturn("world");
        lenient().when(config.isAutoUnloadEmptyWorlds()).thenReturn(false);
        lenient().when(config.getEmptyWorldCheckInterval()).thenReturn(60);
        lenient().when(config.getEmptyWorldUnloadAfter()).thenReturn(300);
        lenient().when(config.isTpToWorldEnabled()).thenReturn(true);
        lenient().when(config.isPermissionPerWorld()).thenReturn(false);
        lenient().when(config.getTpCooldown()).thenReturn(10);
        lenient().when(config.isUseSpawnLocation()).thenReturn(true);
        lenient().when(config.isShowDescriptionOnTeleport()).thenReturn(true);
        lenient().when(config.isInventoryIsolation()).thenReturn(false);
        lenient().when(config.isSeparateInventory()).thenReturn(true);
        lenient().when(config.isSeparateEnderChest()).thenReturn(true);
        lenient().when(config.isSeparateExperience()).thenReturn(false);
        lenient().when(config.isSeparateHealth()).thenReturn(false);
        lenient().when(config.isSeparateHunger()).thenReturn(false);
        lenient().when(config.isSeparateEffects()).thenReturn(false);
        return config;
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
        lenient().when(player.hasPermission(anyString())).thenReturn(true);
        lenient().when(player.getHealth()).thenReturn(20.0);
        lenient().when(player.getMaxHealth()).thenReturn(20.0);
        lenient().when(player.getFoodLevel()).thenReturn(20);
        lenient().when(player.getSaturation()).thenReturn(5.0f);

        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Location location = new Location(world, 100.5, 64.0, -200.5);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);

        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(inventory.getContents()).thenReturn(new ItemStack[36]);
        lenient().when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        lenient().when(inventory.getItemInOffHand()).thenReturn(null);
        lenient().when(player.getInventory()).thenReturn(inventory);

        Inventory enderChest = mock(Inventory.class);
        lenient().when(enderChest.getContents()).thenReturn(new ItemStack[27]);
        lenient().when(player.getEnderChest()).thenReturn(enderChest);

        return player;
    }

    /**
     * Create a sample WorldSettings with default values.
     */
    public static WorldSettings createSampleWorldSettings(String worldName) {
        return WorldSettings.builder()
                .worldName(worldName)
                .displayName(worldName)
                .description("Test world")
                .icon("GRASS_BLOCK")
                .pvpEnabled(true)
                .monstersEnabled(true)
                .animalsEnabled(true)
                .weatherEnabled(true)
                .difficulty(null)
                .postTeleportCommands(null)
                .hidden(false)
                .locked(false)
                .blocked(false)
                .autoUnload(true)
                .protectBreak(false)
                .protectPlace(false)
                .protectInteract(false)
                .protectExplosion(false)
                .spawnX(0)
                .spawnY(64)
                .spawnZ(0)
                .spawnYaw(0f)
                .spawnPitch(0f)
                .createdAt(System.currentTimeMillis())
                .build();
    }

    /**
     * Create a sample WorldInventory.
     */
    public static WorldInventory createSampleWorldInventory(UUID playerUuid, String worldGroup) {
        return WorldInventory.create(playerUuid, worldGroup);
    }

    // --- Reflection ---

    public static void setStaticField(Class<?> clazz, String fieldName, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD - test reflection
        field.set(null, value);
    }

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD - test reflection
        field.set(target, value);
    }
}
