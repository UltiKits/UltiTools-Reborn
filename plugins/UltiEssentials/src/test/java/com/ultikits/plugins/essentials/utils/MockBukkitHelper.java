/**
 * Synced from UltiTools-API v6.2.0 - 2026-01-08
 * Source: src/test/java/com/ultikits/ultitools/utils/MockBukkitHelper.java
 *
 * 如需更新，请从 UltiTools-Reborn 主项目同步此文件
 */
package com.ultikits.plugins.essentials.utils;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;

import be.seeseemelk.mockbukkit.MockBukkit;

/**
 * MockBukkit 测试工具类
 * 提供健壮的 MockBukkit 清理功能，解决测试之间的单例冲突问题
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test helper requires reflection for state cleanup
public final class MockBukkitHelper {

    private MockBukkitHelper() {
        // 工具类不允许实例化
    }

    /**
     * 安全地清理 MockBukkit 和 Bukkit 的单例状态
     * 在每个测试的 @BeforeEach 开始时调用
     *
     * NOTE: This method only unmocks if MockBukkit is currently mocked.
     * It does NOT clear Bukkit.server to null, because that would break
     * subsequent MockBukkit.mock() calls that need to initialize Registry
     * and PotionEffectType classes.
     */
    public static void ensureCleanState() {
        // Only unmock if currently mocked
        try {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock();
            }
        } catch (Exception ignored) {
        }

        // Reset the mocked flag so MockBukkit.mock() can be called again
        try {
            Field mockedField = MockBukkit.class.getDeclaredField("mocked");
            mockedField.setAccessible(true);
            mockedField.setBoolean(null, false);
        } catch (Exception ignored) {
        }

        // DO NOT clear Bukkit.server to null here!
        // MockBukkit.unmock() already does that, and setting it to null
        // before MockBukkit.mock() breaks Registry/PotionEffectType initialization.
    }

    /**
     * 安全地卸载 MockBukkit
     * 在每个测试的 @AfterEach 结束时调用
     */
    public static void safeUnmock() {
        try {
            MockBukkit.unmock();
        } catch (Exception ignored) {
        }
    }
}
