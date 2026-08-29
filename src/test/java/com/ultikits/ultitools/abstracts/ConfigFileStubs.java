package com.ultikits.ultitools.abstracts;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;

/**
 * Test-only bridge for stubbing {@link UltiToolsPlugin#getConfigFolder()} and
 * {@link UltiToolsPlugin#getConfigFile(String)}. Both are package-protected, so a test class
 * outside {@code com.ultikits.ultitools.abstracts} cannot write {@code when(mock.getConfigFile(...))}
 * directly - the Java compiler rejects the call before Mockito's mock maker ever gets a chance to
 * intercept it. This class exists only to sit in the same package as {@link UltiToolsPlugin} so
 * cross-package tests (e.g. {@code ConfigManagerTest}) can still exercise a real
 * {@link AbstractConfigEntity#init(UltiToolsPlugin)} against a temp directory.
 * <p>
 * 测试专用桥接类，用于打桩 {@link UltiToolsPlugin#getConfigFolder()} 和
 * {@link UltiToolsPlugin#getConfigFile(String)}。二者都是包内可见（protected），
 * 不在 {@code com.ultikits.ultitools.abstracts} 包内的测试类无法直接写
 * {@code when(mock.getConfigFile(...))}——Mockito 的 mock maker 还没机会拦截，Java 编译器
 * 就已经拒绝了这次调用。本类的唯一作用就是与 {@link UltiToolsPlugin} 同包，从而让跨包测试
 * （例如 {@code ConfigManagerTest}）仍能针对临时目录跑一次真实的
 * {@link AbstractConfigEntity#init(UltiToolsPlugin)}。
 */
public final class ConfigFileStubs {

    private ConfigFileStubs() {
    }

    /**
     * Stubs {@code getConfigFolder()}/{@code getConfigFile(String)} on {@code mockPlugin} so that
     * every config path resolves under {@code baseDir}.
     *
     * @param mockPlugin a Mockito mock of {@link UltiToolsPlugin}
     * @param baseDir    the directory config files resolve under
     */
    public static void stubConfigFolder(UltiToolsPlugin mockPlugin, File baseDir) {
        lenient().when(mockPlugin.getConfigFolder()).thenReturn(baseDir.getAbsolutePath());
        lenient().when(mockPlugin.getConfigFile(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return new File(baseDir, path);
        });
    }
}
