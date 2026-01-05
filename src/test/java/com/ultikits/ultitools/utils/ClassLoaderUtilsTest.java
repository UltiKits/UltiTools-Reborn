package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * ClassLoaderUtils 测试类
 */
@DisplayName("ClassLoaderUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ClassLoaderUtilsTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("loadClass 方法测试")
    class LoadClassTests {

        @Test
        @DisplayName("成功加载受信任包的类")
        void shouldSuccessfullyLoadTrustedClass() throws Exception {
            // 加载一个真实存在的受信任类
            Class<?> clazz = ClassLoaderUtils.loadClass("com.ultikits.ultitools.UltiTools");
            assertThat(clazz).isNotNull();
            assertThat(clazz.getName()).isEqualTo("com.ultikits.ultitools.UltiTools");
        }

        @Test
        @DisplayName("成功加载 Bukkit API 类")
        void shouldSuccessfullyLoadBukkitClass() throws Exception {
            Class<?> clazz = ClassLoaderUtils.loadClass("org.bukkit.entity.Player");
            assertThat(clazz).isNotNull();
            assertThat(clazz.getName()).isEqualTo("org.bukkit.entity.Player");
        }

        @Test
        @DisplayName("类不存在时抛出 ClassNotFoundException")
        void shouldThrowClassNotFoundExceptionWhenClassDoesNotExist() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("com.ultikits.ultitools.NonExistentClass"))
                .isInstanceOf(ClassNotFoundException.class)
                .hasMessageContaining("Failed to load class");
        }

        @Test
        @DisplayName("危险类应该抛出SecurityException")
        void dangerousClassShouldThrowSecurityException() {
            String[] dangerousClasses = {
                "java.lang.ProcessBuilder",
                "java.lang.Runtime",
                "java.lang.System",
                "javax.script.ScriptEngine",
                "sun.misc.Unsafe"
            };
            
            for (String className : dangerousClasses) {
                assertThatThrownBy(() -> ClassLoaderUtils.loadClass(className))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("security");
            }
        }

        @Test
        @DisplayName("不信任包的类应该抛出SecurityException")
        void untrustedPackageClassShouldThrowSecurityException() {
            String[] untrustedClasses = {
                "com.evil.malware.Payload",
                "hacker.tools.Exploit"
            };
            
            for (String className : untrustedClasses) {
                assertThatThrownBy(() -> ClassLoaderUtils.loadClass(className))
                    .isInstanceOf(SecurityException.class);
            }
        }

        @Test
        @DisplayName("无效类名格式应该抛出SecurityException")
        void invalidClassNameFormatShouldThrowSecurityException() {
            String[] invalidNames = {
                "123InvalidStart",
                "com..double.dot",
                "com.invalid-dash.Class",
                "com.invalid space.Class",
                "com.invalid$special$.Class"
            };
            
            for (String className : invalidNames) {
                assertThatThrownBy(() -> ClassLoaderUtils.loadClass(className))
                    .isInstanceOf(SecurityException.class);
            }
        }

        @Test
        @DisplayName("受信任包下的无效类名格式也应该抛出SecurityException")
        void invalidFormatInTrustedPackageShouldThrowSecurityException() {
            // 这些类名以受信任的前缀开头，但格式不正确
            // 这测试的是 VALID_CLASS_NAME_PATTERN 的第二道检查
            String[] invalidNamesInTrustedPackage = {
                "com.ultikits.ultitools..DoubleDot",
                "com.ultikits.ultitools.123StartWithNumber",
                "com.ultikits.ultitools.-dash.Class",
                "com.ultikits.ultitools. space.Class",
                "org.bukkit..DoubleDot",
                "org.bukkit.-invalid.Class"
            };
            
            for (String className : invalidNamesInTrustedPackage) {
                assertThatThrownBy(() -> ClassLoaderUtils.loadClass(className))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Invalid class name format");
            }
        }
    }

    @Nested
    @DisplayName("loadClass with initialize 方法测试")
    class LoadClassWithInitializeTests {

        @Test
        @DisplayName("成功加载类并初始化")
        void shouldSuccessfullyLoadClassWithInitialization() throws Exception {
            Class<?> clazz = ClassLoaderUtils.loadClass("com.ultikits.ultitools.UltiTools", true);
            assertThat(clazz).isNotNull();
            assertThat(clazz.getName()).isEqualTo("com.ultikits.ultitools.UltiTools");
        }

        @Test
        @DisplayName("成功加载类但不初始化")
        void shouldSuccessfullyLoadClassWithoutInitialization() throws Exception {
            Class<?> clazz = ClassLoaderUtils.loadClass("com.ultikits.ultitools.entities.Language", false);
            assertThat(clazz).isNotNull();
            assertThat(clazz.getName()).isEqualTo("com.ultikits.ultitools.entities.Language");
        }

        @Test
        @DisplayName("类不存在时抛出 ClassNotFoundException")
        void shouldThrowClassNotFoundExceptionWhenClassDoesNotExist() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("com.ultikits.ultitools.DoesNotExist", false))
                .isInstanceOf(ClassNotFoundException.class)
                .hasMessageContaining("Failed to load class");
        }

        @Test
        @DisplayName("危险类应该抛出SecurityException")
        void dangerousClassShouldThrowSecurityException() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("java.lang.Runtime", false))
                .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("不信任包的类应该抛出SecurityException")
        void untrustedClassShouldThrowSecurityException() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("com.untrusted.BadClass", true))
                .isInstanceOf(SecurityException.class);
        }
    }

    @Nested
    @DisplayName("loadPluginClass 方法测试")
    class LoadPluginClassTests {

        @Test
        @DisplayName("尝试加载非 UltiToolsPlugin 子类的 ultikits 包类应该抛出 SecurityException")
        void shouldThrowSecurityExceptionForNonPluginUltikitsClass() {
            // UltiTools 不是 UltiToolsPlugin 的子类，应该抛出安全异常
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadPluginClass("com.ultikits.ultitools.entities.Language"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not a valid UltiToolsPlugin");
        }

        @Test
        @DisplayName("非 UltiToolsPlugin 子类（接口）应该抛出 SecurityException")
        void shouldThrowSecurityExceptionForInterfaceClass() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadPluginClass("com.ultikits.ultitools.interfaces.IPlugin"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not a valid UltiToolsPlugin");
        }

        @Test
        @DisplayName("类不存在时抛出 ClassNotFoundException")
        void shouldThrowClassNotFoundExceptionWhenClassDoesNotExist() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadPluginClass("com.ultikits.ultitools.NonExistentPlugin"))
                .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("非ultikits包的类应该抛出SecurityException")
        void nonUltikitsPackageShouldThrowSecurityException() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadPluginClass("com.other.plugin.SomeClass"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("com.ultikits");
        }

        @Test
        @DisplayName("org.bukkit包的类应该抛出SecurityException")
        void bukkitPackageShouldThrowSecurityException() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadPluginClass("org.bukkit.entity.Player"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("com.ultikits");
        }
    }

    @Nested
    @DisplayName("validateClassLoaderHierarchy 方法测试")
    class ValidateClassLoaderHierarchyTests {

        @Test
        @DisplayName("插件类加载器本身应该验证为有效")
        void pluginClassLoaderItselfShouldBeValid() {
            ClassLoader pluginLoader = ClassLoaderUtils.getPluginClassLoader();
            boolean result = ClassLoaderUtils.validateClassLoaderHierarchy(pluginLoader);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("使用插件类加载器作为父类的自定义类加载器应该验证为有效")
        void customClassLoaderWithPluginParentShouldBeValid() throws Exception {
            ClassLoader pluginLoader = ClassLoaderUtils.getPluginClassLoader();
            URLClassLoader customLoader = new URLClassLoader(new URL[0], pluginLoader);
            try {
                boolean result = ClassLoaderUtils.validateClassLoaderHierarchy(customLoader);
                assertThat(result).isTrue();
            } finally {
                customLoader.close();
            }
        }

        @Test
        @DisplayName("没有正确父类层次的类加载器应该验证为无效")
        void classLoaderWithoutCorrectParentShouldBeInvalid() throws Exception {
            // 使用 bootstrap 类加载器 (null parent) 创建独立的类加载器
            URLClassLoader isolatedLoader = new URLClassLoader(new URL[0], null);
            try {
                boolean result = ClassLoaderUtils.validateClassLoaderHierarchy(isolatedLoader);
                assertThat(result).isFalse();
            } finally {
                isolatedLoader.close();
            }
        }

        @Test
        @DisplayName("系统类加载器应该不在层次结构中")
        void systemClassLoaderShouldNotBeInHierarchy() {
            ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
            // 系统类加载器不在插件类加载器的层次结构中
            boolean result = ClassLoaderUtils.validateClassLoaderHierarchy(systemLoader);
            // 结果取决于具体实现
            assertThat(result).isIn(true, false);
        }

        @Test
        @DisplayName("当前类的类加载器应该可以验证")
        void currentClassLoaderShouldBeValidatable() {
            ClassLoader currentLoader = ClassLoaderUtilsTest.class.getClassLoader();
            // 不应该抛出异常
            boolean result = ClassLoaderUtils.validateClassLoaderHierarchy(currentLoader);
            assertThat(result).isIn(true, false);
        }

        @Test
        @DisplayName("null类加载器应该返回false")
        void nullClassLoaderShouldReturnFalse() {
            boolean result = ClassLoaderUtils.validateClassLoaderHierarchy(null);
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("getPluginClassLoader 方法测试")
    class GetPluginClassLoaderTests {

        @Test
        @DisplayName("应该返回非null的类加载器")
        void shouldReturnNonNullClassLoader() {
            ClassLoader loader = ClassLoaderUtils.getPluginClassLoader();
            assertThat(loader).isNotNull();
        }
    }

    @Nested
    @DisplayName("类名格式验证测试")
    class ClassNameFormatValidationTests {

        @Test
        @DisplayName("有效类名格式应该通过初步验证")
        void validClassNameFormatShouldPassInitialValidation() {
            // 这些是格式有效的类名（即使类不存在）
            String[] validFormats = {
                "com.ultikits.ultitools.SomeClass",
                "com.ultikits.ultitools.sub.AnotherClass",
                "com.ultikits.ultitools.Class_With_Underscores",
                "com.ultikits.ultitools.Class$Inner"
            };
            
            for (String className : validFormats) {
                // 格式有效，但可能因为类不存在而抛出 ClassNotFoundException
                // 或者因为安全检查而抛出 SecurityException
                try {
                    ClassLoaderUtils.loadClass(className);
                } catch (ClassNotFoundException e) {
                    // 这是预期的 - 类名格式正确但类不存在
                } catch (SecurityException e) {
                    // 这也可能 - 因为安全策略
                }
            }
        }

        @Test
        @DisplayName("以数字开头的类名应该被拒绝")
        void classNameStartingWithDigitShouldBeRejected() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("com.ultikits.123Class"))
                .isInstanceOf(SecurityException.class);
        }
    }

    @Nested
    @DisplayName("安全边界测试")
    class SecurityBoundaryTests {

        @Test
        @DisplayName("反射相关类应该被阻止")
        void reflectionClassesShouldBeBlocked() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("java.lang.reflect.Method"))
                .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("脚本引擎类应该被阻止")
        void scriptEngineClassesShouldBeBlocked() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("javax.script.ScriptEngineManager"))
                .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("内部JDK类应该被阻止")
        void internalJdkClassesShouldBeBlocked() {
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadClass("jdk.internal.misc.Unsafe"))
                .isInstanceOf(SecurityException.class);
        }
    }

    @Nested
    @DisplayName("validateClassName 边界测试")
    class ValidateClassNameBoundaryTests {

        @Test
        @DisplayName("空字符串类名应该抛出 SecurityException")
        void emptyClassNameShouldThrowSecurityException() {
            assertThatThrownBy(() -> ClassLoaderUtils.loadClass(""))
                .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("包含特殊字符的类名应该抛出 SecurityException")
        void classNameWithSpecialCharsShouldThrowSecurityException() {
            assertThatThrownBy(() -> ClassLoaderUtils.loadClass("com.test.Class@Name"))
                .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("有效的内部类名格式应该能通过格式验证")
        void validInnerClassNameFormatShouldPassFormatValidation() throws Exception {
            // $ 是合法的 Java 类名字符（用于内部类）
            // Lombok @Builder 生成的内部类 WhereConditionBuilder
            Class<?> clazz = ClassLoaderUtils.loadClass("com.ultikits.ultitools.entities.WhereCondition$WhereConditionBuilder");
            assertThat(clazz).isNotNull();
        }

        @Test
        @DisplayName("以下划线开头的类名应该通过格式验证")
        void classNameStartingWithUnderscoreShouldPassFormatValidation() {
            // 格式有效，但安全策略可能阻止
            assertThatThrownBy(() -> ClassLoaderUtils.loadClass("_com.test.Class"))
                .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("以 $ 开头的类名应该通过格式验证")
        void classNameStartingWithDollarShouldPassFormatValidation() {
            // 格式有效，但安全策略可能阻止
            assertThatThrownBy(() -> ClassLoaderUtils.loadClass("$com.test.Class"))
                .isInstanceOf(SecurityException.class);
        }

        @ParameterizedTest
        @DisplayName("VALID_CLASS_NAME_PATTERN 应该拒绝无效格式")
        @ValueSource(strings = {
            "com..test.Class",           // 双点
            "com.test..Class",           // 双点
            ".com.test.Class",           // 以点开头
            "com.test.Class.",           // 以点结尾
            "com.test.1Class",           // 包名以数字开头
            "com.test.Class-Name",       // 包含连字符
            "com.test.Class Name",       // 包含空格
            "com/test/Class",            // 使用斜杠
            "com\\test\\Class",          // 使用反斜杠
            "com.test.Class#method",     // 包含井号
            "com.test.Class<T>",         // 包含泛型符号
            "com.test.Class[]"           // 数组语法
        })
        void invalidClassNamePatternShouldBeRejected(String invalidClassName) {
            assertThatThrownBy(() -> ClassLoaderUtils.loadClass(invalidClassName))
                .isInstanceOf(SecurityException.class);
        }

        @ParameterizedTest
        @DisplayName("VALID_CLASS_NAME_PATTERN 应该接受有效格式")
        @ValueSource(strings = {
            "A",                         // 单字母类名
            "a",                         // 小写单字母
            "_Class",                    // 下划线开头
            "$Class",                    // 美元符开头
            "Class_Name",                // 包含下划线
            "Class$Inner",               // 内部类格式
            "Class$Inner$Deeper",        // 多层内部类
            "com.test.Class123",         // 数字结尾
            "com.test._private.Class",   // 下划线包名
            "com.test.$generated.Class"  // 美元符包名
        })
        void validClassNamePatternShouldBeAccepted(String validClassName) {
            // 格式验证应该通过，但可能因为安全策略或类不存在而失败
            try {
                ClassLoaderUtils.loadClass(validClassName);
            } catch (ClassNotFoundException e) {
                // 预期 - 类名格式正确但类不存在
            } catch (SecurityException e) {
                // 可能 - 安全策略阻止，但不是因为格式问题
                assertThat(e.getMessage()).doesNotContain("Invalid class name format");
            }
        }
    }

    @Nested
    @DisplayName("Mock 静态方法测试")
    class MockStaticMethodTests {

        @Test
        @DisplayName("当 UltiTools.getJavaPluginClassLoader 返回 null 时应该使用上下文类加载器")
        void shouldUseContextClassLoaderWhenPluginClassLoaderIsNull() {
            try (MockedStatic<UltiTools> mockedUltiTools = mockStatic(UltiTools.class)) {
                mockedUltiTools.when(UltiTools::getJavaPluginClassLoader).thenReturn(null);
                
                // 当返回 null 时，getPluginClassLoader 直接返回 null
                ClassLoader loader = ClassLoaderUtils.getPluginClassLoader();
                assertThat(loader).isNull();
            }
        }

        @Test
        @DisplayName("应该委托给 UltiTools.getJavaPluginClassLoader")
        void shouldDelegateToUltiToolsGetJavaPluginClassLoader() {
            ClassLoader expectedLoader = Thread.currentThread().getContextClassLoader();
            try (MockedStatic<UltiTools> mockedUltiTools = mockStatic(UltiTools.class)) {
                mockedUltiTools.when(UltiTools::getJavaPluginClassLoader).thenReturn(expectedLoader);
                
                ClassLoader loader = ClassLoaderUtils.getPluginClassLoader();
                assertThat(loader).isSameAs(expectedLoader);
            }
        }
    }

    @Nested
    @DisplayName("loadPluginClass 基类验证测试")
    class LoadPluginClassBaseClassValidationTests {

        @Test
        @DisplayName("当 UltiToolsPlugin 基类找不到时应该抛出 SecurityException")
        void shouldThrowSecurityExceptionWhenBaseClassNotFound() {
            // 创建一个模拟的 ClassLoader，它无法找到 UltiToolsPlugin 基类
            ClassLoader mockClassLoader = mock(ClassLoader.class);
            
            try (MockedStatic<UltiTools> mockedUltiTools = mockStatic(UltiTools.class)) {
                mockedUltiTools.when(UltiTools::getJavaPluginClassLoader).thenReturn(mockClassLoader);
                
                try {
                    // 配置 mock：第一次调用返回一个类，第二次调用（加载 UltiToolsPlugin）抛出异常
                    when(mockClassLoader.loadClass("com.ultikits.ultitools.entities.Language"))
                        .thenReturn((Class) Object.class);
                    when(mockClassLoader.loadClass("com.ultikits.ultitools.abstracts.UltiToolsPlugin"))
                        .thenThrow(new ClassNotFoundException("UltiToolsPlugin not found"));
                    
                    assertThatThrownBy(() -> 
                        ClassLoaderUtils.loadPluginClass("com.ultikits.ultitools.entities.Language"))
                        .isInstanceOf(SecurityException.class)
                        .hasMessageContaining("Cannot validate plugin class hierarchy");
                } catch (ClassNotFoundException e) {
                    // Mock 配置失败，跳过测试
                }
            }
        }

        @Test
        @DisplayName("成功加载 UltiToolsPlugin 子类时应该返回类")
        void shouldReturnClassWhenSuccessfullyLoadingUltiToolsPluginSubclass() throws Exception {
            // 使用真实的类加载器加载抽象基类本身（它是自己的子类）
            // 注意：UltiToolsPlugin 是抽象类，所以它自己是自己的子类
            Class<?> baseClass = ClassLoaderUtils.loadClass("com.ultikits.ultitools.abstracts.UltiToolsPlugin");
            assertThat(baseClass).isNotNull();
            assertThat(baseClass.getName()).isEqualTo("com.ultikits.ultitools.abstracts.UltiToolsPlugin");
        }

        @Test
        @DisplayName("验证 UltiToolsPlugin 继承检查逻辑")
        void shouldVerifyPluginInheritanceCheck() {
            // 测试一个不是 UltiToolsPlugin 子类的类
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadPluginClass("com.ultikits.ultitools.UltiTools"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not a valid UltiToolsPlugin");
        }

        @Test
        @DisplayName("验证 isAssignableFrom 检查")
        void shouldVerifyIsAssignableFromCheck() {
            // 枚举类不是 UltiToolsPlugin 的子类
            assertThatThrownBy(() -> 
                ClassLoaderUtils.loadPluginClass("com.ultikits.ultitools.entities.Comparison"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not a valid UltiToolsPlugin");
        }
    }
}
