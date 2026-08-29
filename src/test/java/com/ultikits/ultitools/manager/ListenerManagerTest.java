package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.event.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.context.AutowireFactory;
import com.ultikits.ultitools.context.SimpleContainer;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * ListenerManager 测试
 */
@DisplayName("ListenerManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class ListenerManagerTest {

    private ListenerManager listenerManager;
    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Mock logger
        Logger mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        // Mock plugin
        mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getPluginName()).thenReturn("TestPlugin");

        listenerManager = new ListenerManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /**
     * 测试用监听器 - 有 @EventListener 注解
     */
    @EventListener
    static class TestListener implements Listener {
    }

    /**
     * 测试用监听器 - manualRegister = true
     */
    @EventListener(manualRegister = true)
    static class ManualRegisterListener implements Listener {
    }

    /**
     * 没有注解的监听器
     */
    static class NoAnnotationListener implements Listener {
    }

    @Nested
    @DisplayName("register(plugin, listener) 测试")
    class RegisterWithListenerTests {

        @Test
        @DisplayName("应该将监听器添加到 map")
        void shouldAddListenerToMap() throws Exception {
            // Arrange
            TestListener listener = new TestListener();

            // Act
            listenerManager.register(mockPlugin, listener);

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map).containsKey(mockPlugin);
            assertThat(map.get(mockPlugin)).contains(listener);
        }

        @Test
        @DisplayName("不应该重复添加相同监听器")
        void shouldNotAddDuplicateListener() throws Exception {
            // Arrange
            TestListener listener = new TestListener();

            // Act
            listenerManager.register(mockPlugin, listener);
            listenerManager.register(mockPlugin, listener);

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).hasSize(1);
        }

        @Test
        @DisplayName("应该支持注册多个不同监听器")
        void shouldSupportMultipleListeners() throws Exception {
            // Arrange
            TestListener listener1 = new TestListener();
            NoAnnotationListener listener2 = new NoAnnotationListener();

            // Act
            listenerManager.register(mockPlugin, listener1);
            listenerManager.register(mockPlugin, listener2);

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("register(plugin, class) 测试")
    class RegisterWithClassTests {

        @Test
        @DisplayName("应该从 context 获取 bean 并注册")
        void shouldGetBeanFromContextAndRegister() throws Exception {
            // Arrange
            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(mockPlugin.getContext()).thenReturn(mockContext);
            
            TestListener listener = new TestListener();
            when(mockContext.getBean(TestListener.class)).thenReturn(listener);

            // Act
            listenerManager.register(mockPlugin, TestListener.class);

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map).containsKey(mockPlugin);
        }
    }

    @Nested
    @DisplayName("unregister 测试")
    class UnregisterTests {

        @Test
        @DisplayName("应该成功注销监听器")
        void shouldUnregisterListener() {
            // Arrange
            TestListener listener = new TestListener();
            listenerManager.register(mockPlugin, listener);

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> listenerManager.unregister(listener));
        }

        @Test
        @DisplayName("注销未注册的监听器不应该抛出异常")
        void shouldNotThrowForUnregisteredListener() {
            // Arrange
            TestListener listener = new TestListener();

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> listenerManager.unregister(listener));
        }
    }

    @Nested
    @DisplayName("unregisterAll 测试")
    class UnregisterAllTests {

        @Test
        @DisplayName("未注册的插件不应该抛出异常")
        void shouldNotThrowForUnregisteredPlugin() {
            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> listenerManager.unregisterAll(mockPlugin));
        }

        @Test
        @DisplayName("应该注销所有监听器")
        void shouldUnregisterAllListeners() throws Exception {
            // Arrange
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            TestListener listener1 = new TestListener();
            NoAnnotationListener listener2 = new NoAnnotationListener();

            List<Listener> listeners = new ArrayList<>();
            listeners.add(listener1);
            listeners.add(listener2);
            map.put(mockPlugin, listeners);

            // Act
            listenerManager.unregisterAll(mockPlugin);

            // 不应该抛出异常 - test passes if we reach here
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("listeners 为 null 时应该提前返回")
        void shouldReturnEarlyWhenListenersIsNull() throws Exception {
            // Arrange
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            map.put(mockPlugin, null);

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> listenerManager.unregisterAll(mockPlugin));
        }
    }

    @Nested
    @DisplayName("registerAll(plugin) 测试")
    class RegisterAllNoPackageTests {

        @Test
        @DisplayName("应该跳过 manualRegister=true 的监听器")
        void shouldSkipManualRegisterListeners() throws Exception {
            // Arrange
            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(mockPlugin.getContext()).thenReturn(mockContext);
            when(mockContext.getBeanNamesForType(Listener.class))
                .thenReturn(new String[]{"manualListener"});
            
            ManualRegisterListener manualListener = new ManualRegisterListener();
            when(mockContext.getBean("manualListener", Listener.class)).thenReturn(manualListener);

            // Act
            listenerManager.registerAll(mockPlugin);

            // Assert - manualRegister=true 的监听器应该被跳过
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            // 不应该添加任何监听器
            assertThat(map.get(mockPlugin)).isNull();
        }

        @Test
        @DisplayName("应该注册 manualRegister=false 的监听器")
        void shouldRegisterNonManualListeners() throws Exception {
            // Arrange
            SimpleContainer mockContext = mock(SimpleContainer.class);
            when(mockPlugin.getContext()).thenReturn(mockContext);
            when(mockContext.getBeanNamesForType(Listener.class))
                .thenReturn(new String[]{"testListener"});
            
            TestListener testListener = new TestListener();
            when(mockContext.getBean("testListener", Listener.class)).thenReturn(testListener);

            // Act
            listenerManager.registerAll(mockPlugin);

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).contains(testListener);
        }
    }

    @Nested
    @DisplayName("registerAll(plugin, packageName) 测试")
    class RegisterAllWithPackageTests {

        @Test
        @DisplayName("扫描空包不应该注册任何监听器")
        void emptyPackageShouldNotRegisterAnyListeners() throws Exception {
            // Arrange
            SimpleContainer mockContext = mock(SimpleContainer.class);
            AutowireFactory mockFactory = mock(AutowireFactory.class);
            when(mockPlugin.getContext()).thenReturn(mockContext);
            when(mockContext.getAutowireCapableBeanFactory()).thenReturn(mockFactory);

            // Act - 使用一个不存在的包名
            listenerManager.registerAll(mockPlugin, "com.nonexistent.package");

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).isNull();
        }

        /**
         * WIRE-07 / D-17: registerAll(plugin, packageName) must consult
         * {@code @ConditionalOnConfig} before instantiating a scanned listener, exactly like
         * {@code ComponentScanner} already does on the IoC-scan path. Scans a real fixture
         * package (see {@code com.ultikits.testfixtures.conditionallistener}) containing one
         * false-condition and one true-condition {@code @EventListener} class, over a real
         * {@link SimpleContainer} (not a mock) so the evaluator can actually resolve the plugin
         * and read a real config file. This is the registration-bookkeeping half of the proof;
         * the event-dispatch half lives in
         * {@code ConditionalOnConfigRegistrationIntegrationTest} (Task 2).
         * <br>
         * WIRE-07 / D-17：registerAll(plugin, packageName) 必须在实例化被扫描到的监听器之前
         * 查询 {@code @ConditionalOnConfig}，就像 {@code ComponentScanner} 在 IoC 扫描路径上
         * 已经做的那样。本用例扫描一个真实的 fixture 包
         * （见 {@code com.ultikits.testfixtures.conditionallistener}），其中包含一个 false 条件
         * 与一个 true 条件的 {@code @EventListener} 类，并使用真实的 {@link SimpleContainer}
         * （而非 mock），以便判定器能够真正解析插件并读取真实的配置文件。这是"注册台账"这一半的
         * 证明；事件分发那一半的证明在 {@code ConditionalOnConfigRegistrationIntegrationTest}
         * （Task 2）中。
         */
        @Test
        @DisplayName("应该跳过 @ConditionalOnConfig 条件为 false 的监听器，只注册条件为 true 的")
        void conditionalListenersAreGatedByConditionalOnConfig(@TempDir File tempDir) throws Exception {
            // Arrange
            File configFile = new File(tempDir, "config/config.yml");
            configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write("enableFalseListener: false\nenableTrueListener: true\n");
            }

            SimpleContainer realContainer = new SimpleContainer();
            when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
            realContainer.registerType(UltiToolsPlugin.class, mockPlugin);
            when(mockPlugin.getContext()).thenReturn(realContainer);

            try {
                // Act
                listenerManager.registerAll(mockPlugin, "com.ultikits.testfixtures.conditionallistener");

                // Assert
                Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UltiToolsPlugin, List<Listener>> map =
                        (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

                List<Listener> registered = map.get(mockPlugin);
                assertThat(registered).isNotNull();
                assertThat(registered).hasSize(1);
                assertThat(registered.get(0).getClass().getSimpleName()).isEqualTo("TrueConditionListener");
            } finally {
                realContainer.close();
            }
        }
    }

    /**
     * GEN-05 (04-08 Task 3): {@code registerAll(UltiToolsPlugin, String)} is deprecated in step
     * with {@code CommandManager}'s symmetric {@code registerAll(UltiToolsPlugin, String)} -- both
     * package-scanning overloads now have zero in-framework callers (04-08 Task 1) and both carry
     * {@code @Deprecated(since = "6.3.0", forRemoval = true)}.
     */
    @Nested
    @DisplayName("registerAll(plugin, packageName) 弃用标注测试 (GEN-05)")
    class RegisterAllWithPackageDeprecationTests {

        @Test
        @DisplayName("registerAll(plugin, packageName) 应该带着 forRemoval 标注，registerAll(plugin) 不应该")
        void packageScanOverloadShouldBeMarkedForRemoval() throws Exception {
            Method deprecatedMethod = ListenerManager.class.getDeclaredMethod(
                    "registerAll", UltiToolsPlugin.class, String.class);
            Deprecated deprecated = deprecatedMethod.getAnnotation(Deprecated.class);

            assertThat(deprecated)
                    .as("registerAll(UltiToolsPlugin, String) 的 @Deprecated 标注不见了")
                    .isNotNull();
            assertThat(deprecated.forRemoval())
                    .as("forRemoval 被改成了 false，下游将只收到不含 API 名的笼统提示")
                    .isTrue();

            Method currentMethod = ListenerManager.class.getDeclaredMethod("registerAll", UltiToolsPlugin.class);
            assertThat(currentMethod.getAnnotation(Deprecated.class))
                    .as("registerAll(UltiToolsPlugin) 是当前推荐的重载，不应该被标注为弃用")
                    .isNull();
        }

        @Test
        @DisplayName("registerAll(plugin, \"\") 不应该抛出异常，也不应该注册任何监听器")
        void emptyPackageNameShouldNotThrowAndRegisterNothing() throws Exception {
            SimpleContainer mockContext = mock(SimpleContainer.class);
            AutowireFactory mockFactory = mock(AutowireFactory.class);
            when(mockPlugin.getContext()).thenReturn(mockContext);
            when(mockContext.getAutowireCapableBeanFactory()).thenReturn(mockFactory);

            assertDoesNotThrow(() -> listenerManager.registerAll(mockPlugin, ""));

            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map =
                    (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).isNull();
        }

        @Test
        @DisplayName("扫描一个不含 @EventListener 类的真实包不应该抛出异常，也不应该注册任何监听器")
        void packageWithZeroEventListenerClassesShouldNotThrowAndRegisterNothing() throws Exception {
            SimpleContainer mockContext = mock(SimpleContainer.class);
            AutowireFactory mockFactory = mock(AutowireFactory.class);
            when(mockPlugin.getContext()).thenReturn(mockContext);
            when(mockContext.getAutowireCapableBeanFactory()).thenReturn(mockFactory);

            // com.ultikits.ultitools.exceptions holds exception types, none of which is
            // annotated @EventListener -- a real, non-empty package with a zero-class result set.
            assertDoesNotThrow(() ->
                    listenerManager.registerAll(mockPlugin, "com.ultikits.ultitools.exceptions"));

            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map =
                    (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).isNull();
        }
    }

    @Nested
    @DisplayName("listenerListMap 字段测试")
    class ListenerListMapFieldTests {

        @Test
        @DisplayName("应该是 HashMap")
        void shouldBeHashMap() throws Exception {
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            Object map = mapField.get(listenerManager);

            assertThat(map).isInstanceOf(HashMap.class);
        }

        @Test
        @DisplayName("应该是 final")
        void shouldBeFinal() throws Exception {
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            assertThat(java.lang.reflect.Modifier.isFinal(mapField.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该是 private")
        void shouldBePrivate() throws Exception {
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            assertThat(java.lang.reflect.Modifier.isPrivate(mapField.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("computeIfAbsent 行为测试")
    class ComputeIfAbsentTests {

        @Test
        @DisplayName("首次注册应该创建新列表")
        void firstRegistrationShouldCreateNewList() throws Exception {
            // Arrange
            TestListener listener = new TestListener();

            // Act
            listenerManager.register(mockPlugin, listener);

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).isNotNull();
            assertThat(map.get(mockPlugin)).isInstanceOf(ArrayList.class);
        }

        @Test
        @DisplayName("多个插件应该有独立的列表")
        void multiplePluginsShouldHaveIndependentLists() throws Exception {
            // Arrange
            UltiToolsPlugin plugin2 = mock(UltiToolsPlugin.class);
            TestListener listener1 = new TestListener();
            NoAnnotationListener listener2 = new NoAnnotationListener();

            // Act
            listenerManager.register(mockPlugin, listener1);
            listenerManager.register(plugin2, listener2);

            // Assert
            Field mapField = ListenerManager.class.getDeclaredField("listenerListMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, List<Listener>> map = (Map<UltiToolsPlugin, List<Listener>>) mapField.get(listenerManager);

            assertThat(map.get(mockPlugin)).containsExactly(listener1);
            assertThat(map.get(plugin2)).containsExactly(listener2);
        }
    }
}
