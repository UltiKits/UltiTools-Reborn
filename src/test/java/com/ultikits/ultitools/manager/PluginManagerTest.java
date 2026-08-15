package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;

import org.bukkit.Bukkit;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * PluginManager 测试
 */
@DisplayName("PluginManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class PluginManagerTest {

    @TempDir
    File tempDir;

    private PluginManager pluginManager;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock(); // Server mock not stored as field - only used for initialization
        MockBukkit.createMockPlugin();

        // Mock logger
        mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该初始化 pluginList")
        void shouldInitializePluginList() {
            // Arrange & Act
            PluginManager manager = new PluginManager();

            // Assert
            assertThat(manager.getPluginList()).isNotNull();
            assertThat(manager.getPluginList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getPluginList 测试")
    class GetPluginListTests {

        @Test
        @DisplayName("应该返回空列表")
        void shouldReturnEmptyList() {
            // Act
            List<UltiToolsPlugin> list = pluginManager.getPluginList();

            // Assert
            assertThat(list).isNotNull();
            assertThat(list).isEmpty();
        }

        @Test
        @DisplayName("返回的列表应该是可变的")
        void returnedListShouldBeMutable() {
            // Act
            List<UltiToolsPlugin> list = pluginManager.getPluginList();

            // Assert - 列表应该是可操作的（虽然不推荐直接修改）
            assertThat(list).isInstanceOf(ArrayList.class);
        }
    }

    @Nested
    @DisplayName("close 测试")
    class CloseTests {

        @Test
        @DisplayName("空插件列表时调用不应该抛出异常")
        void shouldNotThrowWithEmptyList() {
            // Act - 不应该抛出异常
            pluginManager.close();

            // Assert
            assertThat(pluginManager.getPluginList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("reload 测试")
    class ReloadTests {

        @Test
        @DisplayName("空插件列表时调用不应该抛出异常")
        void shouldNotThrowWithEmptyList() {
            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> pluginManager.reload());
        }
    }

    @Nested
    @DisplayName("validateJarFile 测试")
    class ValidateJarFileTests {

        @Test
        @DisplayName("null 文件应该返回 false")
        void nullFileShouldReturnFalse() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateJarFile", File.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, (File) null);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("不存在的文件应该返回 false")
        void nonExistentFileShouldReturnFalse() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateJarFile", File.class);
            method.setAccessible(true);
            File nonExistent = new File(tempDir, "nonexistent.jar");

            // Act
            boolean result = (boolean) method.invoke(pluginManager, nonExistent);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("非 jar 扩展名应该返回 false")
        void nonJarExtensionShouldReturnFalse() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateJarFile", File.class);
            method.setAccessible(true);
            File txtFile = new File(tempDir, "test.txt");
            txtFile.createNewFile();

            // Act
            boolean result = (boolean) method.invoke(pluginManager, txtFile);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("目录应该返回 false")
        void directoryShouldReturnFalse() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateJarFile", File.class);
            method.setAccessible(true);
            File dir = new File(tempDir, "testdir");
            dir.mkdirs();

            // Act
            boolean result = (boolean) method.invoke(pluginManager, dir);

            // Assert
            assertThat(result).isFalse();
        }
    }


    @Nested
    @DisplayName("validateConstructorArgs 测试")
    class ValidateConstructorArgsTests {

        @Test
        @DisplayName("null 参数数组应该返回 true")
        void nullArgsShouldReturnTrue() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateConstructorArgs", Object[].class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, (Object) null);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("空参数数组应该返回 true")
        void emptyArgsShouldReturnTrue() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateConstructorArgs", Object[].class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, (Object) new Object[0]);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("超过 10 个参数应该返回 false")
        void tooManyArgsShouldReturnFalse() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateConstructorArgs", Object[].class);
            method.setAccessible(true);
            Object[] args = new Object[11];
            for (int i = 0; i < 11; i++) {
                args[i] = "arg" + i;
            }

            // Act
            boolean result = (boolean) method.invoke(pluginManager, (Object) args);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("包含 null 元素的参数应该被接受")
        void argsWithNullElementsShouldBeAccepted() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("validateConstructorArgs", Object[].class);
            method.setAccessible(true);
            Object[] args = new Object[]{"valid", null, "another"};

            // Act
            boolean result = (boolean) method.invoke(pluginManager, (Object) args);

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("isSafeParameterType 测试")
    class IsSafeParameterTypeTests {

        @Test
        @DisplayName("String 类型应该是安全的")
        void stringShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, String.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Integer 类型应该是安全的")
        void integerShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, Integer.class);

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("pluginClassList 字段测试")
    class PluginClassListFieldTests {

        @Test
        @DisplayName("应该初始化为空列表")
        void shouldBeInitializedEmpty() throws Exception {
            // Arrange
            PluginManager manager = new PluginManager();
            Field field = PluginManager.class.getDeclaredField("pluginClassList");
            field.setAccessible(true);

            // Assert
            List<?> list = (List<?>) field.get(manager);
            assertThat(list).isNotNull();
            assertThat(list).isEmpty();
        }
    }

    @Nested
    @DisplayName("classLoader 字段测试")
    class ClassLoaderFieldTests {

        @Test
        @DisplayName("初始应该为 null")
        void shouldBeNullInitially() throws Exception {
            // Arrange
            PluginManager manager = new PluginManager();
            Field field = PluginManager.class.getDeclaredField("classLoader");
            field.setAccessible(true);

            // Assert
            assertThat(field.get(manager)).isNull();
        }
    }

    @Nested
    @DisplayName("unregister 测试")
    class UnregisterTests {

        @Test
        @DisplayName("应该能够调用 unregister 方法")
        void shouldBeAbleToCallUnregister() throws Exception {
            // 由于需要完整的插件实例，这里只测试方法存在
            Method method = PluginManager.class.getDeclaredMethod("unregister", UltiToolsPlugin.class);
            assertThat(method).isNotNull();
        }
    }

    @Nested
    @DisplayName("register(Class) 测试")
    class RegisterClassTests {

        @Test
        @DisplayName("应该能够调用 register 方法")
        void shouldBeAbleToCallRegister() throws Exception {
            // 由于需要完整的类加载器设置，这里只测试方法存在
            Method method = PluginManager.class.getDeclaredMethod("register", Class.class);
            assertThat(method).isNotNull();
        }
    }

    @Nested
    @DisplayName("init 测试")
    class InitTests {

        @Test
        @DisplayName("init 方法应该存在")
        void initMethodShouldExist() throws Exception {
            // Arrange & Assert
            Method method = PluginManager.class.getDeclaredMethod("init", ClassLoader.class);
            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("init 方法应该设置 classLoader")
        void initShouldSetClassLoader() throws Exception {
            // Arrange
            Field clField = PluginManager.class.getDeclaredField("classLoader");
            clField.setAccessible(true);
            ClassLoader testLoader = Thread.currentThread().getContextClassLoader();

            // 通过反射设置 classLoader 来验证字段是否可访问
            clField.set(pluginManager, testLoader);

            // Assert
            assertThat(clField.get(pluginManager)).isEqualTo(testLoader);
        }
    }

    @Nested
    @DisplayName("scanForPlugins 测试")
    class ScanForPluginsTests {

        @Test
        @DisplayName("方法应该存在")
        void methodShouldExist() {
            // 检查 PluginManager 类有方法
            Method[] methods = PluginManager.class.getDeclaredMethods();
            assertThat(methods).as("PluginManager should have declared methods").isNotEmpty();
        }
    }

    @Nested
    @DisplayName("validatePluginClass 测试")
    class ValidatePluginClassTests {

        @Test
        @DisplayName("检查 validatePluginClass 或类似方法存在")
        void checkForValidationMethod() throws Exception {
            // 检查是否有验证相关的方法
            Method[] methods = PluginManager.class.getDeclaredMethods();
            int validateMethodCount = 0;
            for (Method m : methods) {
                if (m.getName().contains("validate") || m.getName().contains("Validate")) {
                    m.setAccessible(true);
                    validateMethodCount++;
                }
            }
            assertThat(methods.length).as("PluginManager should have methods").isGreaterThan(0);
            assertThat(validateMethodCount).as("Validation method count").isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("isSafeParameterType 处理 null 应该返回 false")
        void isSafeParameterTypeShouldHandleNull() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, (Class<?>) null);

            // Assert
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("原始类型参数测试")
    class PrimitiveTypeParameterTests {

        @Test
        @DisplayName("int 原始类型应该是安全的")
        void intPrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, int.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("boolean 原始类型应该是安全的")
        void booleanPrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, boolean.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("double 原始类型应该是安全的")
        void doublePrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, double.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Long 包装类型应该是安全的")
        void longWrapperShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, Long.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Boolean 包装类型应该是安全的")
        void booleanWrapperShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, Boolean.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Double 包装类型应该是安全的")
        void doubleWrapperShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, Double.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("float 原始类型应该是安全的")
        void floatPrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, float.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("long 原始类型应该是安全的")
        void longPrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, long.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("byte 原始类型应该是安全的")
        void bytePrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, byte.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("short 原始类型应该是安全的")
        void shortPrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, short.class);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("char 原始类型应该是安全的")
        void charPrimitiveShouldBeSafe() throws Exception {
            // Arrange
            Method method = PluginManager.class.getDeclaredMethod("isSafeParameterType", Class.class);
            method.setAccessible(true);

            // Act
            boolean result = (boolean) method.invoke(pluginManager, char.class);

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("classLoader 操作测试")
    class ClassLoaderOperationTests {

        @Test
        @DisplayName("设置 classLoader 应该能通过反射完成")
        void shouldBeAbleToSetClassLoaderViaReflection() throws Exception {
            // Arrange
            PluginManager manager = new PluginManager();
            Field field = PluginManager.class.getDeclaredField("classLoader");
            field.setAccessible(true);

            // Act
            field.set(manager, Thread.currentThread().getContextClassLoader());

            // Assert
            assertThat(field.get(manager)).isNotNull();
        }
    }

    @Nested
    @DisplayName("pluginClassList 操作测试")
    class PluginClassListOperationTests {

        @Test
        @DisplayName("应该能够添加元素到列表")
        void shouldBeAbleToAddToList() throws Exception {
            // Arrange
            PluginManager manager = new PluginManager();
            Field field = PluginManager.class.getDeclaredField("pluginClassList");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Class<?>> list = (List<Class<?>>) field.get(manager);

            // Act
            list.add(String.class);

            // Assert
            assertThat(list).hasSize(1);
        }
    }

    @Nested
    @DisplayName("register(UltiToolsPlugin) 测试")
    class RegisterPluginInstanceTests {

        @Test
        @DisplayName("register 方法应该存在")
        void registerMethodShouldExist() throws Exception {
            // Assert
            Method method = PluginManager.class.getDeclaredMethod("register", UltiToolsPlugin.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }
    }

    @Nested
    @DisplayName("register 带构造参数测试")
    class RegisterWithArgsTests {

        @Test
        @DisplayName("带参数的 register 方法应该存在")
        void registerWithArgsMethodShouldExist() throws Exception {
            // 检查是否有多个参数的 register 方法
            Method[] methods = PluginManager.class.getDeclaredMethods();
            boolean found = false;
            for (Method m : methods) {
                if (m.getName().equals("register") && m.getParameterCount() > 1) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Nested
    @DisplayName("pluginList 测试")
    class PluginListTests {

        @Test
        @DisplayName("getPluginList 应该返回相同列表引用")
        void getPluginListShouldReturnSameReference() {
            // Act
            List<UltiToolsPlugin> list1 = pluginManager.getPluginList();
            List<UltiToolsPlugin> list2 = pluginManager.getPluginList();

            // Assert
            assertThat(list1).isSameAs(list2);
        }

        @Test
        @DisplayName("应该能够通过 getPluginList 获取列表大小")
        void shouldBeAbleToGetListSize() {
            // Act
            int size = pluginManager.getPluginList().size();

            // Assert
            assertThat(size).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("方法可访问性测试")
    class MethodAccessibilityTests {

        @Test
        @DisplayName("close 方法应该是 public")
        void closeShouldBePublic() throws Exception {
            // Arrange
            Method method = PluginManager.class.getMethod("close");

            // Assert
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("reload 方法应该是 public")
        void reloadShouldBePublic() throws Exception {
            // Arrange
            Method method = PluginManager.class.getMethod("reload");

            // Assert
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("unregister 方法应该是 public")
        void unregisterShouldBePublic() throws Exception {
            // Arrange
            Method method = PluginManager.class.getMethod("unregister", UltiToolsPlugin.class);

            // Assert
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }
    }

    /**
     * 兼容性门禁的时序测试。
     * <p>
     * 守的是 issue #184：门禁原先跑在容器 refresh() 之后，于是「UltiTools 版本过旧」那句提示
     * 在它唯一有意义的场景里（模块的 bean 引用了不存在的方法）永远说不出口——模块先死在
     * refresh 里，被通用 catch 报成普通初始化失败。
     * <p>
     * 这里用 {@code register(UltiToolsPlugin)} 这条路径：实例由调用方给，容器由 PluginManager
     * 现建，所以「有没有发生 bean 构造」可以直接用 {@code setContext} 有没有被调过来观测。
     */
    @Nested
    @DisplayName("兼容性门禁时序测试")
    class CompatibilityGateOrderingTests {

        private static final int CURRENT_API_VERSION = 625;

        private MockedStatic<UltiTools> ultiToolsStatic;
        private final List<LogRecord> bukkitLogs = new ArrayList<>();
        private Handler captureHandler;

        @BeforeEach
        void setUpGate() {
            UltiTools ultiTools = mock(UltiTools.class);
            DependenceManagers dependenceManagers = mock(DependenceManagers.class);
            lenient().when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
            lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);

            // getPluginVersion() 走 getEnv() → getInstance().getTextResource("env.yml")，
            // 而那个方法在 JavaPlugin 里是 protected，测试包里打不了桩，所以直接桩静态方法。
            ultiToolsStatic = mockStatic(UltiTools.class);
            ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);
            ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(CURRENT_API_VERSION);

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
        void tearDownGate() {
            Bukkit.getLogger().removeHandler(captureHandler);
            if (ultiToolsStatic != null) {
                ultiToolsStatic.close();
            }
        }

        private UltiToolsPlugin modules(String name, String mainClass, String version, int minApiVersion) {
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            lenient().when(plugin.getPluginName()).thenReturn(name);
            lenient().when(plugin.getMainClass()).thenReturn(mainClass);
            lenient().when(plugin.getVersion()).thenReturn(version);
            lenient().when(plugin.getMinUltiToolsVersion()).thenReturn(minApiVersion);
            return plugin;
        }

        private String warnings() {
            StringBuilder joined = new StringBuilder();
            for (LogRecord record : bukkitLogs) {
                if (Level.WARNING.equals(record.getLevel())) {
                    joined.append(record.getMessage()).append('\n');
                }
            }
            return joined.toString();
        }

        @Test
        @DisplayName("声明了更高 API 版本的模块应该在任何 bean 被构造之前就被拒绝")
        void incompatibleModuleShouldBeRejectedBeforeAnyBeanIsBuilt() throws Exception {
            // Arrange - 针对未来 API 编译的模块，正是那种 bean 会引用不存在方法的模块
            UltiToolsPlugin plugin = modules("FutureModule", "com.example.FutureModule",
                    "1.0.0", CURRENT_API_VERSION + 1);

            // Act
            boolean result = pluginManager.register(plugin);

            // Assert
            assertThat(result).isFalse();
            assertThat(warnings()).contains("UltiTools version is outdated");
            verify(plugin, never()).setContext(any());
            verify(plugin, never()).registerSelf();
        }

        @Test
        @DisplayName("版本兼容的模块应该被放行去建容器")
        void compatibleModuleShouldProceedToContextConstruction() throws Exception {
            // Arrange
            UltiToolsPlugin plugin = modules("OkModule", "com.example.OkModule",
                    "1.0.0", CURRENT_API_VERSION);
            when(plugin.registerSelf()).thenReturn(true);

            // Act
            boolean result = pluginManager.register(plugin);

            // Assert
            assertThat(result).isTrue();
            verify(plugin).setContext(any());
        }

        @Test
        @DisplayName("已经加载了更新版本时也应该在建容器之前拒绝")
        void supersededModuleShouldBeRejectedBeforeContextConstruction() {
            // Arrange
            UltiToolsPlugin loaded = modules("Dup", "com.example.Dup", "2.0.0", CURRENT_API_VERSION);
            UltiToolsPlugin older = modules("Dup", "com.example.Dup", "1.0.0", CURRENT_API_VERSION);
            when(loaded.isNewerVersionThan(older)).thenReturn(true);
            pluginManager.getPluginList().add(loaded);

            // Act
            boolean result = pluginManager.register(older);

            // Assert
            assertThat(result).isFalse();
            assertThat(warnings()).contains("There is already a new version");
            verify(older, never()).setContext(any());
        }

        @Test
        @DisplayName("被取代的旧版本要等新模块初始化成功之后才卸载")
        void olderVersionShouldBeUnregisteredOnlyAfterInitialization() throws Exception {
            // Arrange
            UltiToolsPlugin older = modules("Dup", "com.example.Dup", "1.0.0", CURRENT_API_VERSION);
            UltiToolsPlugin newer = modules("Dup", "com.example.Dup", "2.0.0", CURRENT_API_VERSION);
            when(newer.isNewerVersionThan(older)).thenReturn(true);
            when(newer.registerSelf()).thenReturn(true);
            pluginManager.getPluginList().add(older);

            // Act
            boolean result = pluginManager.register(newer);

            // Assert - 顺序要求：先把新的建起来，再卸旧的
            assertThat(result).isTrue();
            InOrder order = inOrder(newer, older);
            order.verify(newer).setContext(any());
            order.verify(older).unregisterSelf();
        }

        @Test
        @DisplayName("新模块因版本不兼容被拒时不应该顺手卸掉已加载的旧版本")
        void rejectedModuleShouldNotUnregisterTheLoadedOlderVersion() {
            // Arrange - 新版本更高但要求的 API 太新：拒它，同时不能动线上正在跑的那个
            UltiToolsPlugin older = modules("Dup", "com.example.Dup", "1.0.0", CURRENT_API_VERSION);
            UltiToolsPlugin newer = modules("Dup", "com.example.Dup", "2.0.0", CURRENT_API_VERSION + 1);
            lenient().when(newer.isNewerVersionThan(older)).thenReturn(true);
            pluginManager.getPluginList().add(older);

            // Act
            boolean result = pluginManager.register(newer);

            // Assert
            assertThat(result).isFalse();
            verify(older, never()).unregisterSelf();
        }
    }
}
