package com.ultikits.ultitools.utils;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * MockBukkit 测试工具类
 * 提供健壮的 MockBukkit 清理功能，解决测试之间的单例冲突问题
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test helper requires reflection for singleton cleanup
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

        // 2. 兜底清理 MockBukkit 的内部单例引用
        //    MockBukkit 4.x 只声明了一个字段：private static ServerMock mock。
        //    正常路径上 unmock() 会经 setServerInstanceToNull() 把它置空，所以这里
        //    只是失败路径的兜底：unmock() 的 try/catch 仅覆盖 scheduler 关闭那一段，
        //    disablePlugins() 或 unload()/reset() 抛异常时会跳过置空，使 mock 残留为非 null，
        //    导致下一次 MockBukkit.mock() 抛出 IllegalStateException("Already mocking")。
        try {
            Field mockField = MockBukkit.class.getDeclaredField("mock");
            mockField.setAccessible(true);
            mockField.set(null, null);
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
