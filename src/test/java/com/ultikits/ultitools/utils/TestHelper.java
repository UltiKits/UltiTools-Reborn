package com.ultikits.ultitools.utils;

import java.lang.reflect.Field;
import java.util.function.Consumer;

import org.mockito.Mockito;

import com.ultikits.ultitools.UltiTools;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test helper requires reflection for mocking
public class TestHelper {

    /**
     * 创建一个 UltiTools mock 并把它装进静态单例字段。
     *
     * @return 已经发布出去的 mock，方便调用方继续 verify
     */
    public static UltiTools mockUltiToolsInstance() {
        return mockUltiToolsInstance(null);
    }

    /**
     * 同上，但允许调用方在 mock 被发布到静态字段**之前**补充自己的打桩。
     *
     * <p>为什么必须提供这个回调，而不是让调用方拿到返回值之后再打桩：
     * {@code UltiTools.ultiTools} 是全局静态字段，写进去的瞬间整个 JVM 里所有还活着的
     * 线程（泄漏的调度线程、挂在 root logger 上的 handler、Bukkit 调度器……）都能通过
     * {@code UltiTools.getInstance()} 拿到它并开始调用。而 Mockito 的
     * {@code InvocationContainerImpl.invocationForStubbing} 是 per-mock 的共享可变字段，
     * 既不是 volatile 也没有加锁：只要在 {@code when(...)} 和 {@code thenReturn(...)}
     * 之间有别的线程调了这个 mock 一次，answer 就会被绑到那次调用上去。
     *
     * <p>后果就是把 {@code isEnabled()} 的 {@code true} 挂到了 {@code getLogger()} 上，
     * 之后再调 {@code getLogger()} 就抛
     * {@code ClassCastException: Boolean cannot be cast to Logger}，
     * 堆栈还指在 {@code JavaPlugin.getLogger} 上（inline mock maker 织入的 checkcast
     * 沿用了原方法的行号），看着像 Bukkit 出了问题。见 issue #250。
     *
     * <p>所以规矩是：<b>先打完所有的桩，最后一步才发布</b>。不要先发布再补桩。
     *
     * @param extraStubbing 发布前执行的额外打桩，可为 null
     * @return 已经发布出去的 mock
     */
    public static UltiTools mockUltiToolsInstance(Consumer<UltiTools> extraStubbing) {
        try {
            UltiTools mock = Mockito.mock(UltiTools.class);

            // ---- 以下全部发生在 mock 被发布之前，此时没有任何别的线程能拿到它 ----

            // Mock i18n(String)
            Mockito.lenient().when(mock.i18n(Mockito.anyString())).thenAnswer(invocation -> invocation.getArgument(0));

            // Mock getServer()
            Mockito.lenient().when(mock.getServer()).thenAnswer(invocation -> org.bukkit.Bukkit.getServer());

            // Mock isEnabled()
            Mockito.lenient().when(mock.isEnabled()).thenReturn(true);

            // Mock getPluginLoader()
            org.bukkit.plugin.PluginLoader pluginLoader = Mockito.mock(org.bukkit.plugin.PluginLoader.class);
            Mockito.lenient().when(mock.getPluginLoader()).thenReturn(pluginLoader);

            if (extraStubbing != null) {
                extraStubbing.accept(mock);
            }

            // ---- 发布。这一步必须是最后一步 ----
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, mock);

            return mock;
        } catch (Exception e) {
            // If mocking final class fails, we might need another approach,
            // but let's try this first.
            // If it fails, it will throw an exception and fail the test, which is what we want.
            throw new IllegalStateException("Failed to mock UltiTools instance", e);
        }
    }
}
