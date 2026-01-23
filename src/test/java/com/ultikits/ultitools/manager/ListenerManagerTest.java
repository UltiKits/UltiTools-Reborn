package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
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

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.context.AutowireFactory;
import com.ultikits.ultitools.context.SimpleContainer;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * ListenerManager 测试
 */
@DisplayName("ListenerManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class ListenerManagerTest {

    private ServerMock server;
    private ListenerManager listenerManager;
    private UltiToolsPlugin mockPlugin;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        // Mock logger
        mockLogger = mock(Logger.class);
        when(UltiTools.getInstance().getLogger()).thenReturn(mockLogger);

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

            // Act - 不应该抛出异常
            listenerManager.unregister(listener);
        }

        @Test
        @DisplayName("注销未注册的监听器不应该抛出异常")
        void shouldNotThrowForUnregisteredListener() {
            // Arrange
            TestListener listener = new TestListener();

            // Act & Assert - 不应该抛出异常
            listenerManager.unregister(listener);
        }
    }

    @Nested
    @DisplayName("unregisterAll 测试")
    class UnregisterAllTests {

        @Test
        @DisplayName("未注册的插件不应该抛出异常")
        void shouldNotThrowForUnregisteredPlugin() {
            // Act & Assert - 不应该抛出异常
            listenerManager.unregisterAll(mockPlugin);
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

            // 不应该抛出异常
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
            listenerManager.unregisterAll(mockPlugin);
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
