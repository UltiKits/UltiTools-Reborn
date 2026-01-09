/**
 * Synced from UltiTools-API v6.2.0
 * Source: UltiEssentials test utilities
 */
package com.ultikits.plugins.mail.utils;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;

import be.seeseemelk.mockbukkit.MockBukkit;

/**
 * MockBukkit 测试工具类
 * 提供健壮的 MockBukkit 清理功能，解决测试之间的单例冲突问题
 */
public final class MockBukkitHelper {

    private MockBukkitHelper() {
        // 工具类不允许实例化
    }

    /**
     * 安全地清理 MockBukkit 和 Bukkit 的单例状态
     * 在每个测试的 @BeforeEach 开始时调用
     */
    public static void ensureCleanState() {
        // 1. 尝试标准的 MockBukkit.unmock()
        try {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock();
            }
        } catch (Exception ignored) {
        }

        // 2. 强制清理 MockBukkit 的内部状态
        try {
            Field mockedField = MockBukkit.class.getDeclaredField("mocked");
            mockedField.setAccessible(true);
            mockedField.setBoolean(null, false);
        } catch (Exception ignored) {
        }

        // 3. 强制清理 Bukkit 的 server 单例
        if (Bukkit.getServer() != null) {
            try {
                Field serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                serverField.set(null, null);
            } catch (Exception ignored) {
            }
        }
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
        
        // 确保完全清理
        ensureCleanState();
    }
}
