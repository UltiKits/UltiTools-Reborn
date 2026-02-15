package com.ultikits.ultitools.testing;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;

/**
 * Utility for cleaning up MockBukkit and Bukkit singleton state between tests.
 * <p>
 * Call {@link #ensureCleanState()} in @BeforeEach to reset singletons.
 * Call {@link #safeUnmock()} in @AfterEach for safe teardown.
 *
 * @since 1.0.0
 */
public final class MockBukkitHelper {

    private MockBukkitHelper() {}

    /**
     * Ensures MockBukkit and Bukkit singletons are in a clean state.
     * Safe to call even if MockBukkit is not on the classpath.
     */
    public static void ensureCleanState() {
        resetBukkitServer();
        resetMockBukkit();
    }

    /**
     * Safely unmocks MockBukkit if it was initialized.
     */
    public static void safeUnmock() {
        try {
            Class<?> mockBukkitClass = Class.forName("be.seeseemelk.mockbukkit.MockBukkit");
            Field mockedField = mockBukkitClass.getDeclaredField("mocked");
            mockedField.setAccessible(true); // NOPMD
            if (mockedField.getBoolean(null)) {
                mockBukkitClass.getMethod("unmock").invoke(null);
            }
        } catch (ClassNotFoundException e) {
            // MockBukkit not on classpath — nothing to unmock
        } catch (Exception e) {
            // Best effort — reset state manually
            resetBukkitServer();
            resetMockBukkit();
        }
    }

    private static void resetBukkitServer() {
        try {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true); // NOPMD
            serverField.set(null, null);
        } catch (Exception ignored) {
            // Bukkit not available
        }
    }

    private static void resetMockBukkit() {
        try {
            Class<?> mockBukkitClass = Class.forName("be.seeseemelk.mockbukkit.MockBukkit");
            Field mockedField = mockBukkitClass.getDeclaredField("mocked");
            mockedField.setAccessible(true); // NOPMD
            mockedField.setBoolean(null, false);
        } catch (ClassNotFoundException e) {
            // MockBukkit not on classpath
        } catch (Exception ignored) {
            // Best effort
        }
    }
}
