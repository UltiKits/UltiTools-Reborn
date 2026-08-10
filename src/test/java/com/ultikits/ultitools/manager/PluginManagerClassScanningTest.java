package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.IPlugin;

/**
 * PluginManager module main-class scanning tests.
 */
@DisplayName("PluginManager 模块主类扫描测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerClassScanningTest {

    @TempDir
    File tempDir;

    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("只选择具体的 UltiToolsPlugin 子类并隔离坏类条目")
    void shouldSelectOnlyConcreteUltiToolsPluginSubclass() throws Exception {
        File pluginJar = createJar(
                "mixed-plugin.jar",
                IPlugin.class,
                PluginContract.class,
                AbstractPlugin.class,
                PlainPlugin.class,
                "com.ultikits.ultitools.manager.MissingPlugin",
                ConcretePlugin.class
        );

        assertThat(invokeLoadPluginMainClass(pluginJar)).isEqualTo(ConcretePlugin.class);
    }

    @Test
    @DisplayName("坏 JAR 不应该阻止后续有效 JAR 扫描")
    void badJarShouldNotPreventLaterValidJarScan() throws Exception {
        File badJar = new File(tempDir, "bad-plugin.jar");
        Files.write(badJar.toPath(), new byte[]{0x00, 0x01, 0x02});
        File validJar = createJar("valid-plugin.jar", ConcretePlugin.class);

        assertThat(invokeLoadPluginMainClass(badJar)).isNull();
        assertThat(invokeLoadPluginMainClass(validJar)).isEqualTo(ConcretePlugin.class);
    }

    private Class<? extends UltiToolsPlugin> invokeLoadPluginMainClass(File pluginJar) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod(
                "loadPluginMainClass", ClassLoader.class, File.class
        );
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Class<? extends UltiToolsPlugin> result = (Class<? extends UltiToolsPlugin>) method.invoke(
                pluginManager,
                Thread.currentThread().getContextClassLoader(),
                pluginJar
        );
        return result;
    }

    private File createJar(String name, Object... entries) throws IOException {
        File jar = new File(tempDir, name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Object entry : entries) {
                String className = entry instanceof Class ? ((Class<?>) entry).getName() : (String) entry;
                String resourceName = className.replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(resourceName));
                if (entry instanceof Class) {
                    try (InputStream input = ((Class<?>) entry).getResourceAsStream("/" + resourceName)) {
                        assertThat(input).as("compiled class resource for %s", className).isNotNull();
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                output.closeEntry();
            }
        }
        return jar;
    }

    interface PluginContract extends IPlugin {
    }

    abstract static class AbstractPlugin extends UltiToolsPlugin {
    }

    static class PlainPlugin implements IPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }

        @Override
        public void unregisterSelf() {
            // No-op for this scanner fixture.
        }

        @Override
        public void reloadSelf() {
            // No-op for this scanner fixture.
        }
    }

    static class ConcretePlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }
}
