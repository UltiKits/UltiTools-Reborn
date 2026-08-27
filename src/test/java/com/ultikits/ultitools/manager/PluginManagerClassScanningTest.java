package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.UltiToolsModule;
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
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();

        bukkitLogs.clear();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                bukkitLogs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Bukkit.getLogger().addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(captureHandler);
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

    @Test
    @DisplayName("扫描应只收集本 JAR 中携带 @Table 的类，忽略非 @Table 类")
    void shouldScanTableAnnotatedClassesAndOnlyThoseInThisJar() throws Exception {
        File pluginJar = createJar("entity-plugin.jar", EntityA.class, EntityB.class, PlainPlugin.class);

        Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

        assertThat(scanned).containsExactlyInAnyOrder(EntityA.class, EntityB.class);

        DataScope scope = DataScope.forExternal("entity-plugin", tempDir, scanned);
        assertThat(scope.owns(EntityA.class)).isTrue();
        assertThat(scope.owns(EntityB.class)).isTrue();
        assertThat(scope.owns(UnrelatedEntity.class)).isFalse();
    }

    @Test
    @DisplayName("零 @Table 类的 JAR 扫描出空集合，不影响插件加载")
    void shouldReturnEmptySetForJarWithNoTableClasses() throws Exception {
        File pluginJar = createJar("no-entity-plugin.jar", ConcretePlugin.class);

        Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

        assertThat(scanned).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("超过 1000 类上限只报告、不截断——扫描完整跑完并记录一条 WARNING")
    void shouldReportWithoutTruncatingWhenClassCountExceedsCap() throws Exception {
        File pluginJar = new File(tempDir, "oversized-plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(pluginJar.toPath()))) {
            for (int i = 0; i < 1001; i++) {
                output.putNextEntry(new JarEntry(
                        "com/ultikits/ultitools/manager/scanfixture/SyntheticClass" + i + ".class"));
                output.closeEntry();
            }
        }

        Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

        assertThat(scanned).isNotNull();
        boolean warned = bukkitLogs.stream().anyMatch(record ->
                Level.WARNING.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(pluginJar.getName())
                        && record.getMessage().contains("1000"));
        assertThat(warned).as("expected a WARNING naming the jar and the 1000-class cap, got: %s", bukkitLogs)
                .isTrue();
    }

    @Test
    @DisplayName("@UltiToolsModule#additionalEntities 声明的实体并入插件的 scope")
    void additionalEntitiesAttributeShouldBeAddedToPluginScope() throws Exception {
        // scanPluginEntities takes the plugin's Class, not an instance -- everything it reads (the
        // jar's own CodeSource and the class-level @UltiToolsModule annotation) is a class-level
        // fact, so no UltiToolsPlugin construction (real plugin.yml/language/config I/O) is needed
        // here at all.
        Set<Class<?>> scanned = invokeScanPluginEntities(ModuleWithAdditionalEntities.class);

        assertThat(scanned).contains(UnrelatedEntity.class);
    }

    @Test
    @DisplayName("没有声明任何实体的插件得到空集合而非 null，owns() 不抛异常")
    void pluginWithNoEntitiesGetsEmptySetNotNull() throws Exception {
        Set<Class<?>> scanned = invokeScanPluginEntities(ConcretePlugin.class);

        assertThat(scanned).isNotNull().isEmpty();
        DataScope scope = DataScope.forExternal("no-entities-plugin", tempDir, scanned);
        assertThat(scope.owns(EntityA.class)).isFalse();
    }

    @Test
    @DisplayName("connect(plugin, additionalEntities) 声明的实体并入外部插件的 scope")
    void externalConnectAdditionalEntitiesShouldBeAddedToScope() throws Exception {
        org.bukkit.plugin.java.JavaPlugin mockPlugin = MockBukkit.createMockPlugin("ExternalScanFixture");
        com.ultikits.ultitools.api.ExternalPluginAdapter adapter =
                new com.ultikits.ultitools.api.ExternalPluginAdapter(mockPlugin);

        Set<Class<?>> scanned = invokeScanExternalEntities(adapter, new Class<?>[]{EntityA.class});

        assertThat(scanned).contains(EntityA.class);
    }

    @SuppressWarnings("unchecked")
    private Set<Class<?>> invokeScanEntitiesInJar(File pluginJar) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("scanEntitiesInJar", File.class);
        method.setAccessible(true);
        return (Set<Class<?>>) method.invoke(pluginManager, pluginJar);
    }

    @SuppressWarnings("unchecked")
    private Set<Class<?>> invokeScanPluginEntities(Class<? extends UltiToolsPlugin> pluginClass) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("scanPluginEntities", Class.class);
        method.setAccessible(true);
        return (Set<Class<?>>) method.invoke(pluginManager, pluginClass);
    }

    @SuppressWarnings("unchecked")
    private Set<Class<?>> invokeScanExternalEntities(
            com.ultikits.ultitools.api.ExternalPluginAdapter adapter, Class<?>[] additionalEntities
    ) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod(
                "scanExternalEntities", com.ultikits.ultitools.api.ExternalPluginAdapter.class, Class[].class
        );
        method.setAccessible(true);
        return (Set<Class<?>>) method.invoke(pluginManager, adapter, additionalEntities);
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

    @Table("scan_entity_a")
    static class EntityA {
    }

    @Table("scan_entity_b")
    static class EntityB {
    }

    @Table("scan_entity_other")
    static class UnrelatedEntity {
    }

    @UltiToolsModule(additionalEntities = {UnrelatedEntity.class})
    static class ModuleWithAdditionalEntities extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }
}
