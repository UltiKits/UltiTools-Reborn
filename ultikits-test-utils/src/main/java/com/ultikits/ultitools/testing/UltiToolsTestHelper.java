package com.ultikits.ultitools.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Logger;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * Reusable test setup for mocking UltiTools framework objects.
 * <p>
 * Usage:
 * <pre>
 * &#64;BeforeEach
 * void setUp() {
 *     UltiToolsTestHelper.mockBukkitServer();
 *     plugin = UltiToolsTestHelper.mockPlugin();
 * }
 *
 * &#64;AfterEach
 * void tearDown() {
 *     UltiToolsTestHelper.cleanup();
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public final class UltiToolsTestHelper {

    private static Server mockServer;

    private UltiToolsTestHelper() {}

    /**
     * Creates a mock UltiToolsPlugin with common stubs.
     * <p>
     * Provides:
     * <ul>
     *   <li>Mocked {@link PluginLogger} via {@code getLogger()}</li>
     *   <li>i18n passthrough (returns the key as-is)</li>
     *   <li>Mocked {@link DataOperator} via {@code getDataOperator(any())}</li>
     * </ul>
     *
     * @return the mock plugin
     */
    @SuppressWarnings("unchecked")
    public static UltiToolsPlugin mockPlugin() {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        PluginLogger logger = mock(PluginLogger.class);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(plugin.getDataOperator(any())).thenReturn(mock(DataOperator.class));
        return plugin;
    }

    /**
     * Installs a mock Bukkit Server singleton via reflection.
     * <p>
     * Sets up:
     * <ul>
     *   <li>Mock {@link Server} with logger, scheduler, plugin manager</li>
     *   <li>{@code getOnlinePlayers()} returns empty list</li>
     *   <li>{@code getMaxPlayers()} returns 100</li>
     *   <li>{@code getPlayer(UUID)} returns null</li>
     * </ul>
     *
     * @return the mock Server
     */
    public static Server mockBukkitServer() {
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
        return mockServer;
    }

    /**
     * Gets the mock Server created by {@link #mockBukkitServer()}.
     *
     * @return the mock Server, or null if not yet created
     */
    public static Server getMockServer() {
        return mockServer;
    }

    /**
     * Resets Bukkit.server to null and clears internal state.
     */
    public static void cleanup() {
        setStaticField(Bukkit.class, "server", null);
        mockServer = null;
    }

    /**
     * Sets a static field value via reflection.
     *
     * @param clazz     the class containing the static field
     * @param fieldName the field name
     * @param value     the value to set
     */
    public static void setStaticField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true); // NOPMD
            field.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set static field: " + clazz.getName() + "." + fieldName, e);
        }
    }

    /**
     * Gets a static field value via reflection.
     *
     * @param clazz     the class containing the static field
     * @param fieldName the field name
     * @param <T>       the expected type
     * @return the field value
     */
    @SuppressWarnings("unchecked")
    public static <T> T getStaticField(Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true); // NOPMD
            return (T) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get static field: " + clazz.getName() + "." + fieldName, e);
        }
    }

    /**
     * Sets a private instance field value via reflection.
     * Searches up the class hierarchy to find the field.
     *
     * @param target    the object to modify
     * @param fieldName the field name
     * @param value     the value to set
     */
    public static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true); // NOPMD
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    /**
     * Gets a private instance field value via reflection.
     * Searches up the class hierarchy to find the field.
     *
     * @param target    the object to read from
     * @param fieldName the field name
     * @param <T>       the expected type
     * @return the field value
     */
    @SuppressWarnings("unchecked")
    public static <T> T getField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true); // NOPMD
            return (T) field.get(target);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get field: " + fieldName, e);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found in " + clazz.getName());
    }
}
