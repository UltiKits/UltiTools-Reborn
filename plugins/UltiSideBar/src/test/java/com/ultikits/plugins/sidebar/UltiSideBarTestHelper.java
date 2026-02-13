package com.ultikits.plugins.sidebar;

import com.ultikits.plugins.sidebar.data.SideBarPreference;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiTools framework dependencies.
 * <p>
 * Since the singleton pattern has been removed, this helper creates mock
 * UltiToolsPlugin instances for injection into services and commands.
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
public final class UltiSideBarTestHelper {

    private UltiSideBarTestHelper() {}

    private static UltiSideBar mockPlugin;
    private static PluginLogger mockLogger;

    /**
     * Set up UltiSideBar mock. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Mock UltiSideBar (abstract UltiToolsPlugin — mockable)
        mockPlugin = mock(UltiSideBar.class);

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
        mockLogger = null;
    }

    public static UltiSideBar getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
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

        Scoreboard scoreboard = mock(Scoreboard.class);
        lenient().when(player.getScoreboard()).thenReturn(scoreboard);

        return player;
    }

    /**
     * Create a sample SideBarPreference.
     */
    public static SideBarPreference createSamplePreference(UUID playerUuid, boolean enabled) {
        SideBarPreference pref = new SideBarPreference(playerUuid.toString(), enabled);
        pref.setId("test-id-" + playerUuid);
        return pref;
    }

    // --- Reflection ---

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = null;
        while (clazz != null) {
            try {
                field = clazz.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true); // NOPMD - intentional reflection for test mock injection
        field.set(target, value);
    }
}
