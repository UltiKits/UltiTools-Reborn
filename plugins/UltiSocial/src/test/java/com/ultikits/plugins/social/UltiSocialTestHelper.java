package com.ultikits.plugins.social;

import com.ultikits.plugins.social.config.SocialConfig;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiTools framework dependencies.
 * <p>
 * Since UltiSocial no longer uses a static singleton, this helper creates
 * a mock UltiToolsPlugin that can be injected into services and commands
 * via reflection (simulating @Autowired injection).
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
public final class UltiSocialTestHelper {

    private UltiSocialTestHelper() {}

    private static UltiToolsPlugin mockPlugin;
    private static PluginLogger mockLogger;
    private static Server mockServer;

    /**
     * Set up mock dependencies. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Mock Bukkit.server static field
        mockServer = mock(Server.class);
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, mockServer);
        lenient().when(mockServer.getOnlinePlayers()).thenReturn(Collections.emptyList());

        // Mock UltiToolsPlugin (not UltiSocial -- no more singleton)
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

        // Reset Bukkit.server to null
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, null);
    }

    public static UltiToolsPlugin getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    public static Server getMockServer() {
        return mockServer;
    }

    /**
     * Create a default SocialConfig mock with all features enabled.
     */
    public static SocialConfig createDefaultConfig() {
        SocialConfig config = mock(SocialConfig.class);
        lenient().when(config.getMaxFriends()).thenReturn(50);
        lenient().when(config.getRequestTimeout()).thenReturn(60);
        lenient().when(config.isNotifyFriendOnline()).thenReturn(true);
        lenient().when(config.isNotifyFriendOffline()).thenReturn(true);
        lenient().when(config.isNotifyFriendJoinWorld()).thenReturn(false);
        lenient().when(config.isTpToFriendEnabled()).thenReturn(true);
        lenient().when(config.getTpCooldown()).thenReturn(30);
        lenient().when(config.getGuiTitle()).thenReturn("&6Friend List");
        lenient().when(config.getFriendAddedMessage()).thenReturn("&aYou are now friends with {PLAYER}!");
        lenient().when(config.getFriendRemovedMessage()).thenReturn("&cRemoved friend {PLAYER}");
        lenient().when(config.getFriendOnlineMessage()).thenReturn("&aYour friend {PLAYER} is now online!");
        lenient().when(config.getFriendOfflineMessage()).thenReturn("&7Your friend {PLAYER} went offline");
        lenient().when(config.getRequestSentMessage()).thenReturn("&aFriend request sent to {PLAYER}!");
        lenient().when(config.getRequestReceivedMessage()).thenReturn("&e{PLAYER} wants to be your friend!");
        lenient().when(config.getRequestDeniedMessage()).thenReturn("&cDenied friend request from {PLAYER}");
        lenient().when(config.getMaxFriendsMessage()).thenReturn("&cYou have reached the maximum number of friends!");
        lenient().when(config.getAlreadyFriendsMessage()).thenReturn("&cYou are already friends with {PLAYER}!");
        lenient().when(config.getBlockedMessage()).thenReturn("&cCannot interact with {PLAYER} due to blacklist");
        lenient().when(config.getPlayerBlockedMessage()).thenReturn("&cBlocked {PLAYER}");
        lenient().when(config.getPlayerUnblockedMessage()).thenReturn("&aUnblocked {PLAYER}");
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
